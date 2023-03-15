/**
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Simple app to demonstrate CameraX Video capturing with Recorder ( to local files ), with the
 * following simple control follow:
 *   - user starts capture.
 *   - this app disables all UI selections.
 *   - this app enables capture run-time UI (pause/resume/stop).
 *   - user controls recording with run-time UI, eventually tap "stop" to end.
 *   - this app informs CameraX recording to stop with recording.stop() (or recording.close()).
 *   - CameraX notify this app that the recording is indeed stopped, with the Finalize event.
 *   - this app starts VideoViewer fragment to view the captured result.
*/

package com.hardkernel.odroid.cctv.video.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import java.text.SimpleDateFormat
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import com.hardkernel.odroid.cctv.video.R
import com.hardkernel.odroid.cctv.video.databinding.FragmentCaptureBinding
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.video.*
import androidx.concurrent.futures.await
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.util.Consumer
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.whenCreated
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.android.camera.utils.GenericListAdapter
import com.hardkernel.odroid.cctv.video.extensions.getAspectRatio
import com.hardkernel.odroid.cctv.video.extensions.getAspectRatioString
import com.hardkernel.odroid.cctv.video.extensions.getNameString
import kotlinx.coroutines.*
import java.io.File
import java.util.*
import kotlin.math.min
import java.util.concurrent.TimeUnit.NANOSECONDS

class CaptureFragment : Fragment() {

    // UI with ViewBinding
    private var _captureViewBinding: FragmentCaptureBinding? = null
    private val captureViewBinding get() = _captureViewBinding!!
    private val captureLiveStatus = MutableLiveData<String>()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    private val cameraCapabilities = mutableListOf<CameraCapability>()

    private lateinit var videoCapture: VideoCapture<Recorder>
    private var currentRecording: Recording? = null
    private lateinit var recordingState:VideoRecordEvent

    private lateinit var getPath: ActivityResultLauncher<Intent>
    private var currentPathUri:Uri? = null
    private var currentUUID:String? = null
    private lateinit var currentRecordingFileList: ArrayDeque<DocumentFile>
    private var currentRecordingVolumeMin: Long = 1024 * 1024 * 1024
    private var isRollingRecord:Boolean = true
    private val MB = 1048576

    // Camera UI  states and inputs
    enum class UiState {
        IDLE,       // Not recording, all UI controls are active.
        RECORDING,  // Camera is recording, only display Pause/Resume & Stop button.
        FINALIZED,  // Recording just completes, disable all RECORDING UI controls.
        RECOVERY    // For future use.
    }
    private var cameraIndex = 0
    private var qualityIndex = DEFAULT_QUALITY_IDX
    private var audioEnabled = false

    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(requireContext()) }
    private var enumerationDeferred:Deferred<Unit>? = null

    private var currentRecordingTimeSum:Long = 0
    private var currentRecordingContinue:Boolean = false
    private var stopInSequence: Boolean = true

    // main cameraX capture functions
    /**
     *   Always bind preview + video capture use case combinations in this sample
     *   (VideoCapture can work on its own). The function should always execute on
     *   the main thread.
     */
    private suspend fun bindCaptureUsecase() {
        val cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()

        val cameraSelector = getCameraSelector(cameraIndex)

        // create the user required QualitySelector (video resolution): we know this is
        // supported, a valid qualitySelector will be created.
        val quality = cameraCapabilities[cameraIndex].qualities[qualityIndex]
        val qualitySelector = QualitySelector.from(quality)

        captureViewBinding.previewView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            val orientation = this@CaptureFragment.resources.configuration.orientation
            dimensionRatio = quality.getAspectRatioString(quality,
                (orientation == Configuration.ORIENTATION_PORTRAIT))
        }

        val preview = Preview.Builder()
            .setTargetAspectRatio(quality.getAspectRatio(quality))
            .build().apply {
                setSurfaceProvider(captureViewBinding.previewView.surfaceProvider)
            }

        // build a recorder, which can:
        //   - record video/audio to MediaStore(only shown here), File, ParcelFileDescriptor
        //   - be used create recording(s) (the recording performs recording)
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                videoCapture,
                preview
            )
        } catch (exc: Exception) {
            // we are on main thread, let's reset the controls on the UI.
            Log.e(TAG, "Use case binding failed", exc)
            resetUIandState("bindToLifecycle failed: $exc")
        }
        enableUI(true)
    }

    /**
     * Kick start the video recording
     *   - config Recorder to capture to MediaStoreOutput
     *   - register RecordEvent Listener
     *   - apply audio request from user
     *   - start recording!
     * After this function, user could start/pause/resume/stop recording and application listens
     * to VideoRecordEvent for the current recording status.
     */
    @SuppressLint("MissingPermission")
    private fun startRecording() {
        // check it start with basic sequence
        // It can cause when USB HID like keyboard is removed or attached.
        if (!stopInSequence)
            return

        // create MediaStoreOutputOptions for our recorder: resulting our recording!
        val name = "cctv-recording-" +
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis()) + ".mp4"

         val pendingRecording = currentPathUri?.let { uri ->
             val folder = DocumentFile.fromTreeUri(requireContext(), uri)
             val subFolder = folder
                 ?.listFiles()
                 ?.find { it.name == "cctv" } ?: folder?.createDirectory("cctv")
             val file = subFolder?.createFile("video/mp4", name)
             currentRecordingFileList.offer(file)

             val fileDescriptor =
                 requireContext().contentResolver.openFileDescriptor(file!!.uri, "w")
             val fileDescriptorOutputOption =
                 FileDescriptorOutputOptions.Builder(fileDescriptor!!).build()
             videoCapture.output
                 .prepareRecording(requireContext(), fileDescriptorOutputOption)
        } ?: run {
            val file = File("/sdcard/DCIM/cctv", name)
             val docFile = DocumentFile.fromFile(file)
             currentRecordingFileList.offer(docFile)
            val fileOutputOption = FileOutputOptions.Builder(file).build()
            videoCapture.output
                .prepareRecording(requireActivity(), fileOutputOption)
        }

        currentRecording = pendingRecording
            .apply { if (audioEnabled) withAudioEnabled() }
            .start(mainThreadExecutor, captureListener)

        Log.i(TAG, "Recording started")
    }

    /**
     * stop the video recording
     *   - check stop sequence true
     *   - kick stop to recorder
     *  you can fish recording stuff in here
     */
    private fun stopRecording(isFinsh: Boolean) {
        val recording = currentRecording
        if (recording != null) {
            recording.stop()
            stopInSequence = true
            currentRecording = null
            if (isFinsh) {
                currentRecordingContinue = false
                currentRecordingFileList.clear()
            }
        }
    }

    /**
     * CaptureEvent listener.
     */
    private val captureListener = Consumer<VideoRecordEvent> { event ->
        // cache the recording state
        if (event !is VideoRecordEvent.Status)
            recordingState = event

        updateUI(event)
    }

    /**
     * Retrieve the asked camera's type(lens facing type). In this sample, only 2 types:
     *   idx is even number:  CameraSelector.LENS_FACING_BACK
     *          odd number:   CameraSelector.LENS_FACING_FRONT
     */
    private fun getCameraSelector(idx: Int) : CameraSelector {
        if (cameraCapabilities.size == 0) {
            Log.i(TAG, "Error: This device does not have any camera, bailing out")
            requireActivity().finish()
        }
        return (cameraCapabilities[idx % cameraCapabilities.size].camSelector)
    }

    data class CameraCapability(val camSelector: CameraSelector, val qualities:List<Quality>)
    /**
     * Query and cache this platform's camera capabilities, run only once.
     */
    init {
        enumerationDeferred = lifecycleScope.async {
            whenCreated {
                val provider = ProcessCameraProvider.getInstance(requireContext()).await()

                provider.unbindAll()
                for (camSelector in arrayOf(
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    CameraSelector.DEFAULT_FRONT_CAMERA
                )) {
                    try {
                        // just get the camera.cameraInfo to query capabilities
                        // we are not binding anything here.
                        if (provider.hasCamera(camSelector)) {
                            val camera = provider.bindToLifecycle(requireActivity(), camSelector)
                            QualitySelector
                                .getSupportedQualities(camera.cameraInfo)
                                .filter { quality ->
                                    listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                                        .contains(quality)
                                }.also {
                                    cameraCapabilities.add(CameraCapability(camSelector, it))
                                }
                        }
                    } catch (exc: java.lang.Exception) {
                        Log.e(TAG, "Camera Face $camSelector is not supported")
                    }
                }
            }
        }
    }

    /**
     * One time initialize for CameraFragment (as a part of fragment layout's creation process).
     * This function performs the following:
     *   - initialize but disable all UI controls except the Quality selection.
     *   - set up the Quality selection recycler view.
     *   - bind use cases to a lifecycle camera, enable UI controls.
     */
    private fun initCameraFragment() {
        initializeUI()
        viewLifecycleOwner.lifecycleScope.launch {
            if (enumerationDeferred != null) {
                enumerationDeferred!!.await()
                enumerationDeferred = null
            }
            initializeQualitySectionsUI()

            bindCaptureUsecase()
        }
    }

    /**
     * Initialize UI. Preview and Capture actions are configured in this function.
     * Note that preview and capture are both initialized either by UI or CameraX callbacks
     * (except the very 1st time upon entering to this fragment in onCreateView()
     */
    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    private fun initializeUI() {
        captureViewBinding.cameraButton.apply {
            setOnClickListener {
                cameraIndex = (cameraIndex + 1) % cameraCapabilities.size
                // camera device change is in effect instantly:
                //   - reset quality selection
                //   - restart preview
                qualityIndex = DEFAULT_QUALITY_IDX
                initializeQualitySectionsUI()
                enableUI(false)
                viewLifecycleOwner.lifecycleScope.launch {
                    bindCaptureUsecase()
                }
            }
            isEnabled = false
        }

        // audioEnabled by default is disabled.
        captureViewBinding.audioSelection.isChecked = audioEnabled
        captureViewBinding.audioSelection.setOnClickListener {
            audioEnabled = captureViewBinding.audioSelection.isChecked
        }

        // rollingEnabled by default is enabled.
        captureViewBinding.rollingSelection.isChecked = isRollingRecord
        captureViewBinding.rollingSelection.setOnClickListener {
            isRollingRecord = captureViewBinding.rollingSelection.isChecked
        }

        // React to user touching the capture button
        captureViewBinding.captureButton.apply {
            setOnClickListener {
                if (!this@CaptureFragment::recordingState.isInitialized ||
                    recordingState is VideoRecordEvent.Finalize)
                {
                    enableUI(false)  // Our eventListener will turn on the Recording UI.
                    currentRecordingTimeSum = 0
                    currentRecordingContinue = true
                    captureViewBinding.changePath.visibility = View.INVISIBLE
                    startRecording()
                } else {
                    when (recordingState) {
                        is VideoRecordEvent.Start -> {
                            currentRecording?.pause()
                            captureViewBinding.stopButton.visibility = View.VISIBLE
                        }
                        is VideoRecordEvent.Pause -> currentRecording?.resume()
                        is VideoRecordEvent.Resume -> currentRecording?.pause()
                        else -> throw IllegalStateException("recordingState in unknown state")
                    }
                }
            }
            isEnabled = false
        }

        captureViewBinding.stopButton.apply {
            setOnClickListener {
                // stopping: hide it after getting a click before we go to viewing fragment
                captureViewBinding.stopButton.visibility = View.INVISIBLE
                if (currentRecording == null || recordingState is VideoRecordEvent.Finalize) {
                    return@setOnClickListener
                }

                stopRecording(true)
                captureViewBinding.captureButton.setImageResource(R.drawable.ic_start)
            }
            // ensure the stop button is initialized disabled & invisible
            visibility = View.INVISIBLE
            isEnabled = false
        }

        captureLiveStatus.observe(viewLifecycleOwner) {
            captureViewBinding.captureStatus.apply {
                post { text = it }
            }
        }
        captureLiveStatus.value = getString(R.string.Idle)

        getPath = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                currentPathUri = result.data?.data
                requireContext().contentResolver
                    .takePersistableUriPermission(
                        currentPathUri!!,
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    )
            }
        }

        captureViewBinding.changePath.apply {
            setOnClickListener {
                val manager = requireActivity().getSystemService(Context.STORAGE_SERVICE) as StorageManager
                val volumes = manager.storageVolumes

                var volumeArray: Array<String> = emptyArray()
                volumes.forEach { volume ->
                    volumeArray += volume.getDescription(requireContext())
                }

                AlertDialog.Builder(requireContext())
                    .setTitle("Select target Media")
                    .setItems(volumeArray) { _, which ->
                        currentUUID = volumes[which].uuid
                        captureViewBinding.changePath.text =
                            "MEDIA : ${currentUUID?.let {currentUUID} ?: "OnBoard"}"
                        currentRecordingVolumeMin =
                            min(currentVolume().totalSpace/10, 1024 * 1024 * 1024)
                        getPath.launch(
                            volumes[which].createAccessIntent(
                                Environment.DIRECTORY_DCIM
                            )
                        )
                    }.show()
            }
        }
    }

    /**
     * UpdateUI according to CameraX VideoRecordEvent type:
     *   - user starts capture.
     *   - this app disables all UI selections.
     *   - this app enables capture run-time UI (pause/resume/stop).
     *   - user controls recording with run-time UI, eventually tap "stop" to end.
     *   - this app informs CameraX recording to stop with recording.stop() (or recording.close()).
     *   - CameraX notify this app that the recording is indeed stopped, with the Finalize event.
     *   - this app starts VideoViewer fragment to view the captured result.
     */
    private fun updateUI(event: VideoRecordEvent) {
        val state = if (event is VideoRecordEvent.Status) recordingState.getNameString()
                    else event.getNameString()

        val stats = event.recordingStats

        when (event) {
                is VideoRecordEvent.Status -> {
                    /**
                     * Storage volume check:
                     *   - with rolling record, just remove fisrt reconrding file.
                     *   - or just stop.
                     */
                    if (isVolumeFull()) {
                        if (isRollingRecord)
                            currentRecordingFileList.poll()?.delete()
                        else {
                            stopRecording(true)
                        }
                    }

                    if (stats.numBytesRecorded > RECORD_SEPARATE_SIZE) {
                        stopRecording(false)
                    }
                }
                is VideoRecordEvent.Start -> {
                    showUI(UiState.RECORDING, event.getNameString())
                }
                is VideoRecordEvent.Finalize-> {
                    if (currentRecordingContinue) {
                        currentRecordingTimeSum += event.recordingStats.recordedDurationNanos
                        startRecording()
                        Log.d(TAG, "uri " + event.outputResults.outputUri)
                    } else
                        showUI(UiState.FINALIZED, event.getNameString())
                }
                is VideoRecordEvent.Pause -> {
                    captureViewBinding.captureButton.setImageResource(R.drawable.ic_resume)
                }
                is VideoRecordEvent.Resume -> {
                    captureViewBinding.captureButton.setImageResource(R.drawable.ic_pause)
                }
        }

        val size = stats.numBytesRecorded / MB
        val time = NANOSECONDS.toSeconds(currentRecordingTimeSum + stats.recordedDurationNanos)

        var text = "${state}: recorded ${time/60}minute ${time%60}second(${size}MB)"
        if(event is VideoRecordEvent.Finalize) {
            text = currentPathUri?.let {
                "${text}\nFile saved to: ${currentPathUri!!.path}/cctv"
            } ?: run {
                "${text}\nFile saved to: ${event.outputResults.outputUri.path}"
            }
        }

        captureLiveStatus.value = text
        Log.i(TAG, "recording event: $text")
    }

    private fun currentVolume(): File {
        return currentUUID?.let {
            File("/storage/${currentUUID}")
        }?: run {
            File("/sdcard")
        }
    }

    private fun isVolumeFull(): Boolean {
        val volume = currentVolume()
        return (volume.usableSpace < currentRecordingVolumeMin)
    }

    /**
     * Enable/disable UI:
     *    User could select the capture parameters when recording is not in session
     *    Once recording is started, need to disable able UI to avoid conflict.
     */
    private fun enableUI(enable: Boolean) {
        arrayOf(captureViewBinding.cameraButton,
                captureViewBinding.captureButton,
                captureViewBinding.stopButton,
                captureViewBinding.audioSelection,
                captureViewBinding.rollingSelection,
                captureViewBinding.qualitySelection,
                captureViewBinding.changePath).forEach {
                    it.isEnabled = enable
        }
        // disable the camera button if no device to switch
        if (cameraCapabilities.size <= 1) {
            captureViewBinding.cameraButton.isEnabled = false
        }
        // disable the resolution list if no resolution to switch
        if (cameraCapabilities[cameraIndex].qualities.size <= 1) {
            captureViewBinding.qualitySelection.apply { isEnabled = false }
        }
    }

    /**
     * initialize UI for recording:
     *  - at recording: hide audio, qualitySelection,change camera UI; enable stop button
     *  - otherwise: show all except the stop button
     */
    private fun showUI(state: UiState, status:String = "idle") {
        captureViewBinding.let {
            when(state) {
                UiState.IDLE -> {
                    it.captureButton.setImageResource(R.drawable.ic_start)
                    it.stopButton.visibility = View.INVISIBLE

                    it.cameraButton.visibility= View.VISIBLE
                    it.audioSelection.visibility = View.VISIBLE
                    it.rollingSelection.visibility = View.VISIBLE
                    it.qualitySelection.visibility=View.VISIBLE
                }
                UiState.RECORDING -> {
                    it.cameraButton.visibility = View.INVISIBLE
                    it.audioSelection.visibility = View.INVISIBLE
                    it.rollingSelection.visibility = View.INVISIBLE
                    it.qualitySelection.visibility = View.INVISIBLE

                    it.captureButton.setImageResource(R.drawable.ic_pause)
                    it.captureButton.isEnabled = true
                    it.stopButton.visibility = View.VISIBLE
                    it.stopButton.isEnabled = true
                }
                UiState.FINALIZED -> {
                    it.captureButton.setImageResource(R.drawable.ic_start)
                    it.stopButton.visibility = View.INVISIBLE
                    it.changePath.visibility = View.VISIBLE
                    it.changePath.isEnabled = true
                    it.rollingSelection.visibility = View.VISIBLE
                    it.rollingSelection.isEnabled = true
                    it.audioSelection.visibility = View.VISIBLE
                    it.audioSelection.isEnabled = true
                }
                else -> {
                    val errorMsg = "Error: showUI($state) is not supported"
                    Log.e(TAG, errorMsg)
                    return
                }
            }
            it.captureStatus.text = status
        }
    }

    /**
     * ResetUI (restart):
     *    in case binding failed, let's give it another change for re-try. In future cases
     *    we might fail and user get notified on the status
     */
    private fun resetUIandState(reason: String) {
        enableUI(true)
        showUI(UiState.IDLE, reason)

        cameraIndex = 0
        qualityIndex = DEFAULT_QUALITY_IDX
        audioEnabled = false
        captureViewBinding.audioSelection.isChecked = audioEnabled
        isRollingRecord = true
        captureViewBinding.rollingSelection.isChecked = isRollingRecord
        initializeQualitySectionsUI()
    }

    /**
     *  initializeQualitySectionsUI():
     *    Populate a RecyclerView to display camera capabilities:
     *       - one front facing
     *       - one back facing
     *    User selection is saved to qualityIndex, will be used
     *    in the bindCaptureUsecase().
     */
    private fun initializeQualitySectionsUI() {
        val selectorStrings = cameraCapabilities[cameraIndex].qualities.map {
            it.getNameString()
        }
        // create the adapter to Quality selection RecyclerView
        captureViewBinding.qualitySelection.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = GenericListAdapter(
                selectorStrings,
                itemLayoutId = R.layout.video_quality_item
            ) { holderView, qcString, position ->

                holderView.apply {
                    findViewById<TextView>(R.id.qualityTextView)?.text = qcString
                    // select the default quality selector
                    isSelected = (position == qualityIndex)
                }

                holderView.setOnClickListener { view ->
                    if (qualityIndex == position) return@setOnClickListener

                    captureViewBinding.qualitySelection.let {
                        // deselect the previous selection on UI.
                        it.findViewHolderForAdapterPosition(qualityIndex)
                            ?.itemView
                            ?.isSelected = false
                    }
                    // turn on the new selection on UI.
                    view.isSelected = true
                    qualityIndex = position

                    // rebind the use cases to put the new QualitySelection in action.
                    enableUI(false)
                    viewLifecycleOwner.lifecycleScope.launch {
                        bindCaptureUsecase()
                    }
                }
            }
            isEnabled = false
        }
    }

    private fun saveConfig() {
        val settings = requireActivity().getPreferences(Context.MODE_PRIVATE) ?: return
        settings.edit {
            this.putString("currentPathUri", currentPathUri?.toString())
            this.putString("currentUUID", currentUUID)
            this.putLong("currentRecordingVolumeMin", currentRecordingVolumeMin)
            this.putBoolean("isRollingRecord", isRollingRecord)
            this.putBoolean("audioEnabled", audioEnabled)
        }
    }

    private fun loadConfig() {
        val settings = requireActivity().getPreferences(Context.MODE_PRIVATE) ?: return
        val pathUri = settings.getString("currentPathUri", null)

        currentPathUri = pathUri?.let { Uri.parse(pathUri)}
        currentUUID = settings.getString("currentUUID", null)
        currentRecordingVolumeMin =
            settings.getLong("currentRecordingVolumeMin", 1024 * 1024  *1024)
        isRollingRecord = settings.getBoolean("isRollingRecord", true)
        audioEnabled = settings.getBoolean("audioEnabled", false)
    }

    // System function implementations
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        loadConfig()
        currentRecordingFileList = ArrayDeque()
        _captureViewBinding = FragmentCaptureBinding.inflate(inflater, container, false)

        captureViewBinding.changePath.text =
            "MEDIA : ${currentUUID?.let {currentUUID} ?: "OnBoard"}"
        return captureViewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initCameraFragment()
    }
    override fun onDestroyView() {
        _captureViewBinding = null
        saveConfig()
        super.onDestroyView()
    }

    override fun onStop() {
        saveConfig()
        super.onStop()
    }

    companion object {
        // default Quality selection if no input from UI
        const val DEFAULT_QUALITY_IDX = 0
        val TAG:String = CaptureFragment::class.java.simpleName
        private const val FILENAME_FORMAT = "yyyyMMdd-HH:mm:ss.SSS"
        private const val RECORD_SEPARATE_SIZE = 0x40000000
    }
}
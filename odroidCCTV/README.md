
ODROID CCTV Sample
===========================

This Sample based on the demonstration of CameraX Video Capture (Recorder) API with Capture + Preview.
Check [here](https://github.com/android/camera-samples/tree/main/CameraXVideo).
As I mentioned above, It based on the CameraXVideo sample. Just add some features for using like CCTV.
So you can use it as a basic cctv program with a single usb camera.

Introduction
------------

The basic shape is similar to the CameraXVideo. But some of things are changed and added.
1. Removed after recording view: No need to see the recorded video on this app. you must see it from gallery and other video player.
2. Add media selector. you can change media location. default location is /sdcard/DCIM/cctv. but you can select other media, then it will create /[volume_root]/DCIM/cctv directory.
3. Add load/save feature for some configurations. load at start app, and save at stop app.
4. Infinite recording is supported. each of recording video file is seperated by 1GB. and you can select option as rolling save or limited stop.
- rolling save: when your media is almost full(left 10%) or left 1GB, most oldest video will be deleted.
- limited stop: when your media is almost full(left 10%) or left 1GB, recording will be stop.

Most of code stuffs are in CaptureFragment. and UI stuff are in fragment_captreu.xml

Pre-requisites
--------------
- Android SDK 31+
- Android Studio Arctic Fox (2020.3.1)
- Device with video capture capability (or emulator)
- ODROID-N2/C4

Getting Started
---------------
This sample uses the Gradle build system. To build this project, use the
"gradlew build" command or use "Import Project" in Android Studio.

If you've found an error in this sample, please file an issue:
https://github.com/xiane/odroid-cctv

Patches are encouraged, and may be submitted by forking this project and
submitting a pull request through GitHub.

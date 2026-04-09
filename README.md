# PopSubs - Standalone Android Subtitle Player (VTT/SRT)

## Description

![alt text](https://github.com/adam-p/markdown-here/raw/master/src/common/images/icon48.jpg "Logo Title Text 1")



PopSubs is an Android application for displaying WebVTT (`.vtt`) and SubRip (`.srt`) subtitle files in a **floating overlay window**, independent of any specific video player. It acts as a **standalone subtitle player**, making it useful when watching videos from sources lacking built-in subtitle support, or when you simply have a separate subtitle file you want to display on your screen.

## Motivation

This project was inspired by the lack of a simple, dedicated **standalone subtitle player for Android** similar to popular desktop applications like **Penguin Subtitle Player**. The goal was to create a lightweight **Android app** focused solely on loading a `.vtt` or `.srt` file and displaying the timed text in a configurable overlay, providing a functional equivalent for mobile users needing such a tool.

## Features

* **Load Local Subtitle Files:** Select `.vtt` and `.srt` files using the Android system file picker. Includes robust filename retrieval.
* **VTT/SRT Parsing:** Parses both WebVTT (`.vtt`) and SubRip (`.srt`) file formats to extract timings and text content. Handles basic format variations.
* **Simulated Playback:** Controls (`Play/Pause`, `Reset`) allow simulating subtitle progression without needing video.
* **Seeking:** A `Material Slider` allows seeking to specific times within the subtitle file.
* **Accurate Timing:** Displays elapsed playback time in `MM:SS.ms` format.
* **Keep Screen Awake:** Option prevents the screen from sleeping during active playback.
* **Floating Overlay Window:** Displays the current subtitle text in a system overlay window that floats above other applications (similar in concept to the **Penguin Subtitle Player** overlay).
* **Overlay Toggle:** Easily hide or show the floating overlay window using a button in the main app.

## Setup & Build

There are two ways to get the app:

1.  **Build from Source (Recommended for Developers):**
    * Clone this repository.
    * Open the project in a recent version of Android Studio (e.g., Iguana or later).
    * Ensure the Material Components dependency (`implementation 'com.google.android.material:material:...'`) is present and synced.
    * Build the project (`Build` > `Make Project`).
    * Run on an Android device or emulator (API 23+ recommended).

2.  **Download Pre-built APK:**
    * Alternatively, check the **[Releases section](releases)** of this repository for a pre-built `app-release.apk` file that you can download and install directly onto your Android device.
    * *(Note: You may need to enable "Install from unknown sources" in your phone's security settings).*

## Usage

1.  Launch PopSubs.
2.  Click "Select VTT / SRT File" and choose a valid `.vtt` or `.srt` file.
3.  Use the Play/Pause/Reset buttons and the Slider to control playback.
4.  Click "Toggle Overlay Visibility" (grant permission if needed) to show/hide the floating subtitles.

## Known Issues / Limitations

* Subtitle parsers are relatively basic; may not support all advanced VTT/SRT features (e.g., complex styling, positioning tags).
* Playback is simulated; **not** synchronized with external video/audio players.
* Basic error handling for malformed files.
* Simple UI.
* Seeking controls are only in the main app interface.
* Overlay cannot be repositioned.

## Keywords

Android, Subtitle Player, VTT Player, SRT Player, Standalone Subtitle Player, Floating Subtitles, Overlay Subtitles, Subtitle Overlay App, Penguin Subtitle Player Alternative, PopSubs

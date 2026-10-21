# Bluright — The Right to Blur

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.10.01-blue.svg)](https://developer.android.com/jetpack/compose)
[![CameraX](https://img.shields.io/badge/CameraX-1.4.1-teal.svg)](https://developer.android.com/training/camerax)
[![MediaPipe](https://img.shields.io/badge/MediaPipe%20Tasks%20Vision-0.10.14-orange.svg)](https://developers.google.com/mediapipe)

**Bluright** is a privacy-first Android camera application engineered to protect individuals who do not wish to be recorded or photographed in public or private spaces.

```
Camera Stream (CameraX) ──> Local Frame Analysis ──> On-Device Face Detection (MediaPipe) ──> Coordinate Mapping & Smoothing ──> Live Privacy Mask (Mosaic/Shield)
```

---

## 🛡️ Core Principles

1. **100% Local Inference**: All AI face detection runs strictly on-device using Google MediaPipe Tasks Vision (`blaze_face_short_range.tflite`). No frames, bitmaps, or telemetry leave the smartphone.
2. **Transparent Privacy State**: Real-time status indicators explicitly communicate system health (`SCANNING`, `FACE DETECTED`, `NO FACES • SAFE`, `DETECTION STALE`, `DETECTOR OFFLINE`).
3. **Fail-Safe Warning**: If detection latency exceeds 650ms or inference stalls, Bluright flags a high-priority warning rather than giving false privacy assurances.
4. **Honest Preview Boundaries**: A preview overlay is not a permanent video redaction. The app explicitly informs users of the distinction between Phase 1 (live viewfinder masking) and Phase 2 (hardware-encoded privacy video pipeline).

---

## 📱 Features (Phase 1 MVP)

- **CameraX High-Performance Viewfinder**: Native hardware camera streaming with lifecycle-aware binding and front/back camera toggling.
- **MediaPipe Tasks Vision BlazeFace**: Integrated lightweight `blaze_face_short_range.tflite` model running in asynchronous `LIVE_STREAM` mode.
- **Orientation & Mirror Compensation**: Precise sensor rotation handling and selfie mirroring ensuring detection coordinates match the user's perception.
- **FILL_CENTER Coordinate Mapping**: Mathematical aspect-ratio transformation compensating for viewfinder cropping and scaling across varied mobile screen ratios.
- **Anti-Flicker Smoothing & Grace Decay**: Linear interpolation (LERP) filter smoothing micro-jitter across frames, with a 240ms grace buffer preventing single-frame drop flashing.
- **Multiple Visual Blur Masks**:
  - **Mosaic Pixelation**: Cybernetic multi-tone pixel grid.
  - **Frosted Privacy**: Multi-layer frosted translucent privacy glass.
  - **Solid Shield**: High-tech privacy crest with corner brackets and confidence score tags.
- **Adaptive Permissions**: Runtime camera permission gate with clear privacy rationale and direct deep-linking to system settings if permanently denied.

---

## 🏗️ Architecture

```
com.bluright.app/
├── MainActivity.kt                       # Edge-to-edge entry point & permission flow
├── analyzer/
│   └── FaceBlurImageAnalyzer.kt          # CameraX frame analyzer, rotation, FPS & watchdog
├── detector/
│   └── FaceDetectorHelper.kt             # MediaPipe Tasks Vision FaceDetector wrapper
├── model/
│   ├── DetectedFace.kt                   # Normalized face coordinates, expansion & LERP
│   ├── DetectionState.kt                 # Scanning, Detected, ZeroFaces, Stale, Unavailable
│   ├── BlurStyle.kt                      # Pixelate, Frosted, Solid Shield
│   └── CameraLens.kt                     # Front vs Back camera lens selector
└── ui/
    ├── components/
    │   ├── CameraControls.kt             # Bottom controls, blur selector, lens switch
    │   ├── FaceBlurOverlay.kt            # High-performance Compose Canvas privacy overlay
    │   ├── PermissionGate.kt             # Informative camera permission gate
    │   ├── PrivacyModeHeader.kt          # Brand header, status notice, flash & lens switch
    │   └── PrivacyStatusBadge.kt         # Real-time state pill with pulsing indicator
    ├── screens/
    │   └── CameraScreen.kt               # Central viewfinder screen orchestrating CameraX & UI
    └── theme/
        ├── Color.kt                      # Navy & Cyan/Teal privacy palette
        ├── Theme.kt                      # Material3 dark privacy theme
        └── Type.kt                       # Clean typography hierarchy
```

---

## 🚀 Building & Running

### Prerequisites
- Android Studio Ladybug / Meerkat (or JDK 17 + Android SDK)
- Android SDK Platform 34 or 35 (minSdk 26, Android 8.0+)
- USB Debugging enabled on an Android phone or an active Android Virtual Device (AVD).

### Build APK
```powershell
.\gradlew.bat assembleDebug
```
The compiled APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Install to Connected Device
```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Run on Device
```powershell
adb shell am start -n com.bluright.app/.MainActivity
```

---

## 🗺️ Roadmap

- **Phase 1 (Current)**: Real-time on-device camera preview with MediaPipe face detection and visual privacy masking.
- **Phase 2**: Hardware MediaCodec video encoding pipeline embedding blurred pixels directly into the video stream, verified privacy checks, and JSON privacy audit manifests.
- **Phase 3**: BLE (Bluetooth Low Energy) opt-out beacon scanning and advertising for consent-based privacy decisions.

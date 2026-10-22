# Bluright — The Right to Blur

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.10.01-blue.svg)](https://developer.android.com/jetpack/compose)
[![CameraX](https://img.shields.io/badge/CameraX-1.4.1-teal.svg)](https://developer.android.com/training/camerax)
[![MediaPipe](https://img.shields.io/badge/MediaPipe%20Tasks%20Vision-0.10.14-orange.svg)](https://developers.google.com/mediapipe)
[![Security](https://img.shields.io/badge/Android%20KeyStore-ECDSA%20P--256-red.svg)](https://developer.android.com/training/articles/keystore)
[![Tests](https://img.shields.io/badge/Unit%20Tests-69%2F69%20Passing-brightgreen.svg)](https://github.com/sapnajha757/Bluright)

**Bluright** is a privacy-first, on-device Android camera application engineered to protect individuals who do not wish to be photographed or recorded in public or private spaces. 

By integrating on-device computer vision, Bluetooth Low Energy (BLE) opt-out beaconing, hardware-level video mask burning, and hardware-backed cryptographic audit signing, Bluright establishes an enforceable, fail-closed "right to blur."

```
Camera Stream (CameraX) ──> Local Vision Inference (MediaPipe BlazeFace)
                                     │
BLE Opt-Out Beacons ────────> FaceAssociationEngine (Proximity & Ambiguity)
                                     │
                     ┌───────────────┴───────────────┐
             [Faces Detected]                [0 Faces + Opt-Out]
                     │                               │
        Burn Masks into Bitmaps          Burn Full-Frame Blackout
        (Pixelate/Frosted/Shield)        (#050811 + Warning Banner)
                     │                               │
                     └───────────────┬───────────────┘
                                     │
                       MediaCodec H.264 Encoder
                                     │
                       Final MP4 + Signed Manifest
                       (Android KeyStore ECDSA P-256)
```

---

## 🛡️ Core Privacy Principles

1. **100% Local Inference**: All AI face detection runs strictly on-device using Google MediaPipe Tasks Vision (`blaze_face_short_range.tflite`). No frames, bitmaps, or telemetry leave the smartphone.
2. **Permanent Bitstream Masking**: Privacy masks are burned directly into frame bitmaps in memory before hardware video encoding (`MediaCodec`). Privacy is permanently redacted in the physical MP4 container, never reliant on external video players or presentation layers.
3. **Fail-Closed Privacy Policy (DEF-01)**: If a BLE opt-out signal is detected in the vicinity but the camera detects zero faces (e.g. subject turned away, motion blur, occlusions, or low light), Bluright burns a full-frame opaque blackout (`#050811`) with an alert banner to guarantee zero biometric leakage.
4. **Session-Sticky Protection**: Once an opt-out beacon is detected during an active recording session, fail-closed protection remains sticky throughout the session. Even if RF packet loss or battery exhaustion drops the BLE signal for longer than the 6-second hold window, unmasked frames will never silently leak.
5. **Cryptographic Provenance**: Every exported recording produces a companion JSON manifest cryptographically signed using an ARM TrustZone / Android KeyStore ECDSA key (`SHA256withECDSA`, NIST P-256), anchoring the exact SHA-256 digest of the MP4 and a per-frame audit breakdown.

---

## 📱 Features

### 1. Viewfinder & AI Detection
- **CameraX Pipeline**: Native hardware camera streaming with lifecycle-aware binding, front/back lens switching, and torch toggle.
- **MediaPipe BlazeFace Tasks Vision**: Ultra-fast on-device face detector in asynchronous `LIVE_STREAM` mode.
- **Orientation & Mirror Compensation**: Sensor rotation handling and selfie mirroring ensuring bounding boxes map accurately across orientations.
- **Anti-Flicker LERP Smoothing**: Exponential moving average and linear interpolation filter with a 240ms grace buffer to prevent micro-jitter and single-frame drop flashing.
- **Three Visual Masking Modes**:
  - **Mosaic Pixelation**: Cybernetic multi-tone pixel grid with high-frequency edge destruction.
  - **Frosted Privacy**: Multi-layer frosted translucent privacy glass.
  - **Solid Shield**: Opaque high-tech privacy crest with corner brackets and confidence tags.

### 2. BLE Opt-Out Protocol
- **BLE Beacon Scanner**: Continuously scans for Bluright's Service UUID (`0xB101`), measuring RSSI signal strengths and tracking nearby opt-out requests.
- **BLE Beacon Transmitter**: Allows any phone running Bluright to act as an opt-out beacon, broadcasting rotating ephemeral cryptographic nonces to prevent tracking.
- **Proximity & Ambiguity Engine**: Evaluates spatial correlation between faces and beacons. When ambiguity exists (e.g. multiple people or weak RSSI), the engine enforces conservative blanket blurring across all visible faces.

### 3. Fail-Closed Video Encoding Pipeline
- **Hardware MediaCodec H.264 Encoder**: Encodes pre-masked video frames directly into MP4 containers via `MediaMuxer`.
- **Sequential FIFO Frame Queue**: Dispatches frame encoding over a single-threaded coroutine context (`Dispatchers.Default.limitedParallelism(1)`) to guarantee strict monotonic presentation timestamps (`presentationTimeUs`).
- **Dynamic Mask Quality Auditor**: Inspects face boundary proximity ($<5\%$ of frame edge) and automatically strengthens masking expansion margins up to 25%.

### 4. Hardware Cryptographic Signing
- **Android KeyStore ECDSA P-256**: Generates non-exportable hardware-backed keys (`bluright_privacy_manifest_key_v1`) stored inside the device's secure element / TEE.
- **Sidecar JSON Manifest**: Exports matching `.manifest.json` reporting total, masked, blacked-out, unmasked, and discarded frames, alongside the MP4 file's SHA-256 digest.
- **Built-in Manifest Verifier**: Built-in verification engine validates signatures and detects any video payload or metadata tampering.

---

## 🏗️ Architecture

```
com.bluright.app/
├── MainActivity.kt                       # Edge-to-edge entry point & permission gate
├── analyzer/
│   └── FaceBlurImageAnalyzer.kt          # CameraX frame analyzer, FPS tracker & latency watchdog
├── audit/
│   └── MaskingQualityAuditor.kt          # Boundary proximity auditor & margin expansion heuristics
├── ble/
│   ├── BleAutomationReceiver.kt          # Intent receiver for automated test triggers
│   ├── BleObservation.kt                 # RSSI, distance heuristics & ephemeral token model
│   ├── BleOptOutAdvertiser.kt            # BLE beacon transmitter with rotating nonces
│   ├── BleOptOutConstants.kt             # Service UUID (0xB101) & protocol constants
│   ├── BleOptOutScanner.kt               # Low-latency BLE scanner & prune job
│   └── BleStates.kt                      # UI state models for Bluetooth status
├── crypto/
│   ├── ManifestVerificationResult.kt     # Verification outcome data models
│   ├── PrivacyManifest.kt                # Cryptographic manifest schema & JSON serializer
│   └── PrivacyManifestSigner.kt          # Android KeyStore ECDSA P-256 signer & verifier
├── detector/
│   └── FaceDetectorHelper.kt             # MediaPipe Tasks Vision FaceDetector wrapper
├── export/
│   ├── ExportState.kt                    # Export lifecycle UI state flow
│   ├── PrivacyExportCoordinator.kt       # Session orchestrator, session-sticky privacy & dispatch
│   ├── PrivacyMaskRenderer.kt            # Permanent pixel mask burning & full-frame blackout
│   ├── PrivacyReceiptCollector.kt        # Per-frame audit receipt accumulator & frame categorization
│   └── PrivacyVideoEncoder.kt            # Native MediaCodec AVC/H.264 & MediaMuxer pipeline
├── model/
│   ├── BlurStyle.kt                      # PIXELATE, FROSTED, SOLID_SHIELD
│   ├── CameraLens.kt                     # FRONT vs BACK selector
│   ├── DetectedFace.kt                   # Normalized face coordinates, expansion margins & LERP
│   └── DetectionState.kt                 # Scanning, Detected, ZeroFaces, Stale, Unavailable
├── privacy/
│   ├── FaceAssociationEngine.kt          # Face-to-beacon proximity & ambiguity association engine
│   └── PrivacyDecision.kt                # Formal privacy decision model
└── ui/
    ├── components/
    │   ├── CameraControls.kt             # Shutter button, recording timer, blur selector
    │   ├── ExportSuccessDialog.kt        # Cryptographic verification inspection modal
    │   ├── FaceBlurOverlay.kt            # High-performance Compose Canvas preview overlay
    │   ├── OptOutBroadcastDialog.kt      # BLE beacon transmitter control modal
    │   ├── PermissionGate.kt             # Camera and BLE runtime permission handler
    │   ├── PrivacyDecisionBadge.kt       # Protection status indicator badge
    │   ├── PrivacyModeHeader.kt          # Header bar with torch & lens controls
    │   └── PrivacyStatusBadge.kt         # Inference state pill (Scanning, Detected, Stale)
    ├── screens/
    │   └── CameraScreen.kt               # Primary viewfinder screen orchestrating CameraX & UI
    └── theme/
        ├── Color.kt                      # Cyber-privacy navy & cyan theme palette
        ├── Theme.kt                      # Material3 dark theme configuration
        └── Type.kt                       # Clean typography hierarchy
```

---

## 🧪 Automated Test Suite

Bluright includes 10 automated unit test suites with **69 tests passing (100% pass rate, 0 failures)**:

| Test Suite | Tests | Description |
| :--- | :---: | :--- |
| `CryptographicTamperAuditTest` | 7 | Tamper detection across manifest fields, public keys, and video bytes |
| `DetectedFaceTest` | 3 | Coordinate normalization, bounding box expansion, and LERP smoothing |
| `DetectionWatchdogAndFailsafeTest` | 4 | 650ms watchdog staleness timeouts and frame drop error handling |
| `FaceAssociationEngineTest` | 8 | BLE proximity heuristics, RSSI thresholding, and spatial ambiguity |
| `FaceDetectionPrivacyValidationTest`| 14 | Edge cases: profile faces, front mirror, invalid dimensions, timeouts |
| `FailClosedAdversarialRegressionTest`| 13 | DEF-01 blackout, session-sticky protection hold, token rotation |
| `MaskingQualityAuditorTest` | 7 | Boundary proximity clamping, coverage scoring, and disclaimer validation |
| `PrivacyManifestSignerTest` | 8 | KeyStore key generation, ECDSA signing, JSON serialization, and verify |
| `PrivacyReceiptCollectorTest` | 2 | Frame accumulator categorization and timing invariants |
| `PrivacyReceiptSemanticsTest` | 3 | Manifest counting semantics ($T = M + B + U + D$) |

To run the unit tests:
```powershell
.\gradlew.bat testDebugUnitTest
```

---

## 🚀 Building & Running

### Prerequisites
- Android Studio Ladybug / Meerkat (or JDK 17 + Android SDK)
- Android SDK Platform 34 or 35 (minSdk 26, Android 8.0+)
- USB Debugging enabled on an Android phone or an active Android Virtual Device (AVD).

### Build Debug APK
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

## ⚖️ Scientific & Operational Disclaimers

1. **Biometric Anonymization Limitations**: BlazeFace is an on-device heuristic vision model. Extreme yaw ($>60^\circ$), severe motion blur, or physical occlusions may cause transient detection misses. In such scenarios, Bluright relies on its BLE fail-closed policy (full-frame blackout).
2. **Contextual Re-Identification**: Mosaic pixelation permanently destroys high-frequency biometric facial data inside the bounding box. However, contextual re-identification through clothing, hair, voice, gait, or setting remains an inherent reality of video. Bluright makes no claim of absolute mathematical anonymity against non-facial contextual cues.
3. **Cryptographic Proof Scope**: Digital signatures attest to audit pipeline execution and file hash integrity; they do not certify that computer-vision algorithms are infallible under unconstrained optical conditions.

# Scalfy – tap to share (Android)

Tap two phones together → glow bloom → "Connected" pill → photos / contacts / files / text are exchanged.

## Build the APK
**Android Studio:** File ▸ Open this folder ▸ Build ▸ Build APK(s)  → `app/build/outputs/apk/debug/app-debug.apk`
**GitHub:** push this folder to a repo → Actions ▸ "Build Scalfy APK" ▸ download the `Scalfy-debug-apk` artifact.
**CLI:** `gradle assembleDebug` (Gradle 8.7, JDK 17, Android SDK 34)

## How it works
1. NFC handshake (HCE + reader mode, roles alternate with jitter) swaps a one-time session token + names.
2. Nearby Connections (Wi-Fi / Bluetooth) carries the actual data after the tap; connections are only accepted within 20s of an NFC tap.
3. Received photos → Pictures/Scalfy, files → Download/Scalfy.

Requirements: Android 10+, NFC, Google Play services, app open on both phones.

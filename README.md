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

## Background mode (no need to open the app)
- Turn on **Tap to share anywhere** in the app, or add the **Scalfy Quick Settings tile** and tap it.
- Grants needed once: Bluetooth/Nearby, notifications, and "Display over other apps" (for the glow + pill over any screen).
- While on, a small "Scalfy is ready" notification keeps the app alive. Tap the tile (or "Turn off") to stop.
- Phone screen must be on and unlocked.

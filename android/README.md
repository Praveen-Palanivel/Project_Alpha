# LocalStream Android Scaffold (B2)

This module contains the Android scaffold for LocalStream:

- Kotlin + Jetpack Compose app shell
- Runtime permission handling for storage/media and nearby Wi-Fi devices
- File picker via `OpenDocument`
- Placeholder `Send` and `Receive` controls (no networking yet)
- UI emphasis on high-speed transfer feedback (speed/status surface)

## Module layout

- `app/src/main/java/com/localstream/android/MainActivity.kt`
- `app/src/main/java/com/localstream/android/ui/LocalStreamApp.kt`
- `app/src/main/java/com/localstream/android/ui/theme/*`
- `app/src/main/AndroidManifest.xml`

## Notes

- Core transfer wiring is intentionally deferred to task B4.
- Discovery/transfer protocol integration will bind to Agent A APIs later.

# LocalStream Android Scaffold (B2)

This module contains the Android scaffold for LocalStream:

- Kotlin + Jetpack Compose app shell
- Runtime permission handling for storage/media and nearby Wi-Fi devices
- File picker via `OpenDocument`
- Send/Receive mode switching
- Device discovery list (mocked adapter for now)
- Manual target IP entry
- Simulated high-speed transfer progress with live MB/s and averages
- Cancel and retry transfer controls
- UI emphasis on high-speed transfer feedback (speed/progress/status surfaces)

## Module layout

- `app/src/main/java/com/localstream/android/MainActivity.kt`
- `app/src/main/java/com/localstream/android/ui/LocalStreamApp.kt`
- `app/src/main/java/com/localstream/android/ui/theme/*`
- `app/src/main/AndroidManifest.xml`

## Notes

- Core transfer wiring is intentionally deferred to task B4.
- Discovery/transfer protocol integration can replace `FakeTransferCoreAdapter` with Agent A APIs.

# LocalBridge v0.1.1-alpha

An early Android-hosted local file-transfer prototype. iPhone uses Safari. No cloud or internet service is used, but both devices must be on the same local Wi-Fi network.

## Included
- Embedded NanoHTTPD server on port 8765.
- Custom mobile web UI with `<input type=file multiple>`.
- Sequential multi-file upload from Safari to Android using HTTP PUT.
- Android file listing and download links.
- Initial WebDAV support: OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE.

## Build
Open in Android Studio with JDK 17, sync Gradle, then build and run on Android 8.0+.

## Test
1. Connect both phones to the same Wi-Fi. Internet access is not required.
2. Start LocalBridge on Android.
3. Open the shown `http://<android-ip>:8765` address in iPhone Safari.
4. Select multiple files, upload, then download one back to iPhone.

## Known alpha limitations
- No authentication or HTTPS. Only use on a trusted private network; stop sharing after use.
- Keep the Android app open. No foreground service/background operation yet.
- No automatic hotspot or QR pairing.
- Uploads are sequential; no resume, parallel transfer, folder upload, or robust Range support.
- Files are stored in app-specific storage.
- Not yet device-tested or production-ready. Compile and test before use with important files.


## Build on GitHub (Android-only workflow)
1. Extract this ZIP and push the project contents to a GitHub repository.
2. Ensure the default branch is named `main` or `master`.
3. Open the repository's **Actions** tab. If prompted, enable GitHub Actions.
4. The `Android CI` workflow runs on push/PR, or start it manually with **Run workflow**.
5. Open the successful run and download the `LocalBridge-v0.1.1-alpha-debug` artifact. It contains `app-debug.apk`.

This workflow installs JDK 17 and Gradle 8.9 on GitHub-hosted Ubuntu, then runs the Android Gradle Plugin build. No local Android Studio, Gradle installation, or Android SDK is required on your phone.

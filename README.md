# LoBBY 0.4.0-alpha

Local-network file sharing from Android. The Android app hosts the server; a separate, pluggable website/client can connect to it.

## 0.4.0-alpha highlights

- Live, non-recursive directory listing. Opening a folder reads only that folder's immediate children; no startup-wide recursive index is required.
- SAF directory enumeration uses `DocumentsContract` where supported, with a provider-compatible fallback.
- HTTP byte-range support for shared files (`206 Partial Content`) so browser audio/video players can seek and clients can request independent byte ranges.
- `HEAD`, `Accept-Ranges`, `Content-Range`, `Content-Length`, ETag and `416 Range Not Satisfiable` support.
- Streamed file responses with bounded memory use.
- Native Android UI refresh: soft neutral background, floating bottom navigation pill, Privacy Policy, Copyright & Credits and Support pages.
- The website folder remains independently pluggable. This release does not redesign the served website.

## Build

Use Android Studio or the repository's GitHub Actions workflow. The project uses Android Gradle Plugin 8.7.3, Kotlin 2.0.21, compile SDK 35 and Java 17.

## Use

1. Install the debug APK.
2. Optionally select a shared folder using Android's system folder picker.
3. Optionally select a custom website folder. It is served independently from the file API.
4. Start sharing and open the displayed LAN URL from another device.
5. Shared files are available under the configured share path (default `/files`); directory listing is available at `/api/files`.

## HTTP range behaviour

The server accepts a single `Range: bytes=start-end` request and responds with `206 Partial Content`, `Content-Range`, `Content-Length` and `Accept-Ranges: bytes`. Invalid or unsatisfiable ranges receive `416`. A client can make multiple independent range requests for parallel downloading, but this Android app does not itself implement a download-manager client or choose the number of connections.

## Known alpha limitations

- Adaptive single/multi-connection download orchestration is client-side and is not included in the Android server app.
- Parallel/resumable chunk uploads are not implemented yet; ordinary PUT uploads remain available.
- SAF random access varies by document provider. Pipe-backed providers may need sequential skipping, making distant seeks slower.
- The Support page contains a placeholder. Add the project's real Buy Me a Coffee URL before publishing.
- The server uses unauthenticated, unencrypted HTTP. Use only on a trusted private network and stop sharing when finished.
- The app must remain open during transfers; this release does not add a foreground service.

## Legal

The in-app privacy and credits pages are starter copy, not legal advice. Review them against the actual release, dependency licenses and any future analytics or external services before distribution.

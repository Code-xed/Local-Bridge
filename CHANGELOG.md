# Changelog

## 0.5.0-alpha

- Added persistent Android Keystore device identities and signed first-time pairing with six-digit verification-code comparison.
- Added trusted-device management and optional automatic acceptance for trusted peers; Ask every time remains the default.
- Added receiver-side accept/decline requests before native uploads.
- Added ECDH-derived AES-GCM encrypted native chunks, signed transfer metadata, HMAC session authentication and replay protection.
- Added concurrent multi-file transfers and parallel chunk connections per file behind a global adaptive scheduler.
- Added Battery Saver, Balanced, Performance, Maximum Throughput and Custom profiles, including adjustable concurrency, chunk and buffer limits.
- Added signed synthetic network/receiver-cache benchmarks with three samples per configuration and saved per-peer/direction results.
- Added live sender and receiver progress, per-file progress, throughput and ETA, retries and cancellation.
- Added receiver-side transfer manifest/chunk restoration and temporary-file finalization with conflict-safe names.
- Added foreground receiving service, Android notifications, in-app Inbox/history and open/share actions.
- Preserved the browser compatibility interface and existing direct HTTP upload route; that legacy route remains unauthenticated and unencrypted.
- Updated the GitHub Actions release version to `0.5.x-alpha`.

## 0.4.0-alpha

- Reworked the Android app home screen with a softer neutral surface, status card and floating bottom navigation pill.
- Added in-app Privacy Policy, Copyright & Credits, and Support information pages.
- Replaced the directory listing implementation with a live, non-recursive listing. SAF providers are queried through `DocumentsContract` where supported, with a `DocumentFile` fallback.
- Added HTTP byte-range responses (206), invalid-range responses (416), `Accept-Ranges`, `Content-Range`, `Content-Length`, `HEAD`, and ETag headers for shared files.
- File content is streamed with bounded buffers; the server does not load whole files into memory.
- Preserved the pluggable website folder and existing file API routes.

## Earlier foundation work

- Reorganized the app into Home, Settings, About and Help destinations.
- Added Android NSD/mDNS advertisement and discovery for `_lobby._tcp.`.
- Added native device selection and Android Share Sheet intake for one or multiple files.
- Preserved the existing browser transfer path and QR/link fallback.
- Added `NETWORK_ARCHITECTURE.md` documenting the protocol direction and iOS constraints.
- Kept Wi-Fi Direct out of the connection strategy.

## Known alpha limitations

- Browser-only devices are not mDNS-discoverable; use the receiver's local URL/QR fallback.
- The browser compatibility upload API is unauthenticated and unencrypted; use only on trusted networks.
- Sender-side durable resume after app process death/relaunch is not yet implemented. Native per-chunk retries and receiver-side restoration are available while the current sender operation remains active.
- Automatic hotspot creation, QR Wi-Fi joining, iOS, Windows and Linux clients are not included.

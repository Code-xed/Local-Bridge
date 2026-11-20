# Changelog

## Unreleased - Native sharing experience

- Added a unified Home discovery dashboard with native device selection and a dedicated Send screen.
- Renamed the receiving control to “Turn on LoBBY” / “Turn off LoBBY”.
- Implemented Android Share Sheet intake for single (`ACTION_SEND`) and multiple (`ACTION_SEND_MULTIPLE`) files, including cold launches and `onNewIntent` delivery.
- Added shared-file previews, multi-file selection, and native HTTP PUT sending through the existing receiver API.
- Advertised the configured share path through mDNS so native senders use the receiver's actual HTTP endpoint.
- Kept the existing browser interface and QR/link fallback intact; no Wi-Fi Direct or separate native transfer protocol was added.
- Fixed Android 10 and earlier storage compatibility by gating Android 11 All Files Access APIs and using SAF on older versions.

## Earlier foundation work

- Reorganized the app into Home, Settings, About and Help destinations.
- Moved storage and website configuration out of the Home screen.
- Reframed the Home screen around receiving and nearby devices.
- Added Android NSD/mDNS advertisement and discovery for `_lobby._tcp.`.
- Preserved the existing browser transfer path as a compatibility fallback.
- Added `NETWORK_ARCHITECTURE.md` documenting the platform-independent protocol direction and iOS constraints.
- Did not add Wi-Fi Direct. Automatic hotspot creation, QR Wi-Fi joining, background transfers, and authenticated/encrypted sessions remain future implementation phases.

# Changelog

## 0.4.0-alpha
- Reworked the Android app home screen with a softer neutral surface, status card and floating bottom navigation pill.
- Added in-app Privacy Policy, Copyright & Credits, and Support information pages.
- Replaced the directory listing implementation with a live, non-recursive listing. SAF providers are queried through `DocumentsContract` where supported, with a `DocumentFile` fallback.
- Added HTTP byte-range responses (206), invalid-range responses (416), `Accept-Ranges`, `Content-Range`, `Content-Length`, `HEAD`, and ETag headers for shared files.
- File content is streamed with bounded buffers; the server does not load whole files into memory.
- Preserved the pluggable website folder and existing file API routes.

### Known alpha limitations
- The server supports independent Range requests, but adaptive multi-connection download orchestration belongs to the client and is not included in the Android server app.
- Resumable/parallel chunk upload protocol is not implemented in this alpha.
- SAF random access depends on the selected document provider. Providers exposing pipe-backed streams may require sequential skipping and can be slow for distant seeks.
- HTTP is unauthenticated and unencrypted. Use only on a trusted LAN.
- The Support page needs the project's actual Buy Me a Coffee URL before release.
- Privacy and credits copy should be reviewed against the final distribution, dependencies and data practices before public release.

## Storage and connection improvements
- Restored separate All Files Access and SAF storage modes.
- Added a native in-app folder browser for All Files Access, with persistent selected-folder preference.
- Added direct filesystem listing, transfer, upload, and delete paths for All Files Access.
- Added a tappable LAN address, copy-link action, and QR code connection dialog.
- Kept the existing SAF and pluggable website flows intact.

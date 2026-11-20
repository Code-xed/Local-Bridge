# Changelog

## Unreleased - Share Sheet and shared HTTP transfer

- Reorganized the app into Home, Settings, About and Help destinations.
- Moved storage and website configuration out of the Home screen.
- Renamed receiving controls to **LoBBY sharing**.
- Added Android Sharesheet support for `ACTION_SEND` and `ACTION_SEND_MULTIPLE`.
- Added a Send screen with shared-file previews and removal controls.
- Native Android sending uses the existing HTTP `PUT` upload endpoint used by the browser interface.
- Added the configured share path to the mDNS service record for native clients.
- Preserved the existing browser interface and browser fallback.
- Kept Wi-Fi Direct excluded; hotspot creation and QR-assisted Wi-Fi joining remain future work.

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

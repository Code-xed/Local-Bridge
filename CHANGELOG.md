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

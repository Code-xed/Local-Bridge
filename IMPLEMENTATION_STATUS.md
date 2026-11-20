# Implementation status

## Implemented in this build

- Four-section Android navigation foundation (Home, Settings, About, Help).
- LoBBY sharing controls use the clearer enable/disable wording.
- Android Sharesheet integration for `ACTION_SEND` and `ACTION_SEND_MULTIPLE`.
- Shared-file preview screen with filename, MIME type, size and remove controls.
- Native Android sending uses HTTP `PUT` against the receiver's existing configured share path.
- mDNS advertises the configured HTTP share path so native clients and browser clients target the same API.
- Existing browser interface and browser fallback remain available.

## Still not implemented

- Automatic temporary hotspot creation and QR-assisted Wi-Fi joining.
- iOS, Windows and Linux clients.
- Background transfer service / persistent notification.
- Encryption or authentication for the existing HTTP API.

## Important network note

The receiver must turn on LoBBY sharing and both devices must have local network connectivity. The current HTTP API uses cleartext HTTP and has no authentication, matching the existing browser transfer behaviour. Use trusted networks only.

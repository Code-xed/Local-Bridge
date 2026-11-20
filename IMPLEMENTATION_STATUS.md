# Implementation status

## Implemented in this build

- Four-section Android navigation foundation (Home, Settings, About, Help).
- Home discovery dashboard with native LoBBY peer selection.
- Receiving control renamed to “Turn on LoBBY” / “Turn off LoBBY”.
- Android Sharesheet handling for `ACTION_SEND` and `ACTION_SEND_MULTIPLE`.
- Incoming shares are handled on cold launch and through `onNewIntent` when the activity is already running.
- Native Send screen previews incoming/selected files, displays name, MIME type and size, and allows removing or adding files.
- Native sending uses HTTP `PUT` against the receiver's existing configured share path.
- mDNS advertises the configured HTTP share path so native clients target the same API as the browser interface.
- Existing browser interface, local URL and QR fallback remain available.
- Android 10 and earlier storage compatibility is preserved by gating Android 11 All Files Access APIs and using SAF on older versions.

## Still not implemented

- Browser-only devices are not mDNS-discoverable; browser fallback is available through the receiver's local URL/QR code.
- Receiver-side accept/reject prompts and authenticated transfer sessions.
- Automatic temporary hotspot creation and QR-assisted Wi-Fi joining.
- iOS, Windows and Linux clients.
- Background transfer service / persistent notification.
- Encryption for the existing HTTP API.

## Important network note

The receiving device must turn on LoBBY sharing. Both devices must have local network connectivity. Native sending and the browser interface use the same existing HTTP API; the API currently uses cleartext HTTP without authentication. Use trusted networks only. The receiver writes incoming files to its configured shared folder and the existing upload endpoint's replacement behavior applies to duplicate filenames.

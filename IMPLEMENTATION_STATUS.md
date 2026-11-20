# Implementation status

This source package is the first implementation slice of the approved redesign.

## Included and implemented

- Four bottom destinations: Home, Settings, About and Help.
- Storage and website configuration moved to Settings.
- Home reframed around receiving and nearby LoBBY devices.
- Android NSD advertisement while the existing receiver server is running.
- Android NSD discovery while Home is open.
- Existing browser QR and HTTP interface retained as a compatibility fallback.
- Cross-platform architecture notes, including iOS-specific constraints.

## Deliberately not enabled yet

These require additional protocol/security work and device testing; the UI does not pretend they are already functional:

- Native authenticated app-to-app file sending and receiver approval.
- Session-token handshake and cryptographic integrity verification.
- Android temporary local-only hotspot creation and teardown.
- QR payloads containing Wi-Fi credentials and short-lived LoBBY session data.
- QR scanner and guided Wi-Fi joining.
- iOS, Windows and Linux client implementations.

The current browser server remains unauthenticated HTTP for compatibility. Use only on a trusted local network and stop receiving when finished. Native app-to-app transfer must not be enabled until authentication and explicit receiver approval are in place.

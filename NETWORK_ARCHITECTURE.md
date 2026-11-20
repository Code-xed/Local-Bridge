# LoBBY Network Architecture (foundation)

## Goals

LoBBY is being designed around a platform-independent transfer protocol. Android is the first client, while iOS, Windows and Linux compatibility are considered in the protocol boundaries from the beginning.

## Current Android foundation

- Existing HTTP server remains available for browser-based compatibility.
- The Android app can advertise its running service through Android Network Service Discovery (NSD).
- The Android app can browse for `_lobby._tcp.` services on the current local network.
- Discovery is local-network only and does not require internet access.
- A discovered endpoint currently opens the existing browser fallback. Native authenticated app-to-app transfer is not yet implemented.

## Intended protocol layers

1. Discovery: mDNS / Bonjour using `_lobby._tcp.`.
2. Session handshake: platform-neutral versioned message format.
3. Authentication: short-lived session credentials and explicit receiver consent.
4. Transport: direct TCP streaming over the existing LAN or a locally established hotspot network.
5. Integrity: per-file size and cryptographic digest verification.

The discovery record is only a rendezvous hint. It must not grant permission to upload or download files.

## Network roles

Sender/receiver roles are independent of network-host roles. Either peer may create a local network where the operating system permits it. The transfer protocol must behave identically over a router LAN or a temporary/manual hotspot.

## Planned connection methods

- Existing Wi-Fi LAN: default path, mDNS discovery plus direct TCP.
- Temporary local-only hotspot: optional Android capability, subject to OS support and user approval.
- QR-assisted joining: QR payload will carry Wi-Fi details where applicable plus a short-lived LoBBY session identifier. Joining a Wi-Fi network alone must never authorize a transfer.
- Manual hotspot: users may create and join a hotspot themselves.

Automatic hotspot creation and QR-based Wi-Fi joining are not implemented in this foundation build. iOS does not expose a general-purpose API for apps to enable Personal Hotspot, so iOS clients will need a user-guided network setup path.

## Cross-platform constraints

- Android uses `NsdManager` for NSD.
- iOS will use Bonjour / Network framework and request Local Network permission.
- Desktop clients will use platform mDNS APIs or a compatible library.
- The wire protocol must not depend on Android classes, Android storage URIs, or Android-specific file APIs.
- Wi-Fi Direct is intentionally not part of the connection strategy.

## Security note

The existing browser server still uses unauthenticated HTTP for compatibility. Do not treat the current browser endpoint or discovery record as the final native transfer security model. Native app-to-app sending must not be enabled until the authenticated session handshake and receiver approval are implemented.

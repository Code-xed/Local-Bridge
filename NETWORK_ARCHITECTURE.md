# LoBBY Network Architecture (foundation)

## Goals

LoBBY is being designed around a platform-independent transfer protocol. Android is the first client, while iOS, Windows and Linux compatibility are considered in the protocol boundaries from the beginning.

## Current Android foundation

- Existing HTTP server remains available for browser-based compatibility.
- The Android app can advertise its running service through Android Network Service Discovery (NSD).
- The Android app can browse for `_lobby._tcp.` services on the current local network.
- Discovery is local-network only and does not require internet access.
- The advertised service record includes the configured HTTP share path.
- Android accepts single-file and multiple-file shares from the system Sharesheet.
- The native Send screen previews shared files and uploads them through the existing HTTP `PUT` endpoint.
- The browser interface remains available as a fallback from the Home device list.

## Transfer foundation

1. Discovery: mDNS / Bonjour using `_lobby._tcp.`.
2. Transfer: the existing HTTP API over TCP, including `PUT` uploads and the existing `GET` / `HEAD` download endpoints.
3. Native clients and the browser interface use the same routes and storage implementation.
4. The Android Sharesheet integration streams the selected content URI to the receiver's advertised HTTP share path.

Do not introduce a second native-only transfer protocol or extra handshake layer when implementing other platform clients. Any future protocol evolution must preserve compatibility with the existing browser API or explicitly version the shared API for every client.

The discovery record is only a rendezvous hint, not an authentication mechanism. The current server accepts requests from devices that can reach it on the local network while sharing is enabled.

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

The existing HTTP server is currently unauthenticated and uses cleartext HTTP on the local network. Native Sharesheet sending uses the same API and therefore has the same trust assumptions as browser uploads. Users should only enable LoBBY sharing on trusted networks and should turn it off when finished. Do not describe the current API as authenticated or encrypted.

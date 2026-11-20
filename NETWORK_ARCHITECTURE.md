# LoBBY Network Architecture

## Design goals

LoBBY uses mDNS for discovery and an HTTP/TCP foundation for both the native
Android client and browser compatibility interface. Native transfers use an
authenticated, encrypted session layered over HTTP. The browser interface keeps
its older direct HTTP routes for compatibility.

Wi-Fi Direct is intentionally excluded. Devices must already share a local
network, such as the same Wi-Fi access point or a manually created hotspot.

## Discovery

- Service type: `_lobby._tcp.` using Android NSD/mDNS.
- The service record advertises the TCP port, configured browser share path and
  a non-secret installation device ID (public-key fingerprint).
- Discovery data is only a rendezvous hint. A matching service name, IP address
  or advertised device ID is not proof of identity.
- Native clients fetch the peer's public identity and verify its fingerprint
  against the ID advertised by mDNS.

## Device identity and pairing

Each Android installation creates a P-256 EC key pair in Android Keystore.
The private key is non-exportable through the app's identity API; the public
key determines a stable 128-bit device fingerprint. Pairing requests are
signed by the requesting device. Both devices derive the same six-digit
verification code from the public keys and a fresh request nonce. Users must
compare the displayed codes out-of-band and approve on the receiving device.

Each side stores the other's public key in its trusted-device list. Names and
trust can be managed in Settings. Automatic acceptance is disabled by default
and applies only to already paired identities.

## Native transfer protocol

The native protocol is versioned under `/api/lobby/v1/` and remains HTTP-based:

1. `GET /identity` retrieves the peer's public identity.
2. `POST /pair/request`, `GET /pair/requests` and `GET /pair/status` implement
   signed first-time pairing and verification-code exchange.
3. `POST /transfers/request` submits signed sender metadata and an ephemeral
   ECDH public key. The receiver queues the request for local approval or
   accepts it automatically only when trusted-device auto-accept is enabled.
4. The receiver creates its own ephemeral ECDH key pair and signs the session
   transcript. Both sides derive a per-transfer AES key with ECDH and an
   HMAC-based key derivation step.
5. `PUT /transfers/{id}/files/{file}/chunks/{chunk}` carries independently
   encrypted AES-GCM chunks. Session requests are authenticated with HMAC and
   replay nonces.
6. `POST /transfers/{id}/complete` verifies that all chunks arrived and commits
   staged files into the configured destination. `POST /cancel` cancels an
   active session.

Chunks are written to temporary app-private files. The receiver validates the
AES-GCM tag before writing plaintext to the staged file. Completed files are
then copied to temporary destination entries and renamed into place, with
filename conflicts resolved without overwriting an existing file.

The receiver persists transfer manifests and completed chunk indexes so the
receiving service can restore incomplete sessions after its process restarts.
The sender retries failed chunks while its transfer operation remains alive;
durable sender-side resume after process death/relaunch is not yet implemented.

## Parallel transfer scheduler

Two levels of parallelism are supported:

- Several files can be active simultaneously.
- Each active file can have multiple chunk connections.

A global scheduler limits total workers, per-file workers and simultaneous
files. Adaptive mode increases or decreases worker count based on measured
throughput, never exceeding the selected profile/custom ceilings. There is no
artificial payload rate limit.

Settings provide Battery Saver, Balanced, Performance, Maximum Throughput and
Custom profiles. A signed benchmark probe uploads generated payloads into the
receiver's temporary cache using file-count × connections-per-file test cases.
Three samples are measured per case and the best median configuration is saved
per peer and direction. This benchmark measures network plus receiver cache
write behaviour. It does not fully measure sender source-disk reads, AES-GCM
work, final destination copy speed or every SAF provider's performance, so its
result is a starting profile rather than a guaranteed file-transfer rate.

The design target is a 60 MB/s median sustained native payload rate on capable
modern Wi-Fi devices. Actual speed depends on the weaker device and the complete
network/storage path.

## Background receiving, notifications and history

When receiving is enabled, a foreground service keeps the native HTTP server
and mDNS advertisement running while the UI is backgrounded. Android
notifications report incoming requests, active receiving and completion. The
in-app Inbox retains completed, declined, failed and cancelled transfer
records, and lets users open or share completed files.

## Browser compatibility surface

The older browser-facing routes (`GET`, `HEAD`, byte-range reads, direct `PUT`
upload and delete under the configured share path) remain available so existing
browser clients continue working. These legacy routes are **not** protected by
the native identity/session protocol: they use cleartext HTTP, do not require
pairing or receiver approval, and direct uploads are not staged atomically.
They should only be used on a trusted local network.

Native clients must use the authenticated `/api/lobby/v1/` transfer routes.
The browser compatibility path must not be described as encrypted or
authenticated.

## Cross-platform direction

The wire-level concepts (device identity, pairing, request approval, ECDH
session establishment, chunk encryption, progress and transfer history) are
not intended to depend on Android storage URIs. Future iOS and desktop clients
will need their own platform discovery, secure-key storage and filesystem
adapters. Bonjour/Network.framework is the expected discovery direction on
iOS. iOS clients will need user-guided network setup; apps cannot generally
turn on Personal Hotspot for the user.

## Explicit exclusions

- No Wi-Fi Direct.
- No automatic hotspot enablement in this release.
- No second native-only transport stack separate from the HTTP foundation.
- No claim that legacy browser uploads are authenticated or encrypted.

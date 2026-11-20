# Implementation status: v0.5-alpha

## Implemented in this source tree

- Existing Home, Send, Settings, About and Help areas retained.
- Native mDNS discovery advertises configured share path and installation device fingerprint.
- Persistent P-256 installation identity generated in Android Keystore.
- Signed pairing requests with six-digit verification code, local approval and stored trusted peers.
- Trusted-peer rename and revoke controls; optional auto-accept for trusted peers, disabled by default.
- Signed transfer metadata, ephemeral ECDH session key establishment, AES-GCM encrypted chunks, HMAC-authenticated session requests and replay nonces.
- Receiver-side accept/decline before native upload begins.
- Multiple files can transfer in parallel, with parallel chunks per file and a global worker scheduler.
- Battery Saver, Balanced, Performance, Maximum Throughput and Custom transfer profiles.
- Custom ceilings for simultaneous files, connections per file, total connections, chunk size and per-worker buffer size.
- Adaptive worker adjustment and saved peer/direction benchmark results.
- Signed generated-data benchmark across multiple file/worker combinations, with three measured samples per configuration.
- Live sender progress, per-file progress, throughput and ETA; receiver progress, per-file progress, throughput and ETA.
- Native cancellation, per-chunk retries, receiver-side persisted chunk indexes and service restoration.
- Receiver temporary files, authenticated chunk validation and finalization without overwriting existing filenames.
- Foreground receiving service, incoming/accepted/completed notifications, in-app Inbox/history and open/share actions for completed files.
- Existing browser interface, local URL/QR fallback, settings, storage configuration and Android Share Sheet integration retained.
- Build workflow release version advanced to `0.5.x-alpha`.

## Known limitations and compatibility boundaries

- Browser-only devices are not mDNS-discoverable; use the local URL/QR fallback.
- The legacy browser routes, including direct `PUT` upload, remain unauthenticated and unencrypted for compatibility. They do not use native accept/decline, encrypted chunks or staging/finalization.
- The native HTTP control metadata is signed but not protected by TLS. File chunks are AES-GCM encrypted and transfer-session requests are HMAC authenticated.
- The transfer session key is persisted in app-private receiver session metadata to support receiver restoration. Application-level encryption of that metadata at rest is not yet added.
- Receiver-side session state and completed chunks survive a receiving-service process restart. Sender-side durable resume after the sender process is killed/relaunched is not yet implemented; retries are available while the current send operation remains alive.
- Benchmark results are synthetic and directional. They cover signed concurrent HTTP uploads into receiver app-cache temporary files, but do not reproduce sender source-disk reads, AES-GCM payload encryption overhead or final configured destination/SAF copy performance.
- The 60 MB/s median is an engineering target for capable modern devices, not a guaranteed rate. Wi-Fi, CPU and storage limitations on older devices will reduce throughput.
- iOS, Windows and Linux apps, automatic hotspot creation and QR Wi-Fi joining are not included.

## Build and verification

Builds are intended to run through the repository's GitHub Actions workflow. This working environment does not include the Android SDK/Gradle distribution, so a full Android compile or device test has not been run here. The first GitHub Actions run is the authoritative compile check; test pairing, sender/receiver transfer, cancellation, notifications, permissions, benchmarks and storage providers on two physical Android devices before publishing broadly.

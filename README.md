# LoBBY

**Local-first file sharing over your existing Wi-Fi network.**

LoBBY discovers nearby LoBBY devices over mDNS and provides a native Android
sending and receiving experience alongside a browser compatibility interface.
The v0.5-alpha line introduces paired device identities, receiver approval,
encrypted native transfer sessions, parallel chunk scheduling and performance
profiles.

LoBBY is an independent open-source project by **Code-xed**.

## What it does

- Discovers nearby LoBBY installations on the same local network.
- Uses a persistent Android Keystore device identity and one-time verification-code pairing.
- Lets the receiver ask for each transfer or automatically accept previously trusted devices.
- Encrypts native transfer payload chunks with AES-GCM and authenticates transfer-session requests.
- Transfers multiple files concurrently and can run multiple chunk connections per file, within a global adaptive worker limit.
- Shows sender and receiver progress, per-file completion, throughput and estimated time remaining.
- Provides Battery Saver, Balanced, Performance, Maximum Throughput and Custom profiles.
- Benchmarks signed parallel HTTP uploads with generated data and stores the best measured configuration per peer and direction.
- Keeps an in-app Received/history view and Android notifications for native transfer requests and completed transfers.
- Receives into temporary app-private files before finalizing completed native transfers into the configured shared folder.
- Preserves the browser URL/QR compatibility interface and the existing HTTP `PUT` upload route.
- Supports Android Share Sheet intake for one or multiple files.

## Performance target

The engineering target is a **60 MB/s median sustained native payload rate** on
capable modern Wi-Fi devices. This is a target, not a guarantee. Actual
throughput depends on both devices, the access point, signal quality, network
contention, CPU, source storage and destination storage. LoBBY applies no
artificial bandwidth cap. Older devices use the fastest stable configuration
their hardware can sustain.

The benchmark is a useful configuration probe, not a promise of real-file
throughput. It uses generated data and temporary receiver-cache writes; it does
not fully reproduce sender source-storage reads, AES-GCM encryption cost or the
final copy/rename path of every SAF provider.

## Build

The project uses Android Gradle Plugin 8.7.3, Kotlin 2.0.21, compile SDK 35
and Java 17. Release builds and publishing are handled by the repository's
GitHub Actions workflow. The workflow publishes v0.5.x-alpha releases from
`main`.

## Use

1. Install the APK from the project's GitHub Releases.
2. Select the folder where received files should be saved.
3. Turn on LoBBY on the receiving device.
4. On the sending device, select the nearby receiver or share files to LoBBY.
5. Pair the devices the first time. Compare the six-digit verification code on both devices before approving the pairing.
6. Send files. The receiver accepts or declines unless trusted-device auto-accept is enabled.
7. Configure transfer behaviour under **Settings → Transfer Performance** and run a benchmark against a paired receiver.
8. Open **Inbox** to review received files and transfer history.
9. For browser-only clients, open the displayed LAN address or scan the browser QR code.

## Network and security notes

The native LoBBY API uses signed device identities, authenticated transfer
sessions and AES-GCM encrypted file chunks. Its HTTP control metadata remains
visible to devices on the local network; the project does not currently wrap
the whole connection in TLS.

The legacy browser compatibility routes, including direct `PUT` uploads, remain
unauthenticated and unencrypted for backwards compatibility. They do not use
the native pairing, accept/decline or temporary-file workflow. Anyone who can
reach those legacy routes may be able to access or modify exposed data while
sharing is enabled. Use a trusted private network and turn off LoBBY when not
needed.

## Current limitations

- Browser-only devices are not mDNS-discoverable; use the local URL/QR fallback.
- Sender-side resume after the sender app process is killed and relaunched is not yet durable. Active chunk retries and receiver-side session restoration are implemented.
- The current benchmark measures synthetic signed HTTP uploads into receiver cache, not every stage of a real encrypted file transfer.
- iOS, Windows and Linux clients are not included in this Android build.
- Automatic hotspot creation and QR-assisted Wi-Fi joining are not implemented.

## Open-source license

LoBBY's original source code and project documentation are licensed under the
[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). Third-party dependencies keep
their own licenses; see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Support the project

LoBBY is free to use. If you find it useful and want to support continued
maintenance, you can do so voluntarily at https://buymeacoffee.com/code.xed.
Support is optional and does not unlock features or change app functionality.

## Project links

- Source code: https://github.com/Code-xed/Local-Bridge
- Support: https://buymeacoffee.com/code.xed
- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0

# LoBBY

**Local-network file sharing from Android.** Your Android device hosts the
server; another device on the same trusted network connects through a browser
or a compatible client.

LoBBY is an independent open-source project by **Code-xed**.

## What it does

- Shares a folder selected through Android's Storage Access Framework, or
  storage locations permitted by Android's All Files Access setting.
- Serves directory listings and files over a local HTTP server.
- Supports HTTP byte ranges for media seeking and clients that request
  independent portions of a file.
- Provides a QR code and local address to make connecting another device easy.
- Can serve a separately selected website/client folder.

## Build

The project uses Android Gradle Plugin 8.7.3, Kotlin 2.0.21, compile SDK 35
and Java 17. Builds and release publishing are handled through the repository's
GitHub Actions workflow.

## Use

1. Install the APK from the project's GitHub Releases.
2. Select the folder you want to share.
3. Start the local server.
4. Connect another device to the same trusted Wi-Fi network or hotspot.
5. Open the displayed LAN address or scan the QR code.
6. Stop the server when the transfer is finished.

## Network and security notes

The current alpha server uses unencrypted HTTP and does not provide
authentication. Any device that can reach the server on the local network may
be able to access the shared folder while sharing is enabled. Use a trusted
private network, share only folders you intend to expose, and stop the server
when finished. Keep LoBBY open while transfers are in progress.

## Open-source license

LoBBY's original source code and project documentation are licensed under the
[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). Third-party dependencies keep
their own licenses; see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Support the project

LoBBY is free to use. If you find it useful and want to support continued
maintenance and development, you can do so voluntarily at
https://buymeacoffee.com/code.xed. Support is optional and does not unlock
features or change app functionality.

## Project links

- Source code: https://github.com/Code-xed/Local-Bridge
- Support: https://buymeacoffee.com/code.xed
- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0

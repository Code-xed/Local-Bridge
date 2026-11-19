# LoBBY v0.3.0-alpha.1

Local HTTP file sharing between Android and iPhone. The iPhone can open the server URL in Safari; both devices use the same local network and shared storage.

## v0.3.0-alpha.1
- Adds Android All Files Access as the primary shared-storage mode.
- Keeps the Storage Access Framework folder picker for sharing a specific folder instead.
- Adds a persistent SQLite filesystem index for fast `/api/files` listings.
- Performs an initial scan, watches filesystem changes where direct filesystem access is available, and periodically reconciles the index.
- Restructures the native Android app screen around server status, storage, indexing and settings.
- APK filename and Actions artifact: `LoBBY-0.3.0-alpha.1.apk`.

## Storage access
Grant **All Files Access** to share the shared internal-storage root. This includes accessible shared folders such as `Download`, `Documents` and `DCIM`. Android still restricts access to other apps' private data under locations such as `Android/data` and `Android/obb`.

Alternatively, use **Choose a specific shared folder** to grant access to a folder through Android's system folder picker. All Files Access and SAF are alternatives; both are not required.

## Build with GitHub Actions
1. Upload the project contents to the root of a GitHub repository.
2. Push to `main` or `master`, or run the workflow manually from Actions.
3. Download the `LoBBY-0.3.0-alpha.1` artifact. The APK inside is named `LoBBY-0.3.0-alpha.1.apk`.

## Usage
1. Install the APK and grant All Files Access, or choose a specific shared folder.
2. Wait for the initial index scan to begin/populate. The server can start while indexing continues.
3. Optionally choose a custom website folder and/or change the file share URL path.
4. Start the server and open the displayed HTTP address from the other device on the same Wi-Fi.

A custom website replaces the built-in page at `/`. Keep using `/api/files` and the configured share path for file operations. The custom website folder is independent of the shared storage folder.

## Indexing notes
The index is stored locally in the app database and survives app restarts. Direct filesystem access uses directory observers for changes, with a periodic reconciliation scan as a fallback. SAF-backed folders are reconciled periodically and updated immediately after operations performed through LoBBY. The app performs a fresh reconciliation when opened.

## Current limitations
- HTTP only, with no TLS or authentication. Anyone able to reach the server on the local network may access the shared files. Use only on a trusted network.
- The app must remain open during transfers and indexing.
- Android restrictions still apply to protected app-private directories.
- No automatic hotspot pairing, resumable transfers, or background service.
- The server supports the current HTTP file operations; it is not a complete standards-compliant WebDAV implementation.

# LocalBridge v0.2.0-alpha

Local HTTP file sharing between Android and iPhone. The iPhone can open the server URL in Safari; both devices use the same HTTP server and shared storage.

## v0.2 changes
- Choose a shared folder through Android's system folder picker.
- Existing default storage remains `Android/data/app.localbridge/files/Shared` when no custom folder is selected.
- Configure the file URL path (default `/files`).
- Choose a static website folder. LocalBridge serves its `index.html`, CSS, JavaScript and other assets from `/`.
- Browse nested shared folders in the built-in browser page.
- Folder permissions are persisted through Android's Storage Access Framework.

## Build with GitHub Actions
1. Upload the project contents to the root of a GitHub repository.
2. Push to `main` or `master`, or run **Android Build** manually from Actions.
3. Download the `LocalBridge-v0.2.0-alpha-debug` artifact.

## Usage
1. Install the debug APK.
2. Optionally choose a shared folder and/or website folder.
3. Set the file share URL path. Stop the server before changing settings.
4. Start sharing and open the displayed HTTP URL from the other device.

If a custom website folder is selected, put `index.html` at its root. The built-in transfer UI is served when no custom website is selected. File listing remains available at `/api/files`; uploads/downloads use the configured share path.

## Current limitations
- HTTP only, no TLS or authentication. Use only on a trusted private network.
- The app must remain open during transfers.
- No automatic hotspot pairing, resumable transfers, or background service.
- The server supports the current HTTP file operations; it is not a complete standards-compliant WebDAV implementation.
- A custom website replaces the built-in Safari interface at `/`; use `/api/files` and the configured share path for file operations.

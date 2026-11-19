package app.localbridge

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Local LAN server. Directory browsing is live and non-recursive. Media/download
 * responses support single HTTP byte ranges so browser players can seek and
 * download clients can issue independent parallel Range requests.
 */
class LocalBridgeServer(
    private val context: Context,
    private val sharedTreeUri: Uri?,
    private val websiteTreeUri: Uri?,
    sharePathInput: String
) : NanoHTTPD("0.0.0.0", 8765) {

    private val sharePath = normalizePath(sharePathInput)
    private val defaultSharedFolder: File = (context.getExternalFilesDir("Shared") ?: File(context.filesDir, "Shared")).apply { mkdirs() }
    private val sharedFileRoot: File? = if (sharedTreeUri == null) defaultSharedFolder else null
    private val sharedRoot: DocumentFile? = sharedTreeUri?.let { DocumentFile.fromTreeUri(context, it) }
        ?: DocumentFile.fromFile(defaultSharedFolder)
    private val websiteRoot = websiteTreeUri?.let { DocumentFile.fromTreeUri(context, it) }
    private val fallbackHtml = context.assets.open("index.html").bufferedReader().use { it.readText() }

    override fun serve(session: IHTTPSession): Response {
        return try {
            val rawPath = session.uri.substringBefore('?')
            when {
                session.method == Method.OPTIONS -> newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
                session.method == Method.GET && rawPath == "/api/files" -> listing(session.parameters["path"]?.firstOrNull().orEmpty())
                session.method == Method.GET && rawPath.startsWith("$sharePath/") -> transfer(rawPath.removePrefix("$sharePath/"), session.headers["range"])
                session.method == Method.GET && rawPath == sharePath -> listing("")
                session.method == Method.HEAD && rawPath.startsWith("$sharePath/") -> head(rawPath.removePrefix("$sharePath/"))
                session.method == Method.PUT && rawPath.startsWith("$sharePath/") -> upload(session, rawPath.removePrefix("$sharePath/"))
                session.method == Method.DELETE && rawPath.startsWith("$sharePath/") -> delete(rawPath.removePrefix("$sharePath/"))
                session.method == Method.GET -> serveWebsite(rawPath)
                else -> text(Response.Status.NOT_FOUND, "Not found")
            }.withCommonHeaders()
        } catch (e: IllegalArgumentException) {
            text(Response.Status.BAD_REQUEST, e.message ?: "Bad request").withCommonHeaders()
        } catch (e: Exception) {
            text(Response.Status.INTERNAL_ERROR, e.message ?: "Server error").withCommonHeaders()
        }
    }

    private fun Response.withCommonHeaders(): Response = apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, PUT, HEAD, DELETE, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Content-Type, Range, If-Range, Depth")
        addHeader("Access-Control-Expose-Headers", "Accept-Ranges, Content-Length, Content-Range, ETag")
        addHeader("DAV", "1")
        addHeader("X-Content-Type-Options", "nosniff")
    }

    private fun normalizePath(value: String): String {
        val cleaned = value.trim().ifBlank { "/files" }
        val withSlash = if (cleaned.startsWith('/')) cleaned else "/$cleaned"
        val normalized = withSlash.trimEnd('/').ifBlank { "/files" }
        require(normalized.matches(Regex("/[A-Za-z0-9_/-]+")) && !normalized.contains("//") && normalized.split('/').none { it == "." || it == ".." }) {
            "Use letters, numbers, hyphens, underscores, and slashes in the share path"
        }
        require(normalized != "/" && normalized != "/api" && normalized != "/api/files") { "Choose a share path other than /, /api, or /api/files" }
        return normalized
    }

    private fun decodedSegments(path: String): List<String> = path.split('/')
        .filter { it.isNotBlank() }
        .map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        .also { parts -> require(parts.all { it != "." && it != ".." && !it.contains('/') && !it.contains('\\') }) { "Invalid path" } }

    private fun resolveDocument(path: String): DocumentFile? {
        var current = sharedRoot?.takeIf { it.exists() && it.isDirectory } ?: return null
        decodedSegments(path).forEach { segment -> current = current.findFile(segment) ?: return null }
        return current
    }

    private fun resolveFile(path: String): File? {
        val root = sharedFileRoot ?: return null
        val candidate = decodedSegments(path).fold(root) { parent, child -> File(parent, child) }
        val canonicalRoot = root.canonicalFile
        val canonical = candidate.canonicalFile
        return canonical.takeIf { it.path == canonicalRoot.path || it.path.startsWith(canonicalRoot.path + File.separator) }
    }

    /** Uses DocumentsContract's children query for one directory only. */
    private fun listing(relativePath: String): Response {
        if (sharedFileRoot != null) {
            val directory = resolveFile(relativePath) ?: return text(Response.Status.NOT_FOUND, "Folder not found")
            if (!directory.exists() || !directory.isDirectory) return text(Response.Status.NOT_FOUND, "Folder not found")
            val array = JSONArray()
            directory.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })?.forEach { item ->
                array.put(JSONObject().put("name", item.name).put("size", if (item.isFile) item.length() else -1L)
                    .put("directory", item.isDirectory).put("modified", item.lastModified()).put("type", if (item.isDirectory) "vnd.android.document/directory" else mimeType(item.name)))
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", array.toString())
        }
        val directory = if (relativePath.isBlank()) sharedRoot else resolveDocument(relativePath)
            ?: return text(Response.Status.NOT_FOUND, "Folder not found")
        if (directory == null || !directory.exists() || !directory.isDirectory) return text(Response.Status.NOT_FOUND, "Folder not found")
        val rows = JSONArray()
        val childrenUri = try { DocumentsContract.buildChildDocumentsUriUsingTree(directory.uri, DocumentsContract.getDocumentId(directory.uri)) }
            catch (_: Exception) { null }
        var queried = false
        if (childrenUri != null) {
            try {
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                )
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    while (cursor.moveToNext()) {
                        val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                        val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                        rows.put(JSONObject()
                            .put("name", name)
                            .put("size", if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L)
                            .put("directory", mime == DocumentsContract.Document.MIME_TYPE_DIR)
                            .put("modified", if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) cursor.getLong(modifiedCol) else 0L)
                            .put("type", mime ?: "application/octet-stream"))
                    }
                    queried = true
                }
            } catch (_: Exception) { /* Some providers reject projections; use provider fallback. */ }
        }
        if (!queried) {
            directory.listFiles().forEach { item ->
                rows.put(JSONObject().put("name", item.name ?: "").put("size", item.length())
                    .put("directory", item.isDirectory).put("modified", item.lastModified()).put("type", item.type ?: "application/octet-stream"))
            }
        }
        // Sort only the immediate children; no recursive scan or database dependency.
        val items = (0 until rows.length()).map { rows.getJSONObject(it) }
            .sortedWith(compareByDescending<JSONObject> { it.optBoolean("directory") }.thenBy { it.optString("name").lowercase() })
        val result = JSONArray()
        items.forEach { result.put(it) }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", result.toString())
    }

    private data class ByteRange(val start: Long, val end: Long)

    private fun parseRange(header: String?, length: Long): ByteRange? {
        if (header.isNullOrBlank()) return null
        if (!header.startsWith("bytes=") || header.contains(',')) throw IllegalArgumentException("Only one byte range is supported")
        val spec = header.removePrefix("bytes=").trim()
        val parts = spec.split('-', limit = 2)
        if (parts.size != 2 || length <= 0) throw IllegalArgumentException("Invalid byte range")
        val start: Long
        val end: Long
        if (parts[0].isBlank()) {
            val suffix = parts[1].toLongOrNull() ?: throw IllegalArgumentException("Invalid byte range")
            if (suffix <= 0) throw IllegalArgumentException("Invalid byte range")
            start = (length - suffix).coerceAtLeast(0)
            end = length - 1
        } else {
            start = parts[0].toLongOrNull() ?: throw IllegalArgumentException("Invalid byte range")
            end = if (parts[1].isBlank()) length - 1 else (parts[1].toLongOrNull() ?: throw IllegalArgumentException("Invalid byte range"))
        }
        if (start >= length || end < start) throw IllegalArgumentException("Range not satisfiable")
        return ByteRange(start, end.coerceAtMost(length - 1))
    }

    private fun transfer(relativePath: String, rangeHeader: String?): Response {
        if (sharedFileRoot != null) {
            val file = resolveFile(relativePath)?.takeIf { it.exists() } ?: return text(Response.Status.NOT_FOUND, "Not found")
            if (file.isDirectory) return listing(relativePath)
            if (!file.isFile) return text(Response.Status.NOT_FOUND, "Not found")
            val length = file.length()
            val range = try { parseRange(rangeHeader, length) } catch (_: IllegalArgumentException) { return rangeNotSatisfiable(length) }
            val start = range?.start ?: 0L
            val count = range?.let { it.end - it.start + 1 } ?: length
            val input = FileInputStream(file)
            input.channel.position(start)
            val bounded = object : FilterInputStream(input) {
                private var remaining = count
                override fun read(): Int { if (remaining <= 0) return -1; val v = super.read(); if (v >= 0) remaining--; return v }
                override fun read(b: ByteArray, off: Int, len: Int): Int { if (remaining <= 0) return -1; val n = super.read(b, off, minOf(len.toLong(), remaining).toInt()); if (n > 0) remaining -= n; return n }
            }
            return newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT, mimeType(file.name), bounded, count).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Length", count.toString())
                if (range != null) addHeader("Content-Range", "bytes ${range.start}-${range.end}/$length")
                addHeader("ETag", "W/\"${length}-${file.lastModified()}\"")
                addHeader("Cache-Control", "no-cache")
            }
        }
        val doc = resolveDocument(relativePath) ?: return text(Response.Status.NOT_FOUND, "Not found")
        if (doc.isDirectory) return listing(relativePath)
        val length = doc.length()
        if (length < 0) {
            if (!rangeHeader.isNullOrBlank()) return rangeNotSatisfiable(-1)
            val stream = context.contentResolver.openInputStream(doc.uri) ?: return text(Response.Status.NOT_FOUND, "Not found")
            return newChunkedResponse(Response.Status.OK, doc.type ?: mimeType(doc.name ?: "file"), stream)
                .apply { addHeader("Accept-Ranges", "none") }
        }
        val range = try { parseRange(rangeHeader, length) } catch (_: IllegalArgumentException) { return rangeNotSatisfiable(length) }
        val start = range?.start ?: 0L
        val count = range?.let { it.end - it.start + 1 } ?: length
        val stream = openAt(doc.uri, start) ?: return text(Response.Status.INTERNAL_ERROR, "Storage provider cannot open this file")
        val bounded = object : FilterInputStream(stream) {
            private var remaining = count
            override fun read(): Int {
                if (remaining <= 0) return -1
                val value = super.read()
                if (value >= 0) remaining--
                return value
            }
            override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val read = super.read(buffer, offset, minOf(len.toLong(), remaining).toInt())
                if (read > 0) remaining -= read
                return read
            }
        }
        val status = if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
        return newFixedLengthResponse(status, doc.type ?: mimeType(doc.name ?: "file"), bounded, count).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Length", count.toString())
            if (range != null) addHeader("Content-Range", "bytes ${range.start}-${range.end}/$length")
            addHeader("ETag", "W/\"${length}-${doc.lastModified()}\"")
            addHeader("Cache-Control", "no-cache")
        }
    }

    /** Seek using the provider's file descriptor when possible; sequential fallback otherwise. */
    private fun openAt(uri: Uri, offset: Long): InputStream? {
        val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null }
        if (pfd != null) {
            val input = FileInputStream(pfd.fileDescriptor)
            try {
                input.channel.position(offset)
                return object : FilterInputStream(input) { override fun close() { try { super.close() } finally { pfd.close() } } }
            } catch (_: Exception) {
                // A pipe-backed provider may not support seeking. Skip sequentially instead.
                try {
                    var skipped = 0L
                    while (skipped < offset) {
                        val n = input.skip(offset - skipped)
                        if (n <= 0) { if (input.read() == -1) break else skipped++ } else skipped += n
                    }
                    return object : FilterInputStream(input) { override fun close() { try { super.close() } finally { pfd.close() } } }
                } catch (_: Exception) { try { pfd.close() } catch (_: Exception) {}; return null }
            }
        }
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        var skipped = 0L
        while (skipped < offset) {
            val n = stream.skip(offset - skipped)
            if (n <= 0) { if (stream.read() == -1) break else skipped++ } else skipped += n
        }
        return stream
    }

    private fun rangeNotSatisfiable(length: Long): Response = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Range not satisfiable").apply {
        addHeader("Accept-Ranges", "bytes")
        if (length >= 0) addHeader("Content-Range", "bytes */$length")
    }

    private fun head(relativePath: String): Response {
        if (sharedFileRoot != null) {
            val file = resolveFile(relativePath)?.takeIf { it.exists() && it.isFile } ?: return text(Response.Status.NOT_FOUND, "Not found")
            return newFixedLengthResponse(Response.Status.OK, mimeType(file.name), "").apply {
                addHeader("Content-Length", file.length().toString())
                addHeader("Accept-Ranges", "bytes")
                addHeader("ETag", "W/\"${file.length()}-${file.lastModified()}\"")
            }
        }
        val doc = resolveDocument(relativePath) ?: return text(Response.Status.NOT_FOUND, "Not found")
        if (doc.isDirectory) return text(Response.Status.OK, "Directory")
        val length = doc.length()
        return newFixedLengthResponse(Response.Status.OK, doc.type ?: mimeType(doc.name ?: "file"), "").apply {
            if (length >= 0) addHeader("Content-Length", length.toString())
            addHeader("Accept-Ranges", if (length >= 0) "bytes" else "none")
            addHeader("ETag", "W/\"${length}-${doc.lastModified()}\"")
        }
    }

    private fun upload(session: IHTTPSession, relativePath: String): Response {
        val segments = decodedSegments(relativePath)
        if (segments.isEmpty()) return text(Response.Status.BAD_REQUEST, "Missing filename")
        var parent = sharedRoot?.takeIf { it.exists() && it.isDirectory } ?: return text(Response.Status.INTERNAL_ERROR, "Choose a shared folder in LoBBY")
        segments.dropLast(1).forEach { segment -> parent = parent.findFile(segment)?.takeIf { it.isDirectory } ?: return text(Response.Status.NOT_FOUND, "Folder does not exist") }
        val parsed = HashMap<String, String>()
        session.parseBody(parsed)
        val tempPath = parsed["content"] ?: return text(Response.Status.BAD_REQUEST, "Missing upload body")
        val tempFile = File(tempPath)
        val name = segments.last()
        val old = parent.findFile(name)
        if (old != null && !old.delete()) return text(Response.Status.INTERNAL_ERROR, "Could not replace existing file")
        val destination = parent.createFile(mimeType(name), name) ?: return text(Response.Status.INTERNAL_ERROR, "Could not create file")
        context.contentResolver.openOutputStream(destination.uri, "w")?.use { output -> FileInputStream(tempFile).use { input -> input.copyTo(output, 256 * 1024) } }
            ?: return text(Response.Status.INTERNAL_ERROR, "Could not open destination")
        return newFixedLengthResponse(Response.Status.CREATED, "application/json", "{\"ok\":true}")
    }

    private fun delete(relativePath: String): Response {
        if (sharedFileRoot != null) {
            val file = resolveFile(relativePath)?.takeIf { it.exists() } ?: return text(Response.Status.NOT_FOUND, "Not found")
            return if (file.deleteRecursively()) newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "") else text(Response.Status.INTERNAL_ERROR, "Could not delete")
        }
        val doc = resolveDocument(relativePath) ?: return text(Response.Status.NOT_FOUND, "Not found")
        return if (doc.delete()) newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "") else text(Response.Status.INTERNAL_ERROR, "Could not delete")
    }

    private fun serveWebsite(rawPath: String): Response {
        val root = websiteRoot?.takeIf { it.exists() && it.isDirectory }
        if (root == null) {
            if (rawPath == "/" || rawPath == "/index.html") {
                val html = fallbackHtml.replace("__SHARE_PATH__", sharePath)
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
            }
            return text(Response.Status.NOT_FOUND, "Not found")
        }
        val relative = rawPath.removePrefix("/").ifBlank { "index.html" }
        val segments = decodedSegments(relative)
        var current: DocumentFile? = root
        for (segment in segments) {
            current = current?.findFile(segment)
                ?: return text(Response.Status.NOT_FOUND, "Not found")
        }
        var doc = current ?: return text(Response.Status.NOT_FOUND, "Not found")
        if (doc.isDirectory) {
            doc = doc.findFile("index.html")
                ?: return text(Response.Status.NOT_FOUND, "No index.html in this folder")
        }
        val length = doc.length()
        val input = context.contentResolver.openInputStream(doc.uri) ?: return text(Response.Status.NOT_FOUND, "Not found")
        return if (length >= 0) newFixedLengthResponse(Response.Status.OK, doc.type ?: mimeType(doc.name ?: "file"), input, length)
            else newChunkedResponse(Response.Status.OK, doc.type ?: mimeType(doc.name ?: "file"), input)
    }

    private fun mimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun text(status: Response.Status, message: String): Response = newFixedLengthResponse(status, "text/plain; charset=utf-8", message)
}

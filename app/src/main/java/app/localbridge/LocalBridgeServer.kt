package app.localbridge

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
import java.security.KeyPair
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Local LAN server. Directory browsing is live and non-recursive. Media/download
 * responses support single HTTP byte ranges so browser players can seek and
 * download clients can issue independent parallel Range requests.
 */
class LocalBridgeServer(
    private val context: Context,
    private val sharedTreeUri: Uri?,
    private val websiteTreeUri: Uri?,
    sharePathInput: String,
    selectedFileRoot: File? = null,
    private val onTransferChanged: (() -> Unit)? = null
) : NanoHTTPD("0.0.0.0", 8765) {

    private val sharePath = normalizePath(sharePathInput)
    private val defaultSharedFolder: File = (context.getExternalFilesDir("Shared") ?: File(context.filesDir, "Shared")).apply { mkdirs() }
    private val sharedFileRoot: File? = selectedFileRoot ?: if (sharedTreeUri == null) defaultSharedFolder else null
    private val sharedRoot: DocumentFile? = sharedTreeUri?.let { DocumentFile.fromTreeUri(context, it) }
        ?: DocumentFile.fromFile(sharedFileRoot ?: defaultSharedFolder)
    private val websiteRoot = websiteTreeUri?.let { DocumentFile.fromTreeUri(context, it) }
    private val fallbackHtml = context.assets.open("index.html").bufferedReader().use { it.readText() }
    private val pendingPairs = ConcurrentHashMap<String, PendingPair>()
    private val incomingTransfers = ConcurrentHashMap<String, IncomingTransfer>()
    private val usedTransferIds = ConcurrentHashMap.newKeySet<String>()
    private val usedSessionNonces = ConcurrentHashMap<String, Long>()
    private val benchmarkSessions = ConcurrentHashMap<String, BenchmarkSession>()

    private data class PendingPair(
        val nonce: String,
        val peerId: String,
        val peerName: String,
        val publicKey: String,
        val code: String,
        val createdAt: Long,
        @Volatile var state: String = "pending"
    )

    private data class IncomingFile(
        val name: String,
        val size: Long,
        val mime: String,
        val temp: File,
        val completedChunks: MutableSet<Int> = ConcurrentHashMap.newKeySet(),
        val inFlightChunks: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    )

    private data class BenchmarkSession(
        val id: String,
        val senderId: String,
        val fileCount: Int,
        val chunksPerFile: Int,
        val directory: File,
        val files: ConcurrentHashMap<Int, File> = ConcurrentHashMap(),
        val completed: ConcurrentHashMap<Int, MutableSet<Int>> = ConcurrentHashMap(),
        val inFlight: ConcurrentHashMap<Int, MutableSet<Int>> = ConcurrentHashMap(),
        val createdAt: Long = System.currentTimeMillis()
    )

    private data class PreparedDestination(
        val name: String,
        val stagingFile: File? = null,
        val stagingDocument: DocumentFile? = null,
        var committedFile: File? = null,
        var committedDocument: DocumentFile? = null
    )

    private data class IncomingTransfer(
        val info: IncomingTransferInfo,
        val senderPublicKey: String,
        val chunkSize: Long,
        val sessionKey: SecretKeySpec,
        val receiverEphemeralPublicKey: String,
        val receiverEphemeralSignature: String,
        @Volatile var files: List<IncomingFile>,
        @Volatile var state: String,
        val completedBytes: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0L),
        val finalizing: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        @Volatile var speedBytesPerSecond: Double = 0.0,
        @Volatile var lastSpeedBytes: Long = 0L,
        @Volatile var lastSpeedAtNanos: Long = System.nanoTime()
    )

    init {
        cleanupStaleBenchmarkFiles()
        restoreIncomingTransfers()
    }

    private fun cleanupStaleBenchmarkFiles() {
        val cutoff = System.currentTimeMillis() - 15L * 60L * 1000L
        context.cacheDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("lobby-bench-") && it.lastModified() < cutoff }
            ?.forEach { it.deleteRecursively() }
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            val rawPath = session.uri.substringBefore('?')
            when {
                rawPath.startsWith("/api/lobby/v1/") -> handleNativeApi(session, rawPath)
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
        addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, HEAD, DELETE, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Content-Type, Range, If-Range, Depth, X-Chunk-Index, X-Chunk-Size, X-LoBBY-Session")
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
        val length = documentLength(doc)
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

    /** Resolve size from the document metadata, OpenableColumns, or a seekable descriptor. */
    private fun documentLength(doc: DocumentFile): Long {
        val direct = doc.length()
        if (direct >= 0) return direct
        try {
            context.contentResolver.query(doc.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (column >= 0 && !cursor.isNull(column)) {
                        val size = cursor.getLong(column)
                        if (size >= 0) return size
                    }
                }
            }
        } catch (_: Exception) { }
        try {
            context.contentResolver.openAssetFileDescriptor(doc.uri, "r")?.use { afd ->
                if (afd.length >= 0) return afd.length
                val stat = afd.parcelFileDescriptor.statSize
                if (stat >= 0) return stat
            }
        } catch (_: Exception) { }
        return -1L
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
        val length = documentLength(doc)
        return newFixedLengthResponse(Response.Status.OK, doc.type ?: mimeType(doc.name ?: "file"), "").apply {
            if (length >= 0) addHeader("Content-Length", length.toString())
            addHeader("Accept-Ranges", if (length >= 0) "bytes" else "none")
            addHeader("ETag", "W/\"${length}-${doc.lastModified()}\"")
        }
    }

    private fun handleNativeApi(session: IHTTPSession, path: String): Response {
        return when {
            session.method == Method.GET && path == "/api/lobby/v1/identity" -> identityResponse()
            session.method == Method.POST && path == "/api/lobby/v1/pair/request" -> createPairRequest(session)
            session.method == Method.GET && path == "/api/lobby/v1/pair/requests" -> jsonResponse(JSONArray().apply { pendingPairRequests().forEach { put(it) } })
            session.method == Method.GET && path == "/api/lobby/v1/pair/status" -> pairStatus(session.parameters["nonce"]?.firstOrNull().orEmpty())
            session.method == Method.POST && path == "/api/lobby/v1/transfers/request" -> createTransferRequest(session)
            session.method == Method.GET && path == "/api/lobby/v1/transfers/pending" -> jsonResponse(JSONArray().apply { pendingTransfers().forEach { put(it.toJson()) } })
            path.startsWith("/api/lobby/v1/transfers/") && path.contains("/files/") && session.method == Method.PUT -> receiveChunk(session, path)
            path.startsWith("/api/lobby/v1/transfers/") && path.endsWith("/complete") && session.method == Method.POST -> completeTransfer(session, path)
            path.startsWith("/api/lobby/v1/transfers/") && path.endsWith("/cancel") && session.method == Method.POST -> cancelTransfer(session, path)
            path.startsWith("/api/lobby/v1/transfers/") && session.method == Method.GET -> transferStatus(path)
            session.method == Method.POST && path.startsWith("/api/lobby/v1/benchmark/") -> benchmarkChunk(session, path)
            session.method == Method.DELETE && path.startsWith("/api/lobby/v1/benchmark/") -> cleanupBenchmark(session, path)
            else -> text(Response.Status.NOT_FOUND, "Unknown LoBBY API route")
        }
    }

    private fun ownIdentity(): LobbyIdentity.Identity = LobbyIdentity.get(context)

    private fun identityResponse(): Response {
        val identity = ownIdentity()
        return jsonResponse(JSONObject()
            .put("deviceId", identity.deviceId)
            .put("publicKey", identity.publicKey)
            .put("name", "LoBBY-${android.os.Build.MODEL}")
            .put("protocol", 1))
    }

    private fun readJsonBody(session: IHTTPSession): JSONObject {
        val parsed = HashMap<String, String>()
        session.parseBody(parsed)
        val bodyPath = parsed["content"] ?: throw IllegalArgumentException("Missing request body")
        val bodyFile = File(bodyPath)
        return try { JSONObject(bodyFile.readText(Charsets.UTF_8)) } finally { bodyFile.delete() }
    }

    private fun jsonResponse(value: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", value.toString())

    private fun jsonResponse(value: JSONArray): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", value.toString())

    private fun errorResponse(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", JSONObject().put("error", message).toString())

    private fun createPairRequest(session: IHTTPSession): Response {
        val now = System.currentTimeMillis()
        pendingPairs.entries.removeIf { now - it.value.createdAt > 180_000L }
        val body = readJsonBody(session)
        val peerId = body.getString("deviceId")
        val publicKey = body.getString("publicKey")
        val nonce = body.getString("nonce")
        val signature = body.getString("signature")
        if (LobbyIdentity.deviceId(publicKey) != peerId || nonce.length !in 16..128) {
            return errorResponse(Response.Status.FORBIDDEN, "Invalid device identity")
        }
        val signed = "PAIR|$peerId|$nonce|$publicKey".toByteArray(Charsets.UTF_8)
        if (!LobbyIdentity.verify(publicKey, signed, signature)) return errorResponse(Response.Status.FORBIDDEN, "Identity signature failed")
        val own = ownIdentity()
        val code = LobbyIdentity.pairingCode(publicKey, own.publicKey, nonce)
        val request = PendingPair(nonce, peerId, body.optString("name", "LoBBY device"), publicKey, code, System.currentTimeMillis())
        pendingPairs[nonce] = request
        onTransferChanged?.invoke()
        LobbyNotifications.show(context, nonce.hashCode(), "LoBBY pairing request", "${request.peerName} · verification code ${request.code}", openInbox = false)
        return jsonResponse(JSONObject().put("status", "pending").put("nonce", nonce).put("code", code).put("identity", identityJson(own)))
    }

    private fun identityJson(identity: LobbyIdentity.Identity): JSONObject = JSONObject()
        .put("deviceId", identity.deviceId).put("publicKey", identity.publicKey).put("name", "LoBBY-${android.os.Build.MODEL}")

    fun pendingPairRequests(): List<JSONObject> = pendingPairs.values
        .filter { it.state == "pending" && System.currentTimeMillis() - it.createdAt < 180_000L }
        .sortedBy { it.createdAt }
        .map { JSONObject().put("nonce", it.nonce).put("deviceId", it.peerId).put("name", it.peerName).put("code", it.code).put("createdAt", it.createdAt) }

    private fun pairStatus(nonce: String): Response {
        val request = pendingPairs[nonce] ?: return errorResponse(Response.Status.NOT_FOUND, "Pairing request not found")
        if (System.currentTimeMillis() - request.createdAt > 180_000L) {
            pendingPairs.remove(nonce)
            return jsonResponse(JSONObject().put("status", "expired"))
        }
        return jsonResponse(JSONObject().put("status", request.state).put("identity", identityJson(ownIdentity())))
    }

    private fun createTransferRequest(session: IHTTPSession): Response {
        val body = readJsonBody(session)
        val transferId = body.getString("transferId")
        val timestamp = body.getLong("timestamp")
        val senderId = body.getString("senderId")
        val senderPublicKey = body.getString("senderPublicKey")
        val requestedChunkSize = body.optLong("chunkSize", 8L * 1024L * 1024L)
        val senderEphemeralPublicKey = body.getString("senderEphemeralPublicKey")
        val senderNameClaim = body.optString("senderName", "LoBBY-${senderId.take(8)}").take(80)
        val signature = body.getString("signature")
        val filesJson = body.getJSONArray("files")
        if (transferId.length !in 16..80 || timestamp < System.currentTimeMillis() - 300_000L || timestamp > System.currentTimeMillis() + 300_000L || filesJson.length() !in 1..64) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid transfer request")
        }
        if (LobbyIdentity.deviceId(senderPublicKey) != senderId) return errorResponse(Response.Status.FORBIDDEN, "Invalid sender identity")
        val trusted = LobbyIdentity.findTrustedPeer(context, senderId)
        if (trusted != null && trusted.publicKey != senderPublicKey) return errorResponse(Response.Status.FORBIDDEN, "Trusted identity mismatch")
        if (requestedChunkSize !in (1L * 1024L * 1024L)..(32L * 1024L * 1024L)) return errorResponse(Response.Status.BAD_REQUEST, "Invalid chunk size")
        val signedPayload = "$transferId|$timestamp|$senderId|$senderPublicKey|${filesJson}|$requestedChunkSize|$senderEphemeralPublicKey|$senderNameClaim".toByteArray(Charsets.UTF_8)
        if (!LobbyIdentity.verify(senderPublicKey, signedPayload, signature)) return errorResponse(Response.Status.FORBIDDEN, "Transfer request signature failed")
        if (!usedTransferIds.add(transferId)) return errorResponse(Response.Status.CONFLICT, "Transfer request was already used")

        val files = mutableListOf<TransferFileInfo>()
        var total = 0L
        for (index in 0 until filesJson.length()) {
            val item = filesJson.getJSONObject(index)
            val name = sanitizeIncomingName(item.getString("name"))
            val size = item.getLong("size")
            if (size < 0L || size > Long.MAX_VALUE - total) {
                return errorResponse(Response.Status.BAD_REQUEST, "File size is invalid or total size overflows")
            }
            total += size
            files += TransferFileInfo(name, size, item.optString("mime", "application/octet-stream"))
        }
        val nowForCleanup = System.currentTimeMillis()
        incomingTransfers.entries.removeIf { nowForCleanup - it.value.info.createdAt > 24L * 60L * 60L * 1000L && it.value.state in setOf("completed", "declined", "cancelled", "failed") }
        val own = ownIdentity()
        val receiverEphemeral = TransferCrypto.createEphemeralKeyPair()
        val receiverEphemeralPublicKey = TransferCrypto.encodePublicKey(receiverEphemeral)
        val sessionKey = try {
            TransferCrypto.deriveSessionKey(receiverEphemeral.private, senderEphemeralPublicKey, transferId, senderId, own.deviceId)
        } catch (_: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid ephemeral session key")
        }
        val sessionTranscript = "SESSION|$transferId|$senderId|${own.deviceId}|$receiverEphemeralPublicKey"
        val receiverEphemeralSignature = LobbyIdentity.sign(context, sessionTranscript.toByteArray(Charsets.UTF_8))
        val senderName = trusted?.name ?: senderNameClaim
        val info = IncomingTransferInfo(transferId, senderId, senderName, files, total, "pending", System.currentTimeMillis())
        val securityPrefs = context.getSharedPreferences("lobby_security", Context.MODE_PRIVATE)
        val autoAcceptAll = securityPrefs.getBoolean("receive_without_confirmation", false)
        val autoAcceptTrusted = securityPrefs.getBoolean("auto_accept_trusted", false) && trusted != null
        val autoAccept = autoAcceptAll || autoAcceptTrusted
        val transfer = IncomingTransfer(
            info, senderPublicKey, requestedChunkSize, sessionKey, receiverEphemeralPublicKey,
            receiverEphemeralSignature, emptyList(), if (autoAccept) "accepted" else "pending"
        )
        incomingTransfers[transferId] = transfer
        if (autoAccept) {
            try {
                allocateTransferFiles(transfer)
                persistIncomingTransfer(transfer)
                LobbyNotifications.show(context, transferId.hashCode(), "Receiving LoBBY files", "$senderName: ${files.size} file(s) · ${formatBytes(total)}", transferId, openInbox = false)
            } catch (e: Exception) {
                transfer.state = "failed"
                appendHistory(transfer, "failed", null)
                incomingTransfers.remove(transferId)
                incomingSessionFolder(transferId).deleteRecursively()
                onTransferChanged?.invoke()
                return errorResponse(Response.Status.INTERNAL_ERROR, e.message ?: "Not enough temporary storage")
            }
        } else {
            persistIncomingTransfer(transfer)
            LobbyNotifications.show(context, transferId.hashCode(), "Incoming LoBBY transfer", "$senderName: ${files.size} file(s) · ${formatBytes(total)}", transferId, openInbox = false)
        }
        onTransferChanged?.invoke()
        return jsonResponse(JSONObject().put("transferId", transferId).put("status", transfer.state))
    }

    fun decideTransferLocally(id: String, accept: Boolean): String {
        val transfer = incomingTransfers[id] ?: return "expired"
        if (transfer.state != "pending") return transfer.state
        if (!accept) {
            transfer.state = "declined"
            appendHistory(transfer, "declined", null)
            persistIncomingTransfer(transfer)
            onTransferChanged?.invoke()
            return "declined"
        }
        return try {
            allocateTransferFiles(transfer)
            transfer.state = "accepted"
            persistIncomingTransfer(transfer)
            LobbyNotifications.show(context, transfer.info.id.hashCode(), "LoBBY transfer accepted", "Receiving ${transfer.info.files.size} file(s) from ${transfer.info.senderName}", transfer.info.id, openInbox = false)
            onTransferChanged?.invoke()
            "accepted"
        } catch (error: Exception) {
            transfer.state = "failed"
            appendHistory(transfer, "failed", null)
            persistIncomingTransfer(transfer)
            onTransferChanged?.invoke()
            "failed: ${error.message ?: "Could not prepare destination"}"
        }
    }

    fun decidePairingLocally(nonce: String, accept: Boolean): String {
        val request = pendingPairs[nonce] ?: return "expired"
        if (request.state != "pending") return request.state
        request.state = if (accept) "accepted" else "declined"
        if (accept) {
            LobbyIdentity.trustPeer(context, LobbyIdentity.TrustedPeer(request.peerId, request.peerName, request.publicKey, System.currentTimeMillis()))
        }
        onTransferChanged?.invoke()
        return request.state
    }

    internal fun pendingTransfers(): List<IncomingTransferInfo> = incomingTransfers.values
        .filter { it.state in setOf("pending", "accepted", "receiving") }
        .map { transfer ->
            val perFile = transfer.info.files.mapIndexed { index, fileInfo ->
                val incomingFile = transfer.files.getOrNull(index) ?: return@mapIndexed 0L
                incomingFile.completedChunks.sumOf { chunkIndex ->
                    val offset = chunkIndex.toLong() * transfer.chunkSize
                    if (offset < fileInfo.size) minOf(transfer.chunkSize, fileInfo.size - offset) else 0L
                }
            }
            transfer.info.copy(
                status = transfer.state,
                receivedBytes = transfer.completedBytes.get(),
                fileReceivedBytes = perFile,
                speedBytesPerSecond = transfer.speedBytesPerSecond,
                etaSeconds = if (transfer.speedBytesPerSecond > 0.0) ((transfer.info.totalBytes - transfer.completedBytes.get()).coerceAtLeast(0L) / transfer.speedBytesPerSecond).toLong() else null
            )
        }
        .sortedBy { it.createdAt }

    private fun incomingSessionFolder(id: String): File = File(File(context.filesDir, "lobby-incoming"), id)

    private fun allocateTransferFiles(transfer: IncomingTransfer) {
        val folder = incomingSessionFolder(transfer.info.id)
        if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("Cannot create temporary transfer folder")
        val needed = transfer.info.totalBytes
        val safetyReserve = 32L * 1024L * 1024L
        if (needed > (folder.usableSpace - safetyReserve).coerceAtLeast(0L)) {
            throw IllegalStateException("Not enough free temporary storage for this transfer")
        }
        val allocated = transfer.info.files.mapIndexed { index, meta ->
            val temp = File(folder, "file-$index.part")
            if (!temp.exists() || temp.length() != meta.size) {
                java.io.RandomAccessFile(temp, "rw").use { it.setLength(meta.size) }
            }
            IncomingFile(meta.name, meta.size, meta.mimeType, temp)
        }
        transfer.files = allocated
        persistIncomingTransfer(transfer)
    }

    private fun receiveChunk(session: IHTTPSession, path: String): Response {
        val match = Regex("^/api/lobby/v1/transfers/([A-Za-z0-9_-]+)/files/(\\d+)/chunks/(\\d+)$").matchEntire(path)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "Invalid chunk route")
        val id = match.groupValues[1]
        val fileIndex = match.groupValues[2].toInt()
        val chunkIndex = match.groupValues[3].toInt()
        val transfer = incomingTransfers[id] ?: return errorResponse(Response.Status.NOT_FOUND, "Transfer session not found")
        if (!verifySessionRequest(transfer, session, path)) return errorResponse(Response.Status.FORBIDDEN, "Transfer session authentication failed")
        val file = transfer.files.getOrNull(fileIndex) ?: return errorResponse(Response.Status.NOT_FOUND, "File index not found")
        val offset = chunkIndex.toLong() * transfer.chunkSize
        if (file.size == 0L || offset < 0L || offset >= file.size) return errorResponse(Response.Status.BAD_REQUEST, "Chunk index is out of range")
        val expectedLength = minOf(transfer.chunkSize, file.size - offset)
        val expectedEncryptedLength = TransferCrypto.encryptedLength(expectedLength)
        val contentLength = session.headers["content-length"]?.toLongOrNull()
            ?: return errorResponse(Response.Status.LENGTH_REQUIRED, "Chunk content length is required")
        if (contentLength != expectedEncryptedLength) return errorResponse(Response.Status.BAD_REQUEST, "Chunk content length mismatch")

        // Register the request before reading its body. Cancellation/finalization uses
        // the same monitor, so it cannot remove the session directory under an upload.
        synchronized(transfer) {
            if (transfer.state !in setOf("accepted", "receiving") || transfer.finalizing.get()) {
                return errorResponse(Response.Status.CONFLICT, "Transfer is not accepting chunks")
            }
            if (!file.inFlightChunks.add(chunkIndex)) {
                return errorResponse(Response.Status.CONFLICT, "This chunk is already being written")
            }
        }

        var bodyFile: File? = null
        var plainChunk: File? = null
        try {
            val parsed = HashMap<String, String>()
            session.parseBody(parsed)
            val incomingBodyFile = parsed["content"]?.let(::File)
                ?: return errorResponse(Response.Status.BAD_REQUEST, "Missing chunk body")
            bodyFile = incomingBodyFile
            if (incomingBodyFile.length() != expectedEncryptedLength) {
                return errorResponse(Response.Status.BAD_REQUEST, "Encrypted chunk length mismatch")
            }
            if (file.completedChunks.contains(chunkIndex)) {
                return jsonResponse(JSONObject().put("status", "receiving").put("receivedBytes", transfer.completedBytes.get()).put("totalBytes", transfer.info.totalBytes))
            }

            val decryptedChunk = File.createTempFile("lobby-chunk-", ".plain", incomingSessionFolder(id))
            plainChunk = decryptedChunk
            TransferCrypto.decryptFile(
                incomingBodyFile, decryptedChunk, expectedLength, transfer.sessionKey,
                transfer.info.id, fileIndex, chunkIndex, offset
            )
            java.io.RandomAccessFile(file.temp, "rw").use { target ->
                target.seek(offset)
                java.io.FileInputStream(decryptedChunk).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                    }
                }
                target.fd.sync()
            }
            if (file.completedChunks.add(chunkIndex)) transfer.completedBytes.addAndGet(expectedLength)
            updateReceiveSpeed(transfer)
            transfer.state = "receiving"
            persistIncomingTransfer(transfer)
            onTransferChanged?.invoke()
            return jsonResponse(JSONObject().put("status", "receiving").put("receivedBytes", transfer.completedBytes.get()).put("totalBytes", transfer.info.totalBytes))
        } catch (error: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, error.message ?: "Could not authenticate chunk")
        } finally {
            file.inFlightChunks.remove(chunkIndex)
            bodyFile?.delete()
            plainChunk?.delete()
        }
    }

    private fun updateReceiveSpeed(transfer: IncomingTransfer) {
        synchronized(transfer) {
            val now = System.nanoTime()
            val bytes = transfer.completedBytes.get()
            val elapsed = now - transfer.lastSpeedAtNanos
            if (elapsed >= 250_000_000L) {
                val delta = (bytes - transfer.lastSpeedBytes).coerceAtLeast(0L)
                val sample = delta * 1_000_000_000.0 / elapsed.toDouble()
                transfer.speedBytesPerSecond = if (transfer.speedBytesPerSecond <= 0.0) sample
                    else transfer.speedBytesPerSecond * 0.65 + sample * 0.35
                transfer.lastSpeedBytes = bytes
                transfer.lastSpeedAtNanos = now
            }
        }
    }

    private fun completeTransfer(session: IHTTPSession, path: String): Response {
        val id = path.removePrefix("/api/lobby/v1/transfers/").removeSuffix("/complete")
        val transfer = incomingTransfers[id] ?: return errorResponse(Response.Status.NOT_FOUND, "Transfer not found")
        if (!verifySessionRequest(transfer, session, path)) return errorResponse(Response.Status.FORBIDDEN, "Transfer session authentication failed")
        synchronized(transfer) {
            if (transfer.state == "completed") return jsonResponse(JSONObject().put("status", "completed"))
            if (transfer.state !in setOf("accepted", "receiving")) return errorResponse(Response.Status.CONFLICT, "Transfer is not active")
            if (!transfer.finalizing.compareAndSet(false, true)) return errorResponse(Response.Status.CONFLICT, "Transfer is already being finalized")
        }
        return try {
            val deadline = System.currentTimeMillis() + 10_000L
            while (transfer.files.any { it.inFlightChunks.isNotEmpty() } && System.currentTimeMillis() < deadline) {
                Thread.sleep(40L)
            }
            if (transfer.files.any { it.inFlightChunks.isNotEmpty() }) {
                return errorResponse(Response.Status.CONFLICT, "Transfer chunks are still being written; retry completion")
            }
            val allChunksPresent = transfer.files.all { file -> file.completedChunks.size >= chunkCount(file.size, transfer.chunkSize) }
            if (!allChunksPresent) return errorResponse(Response.Status.CONFLICT, "Some chunks are still missing")
            val destinations = finalizeTransfer(transfer)
            transfer.state = "completed"
            appendHistory(transfer, "completed", destinations)
            incomingSessionFolder(id).deleteRecursively()
            LobbyNotifications.show(context, id.hashCode(), "LoBBY transfer complete", "Received ${transfer.info.files.size} file(s) from ${transfer.info.senderName}", id)
            onTransferChanged?.invoke()
            jsonResponse(JSONObject().put("status", "completed"))
        } catch (e: Exception) {
            transfer.state = "failed"
            appendHistory(transfer, "failed", null)
            persistIncomingTransfer(transfer)
            onTransferChanged?.invoke()
            errorResponse(Response.Status.INTERNAL_ERROR, e.message ?: "Could not finalize received files")
        } finally {
            transfer.finalizing.set(false)
        }
    }

    private fun chunkCount(size: Long, chunkSize: Long): Int =
        if (size <= 0L) 0 else (((size - 1L) / chunkSize) + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun finalizeTransfer(transfer: IncomingTransfer): List<String> {
        val prepared = mutableListOf<PreparedDestination>()
        val reservedNames = mutableSetOf<String>()
        val fileRoot = sharedFileRoot
        val documentRoot = if (fileRoot == null) sharedRoot?.takeIf { it.exists() && it.isDirectory } else null
        if (fileRoot == null && documentRoot == null) throw IllegalStateException("Choose a shared folder in LoBBY")

        try {
            transfer.files.forEachIndexed { index, incoming ->
                val finalName = uniqueDestinationName(incoming.name, reservedNames)
                reservedNames += finalName
                if (fileRoot != null) {
                    if (!fileRoot.exists() && !fileRoot.mkdirs()) throw IllegalStateException("Shared folder is unavailable")
                    val staging = File(fileRoot, ".lobby-${transfer.info.id}-$index.part")
                    if (staging.exists() && !staging.delete()) throw IllegalStateException("Could not clear previous staging file")
                    java.io.FileInputStream(incoming.temp).use { input ->
                        java.io.FileOutputStream(staging).use { output ->
                            input.copyTo(output, 1024 * 1024)
                            output.fd.sync()
                        }
                    }
                    prepared += PreparedDestination(finalName, stagingFile = staging)
                } else {
                    val root = documentRoot!!
                    val stagingName = ".lobby-${transfer.info.id}-$index.part"
                    root.findFile(stagingName)?.let { if (!it.delete()) throw IllegalStateException("Could not clear previous staging document") }
                    val staging = root.createFile(incoming.mime, stagingName)
                        ?: throw IllegalStateException("Could not create staging document for ${incoming.name}")
                    try {
                        context.contentResolver.openOutputStream(staging.uri, "w")?.use { output ->
                            java.io.FileInputStream(incoming.temp).use { input -> input.copyTo(output, 1024 * 1024) }
                            output.flush()
                        } ?: throw IllegalStateException("Could not write staging document for ${incoming.name}")
                    } catch (error: Exception) {
                        staging.delete()
                        throw error
                    }
                    prepared += PreparedDestination(finalName, stagingDocument = staging)
                }
            }

            prepared.forEach { entry ->
                if (fileRoot != null) {
                    val staging = entry.stagingFile ?: throw IllegalStateException("Missing staged file")
                    val destination = File(fileRoot, entry.name)
                    if (destination.exists()) throw IllegalStateException("A file named ${entry.name} appeared during transfer finalization")
                    if (!staging.renameTo(destination)) throw IllegalStateException("Could not finalize ${entry.name}")
                    entry.committedFile = destination
                } else {
                    val root = documentRoot!!
                    if (root.findFile(entry.name) != null) throw IllegalStateException("A file named ${entry.name} appeared during transfer finalization")
                    val staging = entry.stagingDocument ?: throw IllegalStateException("Missing staged document")
                    if (!staging.renameTo(entry.name)) throw IllegalStateException("Could not finalize ${entry.name}")
                    entry.committedDocument = root.findFile(entry.name)
                        ?: throw IllegalStateException("Finalized document ${entry.name} cannot be found")
                }
            }

            return prepared.map { entry ->
                entry.committedFile?.let { Uri.fromFile(it).toString() }
                    ?: entry.committedDocument?.uri?.toString()
                    ?: throw IllegalStateException("Finalized file ${entry.name} is unavailable")
            }
        } catch (error: Exception) {
            prepared.forEach { entry ->
                entry.stagingFile?.delete()
                entry.stagingDocument?.delete()
                entry.committedFile?.delete()
                entry.committedDocument?.delete()
            }
            throw error
        }
    }

    private fun uniqueDestinationName(original: String, reserved: Set<String> = emptySet()): String {
        val root = sharedFileRoot?.let { DocumentFile.fromFile(it) } ?: sharedRoot ?: return original
        fun available(name: String): Boolean = name !in reserved && root.findFile(name) == null
        if (available(original)) return original
        val dot = original.lastIndexOf('.')
        val base = if (dot > 0) original.substring(0, dot) else original
        val extension = if (dot > 0) original.substring(dot) else ""
        var index = 1
        while (index < 10000) {
            val candidate = "$base ($index)$extension"
            if (available(candidate)) return candidate
            index++
        }
        throw IllegalStateException("Too many files with the same name")
    }

    private fun sanitizeIncomingName(value: String): String {
        val cleaned = value.replace('/', '_').replace('\\', '_').trim()
        require(cleaned.isNotBlank() && cleaned != "." && cleaned != "..") { "Invalid filename" }
        return cleaned.take(240)
    }

    private fun cancelTransfer(session: IHTTPSession, path: String): Response {
        val id = path.removePrefix("/api/lobby/v1/transfers/").removeSuffix("/cancel")
        val transfer = incomingTransfers[id] ?: return errorResponse(Response.Status.NOT_FOUND, "Transfer not found")
        val body = try { readJsonBody(session) } catch (error: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, error.message ?: "Invalid cancellation request")
        }
        val senderId = body.optString("senderId")
        val timestamp = body.optLong("timestamp", 0L)
        val signature = body.optString("signature")
        if (senderId != transfer.info.senderId || kotlin.math.abs(System.currentTimeMillis() - timestamp) > 120_000L) {
            return errorResponse(Response.Status.FORBIDDEN, "Transfer cancellation identity is invalid")
        }
        val canonical = "CANCEL|$id|$senderId|$timestamp".toByteArray(Charsets.UTF_8)
        if (!LobbyIdentity.verify(transfer.senderPublicKey, canonical, signature)) {
            return errorResponse(Response.Status.FORBIDDEN, "Transfer cancellation signature failed")
        }
        synchronized(transfer) {
            if (transfer.state == "completed") return jsonResponse(JSONObject().put("status", "completed"))
            if (transfer.state in setOf("declined", "cancelled", "failed")) return jsonResponse(JSONObject().put("status", transfer.state))
            if (!transfer.finalizing.compareAndSet(false, true)) return errorResponse(Response.Status.CONFLICT, "Transfer is being finalized")
        }
        try {
            val deadline = System.currentTimeMillis() + 10_000L
            while (transfer.files.any { it.inFlightChunks.isNotEmpty() } && System.currentTimeMillis() < deadline) {
                Thread.sleep(40L)
            }
            if (transfer.files.any { it.inFlightChunks.isNotEmpty() }) {
                return errorResponse(Response.Status.CONFLICT, "Transfer chunks are still closing; retry cancellation")
            }
            transfer.state = "cancelled"
            appendHistory(transfer, "cancelled", null)
            incomingSessionFolder(id).deleteRecursively()
            onTransferChanged?.invoke()
            return jsonResponse(JSONObject().put("status", "cancelled"))
        } finally {
            transfer.finalizing.set(false)
        }
    }

    private fun verifySessionRequest(transfer: IncomingTransfer, session: IHTTPSession, path: String): Boolean {
        val header = session.headers["x-lobby-session"]
        val nonce = TransferCrypto.verifySessionAuthHeader(transfer.sessionKey, header, session.method.name, path) ?: return false
        val now = System.currentTimeMillis()
        usedSessionNonces.entries.removeIf { now - it.value > 180_000L }
        return usedSessionNonces.putIfAbsent("${transfer.info.id}:$nonce", now) == null
    }

    private fun transferStatus(path: String): Response {
        val id = path.removePrefix("/api/lobby/v1/transfers/")
        val transfer = incomingTransfers[id] ?: return errorResponse(Response.Status.NOT_FOUND, "Transfer not found")
        return jsonResponse(JSONObject()
            .put("status", transfer.state)
            .put("receivedBytes", transfer.completedBytes.get())
            .put("totalBytes", transfer.info.totalBytes)
            .put("fileCount", transfer.info.files.size)
            .put("receiverDeviceId", ownIdentity().deviceId)
            .put("receiverEphemeralPublicKey", transfer.receiverEphemeralPublicKey)
            .put("receiverEphemeralSignature", transfer.receiverEphemeralSignature))
    }

    @Synchronized
    private fun persistIncomingTransfer(transfer: IncomingTransfer) {
        if (transfer.state in setOf("completed", "declined", "cancelled", "failed")) {
            incomingSessionFolder(transfer.info.id).deleteRecursively()
            return
        }
        val folder = incomingSessionFolder(transfer.info.id)
        if (!folder.exists() && !folder.mkdirs()) return
        val fileArray = JSONArray()
        transfer.info.files.forEachIndexed { index, fileInfo ->
            val completed = transfer.files.getOrNull(index)?.completedChunks.orEmpty()
            fileArray.put(JSONObject()
                .put("name", fileInfo.name)
                .put("size", fileInfo.size)
                .put("mime", fileInfo.mimeType)
                .put("completed", JSONArray().apply { completed.sorted().forEach { put(it) } }))
        }
        val manifest = JSONObject()
            .put("id", transfer.info.id)
            .put("senderId", transfer.info.senderId)
            .put("senderName", transfer.info.senderName)
            .put("senderPublicKey", transfer.senderPublicKey)
            .put("chunkSize", transfer.chunkSize)
            .put("sessionKey", TransferCrypto.encodeKey(transfer.sessionKey))
            .put("receiverEphemeralPublicKey", transfer.receiverEphemeralPublicKey)
            .put("receiverEphemeralSignature", transfer.receiverEphemeralSignature)
            .put("totalBytes", transfer.info.totalBytes)
            .put("createdAt", transfer.info.createdAt)
            .put("state", transfer.state)
            .put("files", fileArray)
        val temp = File(folder, "session.json.tmp")
        val target = File(folder, "session.json")
        try {
            temp.writeText(manifest.toString(), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) temp.delete()
            }
        } catch (_: Exception) {
            temp.delete()
        }
    }

    private fun restoreIncomingTransfers() {
        val root = File(context.filesDir, "lobby-incoming")
        if (!root.exists()) return
        root.listFiles()?.filter { it.isDirectory }?.forEach { folder ->
            try {
                val manifestFile = File(folder, "session.json")
                if (!manifestFile.isFile) return@forEach
                val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
                val id = manifest.getString("id")
                if (!id.matches(Regex("[A-Za-z0-9_-]{16,80}"))) return@forEach
                val state = manifest.optString("state", "pending")
                if (state !in setOf("pending", "accepted", "receiving")) {
                    folder.deleteRecursively()
                    return@forEach
                }
                val filesJson = manifest.getJSONArray("files")
                val infos = mutableListOf<TransferFileInfo>()
                val incomingFiles = mutableListOf<IncomingFile>()
                var received = 0L
                for (index in 0 until filesJson.length()) {
                    val item = filesJson.getJSONObject(index)
                    val name = sanitizeIncomingName(item.getString("name"))
                    val size = item.getLong("size")
                    val mime = item.optString("mime", "application/octet-stream")
                    val temp = File(folder, "file-$index.part")
                    val completed = ConcurrentHashMap.newKeySet<Int>()
                    val completedArray = item.optJSONArray("completed") ?: JSONArray()
                    for (chunkIndex in 0 until completedArray.length()) {
                        val value = completedArray.optInt(chunkIndex, -1)
                        if (value >= 0) completed.add(value)
                    }
                    if (state != "pending") {
                        if (!temp.isFile || temp.length() != size) {
                            java.io.RandomAccessFile(temp, "rw").use { it.setLength(size) }
                            completed.clear()
                        }
                        val chunkSize = manifest.getLong("chunkSize")
                        received += completed.sumOf { chunk ->
                            val offset = chunk.toLong() * chunkSize
                            if (offset < size) minOf(chunkSize, size - offset) else 0L
                        }
                    }
                    infos += TransferFileInfo(name, size, mime)
                    incomingFiles += IncomingFile(name, size, mime, temp, completed)
                }
                val total = manifest.optLong("totalBytes", infos.sumOf { it.size })
                val info = IncomingTransferInfo(id, manifest.getString("senderId"), manifest.optString("senderName", "LoBBY device"), infos, total, state, manifest.optLong("createdAt", System.currentTimeMillis()))
                val transfer = IncomingTransfer(
                    info,
                    manifest.getString("senderPublicKey"),
                    manifest.getLong("chunkSize"),
                    TransferCrypto.decodeKey(manifest.getString("sessionKey")),
                    manifest.getString("receiverEphemeralPublicKey"),
                    manifest.getString("receiverEphemeralSignature"),
                    if (state == "pending") emptyList() else incomingFiles,
                    state
                )
                transfer.completedBytes.set(received)
                incomingTransfers[id] = transfer
                usedTransferIds.add(id)
            } catch (error: Exception) {
                android.util.Log.w("LoBBY", "Skipping an invalid saved transfer session", error)
            }
        }
    }

    private fun appendHistory(transfer: IncomingTransfer, status: String, destinations: List<String>?) {
        transfer.info.files.forEachIndexed { index, file ->
            TransferHistoryStore.add(context, TransferHistoryEntry(
                transfer.info.id, file.name, transfer.info.senderName, file.size, file.mimeType,
                System.currentTimeMillis(), status, destinations?.getOrNull(index).orEmpty()
            ))
        }
    }

    private fun verifyBenchmarkCaller(session: IHTTPSession, canonical: String): String? {
        val parts = session.headers["x-lobby-benchmark"]?.split(':') ?: return null
        if (parts.size != 4) return null
        val senderId = parts[0]
        val timestamp = parts[1].toLongOrNull() ?: return null
        val nonce = parts[2]
        if (!senderId.matches(Regex("[a-fA-F0-9]{32}")) || !nonce.matches(Regex("[A-Za-z0-9]{16,64}")) || kotlin.math.abs(System.currentTimeMillis() - timestamp) > 120_000L) return null
        val trusted = LobbyIdentity.findTrustedPeer(context, senderId) ?: return null
        if (!LobbyIdentity.verify(trusted.publicKey, canonical.toByteArray(Charsets.UTF_8), parts[3])) return null
        val now = System.currentTimeMillis()
        usedSessionNonces.entries.removeIf { now - it.value > 180_000L }
        if (usedSessionNonces.putIfAbsent("bench:$senderId:$nonce", now) != null) return null
        return senderId
    }

    private fun benchmarkChunk(session: IHTTPSession, path: String): Response {
        val match = Regex("^/api/lobby/v1/benchmark/([A-Fa-f0-9]{32})/(\\d+)/(\\d+)/(\\d+)/(\\d+)$").matchEntire(path)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "Invalid benchmark route")
        val id = match.groupValues[1]
        val fileCount = match.groupValues[2].toIntOrNull() ?: return errorResponse(Response.Status.BAD_REQUEST, "Invalid benchmark file count")
        val chunksPerFile = match.groupValues[3].toIntOrNull() ?: return errorResponse(Response.Status.BAD_REQUEST, "Invalid benchmark chunk count")
        val fileIndex = match.groupValues[4].toIntOrNull() ?: return errorResponse(Response.Status.BAD_REQUEST, "Invalid benchmark file index")
        val chunkIndex = match.groupValues[5].toIntOrNull() ?: return errorResponse(Response.Status.BAD_REQUEST, "Invalid benchmark chunk index")
        val payloadSize = 4 * 1024 * 1024
        if (fileCount !in 1..8 || chunksPerFile !in 1..8 || fileCount * chunksPerFile !in 1..24 || fileIndex !in 0 until fileCount || chunkIndex !in 0 until chunksPerFile) {
            return errorResponse(Response.Status.BAD_REQUEST, "Benchmark configuration is out of range")
        }
        val parts = session.headers["x-lobby-benchmark"]?.split(':') ?: return errorResponse(Response.Status.FORBIDDEN, "Benchmark identity is required")
        if (parts.size != 4) return errorResponse(Response.Status.FORBIDDEN, "Invalid benchmark identity")
        val timestamp = parts[1].toLongOrNull() ?: return errorResponse(Response.Status.FORBIDDEN, "Invalid benchmark timestamp")
        val nonce = parts[2]
        val canonical = "BENCH|${parts[0]}|$timestamp|$nonce|$id|$fileCount|$chunksPerFile|$fileIndex|$chunkIndex|$payloadSize"
        val senderId = verifyBenchmarkCaller(session, canonical)
            ?: return errorResponse(Response.Status.FORBIDDEN, "Benchmark identity or signature is invalid")
        if (session.headers["content-length"]?.toLongOrNull() != payloadSize.toLong()) {
            return errorResponse(Response.Status.BAD_REQUEST, "Benchmark content length mismatch")
        }

        val now = System.currentTimeMillis()
        benchmarkSessions.entries.removeIf { entry ->
            val expired = now - entry.value.createdAt > 15L * 60L * 1000L
            if (expired) entry.value.directory.deleteRecursively()
            expired
        }
        val benchmark = try {
            benchmarkSessions.computeIfAbsent(id) {
                val directory = File(context.cacheDir, "lobby-bench-$id")
                if (directory.exists()) directory.deleteRecursively()
                if (!directory.mkdirs()) throw IllegalStateException("Could not create benchmark staging folder")
                BenchmarkSession(id, senderId, fileCount, chunksPerFile, directory)
            }
        } catch (error: Exception) {
            return errorResponse(Response.Status.INTERNAL_ERROR, error.message ?: "Could not prepare benchmark")
        }
        if (benchmark.senderId != senderId || benchmark.fileCount != fileCount || benchmark.chunksPerFile != chunksPerFile) {
            return errorResponse(Response.Status.CONFLICT, "Benchmark session configuration changed")
        }

        val parsed = HashMap<String, String>()
        session.parseBody(parsed)
        val payloadPath = parsed["content"] ?: return errorResponse(Response.Status.BAD_REQUEST, "Missing benchmark payload")
        val payload = File(payloadPath)
        if (payload.length() != payloadSize.toLong()) {
            payload.delete()
            return errorResponse(Response.Status.BAD_REQUEST, "Benchmark payload size mismatch")
        }
        val completed = benchmark.completed.computeIfAbsent(fileIndex) { ConcurrentHashMap.newKeySet<Int>() }
        val inFlight = benchmark.inFlight.computeIfAbsent(fileIndex) { ConcurrentHashMap.newKeySet<Int>() }
        if (chunkIndex in completed) {
            payload.delete()
            return jsonResponse(JSONObject().put("ok", true).put("duplicate", true))
        }
        if (!inFlight.add(chunkIndex)) {
            payload.delete()
            return errorResponse(Response.Status.CONFLICT, "Benchmark chunk is already in progress")
        }
        return try {
            val target = benchmark.files.computeIfAbsent(fileIndex) {
                File(benchmark.directory, "file-$fileIndex.part").also { file ->
                    java.io.RandomAccessFile(file, "rw").use { it.setLength(chunksPerFile.toLong() * payloadSize) }
                }
            }
            java.io.RandomAccessFile(target, "rw").use { output ->
                output.seek(chunkIndex.toLong() * payloadSize)
                java.io.FileInputStream(payload).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.fd.sync()
            }
            completed.add(chunkIndex)
            jsonResponse(JSONObject().put("ok", true).put("receivedBytes", payloadSize))
        } catch (error: Exception) {
            errorResponse(Response.Status.INTERNAL_ERROR, error.message ?: "Benchmark write failed")
        } finally {
            inFlight.remove(chunkIndex)
            payload.delete()
        }
    }

    private fun cleanupBenchmark(session: IHTTPSession, path: String): Response {
        val match = Regex("^/api/lobby/v1/benchmark/([A-Fa-f0-9]{32})$").matchEntire(path)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "Invalid benchmark cleanup route")
        val id = match.groupValues[1]
        val parts = session.headers["x-lobby-benchmark"]?.split(':') ?: return errorResponse(Response.Status.FORBIDDEN, "Benchmark identity is required")
        if (parts.size != 4) return errorResponse(Response.Status.FORBIDDEN, "Invalid benchmark identity")
        val timestamp = parts[1].toLongOrNull() ?: return errorResponse(Response.Status.FORBIDDEN, "Invalid benchmark timestamp")
        val nonce = parts[2]
        val canonical = "BENCH-CLEAN|${parts[0]}|$timestamp|$nonce|$id"
        val senderId = verifyBenchmarkCaller(session, canonical)
            ?: return errorResponse(Response.Status.FORBIDDEN, "Benchmark cleanup authentication failed")
        val benchmark = benchmarkSessions[id]
        if (benchmark != null && benchmark.senderId != senderId) return errorResponse(Response.Status.FORBIDDEN, "Benchmark owner mismatch")
        benchmarkSessions.remove(id)?.directory?.deleteRecursively()
        return jsonResponse(JSONObject().put("ok", true))
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun recordBrowserUpload(name: String, mime: String, size: Long, uri: Uri) {
        val id = UUID.randomUUID().toString().replace("-", "")
        TransferHistoryStore.add(context, TransferHistoryEntry(
            id, name, "Browser / local network", size, mime, System.currentTimeMillis(), "completed", uri.toString()
        ))
        LobbyNotifications.show(context, id.hashCode(), "LoBBY file received", "$name · ${formatBytes(size)}", id)
        onTransferChanged?.invoke()
    }

    private fun upload(session: IHTTPSession, relativePath: String): Response {
        if (sharedFileRoot != null) {
            val destination = resolveFile(relativePath) ?: return text(Response.Status.BAD_REQUEST, "Invalid destination")
            val parent = destination.parentFile ?: return text(Response.Status.BAD_REQUEST, "Invalid destination")
            if (!parent.exists() || !parent.isDirectory) return text(Response.Status.NOT_FOUND, "Folder does not exist")
            val parsed = HashMap<String, String>()
            session.parseBody(parsed)
            val tempPath = parsed["content"] ?: return text(Response.Status.BAD_REQUEST, "Missing upload body")
            val tempFile = File(tempPath)
            val size = tempFile.length()
            return try {
                FileInputStream(tempFile).use { input -> destination.outputStream().use { output -> input.copyTo(output, 256 * 1024) } }
                recordBrowserUpload(destination.name, mimeType(destination.name), size, Uri.fromFile(destination))
                newFixedLengthResponse(Response.Status.CREATED, "application/json", "{\"ok\":true}")
            } catch (e: Exception) {
                text(Response.Status.INTERNAL_ERROR, e.message ?: "Upload failed")
            } finally { tempFile.delete() }
        }
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
        try {
            context.contentResolver.openOutputStream(destination.uri, "w")?.use { output -> FileInputStream(tempFile).use { input -> input.copyTo(output, 256 * 1024) } }
                ?: throw IllegalStateException("Could not open destination")
            recordBrowserUpload(name, mimeType(name), tempFile.length(), destination.uri)
            return newFixedLengthResponse(Response.Status.CREATED, "application/json", "{\"ok\":true}")
        } catch (error: Exception) {
            destination.delete()
            return text(Response.Status.INTERNAL_ERROR, error.message ?: "Upload failed")
        } finally { tempFile.delete() }
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

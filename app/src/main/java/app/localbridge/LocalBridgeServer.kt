package app.localbridge

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLDecoder

class LocalBridgeServer(
    private val context: Context,
    private val sharedTreeUri: Uri?,
    private val websiteTreeUri: Uri?,
    sharePathInput: String
) : NanoHTTPD("0.0.0.0", 8765) {

    private val sharePath = normalizePath(sharePathInput)
    private val sharedRoot = sharedTreeUri?.let { DocumentFile.fromTreeUri(context, it) } ?: run {
        val defaultFolder = context.getExternalFilesDir("Shared") ?: File(context.filesDir, "Shared")
        defaultFolder.mkdirs()
        DocumentFile.fromFile(defaultFolder)
    }
    private val websiteRoot = websiteTreeUri?.let { DocumentFile.fromTreeUri(context, it) }
    private val fallbackHtml = context.assets.open("index.html").bufferedReader().use { it.readText() }

    override fun serve(session: IHTTPSession): Response {
        return try {
            val rawPath = session.uri.substringBefore('?')
            val response = when {
                session.method == Method.OPTIONS -> newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
                session.method == Method.GET && rawPath == "/api/files" -> listing(session.parameters["path"]?.firstOrNull().orEmpty())
                session.method == Method.GET && rawPath.startsWith("$sharePath/") -> download(rawPath.removePrefix("$sharePath/"))
                session.method == Method.GET && rawPath == sharePath -> listing("")
                session.method == Method.HEAD && rawPath.startsWith("$sharePath/") -> head(rawPath.removePrefix("$sharePath/"))
                session.method == Method.PUT && rawPath.startsWith("$sharePath/") -> upload(session, rawPath.removePrefix("$sharePath/"))
                session.method == Method.DELETE && rawPath.startsWith("$sharePath/") -> delete(rawPath.removePrefix("$sharePath/"))
                session.method == Method.GET -> serveWebsite(rawPath)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, PUT, HEAD, DELETE, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Depth")
            response.addHeader("DAV", "1")
            response
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "Server error")
        }
    }

    private fun normalizePath(value: String): String {
        val cleaned = value.trim().ifBlank { "/files" }
        val withSlash = if (cleaned.startsWith('/')) cleaned else "/$cleaned"
        val normalized = withSlash.trimEnd('/').ifBlank { "/files" }
        require(normalized.matches(Regex("/[A-Za-z0-9_/-]+")) && !normalized.contains("//") && normalized.split('/').none { it == "." || it == ".." }) { "Use letters, numbers, hyphens, underscores, and slashes in the share path" }
        require(normalized != "/" && normalized != "/api" && normalized != "/api/files") { "Choose a share path other than /, /api, or /api/files" }
        return normalized
    }

    private fun decodedSegments(path: String): List<String> = path.split('/')
        .filter { it.isNotBlank() }
        .map { URLDecoder.decode(it, "UTF-8") }
        .also { segments -> require(segments.all { it != "." && it != ".." && !it.contains('\\') && !it.contains('/') }) }

    private fun resolveDocument(path: String, createParents: Boolean = false): DocumentFile? {
        var current = sharedRoot?.takeIf { it.exists() && it.isDirectory } ?: return null
        val segments = decodedSegments(path)
        if (segments.isEmpty()) return current
        segments.forEachIndexed { index, segment ->
            var next = current.findFile(segment)
            if (next == null && createParents && index < segments.lastIndex) next = current.createDirectory(segment)
            current = next ?: return null
        }
        return current
    }

    private fun upload(session: IHTTPSession, relativePath: String): Response {
        val segments = decodedSegments(relativePath)
        if (segments.isEmpty()) return text(Response.Status.BAD_REQUEST, "Missing filename")
        val name = segments.last()
        var parent = sharedRoot?.takeIf { it.exists() && it.isDirectory } ?: return text(Response.Status.INTERNAL_ERROR, "Choose a shared folder in LocalBridge")
        segments.dropLast(1).forEach { segment ->
            parent = parent.findFile(segment)?.takeIf { it.isDirectory }
                ?: return text(Response.Status.NOT_FOUND, "Folder does not exist")
        }
        val parsed = HashMap<String, String>()
        session.parseBody(parsed)
        val tempPath = parsed["content"] ?: return text(Response.Status.BAD_REQUEST, "Missing upload body")
        val tempFile = File(tempPath)
        val old = parent.findFile(name)
        if (old != null && !old.delete()) return text(Response.Status.INTERNAL_ERROR, "Could not replace existing file")
        val mime = mimeType(name)
        val destination = parent.createFile(mime, name) ?: return text(Response.Status.INTERNAL_ERROR, "Could not create file")
        context.contentResolver.openOutputStream(destination.uri, "w")?.use { output ->
            FileInputStream(tempFile).use { input -> input.copyTo(output, 1024 * 1024) }
        } ?: return text(Response.Status.INTERNAL_ERROR, "Could not open destination")
        return newFixedLengthResponse(Response.Status.CREATED, "application/json", "{\"ok\":true}")
    }

    private fun download(relativePath: String): Response {
        val doc = resolveDocument(relativePath) ?: return text(Response.Status.NOT_FOUND, "Not found")
        if (doc.isDirectory) return directoryListing(doc)
        val input = context.contentResolver.openInputStream(doc.uri) ?: return text(Response.Status.NOT_FOUND, "Not found")
        return newChunkedResponse(Response.Status.OK, doc.type ?: mimeType(doc.name ?: "file"), input)
    }

    private fun head(relativePath: String): Response {
        val doc = resolveDocument(relativePath) ?: return text(Response.Status.NOT_FOUND, "Not found")
        if (doc.isDirectory) return text(Response.Status.OK, "Directory")
        return newFixedLengthResponse(Response.Status.OK, doc.type ?: mimeType(doc.name ?: "file"), "").apply {
            if (doc.length() >= 0) addHeader("Content-Length", doc.length().toString())
        }
    }

    private fun delete(relativePath: String): Response {
        val doc = resolveDocument(relativePath) ?: return text(Response.Status.NOT_FOUND, "Not found")
        return if (doc.delete()) newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        else text(Response.Status.INTERNAL_ERROR, "Could not delete")
    }

    private fun listing(relativePath: String): Response {
        val root = sharedRoot ?: return newFixedLengthResponse(Response.Status.OK, "application/json", "[]")
        val directory = if (relativePath.isBlank()) root else resolveDocument(relativePath)
        if (directory == null || !directory.isDirectory) return text(Response.Status.NOT_FOUND, "Folder not found")
        return directoryJson(directory)
    }

    private fun directoryListing(directory: DocumentFile): Response = directoryJson(directory)

    private fun directoryJson(directory: DocumentFile): Response {
        val array = org.json.JSONArray()
        directory.listFiles().sortedBy { if (it.isDirectory) 0 else 1 }.forEach { item ->
            array.put(org.json.JSONObject()
                .put("name", item.name ?: "")
                .put("size", item.length())
                .put("directory", item.isDirectory))
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", array.toString())
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
        var doc: DocumentFile = root
        for (segment in segments) doc = doc.findFile(segment) ?: return text(Response.Status.NOT_FOUND, "Not found")
        if (doc.isDirectory) doc = doc.findFile("index.html") ?: return text(Response.Status.NOT_FOUND, "No index.html in this folder")
        val input = context.contentResolver.openInputStream(doc.uri) ?: return text(Response.Status.NOT_FOUND, "Not found")
        return newChunkedResponse(Response.Status.OK, doc.type ?: mimeType(doc.name ?: "file"), input)
    }

    private fun mimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun text(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", message)
}

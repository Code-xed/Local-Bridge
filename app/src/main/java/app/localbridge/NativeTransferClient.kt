package app.localbridge

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

internal data class TransferPayload(val uri: Uri, val name: String, val mimeType: String, val size: Long)

internal data class TransferProgress(
    val status: String,
    val transferredBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSecond: Double = 0.0,
    val etaSeconds: Long? = null,
    val currentFile: String = "",
    val currentFileIndex: Int = 0,
    val fileCount: Int = 0,
    val completedFiles: Int = 0,
    val fileBytes: List<Long> = emptyList(),
    val fileTotalBytes: List<Long> = emptyList(),
    val finished: Boolean = false,
    val error: String? = null
)

internal class TransferOperation internal constructor(
    private val worker: Thread,
    private val cancelled: AtomicBoolean,
    private val cancelAction: (() -> Unit)? = null
) {
    fun cancel() {
        cancelled.set(true)
        try { cancelAction?.invoke() } catch (_: Exception) { }
        worker.interrupt()
    }
}

internal class NativeTransferClient(context: Context) {
    private val context = context.applicationContext
    private data class PreparedFile(val item: TransferPayload, val size: Long, val staged: File?)
    private data class Chunk(val fileIndex: Int, val chunkIndex: Int, val offset: Long, val length: Long)

    fun beginPairing(
        device: LobbyDiscovery.Device,
        onCode: (String, String, String) -> Unit,
        onFinished: (Boolean, String) -> Unit
    ): TransferOperation {
        val cancelled = AtomicBoolean(false)
        val worker = Thread {
            try {
                val local = LobbyIdentity.get(context)
                val nonce = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
                val payload = "PAIR|${local.deviceId}|$nonce|${local.publicKey}"
                val body = JSONObject()
                    .put("deviceId", local.deviceId)
                    .put("publicKey", local.publicKey)
                    .put("name", "LoBBY-${android.os.Build.MODEL}")
                    .put("nonce", nonce)
                    .put("signature", LobbyIdentity.sign(context, payload.toByteArray(Charsets.UTF_8)))
                val start = requestJson(device.baseUrl + "/api/lobby/v1/pair/request", "POST", body)
                val remote = start.getJSONObject("identity")
                val remoteId = remote.getString("deviceId")
                val remoteKey = remote.getString("publicKey")
                if (LobbyIdentity.deviceId(remoteKey) != remoteId) throw IOException("Remote identity fingerprint is invalid")
                val expectedCode = LobbyIdentity.pairingCode(local.publicKey, remoteKey, nonce)
                if (start.getString("code") != expectedCode) throw IOException("Pairing verification code mismatch")
                onCode(expectedCode, remote.optString("name", device.name), remoteId)
                var status = "pending"
                for (attempt in 0 until 180) {
                    if (cancelled.get()) throw InterruptedException("Pairing cancelled")
                    val result = requestJson(device.baseUrl + "/api/lobby/v1/pair/status?nonce=$nonce", "GET")
                    status = result.optString("status", "pending")
                    if (status != "pending") break
                    Thread.sleep(1000)
                }
                if (status != "accepted") throw IOException(if (status == "declined") "Pairing was declined" else "Pairing timed out")
                LobbyIdentity.trustPeer(context, LobbyIdentity.TrustedPeer(remoteId, remote.optString("name", device.name), remoteKey, System.currentTimeMillis()))
                onFinished(true, "Paired with ${device.name.removePrefix("LoBBY-")}")
            } catch (error: Exception) {
                onFinished(false, error.message ?: "Pairing failed")
            }
        }.apply { name = "LoBBY-Pairing"; start() }
        return TransferOperation(worker, cancelled)
    }

    fun startTransfer(
        device: LobbyDiscovery.Device,
        files: List<TransferPayload>,
        tuningInput: TransferTuning,
        onProgress: (TransferProgress) -> Unit
    ): TransferOperation {
        val cancelled = AtomicBoolean(false)
        val activeConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
        val worker = Thread {
            val stagedFiles = mutableListOf<File>()
            var transferId: String? = null
            var localSenderId: String? = null
            var transferSessionKey: SecretKeySpec? = null
            try {
                val tuning = tuningInput.normalized()
                val localIdentity = LobbyIdentity.get(context)
                localSenderId = localIdentity.deviceId
                val remoteIdentity = requestJson(device.baseUrl + "/api/lobby/v1/identity", "GET")
                val remoteId = remoteIdentity.getString("deviceId")
                val remoteKey = remoteIdentity.getString("publicKey")
                if (LobbyIdentity.deviceId(remoteKey) != remoteId || (device.deviceId != null && device.deviceId != remoteId)) {
                    throw IOException("Discovered device identity changed. Pair it again.")
                }
                val trusted = LobbyIdentity.findTrustedPeer(context, remoteId)
                if (trusted != null && trusted.publicKey != remoteKey) throw IOException("Trusted identity mismatch. Revoke and pair this device again.")
                val prepared = files.map { item -> prepareFile(item).also { it.staged?.let(stagedFiles::add) } }
                val totalBytes = prepared.sumOf { it.size }
                transferId = UUID.randomUUID().toString().replace("-", "")
                val filesJson = JSONArray().apply {
                    prepared.forEach { put(JSONObject().put("name", it.item.name).put("size", it.size).put("mime", it.item.mimeType)) }
                }
                val timestamp = System.currentTimeMillis()
                val chunkSize = tuning.chunkSizeMiB * 1024L * 1024L
                val senderEphemeral = TransferCrypto.createEphemeralKeyPair()
                val senderEphemeralPublicKey = TransferCrypto.encodePublicKey(senderEphemeral)
                val senderName = "LoBBY-${android.os.Build.MODEL}"
                val canonical = "$transferId|$timestamp|${localIdentity.deviceId}|${localIdentity.publicKey}|$filesJson|$chunkSize|$senderEphemeralPublicKey|$senderName"
                val request = JSONObject()
                    .put("transferId", transferId)
                    .put("timestamp", timestamp)
                    .put("senderId", localIdentity.deviceId)
                    .put("senderPublicKey", localIdentity.publicKey)
                    .put("senderName", senderName)
                    .put("senderEphemeralPublicKey", senderEphemeralPublicKey)
                    .put("chunkSize", chunkSize)
                    .put("files", filesJson)
                    .put("signature", LobbyIdentity.sign(context, canonical.toByteArray(Charsets.UTF_8)))
                val created = requestJson(device.baseUrl + "/api/lobby/v1/transfers/request", "POST", request)
                val accepted = awaitAcceptance(device, transferId, cancelled, created.optString("status", "pending"), remoteId, localIdentity.deviceId, remoteKey)
                val receiverEphemeralPublicKey = accepted.getString("receiverEphemeralPublicKey")
                val sessionKey = TransferCrypto.deriveSessionKey(senderEphemeral.private, receiverEphemeralPublicKey, transferId, localIdentity.deviceId, remoteId)
                transferSessionKey = sessionKey

                val totalChunks = prepared.sumOf { if (it.size == 0L) 0 else ceil(it.size.toDouble() / chunkSize).toInt() }
                val fileBytes = AtomicLongArray(prepared.size)
                val wireBytes = AtomicLong(0L)
                val activeFileName = arrayOf("")
                val activeFileIndex = AtomicLong(0L)
                val samples = ArrayDeque<Pair<Long, Long>>()
                var lastTick = System.nanoTime()
                var lastBytes = 0L
                var lastAdaptTick = lastTick
                var lastAdaptBytes = 0L
                var previousWindowSpeed = 0.0
                val savedBenchmark = if (tuning.automaticBenchmark) TransferPerformance.loadBenchmarks(context)
                    .firstOrNull { it.peerId == remoteId && it.direction == "send" } else null
                val maxWorkers = tuning.maxConnections.coerceIn(1, 24)
                val effectiveParallelFiles = minOf(tuning.maxParallelFiles, savedBenchmark?.optimalParallelFiles ?: tuning.maxParallelFiles).coerceAtLeast(1)
                val effectiveConnectionsPerFile = if (savedBenchmark != null && tuning.adaptive) {
                    minOf(tuning.connectionsPerFile, ceil(savedBenchmark.optimalConnections.toDouble() / savedBenchmark.optimalParallelFiles.coerceAtLeast(1)).toInt()).coerceAtLeast(1)
                } else tuning.connectionsPerFile.coerceAtMost(maxWorkers).coerceAtLeast(1)
                var targetWorkers = when {
                    !tuning.adaptive -> maxWorkers
                    savedBenchmark != null -> savedBenchmark.optimalConnections.coerceIn(1, maxWorkers)
                    else -> minOf(2, maxWorkers)
                }
                val executor = Executors.newFixedThreadPool(maxWorkers)
                val completion = ExecutorCompletionService<Chunk>(executor)
                val queues = prepared.mapIndexed { fileIndex, file ->
                    ArrayDeque<Chunk>().apply {
                        val count = if (file.size == 0L) 0 else ceil(file.size.toDouble() / chunkSize).toInt()
                        repeat(count) { chunkIndex ->
                            val offset = chunkIndex.toLong() * chunkSize
                            add(Chunk(fileIndex, chunkIndex, offset, minOf(chunkSize, file.size - offset)))
                        }
                    }
                }
                val activePerFile = IntArray(prepared.size)
                var activeFileCount = 0
                var activeWorkers = 0
                var nextFile = 0
                var finishedChunks = 0
                fun publishProgress(now: Long, force: Boolean = false) {
                    if (!force && now - lastTick < 200_000_000L) return
                    val currentBytes = wireBytes.get().coerceIn(0L, totalBytes)
                    val elapsedMs = ((now - lastTick) / 1_000_000L).coerceAtLeast(1L)
                    val intervalBytes = (currentBytes - lastBytes).coerceAtLeast(0L)
                    samples.addLast(intervalBytes to elapsedMs)
                    while (samples.size > 5) samples.removeFirst()
                    val sampledBytes = samples.sumOf { it.first }
                    val sampledMs = samples.sumOf { it.second }.coerceAtLeast(1L)
                    val speed = sampledBytes * 1000.0 / sampledMs
                    val remaining = (totalBytes - currentBytes).coerceAtLeast(0L)
                    val completedFiles = prepared.indices.count { fileBytes.get(it) >= prepared[it].size }
                    onProgress(TransferProgress(
                        status = "Sending",
                        transferredBytes = currentBytes,
                        totalBytes = totalBytes,
                        speedBytesPerSecond = speed,
                        etaSeconds = if (speed > 0) (remaining / speed).toLong() else null,
                        currentFile = activeFileName[0],
                        currentFileIndex = activeFileIndex.get().toInt(),
                        fileCount = prepared.size,
                        completedFiles = completedFiles,
                        fileBytes = prepared.indices.map { fileBytes.get(it).coerceAtLeast(0L).coerceAtMost(prepared[it].size) },
                        fileTotalBytes = prepared.map { it.size }
                    ))
                    lastTick = now
                    lastBytes = currentBytes
                }
                try {
                    while (finishedChunks < totalChunks) {
                        if (cancelled.get()) throw InterruptedException("Transfer cancelled")
                        var submitted = true
                        while (activeWorkers < targetWorkers && submitted) {
                            submitted = false
                            var selected = -1
                            for (offset in prepared.indices) {
                                val index = (nextFile + offset) % prepared.size
                                val alreadyActive = activePerFile[index] > 0
                                if (queues[index].isNotEmpty() && activePerFile[index] < effectiveConnectionsPerFile && (alreadyActive || activeFileCount < effectiveParallelFiles)) {
                                    selected = index
                                    nextFile = (index + 1) % prepared.size
                                    break
                                }
                            }
                            if (selected >= 0) {
                                val chunk = queues[selected].removeFirst()
                                if (activePerFile[selected] == 0) activeFileCount++
                                activePerFile[selected]++
                                activeWorkers++
                                activeFileName[0] = prepared[chunk.fileIndex].item.name
                                activeFileIndex.set(chunk.fileIndex.toLong())
                                completion.submit {
                                    uploadChunk(device, transferId, prepared[chunk.fileIndex], chunk, tuning, sessionKey, cancelled, activeConnections) { delta ->
                                        wireBytes.addAndGet(delta)
                                        fileBytes.addAndGet(chunk.fileIndex, delta)
                                    }
                                    chunk
                                }
                                submitted = true
                            }
                        }
                        if (activeWorkers == 0) throw IOException("Transfer scheduler stalled")
                        val completedFuture = completion.poll(200, TimeUnit.MILLISECONDS)
                        val now = System.nanoTime()
                        if (completedFuture != null) {
                            val completed = completedFuture.get()
                            activeWorkers--
                            activePerFile[completed.fileIndex]--
                            if (activePerFile[completed.fileIndex] == 0) activeFileCount--
                            finishedChunks++
                            activeFileName[0] = prepared[completed.fileIndex].item.name
                            activeFileIndex.set(completed.fileIndex.toLong())
                        }
                        publishProgress(now)
                        if (tuning.adaptive && now - lastAdaptTick >= 3_000_000_000L) {
                            val currentAdaptBytes = wireBytes.get()
                            val windowBytes = (currentAdaptBytes - lastAdaptBytes).coerceAtLeast(0L)
                            val windowSeconds = ((now - lastAdaptTick) / 1_000_000_000.0).coerceAtLeast(0.1)
                            val windowSpeed = windowBytes / windowSeconds
                            if (previousWindowSpeed > 0.0) {
                                when {
                                    windowSpeed > previousWindowSpeed * 1.06 && targetWorkers < maxWorkers -> targetWorkers++
                                    windowSpeed < previousWindowSpeed * 0.88 && targetWorkers > 1 -> targetWorkers--
                                }
                            } else if (targetWorkers < maxWorkers && savedBenchmark == null) {
                                targetWorkers++
                            }
                            previousWindowSpeed = windowSpeed
                            lastAdaptBytes = currentAdaptBytes
                            lastAdaptTick = now
                        }
                    }
                } finally {
                    executor.shutdownNow()
                }
                if (totalChunks == 0) {
                    onProgress(TransferProgress("Sending", 0L, totalBytes, fileCount = prepared.size, fileBytes = prepared.map { it.size }, fileTotalBytes = prepared.map { it.size }))
                } else {
                    publishProgress(System.nanoTime(), true)
                }
                if (cancelled.get()) throw InterruptedException("Transfer cancelled")
                requestJson(device.baseUrl + "/api/lobby/v1/transfers/$transferId/complete", "POST", JSONObject(), sessionKey)
                onProgress(TransferProgress("Transfer complete", totalBytes, totalBytes, finished = true, fileCount = prepared.size, completedFiles = prepared.size, fileBytes = prepared.map { it.size }, fileTotalBytes = prepared.map { it.size }))
            } catch (error: Exception) {
                transferId?.let { id ->
                    try {
                        val senderId = localSenderId
                        if (senderId != null) {
                            val timestamp = System.currentTimeMillis()
                            val canonical = "CANCEL|$id|$senderId|$timestamp".toByteArray(Charsets.UTF_8)
                            val cancelBody = JSONObject().put("senderId", senderId).put("timestamp", timestamp)
                                .put("signature", LobbyIdentity.sign(context, canonical))
                            requestJson(device.baseUrl + "/api/lobby/v1/transfers/$id/cancel", "POST", cancelBody)
                        }
                    } catch (_: Exception) { }
                }
                val message = if (error is InterruptedException || cancelled.get()) "Transfer cancelled" else (error.message ?: "Transfer failed")
                onProgress(TransferProgress(message, finished = true, error = message, fileCount = files.size))
            } finally {
                stagedFiles.forEach { it.delete() }
            }
        }.apply { name = "LoBBY-Transfer"; start() }
        return TransferOperation(worker, cancelled) {
            activeConnections.toList().forEach { it.disconnect() }
        }
    }

    private fun awaitAcceptance(
        device: LobbyDiscovery.Device,
        transferId: String,
        cancelled: AtomicBoolean,
        initialState: String,
        expectedReceiverId: String,
        senderId: String,
        trustedReceiverKey: String
    ): JSONObject {
        var state = initialState
        var response = JSONObject()
        repeat(300) {
            if (cancelled.get()) throw InterruptedException("Transfer cancelled")
            if (state == "declined") throw IOException("The receiving device declined the transfer")
            if (state == "failed" || state == "cancelled") throw IOException("Receiver could not prepare the transfer")
            if (state == "accepted" || state == "receiving") {
                response = requestJson(device.baseUrl + "/api/lobby/v1/transfers/$transferId", "GET")
                val receiverId = response.optString("receiverDeviceId")
                val ephemeralPublicKey = response.optString("receiverEphemeralPublicKey")
                val signature = response.optString("receiverEphemeralSignature")
                if (receiverId != expectedReceiverId || ephemeralPublicKey.isBlank() || signature.isBlank()) {
                    throw IOException("Receiver session identity is incomplete")
                }
                val transcript = "SESSION|$transferId|$senderId|$receiverId|$ephemeralPublicKey".toByteArray(Charsets.UTF_8)
                if (!LobbyIdentity.verify(trustedReceiverKey, transcript, signature)) {
                    throw IOException("Receiver session authentication failed")
                }
                return response
            }
            Thread.sleep(1000)
            response = requestJson(device.baseUrl + "/api/lobby/v1/transfers/$transferId", "GET")
            state = response.optString("status", "pending")
        }
        throw IOException("Receiver did not respond to the transfer request")
    }

    private fun prepareFile(item: TransferPayload): PreparedFile {
        if (item.size < 0L) {
            val staged = File.createTempFile("lobby-send-", ".stage", context.cacheDir)
            try {
                context.contentResolver.openInputStream(item.uri)?.use { input -> staged.outputStream().use { output -> input.copyTo(output, 1024 * 1024) } }
                    ?: throw IOException("Cannot open ${item.name}")
                return PreparedFile(item, staged.length(), staged)
            } catch (error: Exception) {
                staged.delete()
                throw error
            }
        }
        val pfd = context.contentResolver.openFileDescriptor(item.uri, "r")
            ?: throw IOException("Cannot open ${item.name}")
        var seekable = false
        try {
            val channel = FileInputStream(pfd.fileDescriptor).channel
            channel.position(0L)
            if (item.size > 0) channel.position(minOf(1L, item.size - 1L))
            channel.position(0L)
            seekable = true
        } catch (_: Exception) {
            seekable = false
        } finally {
            try { pfd.close() } catch (_: Exception) { }
        }
        if (seekable) return PreparedFile(item, item.size, null)

        val staged = File.createTempFile("lobby-send-", ".stage", context.cacheDir)
        try {
            context.contentResolver.openInputStream(item.uri)?.use { input -> staged.outputStream().use { output -> input.copyTo(output, 1024 * 1024) } }
                ?: throw IOException("Cannot open ${item.name}")
            if (item.size >= 0L && staged.length() != item.size) throw IOException("${item.name} changed while preparing")
            return PreparedFile(item, staged.length(), staged)
        } catch (error: Exception) {
            staged.delete()
            throw error
        }
    }

    private fun uploadChunk(
        device: LobbyDiscovery.Device,
        transferId: String,
        file: PreparedFile,
        chunk: Chunk,
        tuning: TransferTuning,
        sessionKey: SecretKeySpec,
        cancelled: AtomicBoolean,
        activeConnections: MutableSet<HttpURLConnection>,
        onBytesDelta: (Long) -> Unit
    ) {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < 8) {
            if (cancelled.get()) throw InterruptedException("Transfer cancelled")
            attempt++
            var attemptBytes = 0L
            val encryptedLength = TransferCrypto.encryptedLength(chunk.length)
            val connection = (URL(
                device.baseUrl + "/api/lobby/v1/transfers/$transferId/files/${chunk.fileIndex}/chunks/${chunk.chunkIndex}"
            ).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                val requestPath = "/api/lobby/v1/transfers/$transferId/files/${chunk.fileIndex}/chunks/${chunk.chunkIndex}"
                setRequestProperty("X-LoBBY-Session", TransferCrypto.createSessionAuthHeader(sessionKey, "PUT", requestPath))
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 120_000
                setFixedLengthStreamingMode(encryptedLength)
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("Connection", "keep-alive")
            }
            activeConnections.add(connection)
            try {
                connection.outputStream.use { output ->
                    val onPlainBytes: (Int) -> Unit = { count ->
                        attemptBytes += count
                        onBytesDelta(count.toLong())
                    }
                    if (file.staged != null) {
                        FileInputStream(file.staged).use { input ->
                            input.channel.position(chunk.offset)
                            TransferCrypto.encryptRange(
                                input, output, chunk.length, sessionKey, transferId,
                                chunk.fileIndex, chunk.chunkIndex, chunk.offset,
                                tuning.bufferKiB * 1024, { cancelled.get() }, onPlainBytes
                            )
                        }
                    } else {
                        val pfd = context.contentResolver.openFileDescriptor(file.item.uri, "r")
                            ?: throw IOException("Cannot reopen ${file.item.name}")
                        try {
                            FileInputStream(pfd.fileDescriptor).use { input ->
                                input.channel.position(chunk.offset)
                                TransferCrypto.encryptRange(
                                    input, output, chunk.length, sessionKey, transferId,
                                    chunk.fileIndex, chunk.chunkIndex, chunk.offset,
                                    tuning.bufferKiB * 1024, { cancelled.get() }, onPlainBytes
                                )
                            }
                        } finally { pfd.close() }
                    }
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = try { connection.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
                    throw IOException("Chunk upload HTTP $code${detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
                }
                return
            } catch (error: Exception) {
                lastError = error
                if (attemptBytes > 0L) onBytesDelta(-attemptBytes)
                if (error is InterruptedException || cancelled.get() || attempt >= 8) throw error
                val delay = when (attempt) { 1 -> 300L; 2 -> 600L; 3 -> 1_000L; 4 -> 2_000L; else -> 4_000L }
                try { Thread.sleep(delay) } catch (_: InterruptedException) { throw InterruptedException("Transfer cancelled") }
            } finally {
                activeConnections.remove(connection)
                connection.disconnect()
            }
        }
        throw lastError ?: IOException("Chunk upload failed")
    }

    private fun requestJson(url: String, method: String, body: JSONObject? = null, sessionKey: SecretKeySpec? = null): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            if (sessionKey != null) {
                val requestPath = URL(url).path
                connection.setRequestProperty("X-LoBBY-Session", TransferCrypto.createSessionAuthHeader(sessionKey, method, requestPath))
            }
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = try {
                    JSONObject(text).optString("error").takeIf { it.isNotBlank() } ?: text.takeIf { it.isNotBlank() } ?: "HTTP $code"
                } catch (_: Exception) {
                    text.takeIf { it.isNotBlank() } ?: "HTTP $code"
                }
                throw IOException("HTTP $code: ${message.take(500)}")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    fun runBenchmark(
        device: LobbyDiscovery.Device,
        tuningInput: TransferTuning,
        onProgress: (String) -> Unit,
        onFinished: (PairBenchmark?, String) -> Unit
    ): TransferOperation {
        val cancelled = AtomicBoolean(false)
        val activeConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
        val worker = Thread {
            try {
                val remoteIdentity = requestJson(device.baseUrl + "/api/lobby/v1/identity", "GET")
                val remoteId = remoteIdentity.optString("deviceId", device.deviceId ?: device.name)
                val remotePublicKey = remoteIdentity.getString("publicKey")
                if (LobbyIdentity.deviceId(remotePublicKey) != remoteId) throw IOException("Benchmark peer identity is invalid")
                val trusted = LobbyIdentity.findTrustedPeer(context, remoteId)
                    ?: throw IOException("Pair with this device before benchmarking")
                if (trusted.publicKey != remotePublicKey) throw IOException("Trusted identity mismatch")
                val localIdentity = LobbyIdentity.get(context)
                // A focused matrix keeps the probe useful without needlessly writing gigabytes of test data.
                val limits = tuningInput.normalized()
                val configs = listOf(
                    1 to 1, 1 to 2, 1 to 4,
                    2 to 2, 2 to 4, 2 to 8,
                    3 to 2, 3 to 3, 3 to 4,
                    4 to 2, 4 to 3, 4 to 4,
                    8 to 2
                ).filter {
                    it.first <= limits.maxParallelFiles &&
                        it.second <= limits.connectionsPerFile &&
                        it.first * it.second <= limits.maxConnections
                }.ifEmpty { listOf(1 to 1) }
                var bestMedian = 0.0
                var best = configs.first()
                val payloadSize = 4 * 1024 * 1024

                configs.shuffled().forEach { (parallelFiles, perFile) ->
                    if (cancelled.get()) throw InterruptedException("Benchmark cancelled")
                    val workers = parallelFiles * perFile
                    val measurements = mutableListOf<Double>()
                    repeat(3) { round ->
                        if (cancelled.get()) throw InterruptedException("Benchmark cancelled")
                        onProgress("Testing $parallelFiles file(s) × $perFile chunk connection(s) · run ${round + 1}/3…")
                        val testId = UUID.randomUUID().toString().replace("-", "")
                        val pool = Executors.newFixedThreadPool(workers)
                        var measuredSeconds: Double? = null
                        try {
                            val start = System.nanoTime()
                            val tasks = (0 until parallelFiles).flatMap { fileIndex ->
                                (0 until perFile).map { chunkIndex ->
                                    pool.submit {
                                        sendBenchmarkChunk(
                                            device, testId, parallelFiles, perFile, fileIndex, chunkIndex,
                                            payloadSize, localIdentity, cancelled, activeConnections
                                        )
                                    }
                                }
                            }
                            tasks.forEach { it.get() }
                            measuredSeconds = ((System.nanoTime() - start) / 1_000_000_000.0).coerceAtLeast(0.001)
                        } finally {
                            pool.shutdownNow()
                            try { pool.awaitTermination(10, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                            cleanupBenchmark(device, testId, localIdentity)
                        }
                        val seconds = measuredSeconds ?: throw IOException("Benchmark run did not complete")
                        val medianSample = workers * payloadSize / seconds / 1_000_000.0
                        measurements += medianSample
                    }
                    val median = measurements.sorted()[measurements.size / 2]
                    onProgress("$workers connections ($parallelFiles files × $perFile chunks/file): median %.1f MB/s".format(median))
                    if (median > bestMedian) { bestMedian = median; best = parallelFiles to perFile }
                }
                val result = PairBenchmark(
                    remoteId,
                    "send",
                    bestMedian,
                    best.first * best.second,
                    best.first,
                    4,
                    System.currentTimeMillis()
                )
                TransferPerformance.saveBenchmark(context, result)
                onFinished(result, "Benchmark complete · best measured median %.1f MB/s".format(bestMedian))
            } catch (error: Exception) {
                onFinished(null, error.message ?: "Benchmark failed")
            }
        }.apply { name = "LoBBY-Benchmark"; start() }
        return TransferOperation(worker, cancelled) {
            activeConnections.toList().forEach { it.disconnect() }
        }
    }

    private fun sendBenchmarkChunk(
        device: LobbyDiscovery.Device,
        testId: String,
        fileCount: Int,
        chunksPerFile: Int,
        fileIndex: Int,
        chunkIndex: Int,
        payloadSize: Int,
        identity: LobbyIdentity.Identity,
        cancelled: AtomicBoolean,
        activeConnections: MutableSet<HttpURLConnection>
    ) {
        if (cancelled.get()) throw InterruptedException("Benchmark cancelled")
        val timestamp = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val canonical = "BENCH|${identity.deviceId}|$timestamp|$nonce|$testId|$fileCount|$chunksPerFile|$fileIndex|$chunkIndex|$payloadSize"
        val signature = LobbyIdentity.sign(context, canonical.toByteArray(Charsets.UTF_8))
        val path = "/api/lobby/v1/benchmark/$testId/$fileCount/$chunksPerFile/$fileIndex/$chunkIndex"
        val connection = (URL(device.baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setFixedLengthStreamingMode(payloadSize)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-LoBBY-Benchmark", "${identity.deviceId}:$timestamp:$nonce:$signature")
        }
        val data = ByteArray(256 * 1024) { ((it * 31 + fileIndex * 7 + chunkIndex) and 0xff).toByte() }
        activeConnections.add(connection)
        try {
            connection.outputStream.use { output ->
                var remaining = payloadSize.toLong()
                while (remaining > 0L) {
                    if (cancelled.get()) throw InterruptedException("Benchmark cancelled")
                    val count = minOf(data.size.toLong(), remaining).toInt()
                    output.write(data, 0, count)
                    remaining -= count
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = try { connection.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
                throw IOException("Benchmark request failed: HTTP $code${detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
            }
            connection.inputStream.close()
        } finally {
            activeConnections.remove(connection)
            connection.disconnect()
        }
    }

    private fun cleanupBenchmark(device: LobbyDiscovery.Device, testId: String, identity: LobbyIdentity.Identity) {
        val timestamp = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val canonical = "BENCH-CLEAN|${identity.deviceId}|$timestamp|$nonce|$testId"
        val signature = LobbyIdentity.sign(context, canonical.toByteArray(Charsets.UTF_8))
        val connection = (URL(device.baseUrl + "/api/lobby/v1/benchmark/$testId").openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("X-LoBBY-Benchmark", "${identity.deviceId}:$timestamp:$nonce:$signature")
        }
        try {
            val code = connection.responseCode
            if (code in 200..299) connection.inputStream.close() else connection.errorStream?.close()
        } catch (_: Exception) {
        } finally {
            connection.disconnect()
        }
    }
}

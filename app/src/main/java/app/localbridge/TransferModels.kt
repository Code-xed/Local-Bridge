package app.localbridge

import org.json.JSONArray
import org.json.JSONObject

internal data class TransferFileInfo(
    val name: String,
    val size: Long,
    val mimeType: String
)

internal data class IncomingTransferInfo(
    val id: String,
    val senderId: String,
    val senderName: String,
    val files: List<TransferFileInfo>,
    val totalBytes: Long,
    val status: String,
    val createdAt: Long,
    val receivedBytes: Long = 0L,
    val fileReceivedBytes: List<Long> = emptyList(),
    val speedBytesPerSecond: Double = 0.0,
    val etaSeconds: Long? = null
) {
    fun toJson(): JSONObject {
        val array = JSONArray()
        files.forEach { array.put(JSONObject().put("name", it.name).put("size", it.size).put("mime", it.mimeType)) }
        return JSONObject()
            .put("id", id).put("senderId", senderId).put("senderName", senderName)
            .put("files", array).put("totalBytes", totalBytes).put("status", status).put("createdAt", createdAt)
            .put("receivedBytes", receivedBytes)
            .put("fileReceivedBytes", JSONArray().apply { fileReceivedBytes.forEach { put(it) } })
            .put("speedBytesPerSecond", speedBytesPerSecond)
            .put("etaSeconds", etaSeconds ?: JSONObject.NULL)
    }
}

internal data class TransferHistoryEntry(
    val id: String,
    val name: String,
    val sender: String,
    val size: Long,
    val mimeType: String,
    val timestamp: Long,
    val status: String,
    val uri: String = ""
)

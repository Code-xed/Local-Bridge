package app.localbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object TransferHistoryStore {
    private const val PREFS = "lobby_transfer_history"
    private const val KEY = "entries"

    @Synchronized
    internal fun add(context: Context, entry: TransferHistoryEntry) {
        val current = load(context).filterNot { it.id == entry.id && it.name == entry.name }
        val updated = (listOf(entry) + current).take(200)
        val array = JSONArray()
        updated.forEach { row ->
            array.put(JSONObject()
                .put("id", row.id).put("name", row.name).put("sender", row.sender)
                .put("size", row.size).put("mime", row.mimeType).put("time", row.timestamp)
                .put("status", row.status).put("uri", row.uri))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    internal fun load(context: Context): List<TransferHistoryEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val row = array.optJSONObject(index) ?: return@mapNotNull null
                TransferHistoryEntry(
                    row.optString("id"), row.optString("name"), row.optString("sender"),
                    row.optLong("size", -1L), row.optString("mime", "application/octet-stream"),
                    row.optLong("time"), row.optString("status"), row.optString("uri")
                )
            }
        } catch (_: Exception) { emptyList() }
    }
}

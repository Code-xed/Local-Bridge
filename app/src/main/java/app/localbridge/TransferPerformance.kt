package app.localbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** User-facing performance presets. Limits are ceilings; the scheduler may use fewer workers. */
enum class TransferProfile(val title: String) {
    BATTERY_SAVER("Battery Saver"),
    BALANCED("Balanced"),
    PERFORMANCE("Performance"),
    MAXIMUM_THROUGHPUT("Maximum Throughput"),
    CUSTOM("Custom")
}

data class TransferTuning(
    val maxParallelFiles: Int = 3,
    val connectionsPerFile: Int = 2,
    val maxConnections: Int = 6,
    val chunkSizeMiB: Int = 8,
    val bufferKiB: Int = 256,
    val adaptive: Boolean = true,
    val automaticBenchmark: Boolean = true
) {
    fun normalized(): TransferTuning {
        val files = maxParallelFiles.coerceIn(1, 8)
        val perFile = connectionsPerFile.coerceIn(1, 8)
        val total = maxConnections.coerceIn(1, 24)
        return copy(
            maxParallelFiles = files,
            connectionsPerFile = perFile,
            maxConnections = total,
            chunkSizeMiB = chunkSizeMiB.coerceIn(1, 32),
            bufferKiB = bufferKiB.coerceIn(64, 1024)
        )
    }

    companion object {
        fun preset(profile: TransferProfile): TransferTuning = when (profile) {
            TransferProfile.BATTERY_SAVER -> TransferTuning(1, 1, 1, 4, 128, false, false)
            TransferProfile.BALANCED -> TransferTuning(2, 2, 4, 8, 256, true, true)
            TransferProfile.PERFORMANCE -> TransferTuning(3, 3, 9, 8, 256, true, true)
            TransferProfile.MAXIMUM_THROUGHPUT -> TransferTuning(4, 8, 24, 8, 512, true, true)
            TransferProfile.CUSTOM -> TransferTuning()
        }.normalized()
    }
}

data class PairBenchmark(
    val peerId: String,
    val direction: String,
    val bestMedianMBps: Double,
    val optimalConnections: Int,
    val optimalParallelFiles: Int,
    val chunkSizeMiB: Int,
    val testedAt: Long
)

object TransferPerformance {
    private const val PREFS = "lobby_performance"
    private const val PROFILE = "profile"
    private const val TUNING = "tuning"

    fun loadProfile(context: Context): TransferProfile = try {
        TransferProfile.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PROFILE, TransferProfile.MAXIMUM_THROUGHPUT.name)!!)
    } catch (_: Exception) { TransferProfile.MAXIMUM_THROUGHPUT }

    fun loadTuning(context: Context): TransferTuning {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val profile = loadProfile(context)
        val preset = TransferTuning.preset(profile)
        return try {
            val json = JSONObject(prefs.getString(TUNING, "{}") ?: "{}")
            preset.copy(
                maxParallelFiles = json.optInt("files", preset.maxParallelFiles),
                connectionsPerFile = json.optInt("perFile", preset.connectionsPerFile),
                maxConnections = json.optInt("total", preset.maxConnections),
                chunkSizeMiB = json.optInt("chunk", preset.chunkSizeMiB),
                bufferKiB = json.optInt("buffer", preset.bufferKiB),
                adaptive = json.optBoolean("adaptive", preset.adaptive),
                automaticBenchmark = json.optBoolean("autoBenchmark", preset.automaticBenchmark)
            ).normalized()
        } catch (_: Exception) { preset }
    }

    fun save(context: Context, profile: TransferProfile, tuning: TransferTuning) {
        val value = tuning.normalized()
        val json = JSONObject()
            .put("files", value.maxParallelFiles)
            .put("perFile", value.connectionsPerFile)
            .put("total", value.maxConnections)
            .put("chunk", value.chunkSizeMiB)
            .put("buffer", value.bufferKiB)
            .put("adaptive", value.adaptive)
            .put("autoBenchmark", value.automaticBenchmark)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PROFILE, profile.name)
            .putString(TUNING, json.toString())
            .apply()
    }

    fun saveBenchmark(context: Context, result: PairBenchmark) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = try { JSONArray(prefs.getString("benchmarks", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val filtered = JSONArray()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            if (item.optString("peer") != result.peerId || item.optString("direction") != result.direction) filtered.put(item)
        }
        filtered.put(JSONObject()
            .put("peer", result.peerId)
            .put("direction", result.direction)
            .put("MBps", result.bestMedianMBps)
            .put("connections", result.optimalConnections)
            .put("files", result.optimalParallelFiles)
            .put("chunk", result.chunkSizeMiB)
            .put("tested", result.testedAt))
        prefs.edit().putString("benchmarks", filtered.toString()).apply()
    }

    fun loadBenchmarks(context: Context): List<PairBenchmark> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("benchmarks", "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                PairBenchmark(item.getString("peer"), item.optString("direction", "send"), item.optDouble("MBps", 0.0), item.optInt("connections", 1), item.optInt("files", 1), item.optInt("chunk", 8), item.optLong("tested", 0L))
            }
        } catch (_: Exception) { emptyList() }
    }
}

package io.github.slayer8366.enhancer.jobs

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What produced an output file. Kept so the Compare view can find the original and so results can be cited. */
data class HistoryRecord(
    val output: Uri,
    val source: Uri,
    val sourceName: String,
    val kind: MediaKind,
    val modelId: String,
    val outScale: Int,
    val pad: Int,
    val backend: String,
    val outWidth: Int,
    val outHeight: Int,
    val tiles: Long,
    val msPerTile: Double,
    val totalMs: Long,
    val finishedAt: Long,
    val notes: List<String>,
) {
    fun toJson() = JSONObject().apply {
        put("output", output.toString()); put("source", source.toString()); put("sourceName", sourceName)
        put("kind", kind.name); put("modelId", modelId); put("outScale", outScale); put("pad", pad)
        put("backend", backend); put("outWidth", outWidth); put("outHeight", outHeight); put("tiles", tiles)
        put("msPerTile", msPerTile); put("totalMs", totalMs); put("finishedAt", finishedAt)
        put("notes", JSONArray(notes))
    }

    companion object {
        fun fromJson(o: JSONObject) = HistoryRecord(
            output = Uri.parse(o.getString("output")), source = Uri.parse(o.getString("source")),
            sourceName = o.optString("sourceName"), kind = MediaKind.valueOf(o.getString("kind")),
            modelId = o.getString("modelId"), outScale = o.getInt("outScale"), pad = o.optInt("pad"),
            backend = o.optString("backend"), outWidth = o.optInt("outWidth"), outHeight = o.optInt("outHeight"),
            tiles = o.optLong("tiles"), msPerTile = o.optDouble("msPerTile"), totalMs = o.optLong("totalMs"),
            finishedAt = o.optLong("finishedAt"),
            notes = o.optJSONArray("notes")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
        )
    }
}

object History {
    private val state = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val records: StateFlow<List<HistoryRecord>> = state.asStateFlow()
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "history.json")

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        if (!f.exists()) return
        state.value = runCatching {
            val a = JSONArray(f.readText())
            List(a.length()) { HistoryRecord.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(context: Context, record: HistoryRecord) {
        load(context)
        state.value = listOf(record) + state.value.filter { it.output != record.output }
        val a = JSONArray().apply { state.value.forEach { put(it.toJson()) } }
        val f = file(context)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(f)
    }

    /** Matches by MediaStore id, since gallery URIs and stored URIs may differ in collection path. */
    fun forOutput(id: Long): HistoryRecord? = state.value.firstOrNull { it.output.lastPathSegment == id.toString() }
}

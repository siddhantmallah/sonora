package com.sonora.player.playback

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedQueue(
    val mediaIds: List<String>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
)

/** Remembers the queue between launches so playback resumes exactly where it stopped. */
class QueueStore(context: Context) {
    private val file = File(context.filesDir, "queue.json")

    suspend fun save(q: SavedQueue) = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("ids", JSONArray(q.mediaIds))
            .put("index", q.index)
            .put("position", q.positionMs)
            .put("shuffle", q.shuffle)
            .put("repeat", q.repeatMode)
        runCatching {
            val tmp = File(file.parentFile, "queue.json.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file)
        }
    }

    suspend fun load(): SavedQueue? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching null
            val o = JSONObject(file.readText())
            val ids = o.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } }
            if (ids.isEmpty()) null
            else SavedQueue(ids, o.optInt("index"), o.optLong("position"), o.optBoolean("shuffle"), o.optInt("repeat"))
        }.getOrNull()
    }
}

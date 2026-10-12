package app.minispot.data

import android.content.Context
import app.minispot.api.PlayedTrack
import app.minispot.api.SpotifyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Spotify only returns the last 50 plays, so every refresh is merged into a local history.
 * The more often the app is opened, the more complete the week becomes.
 */
class RecentsStore(context: Context, private val api: SpotifyApi) {
    private val file = File(context.filesDir, "recents.json")
    private val mutex = Mutex()

    suspend fun lastWeek(): List<PlayedTrack> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val fresh = try {
                api.recentlyPlayed()
            } catch (e: Exception) {
                // Offline or rate-limited: fall back to what we already have.
                if (load().isEmpty()) throw e
                emptyList()
            }
            val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            val merged = (load() + fresh)
                .distinctBy { it.playedAt }
                .filter { it.playedAt >= weekAgo }
                .sortedByDescending { it.playedAt }
            save(merged)
            merged
        }
    }

    private fun load(): List<PlayedTrack> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PlayedTrack(o.getLong("playedAt"), o.getJSONObject("track").toTrack())
        }
    }.getOrDefault(emptyList())

    private fun save(items: List<PlayedTrack>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("playedAt", it.playedAt).put("track", it.track.toJson())) }
        file.writeText(arr.toString())
    }
}

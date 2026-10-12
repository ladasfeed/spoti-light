package app.minispot.data

import android.content.Context
import app.minispot.api.SpotifyApi
import app.minispot.api.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local copy of Liked Songs (newest first). Powers the "My tracks" screen and lets search put
 * saved tracks first without extra requests. New likes are picked up by syncing the head of the
 * list; a full reload every 12 hours catches tracks removed in other apps.
 */
class LikedRepo(context: Context, private val api: SpotifyApi) {
    private val file = File(context.filesDir, "liked.json")
    private val mutex = Mutex()

    private val _tracks = MutableStateFlow<List<Track>?>(null)
    val tracks: StateFlow<List<Track>?> = _tracks

    private var savedAt = 0L
    private var lastSync = 0L

    suspend fun ensureLoaded(): List<Track> {
        _tracks.value?.let { return it }
        withContext(Dispatchers.IO) { loadCache() }
        return _tracks.value ?: sync()
    }

    /** Brings the cache up to date. Cheap when nothing changed: usually a single request. */
    suspend fun sync(force: Boolean = false): List<Track> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (_tracks.value == null) loadCache()
            val cached = _tracks.value
            val now = System.currentTimeMillis()
            if (!force && cached != null && now - lastSync < 60_000) return@withContext cached

            val result = if (cached == null || force || now - savedAt > FULL_RELOAD_MS) {
                fullReload()
            } else {
                headSync(cached)
            }
            lastSync = now
            _tracks.value = result
            saveCache(result)
            result
        }
    }

    /** Keeps the cache in step with likes toggled from the player. */
    fun onLikedChanged(uri: String, liked: Boolean) {
        val current = _tracks.value ?: return
        if (!liked) _tracks.value = current.filter { it.uri != uri } else lastSync = 0L
    }

    private suspend fun fullReload(): List<Track> {
        val all = mutableListOf<Track>()
        var offset = 0
        while (true) {
            val page = api.savedTracks(offset)
            all += page.items
            if (!page.hasMore || page.items.isEmpty()) break
            offset += page.items.size
        }
        savedAt = System.currentTimeMillis()
        return all.distinctBy { it.uri }
    }

    private suspend fun headSync(cached: List<Track>): List<Track> {
        val known = cached.take(200).map { it.uri }.toHashSet()
        val fresh = mutableListOf<Track>()
        var offset = 0
        repeat(10) {
            val page = api.savedTracks(offset)
            val newOnes = page.items.takeWhile { it.uri !in known }
            fresh += newOnes
            if (newOnes.size < page.items.size || !page.hasMore) {
                return (fresh + cached).distinctBy { it.uri }
            }
            offset += page.items.size
        }
        // Hundreds of new likes since the last sync: just reload everything.
        return fullReload()
    }

    private fun loadCache() {
        runCatching {
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            savedAt = json.getLong("savedAt")
            val arr = json.getJSONArray("tracks")
            _tracks.value = (0 until arr.length()).map { arr.getJSONObject(it).toTrack() }
        }
    }

    private fun saveCache(tracks: List<Track>) {
        val arr = JSONArray()
        tracks.forEach { arr.put(it.toJson()) }
        file.writeText(JSONObject().put("savedAt", savedAt).put("tracks", arr).toString())
    }

    private companion object {
        const val FULL_RELOAD_MS = 12L * 3600 * 1000
    }
}

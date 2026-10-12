package app.minispot.data

import android.content.Context
import app.minispot.api.Album
import app.minispot.api.SpotifyApi
import app.minispot.api.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Last releases": tracks from albums and singles released in the last 14 days by followed artists.
 * Spotify removed the new-releases endpoint, so this walks every followed artist. The result is
 * cached for a few hours to stay inside the development-mode request quota.
 */
class ReleasesRepo(context: Context, private val api: SpotifyApi) {
    private val file = File(context.filesDir, "releases.json")

    suspend fun lastReleases(force: Boolean, onProgress: (done: Int, total: Int) -> Unit): List<Track> =
        withContext(Dispatchers.IO) {
            if (!force) loadCache()?.let { return@withContext it }

            val cutoff = LocalDate.now().minusDays(14)
            val artists = api.followedArtists()
            val done = AtomicInteger(0)
            onProgress(0, artists.size)
            val limiter = Semaphore(4)

            val albums: List<Album> = coroutineScope {
                artists.map { artist ->
                    async {
                        limiter.withPermit {
                            val found = listOf("single", "album").flatMap { group ->
                                runCatching { api.artistAlbums(artist.id, group, 0, 5).items }.getOrDefault(emptyList())
                            }
                            onProgress(done.incrementAndGet(), artists.size)
                            found.filter { isOnOrAfter(it.releaseDate, cutoff) }
                        }
                    }
                }.awaitAll().flatten().distinctBy { it.id }
            }

            val tracks = coroutineScope {
                albums.map { album ->
                    async { limiter.withPermit { runCatching { api.albumTracks(album) }.getOrDefault(emptyList()) } }
                }.awaitAll().flatten()
            }
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<Track> { it.releaseDate.orEmpty() }
                        .thenBy { it.albumUri.orEmpty() }
                        .thenBy { it.albumIndex ?: 0 }
                )

            saveCache(tracks)
            tracks
        }

    private fun isOnOrAfter(releaseDate: String, cutoff: LocalDate): Boolean {
        // Only day-precision dates can be recent; "2026" or "2026-10" are old catalogue entries.
        if (releaseDate.length != 10) return false
        return runCatching { !LocalDate.parse(releaseDate).isBefore(cutoff) }.getOrDefault(false)
    }

    private fun loadCache(): List<Track>? = runCatching {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        if (System.currentTimeMillis() - json.getLong("savedAt") > CACHE_MS) return null
        val arr = json.getJSONArray("tracks")
        (0 until arr.length()).map { arr.getJSONObject(it).toTrack() }
    }.getOrNull()

    private fun saveCache(tracks: List<Track>) {
        val arr = JSONArray()
        tracks.forEach { arr.put(it.toJson()) }
        file.writeText(JSONObject().put("savedAt", System.currentTimeMillis()).put("tracks", arr).toString())
    }

    private companion object {
        const val CACHE_MS = 6L * 3600 * 1000
    }
}

package app.minispot.data

import android.content.Context
import app.minispot.api.ApiException
import app.minispot.api.Artist
import app.minispot.api.SpotifyApi
import app.minispot.api.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class ReleasesPage(
    val tracks: List<Track>,
    val checkedArtists: Int,
    val totalArtists: Int,
) {
    val hasMore get() = checkedArtists < totalArtists
}

/**
 * "Last releases": tracks released in the last 14 days by followed artists. Spotify removed the
 * new-releases endpoint and limits the artist and album endpoints hard for development-mode apps,
 * so each artist is checked with one track search narrowed by year, one artist after another with
 * pauses between requests. Results arrive in chunks (about 10 tracks per [loadMore]). Each artist's check is cached for
 * half a day so reopening the screen costs nothing.
 */
class ReleasesRepo(context: Context, private val api: SpotifyApi) {
    private val file = File(context.filesDir, "releases-v3.json")
    private val mutex = Mutex()

    private var artists: List<Artist>? = null
    private var artistsAt = 0L
    /** artistId → (checkedAt, that artist's recent tracks). */
    private val scans = HashMap<String, Pair<Long, List<Track>>>()

    private var nextArtist = 0
    private val found = LinkedHashMap<String, Track>()
    private var loaded = false
    private var lastRequestAt = 0L

    init {
        File(context.filesDir, "releases-v2.json").delete()
    }

    fun current(): ReleasesPage = ReleasesPage(sorted(), nextArtist, artists?.size ?: Int.MAX_VALUE)

    /** Starts over from the first artist. With [dropCache], everything is fetched again. */
    suspend fun restart(dropCache: Boolean) = mutex.withLock {
        nextArtist = 0
        found.clear()
        if (dropCache) {
            artists = null
            scans.clear()
            file.delete()
        }
    }

    /** Checks more artists until at least [minNewTracks] new tracks were found or none are left. */
    suspend fun loadMore(minNewTracks: Int = 10, onProgress: (ReleasesPage) -> Unit): ReleasesPage =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (!loaded) {
                    loadCache()
                    loaded = true
                }
                val all = followedArtists()
                val today = LocalDate.now()
                val cutoff = today.minusDays(14)
                val before = found.size
                try {
                    while (nextArtist < all.size && found.size - before < minNewTracks) {
                        val added = recentTracks(all[nextArtist], cutoff, today).count { found.putIfAbsent(it.uri, it) == null }
                        nextArtist++
                        if (added > 0 || nextArtist % 5 == 0) onProgress(current())
                    }
                } finally {
                    saveCache()
                }
                current()
            }
        }

    private suspend fun followedArtists(): List<Artist> {
        val cached = artists
        if (cached != null && System.currentTimeMillis() - artistsAt < ARTISTS_TTL) return cached
        return api.followedArtists().also {
            artists = it
            artistsAt = System.currentTimeMillis()
            if (nextArtist > it.size) nextArtist = 0
        }
    }

    private suspend fun recentTracks(artist: Artist, cutoff: LocalDate, today: LocalDate): List<Track> {
        scans[artist.id]?.let { (at, tracks) ->
            if (System.currentTimeMillis() - at < SCAN_TTL) return tracks.filter { isOnOrAfter(it.releaseDate, cutoff) }
        }
        // Search narrows by year only, so recent tracks are picked out by their release date.
        val years = if (cutoff.year == today.year) "${today.year}" else "${cutoff.year}-${today.year}"
        val query = "artist:\"${artist.name.replace("\"", "")}\" year:$years"
        val result = mutableListOf<Track>()
        try {
            var offset = 0
            while (offset < MAX_RESULTS_PER_ARTIST) {
                pace()
                val page = api.searchTracks(query, offset)
                result += page.items.filter { t -> t.artists.any { it.id == artist.id } && isOnOrAfter(t.releaseDate, cutoff) }
                if (!page.hasMore || page.items.size < 10) break
                offset += page.items.size
            }
        } catch (e: Exception) {
            if (e.isFatalApiError()) throw e
            // One broken artist shouldn't block the rest.
        }
        scans[artist.id] = System.currentTimeMillis() to result
        return result
    }

    /** Keeps requests spread out; bursts are what trigger Spotify's long blocks. */
    private suspend fun pace() {
        val wait = lastRequestAt + REQUEST_GAP_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    /** Newest first; a song that came out as a single and then on an album is listed once. */
    private fun sorted(): List<Track> = found.values.sortedWith(
        compareByDescending<Track> { it.releaseDate.orEmpty() }
            .thenBy { it.albumUri.orEmpty() }
            .thenBy { it.albumIndex ?: 0 }
    ).distinctBy { it.name.lowercase() + "|" + it.artists.firstOrNull()?.id }

    private fun isOnOrAfter(releaseDate: String?, cutoff: LocalDate): Boolean {
        // Only day-precision dates can be recent; "2026" or "2026-10" are old catalogue entries.
        if (releaseDate == null || releaseDate.length != 10) return false
        return runCatching { !LocalDate.parse(releaseDate).isBefore(cutoff) }.getOrDefault(false)
    }

    // --- Persistence ---

    private fun loadCache() {
        runCatching {
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            json.optJSONObject("artists")?.let { a ->
                artistsAt = a.getLong("at")
                val list = a.getJSONArray("list")
                artists = (0 until list.length()).map {
                    val o = list.getJSONObject(it)
                    Artist(o.getString("id"), o.getString("name"), o.optNullableString("imageUrl"))
                }
            }
            val scansJson = json.getJSONObject("scans")
            for (id in scansJson.keys()) {
                val o = scansJson.getJSONObject(id)
                val tracks = o.getJSONArray("tracks")
                scans[id] = o.getLong("at") to (0 until tracks.length()).map { tracks.getJSONObject(it).toTrack() }
            }
        }
    }

    private fun saveCache() {
        runCatching {
            val now = System.currentTimeMillis()
            val json = JSONObject()
            artists?.let { list ->
                json.put("artists", JSONObject().put("at", artistsAt).put("list", JSONArray().apply {
                    list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("imageUrl", it.imageUrl)) }
                }))
            }
            val scansJson = JSONObject()
            for ((id, scan) in scans) {
                if (now - scan.first > SCAN_TTL) continue
                scansJson.put(id, JSONObject().put("at", scan.first).put("tracks", JSONArray().apply { scan.second.forEach { put(it.toJson()) } }))
            }
            json.put("scans", scansJson)
            file.writeText(json.toString())
        }
    }

    private companion object {
        const val SCAN_TTL = 12L * 3600 * 1000
        const val ARTISTS_TTL = 24L * 3600 * 1000
        const val REQUEST_GAP_MS = 350L
        const val MAX_RESULTS_PER_ARTIST = 20
    }
}

/** True for errors worth stopping on (rate limits, auth) rather than skipping one artist. */
internal fun Throwable.isFatalApiError() = this is ApiException && (code == 429 || code == 401 || code == 403)

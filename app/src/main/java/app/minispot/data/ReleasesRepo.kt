package app.minispot.data

import android.content.Context
import app.minispot.api.Album
import app.minispot.api.ApiException
import app.minispot.api.Artist
import app.minispot.api.ArtistRef
import app.minispot.api.SpotifyApi
import app.minispot.api.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
 * "Last releases": tracks from albums and singles released in the last 14 days by followed artists.
 * Spotify removed the new-releases endpoint, so followed artists are checked a few at a time and
 * results arrive in chunks (about 10 tracks per [loadMore]). Each artist's check is cached for a
 * few hours so reopening the screen doesn't cost requests again.
 */
class ReleasesRepo(context: Context, private val api: SpotifyApi) {
    private val file = File(context.filesDir, "releases-v2.json")
    private val mutex = Mutex()

    private var artists: List<Artist>? = null
    private var artistsAt = 0L
    /** artistId → (checkedAt, recent releases). */
    private val scans = HashMap<String, Pair<Long, List<Album>>>()
    private val albumTracks = HashMap<String, List<Track>>()

    private var nextArtist = 0
    private val found = LinkedHashMap<String, Track>()
    private var loaded = false

    fun current(): ReleasesPage = ReleasesPage(sorted(), nextArtist, artists?.size ?: Int.MAX_VALUE)

    /** Starts over from the first artist. With [dropCache], everything is fetched again. */
    suspend fun restart(dropCache: Boolean) = mutex.withLock {
        nextArtist = 0
        found.clear()
        if (dropCache) {
            artists = null
            scans.clear()
            albumTracks.clear()
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
                val cutoff = LocalDate.now().minusDays(14)
                val limiter = Semaphore(2)
                val before = found.size
                try {
                    while (nextArtist < all.size && found.size - before < minNewTracks) {
                        val batch = all.subList(nextArtist, minOf(nextArtist + 6, all.size))
                        val releases = coroutineScope {
                            batch.map { artist -> async { limiter.withPermit { recentReleases(artist, cutoff) } } }
                                .awaitAll().flatten().distinctBy { it.id }
                        }
                        for (album in releases) {
                            for (t in tracksOf(album)) found.putIfAbsent(t.uri, t)
                        }
                        nextArtist += batch.size
                        onProgress(current())
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

    private suspend fun recentReleases(artist: Artist, cutoff: LocalDate): List<Album> {
        scans[artist.id]?.let { (at, albums) ->
            if (System.currentTimeMillis() - at < SCAN_TTL) return albums.filter { isOnOrAfter(it.releaseDate, cutoff) }
        }
        // Singles and albums are listed per group, newest first, so the first few of each are enough.
        val albums = try {
            listOf("single", "album").flatMap { group -> api.artistAlbums(artist.id, group, 0, 5).items }
                .filter { isOnOrAfter(it.releaseDate, cutoff) }
        } catch (e: Exception) {
            if (e.isFatalApiError()) throw e
            emptyList() // One broken artist shouldn't block the rest.
        }
        scans[artist.id] = System.currentTimeMillis() to albums
        return albums
    }

    private suspend fun tracksOf(album: Album): List<Track> = albumTracks[album.id] ?: try {
        api.albumTracks(album).also { albumTracks[album.id] = it }
    } catch (e: Exception) {
        if (e.isFatalApiError()) throw e
        emptyList()
    }

    private fun sorted(): List<Track> = found.values.sortedWith(
        compareByDescending<Track> { it.releaseDate.orEmpty() }
            .thenBy { it.albumUri.orEmpty() }
            .thenBy { it.albumIndex ?: 0 }
    )

    private fun isOnOrAfter(releaseDate: String, cutoff: LocalDate): Boolean {
        // Only day-precision dates can be recent; "2026" or "2026-10" are old catalogue entries.
        if (releaseDate.length != 10) return false
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
                val albums = o.getJSONArray("albums")
                scans[id] = o.getLong("at") to (0 until albums.length()).map { albums.getJSONObject(it).toAlbum() }
            }
            val tracksJson = json.getJSONObject("tracks")
            for (id in tracksJson.keys()) {
                val arr = tracksJson.getJSONArray(id)
                albumTracks[id] = (0 until arr.length()).map { arr.getJSONObject(it).toTrack() }
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
            val keepAlbums = HashSet<String>()
            for ((id, scan) in scans) {
                if (now - scan.first > SCAN_TTL) continue
                scan.second.forEach { keepAlbums += it.id }
                scansJson.put(id, JSONObject().put("at", scan.first).put("albums", JSONArray().apply { scan.second.forEach { put(it.toJson()) } }))
            }
            json.put("scans", scansJson)
            val tracksJson = JSONObject()
            for ((id, tracks) in albumTracks) {
                if (id in keepAlbums) tracksJson.put(id, JSONArray().apply { tracks.forEach { put(it.toJson()) } })
            }
            json.put("tracks", tracksJson)
            file.writeText(json.toString())
        }
    }

    private fun Album.toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type).put("releaseDate", releaseDate).put("imageUrl", imageUrl)
        .put("artists", JSONArray().apply { artists.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })

    private fun JSONObject.toAlbum(): Album {
        val arr = getJSONArray("artists")
        return Album(
            id = getString("id"),
            name = getString("name"),
            type = optString("type"),
            releaseDate = optString("releaseDate"),
            imageUrl = optNullableString("imageUrl"),
            artists = (0 until arr.length()).map { arr.getJSONObject(it).let { a -> ArtistRef(a.getString("id"), a.getString("name")) } },
        )
    }

    private companion object {
        const val SCAN_TTL = 6L * 3600 * 1000
        const val ARTISTS_TTL = 24L * 3600 * 1000
    }
}

/** True for errors worth stopping on (rate limits, auth) rather than skipping one artist. */
internal fun Throwable.isFatalApiError() = this is ApiException && (code == 429 || code == 401 || code == 403)

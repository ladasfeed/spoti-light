package app.minispot.data

import android.content.Context
import android.util.Log
import app.minispot.api.ApiException
import app.minispot.api.Artist
import app.minispot.api.SpotifyApi
import app.minispot.api.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class ReleasesState(
    val tracks: List<Track> = emptyList(),
    /** False until the saved results have been read. */
    val loaded: Boolean = false,
    val scanning: Boolean = false,
    val checkedArtists: Int = 0,
    val totalArtists: Int = 0,
    /** When the last full check of all followed artists finished, 0 if never. */
    val completedAt: Long = 0,
    val error: String? = null,
)

/**
 * "Last releases": tracks released in the last [WINDOW_DAYS] days by followed artists.
 *
 * Spotify removed the new-releases endpoint and limits development-mode apps both per 30 seconds
 * and per day, so all followed artists are checked once a day in the background while the app is
 * running: at [DAILY_AT], or as soon as the app runs after that time if the day's check hasn't
 * happened yet. Each artist costs one track search narrowed by year, sent one per second.
 * An interrupted check resumes where it stopped instead of starting over.
 */
class ReleasesRepo(context: Context, private val api: SpotifyApi) {
    private val file = File(context.filesDir, "releases-v4.json")

    private var artists: List<Artist>? = null
    private var artistsAt = 0L
    /** artistId → (checkedAt, that artist's recent tracks). */
    private val scans = ConcurrentHashMap<String, Pair<Long, List<Track>>>()
    private var completedAt = 0L
    private var lastRequestAt = 0L

    private val _state = MutableStateFlow(ReleasesState())
    val state: StateFlow<ReleasesState> = _state

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val forceNext = AtomicBoolean(false)

    init {
        // Older caches were filtered to two weeks, so they'd miss the rest of the month.
        listOf("releases-v2.json", "releases-v3.json").forEach { File(context.filesDir, it).delete() }
    }

    /** Runs the daily check for as long as the app process lives. */
    fun start(scope: CoroutineScope, loggedIn: StateFlow<Boolean>) {
        scope.launch(Dispatchers.IO) {
            loadCache()
            publish()
            while (true) {
                loggedIn.first { it }
                val now = System.currentTimeMillis()
                val force = forceNext.getAndSet(false)
                val mark = if (force) now else lastDailyMark(now)
                if (force || completedAt < mark) {
                    if (!scan(freshSince = mark)) {
                        sleep(RETRY_AFTER_FAILURE_MS)
                        continue
                    }
                }
                // Timers stop while the phone sleeps, so the clock is re-checked regularly.
                sleep(minOf(nextDailyMark(System.currentTimeMillis()) - System.currentTimeMillis(), RECHECK_MS))
            }
        }
    }

    /** Re-checks the schedule now, e.g. when the app comes back to the foreground. */
    fun kick() {
        wake.trySend(Unit)
    }

    /** Checks every followed artist again, ignoring today's results. */
    fun refreshNow() {
        if (_state.value.scanning) return
        forceNext.set(true)
        wake.trySend(Unit)
    }

    private suspend fun sleep(ms: Long) {
        withTimeoutOrNull(ms.coerceAtLeast(1000)) { wake.receive() }
    }

    /** Checks the artists not yet checked since [freshSince]. Returns false if it had to stop. */
    private suspend fun scan(freshSince: Long): Boolean {
        _state.update { it.copy(scanning = true, error = null) }
        return try {
            val all = artists?.takeIf { artistsAt >= freshSince } ?: api.followedArtists().also {
                artists = it
                artistsAt = System.currentTimeMillis()
            }
            val followed = all.mapTo(HashSet()) { it.id }
            scans.keys.retainAll(followed)
            val today = LocalDate.now()
            val cutoff = today.minusDays(WINDOW_DAYS)
            all.forEachIndexed { i, artist ->
                val previous = scans[artist.id]
                if (previous == null || previous.first < freshSince) {
                    scans[artist.id] = System.currentTimeMillis() to searchRecent(artist, cutoff, today)
                    publish(checked = i + 1, total = all.size)
                    if (i % 10 == 9) saveCache()
                }
            }
            completedAt = System.currentTimeMillis()
            publish()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Minispot", "Releases check stopped", e)
            publish(error = e.message ?: "Couldn't check new releases")
            false
        } finally {
            saveCache()
        }
    }

    private suspend fun searchRecent(artist: Artist, cutoff: LocalDate, today: LocalDate): List<Track> {
        // Search narrows by year only, so recent tracks are picked out by their release date.
        val years = if (cutoff.year == today.year) "${today.year}" else "${cutoff.year}-${today.year}"
        val query = "artist:\"${artist.name.replace("\"", "")}\" year:$years"
        val result = mutableListOf<Track>()
        try {
            var offset = 0
            while (offset < MAX_RESULTS_PER_ARTIST) {
                pace()
                val page = api.searchTracks(query, offset)
                val recent = page.items.filter { t -> t.artists.any { it.id == artist.id } && isOnOrAfter(t.releaseDate, cutoff) }
                result += recent
                // A second page only when this one is full of fresh tracks, like a new album.
                if (!page.hasMore || page.items.size < 10 || recent.isEmpty()) break
                offset += page.items.size
            }
        } catch (e: Exception) {
            if (e.isFatalApiError()) throw e
            // One broken artist shouldn't block the rest.
        }
        return result
    }

    /** Keeps requests spread out; bursts are what trigger Spotify's long blocks. */
    private suspend fun pace() {
        val wait = lastRequestAt + REQUEST_GAP_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    private fun publish(checked: Int? = null, total: Int? = null, error: String? = null) {
        val cutoff = LocalDate.now().minusDays(WINDOW_DAYS)
        val tracks = scans.values.asSequence()
            .flatMap { it.second.asSequence() }
            .filter { isOnOrAfter(it.releaseDate, cutoff) }
            .distinctBy { it.uri }
            .sortedWith(
                compareByDescending<Track> { it.releaseDate.orEmpty() }
                    .thenBy { it.albumUri.orEmpty() }
                    .thenBy { it.albumIndex ?: 0 }
            )
            // A song that came out as a single and then on an album is listed once.
            .distinctBy { it.name.lowercase() + "|" + it.artists.firstOrNull()?.id }
            .toList()
        _state.value = ReleasesState(
            tracks = tracks,
            loaded = true,
            scanning = checked != null,
            checkedArtists = checked ?: 0,
            totalArtists = total ?: 0,
            completedAt = completedAt,
            error = error,
        )
    }

    private fun isOnOrAfter(releaseDate: String?, cutoff: LocalDate): Boolean {
        // Only day-precision dates can be recent; "2026" or "2026-10" are old catalogue entries.
        if (releaseDate == null || releaseDate.length != 10) return false
        return runCatching { !LocalDate.parse(releaseDate).isBefore(cutoff) }.getOrDefault(false)
    }

    /** The latest [DAILY_AT] at or before [now]. */
    private fun lastDailyMark(now: Long): Long {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayMark = today.atTime(DAILY_AT).atZone(zone).toInstant().toEpochMilli()
        return if (now >= todayMark) todayMark else today.minusDays(1).atTime(DAILY_AT).atZone(zone).toInstant().toEpochMilli()
    }

    /** The first [DAILY_AT] after [now]. */
    private fun nextDailyMark(now: Long): Long {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayMark = today.atTime(DAILY_AT).atZone(zone).toInstant().toEpochMilli()
        return if (now < todayMark) todayMark else today.plusDays(1).atTime(DAILY_AT).atZone(zone).toInstant().toEpochMilli()
    }

    // --- Persistence ---

    private fun loadCache() {
        runCatching {
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            completedAt = json.optLong("completedAt", 0)
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

    @Synchronized
    private fun saveCache() {
        runCatching {
            val json = JSONObject().put("completedAt", completedAt)
            artists?.let { list ->
                json.put("artists", JSONObject().put("at", artistsAt).put("list", JSONArray().apply {
                    list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("imageUrl", it.imageUrl)) }
                }))
            }
            val scansJson = JSONObject()
            for ((id, scan) in scans) {
                scansJson.put(id, JSONObject().put("at", scan.first).put("tracks", JSONArray().apply { scan.second.forEach { put(it.toJson()) } }))
            }
            json.put("scans", scansJson)
            file.writeText(json.toString())
        }
    }

    private companion object {
        const val WINDOW_DAYS = 30L
        val DAILY_AT: LocalTime = LocalTime.of(9, 0)
        const val REQUEST_GAP_MS = 1000L
        const val MAX_RESULTS_PER_ARTIST = 20
        const val RECHECK_MS = 15L * 60 * 1000
        const val RETRY_AFTER_FAILURE_MS = 60L * 60 * 1000
    }
}

/** True for errors worth stopping on (rate limits, auth) rather than skipping one artist. */
internal fun Throwable.isFatalApiError() = this is ApiException && (code == 429 || code == 401 || code == 403)

package app.minispot.api

import app.minispot.auth.SpotifyAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ApiException(val code: Int, message: String) : Exception(message)

/**
 * Thin Web API client. Only endpoints still available to development-mode apps after the
 * February 2026 changes are used: search is capped at 10 results per request.
 */
class SpotifyApi(private val auth: SpotifyAuth) {
    /**
     * A long 429 blocks one group of endpoints ("artists", "search", "me"…) for hours. Requests to a
     * blocked group fail right away instead of reaching Spotify, which could extend the block.
     */
    private val blockedUntil = ConcurrentHashMap<String, Long>()
    private val blockedByQuota = ConcurrentHashMap.newKeySet<String>()

    suspend fun me(): JSONObject = get("/me")

    suspend fun searchTracks(query: String, offset: Int): Page<Track> {
        val json = get("/search", "q" to query, "type" to "track", "limit" to "10", "offset" to "$offset")
        val tracks = json.getJSONObject("tracks")
        return Page(tracks.getJSONArray("items").mapObjects(::parseTrack), tracks.optString("next").isNotNull())
    }

    suspend fun searchArtists(query: String, offset: Int): Page<Artist> {
        val json = get("/search", "q" to query, "type" to "artist", "limit" to "10", "offset" to "$offset")
        val artists = json.getJSONObject("artists")
        return Page(artists.getJSONArray("items").mapObjects(::parseArtist), artists.optString("next").isNotNull())
    }

    suspend fun recentlyPlayed(): List<PlayedTrack> {
        val json = get("/me/player/recently-played", "limit" to "50")
        return json.getJSONArray("items").mapObjects {
            PlayedTrack(Instant.parse(it.getString("played_at")).toEpochMilli(), parseTrack(it.getJSONObject("track")))
        }
    }

    /** Liked Songs, newest first. */
    suspend fun savedTracks(offset: Int, limit: Int = 50): Page<Track> {
        val json = get("/me/tracks", "limit" to "$limit", "offset" to "$offset")
        val items = json.getJSONArray("items").mapObjects { parseTrack(it.getJSONObject("track")) }
        return Page(items, json.optString("next").isNotNull())
    }

    /** Playlist name only; contents of playlists the user doesn't own are hidden from dev-mode apps. */
    suspend fun playlistName(id: String): String? =
        runCatching { get("/playlists/$id", "fields" to "name").optString("name") }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Starts [contextUri] (Liked Songs works as "spotify:user:<id>:collection") at [offsetUri].
     * Plays on the active device, or on a phone if Spotify reports none as active.
     */
    suspend fun startPlayback(contextUri: String, offsetUri: String?) {
        val body = JSONObject().put("context_uri", contextUri)
        offsetUri?.let { body.put("offset", JSONObject().put("uri", it)) }
        try {
            request("PUT", "/me/player/play", body = body)
        } catch (e: ApiException) {
            if (e.code != 404) throw e
            val devices = get("/me/player/devices").optJSONArray("devices")?.mapObjects { it }.orEmpty()
            val device = devices.firstOrNull { it.optString("type").equals("Smartphone", ignoreCase = true) }
                ?: devices.firstOrNull()
                ?: throw e
            request("PUT", "/me/player/play", "device_id" to device.getString("id"), body = body)
        }
    }

    /** What's playing now and what plays next (the context's upcoming tracks included). */
    suspend fun queue(): Pair<Track?, List<Track>> {
        val json = get("/me/player/queue")
        val current = json.optJSONObject("currently_playing")?.let { runCatching { parseTrack(it) }.getOrNull() }
        val next = json.optJSONArray("queue")?.mapObjects(::parseTrack).orEmpty()
        return current to next
    }

    suspend fun followedArtists(): List<Artist> {
        val result = mutableListOf<Artist>()
        var after: String? = null
        do {
            val params = mutableListOf("type" to "artist", "limit" to "50")
            after?.let { params += "after" to it }
            val artists = get("/me/following", *params.toTypedArray()).getJSONObject("artists")
            result += artists.getJSONArray("items").mapObjects(::parseArtist)
            after = artists.optJSONObject("cursors")?.optString("after")?.takeIf { it.isNotNull() }
        } while (after != null)
        return result
    }

    suspend fun artist(id: String): Artist = parseArtist(get("/artists/$id"))

    suspend fun artistAlbums(id: String, groups: String, offset: Int, limit: Int = 10): Page<Album> {
        val json = get(
            "/artists/$id/albums",
            "include_groups" to groups, "limit" to "$limit", "offset" to "$offset",
        )
        return Page(json.getJSONArray("items").mapObjects(::parseAlbum), json.optString("next").isNotNull())
    }

    suspend fun albumTracks(album: Album): List<Track> {
        val result = mutableListOf<Track>()
        var offset = 0
        while (true) {
            val json = get("/albums/${album.id}/tracks", "limit" to "50", "offset" to "$offset")
            val items = json.getJSONArray("items")
            for (i in 0 until items.length()) {
                val t = items.getJSONObject(i)
                result += Track(
                    id = t.getString("id"),
                    uri = t.getString("uri"),
                    name = t.getString("name"),
                    artists = parseArtistRefs(t.getJSONArray("artists")),
                    albumName = album.name,
                    albumUri = album.uri,
                    imageUrl = album.imageUrl,
                    durationMs = t.optLong("duration_ms"),
                    releaseDate = album.releaseDate,
                    albumIndex = offset + i,
                )
            }
            if (!json.optString("next").isNotNull()) break
            offset += items.length()
        }
        return result
    }

    // --- HTTP ---

    private suspend fun get(path: String, vararg params: Pair<String, String>): JSONObject =
        request("GET", path, *params)

    private suspend fun request(
        method: String,
        path: String,
        vararg params: Pair<String, String>,
        body: JSONObject? = null,
    ): JSONObject {
        val group = path.trimStart('/').substringBefore('/')
        blockedUntil[group]?.let { until ->
            val left = (until - System.currentTimeMillis()) / 1000
            if (left > 0) throw ApiException(429, rateLimitMessage(left, quota = group in blockedByQuota))
        }
        val query = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val url = "https://api.spotify.com/v1$path" + if (query.isNotEmpty()) "?$query" else ""
        var attempt = 0
        var forceRefresh = false
        while (true) {
            val token = auth.accessToken(forceRefresh)
            val (code, responseBody, retryAfter) = withContext(Dispatchers.IO) {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                Triple(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty(), conn.getHeaderField("Retry-After"))
            }
            attempt++
            when {
                code in 200..299 -> return if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
                code == 401 && attempt == 1 -> forceRefresh = true
                code == 429 -> {
                    // Short waits are retried; long ones (dev-mode quota) are reported instead of hanging.
                    val wait = retryAfter?.toLongOrNull() ?: 2L
                    val quota = "QUOTA_EXCEEDED" in responseBody
                    if (wait > 20) {
                        blockedUntil[group] = System.currentTimeMillis() + wait * 1000
                        if (quota) blockedByQuota += group else blockedByQuota -= group
                    }
                    if (wait > 20 || attempt > 3) throw ApiException(429, rateLimitMessage(wait, quota))
                    delay(wait * 1000)
                }
                code >= 500 && attempt <= 2 -> delay(1000)
                else -> throw ApiException(code, errorMessage(code, responseBody))
            }
        }
    }

    private fun rateLimitMessage(waitSeconds: Long, quota: Boolean = false): String {
        val minutes = (waitSeconds + 59) / 60
        val hours = (waitSeconds + 1799) / 3600
        val wait = when {
            waitSeconds <= 60 -> "a minute"
            minutes < 90 -> "about $minutes min"
            else -> "about $hours h"
        }
        return when {
            quota -> "Spotify's daily request quota for this app is used up, try again in $wait."
            waitSeconds > 3600 -> "Spotify paused these requests for this app, try again in $wait."
            else -> "Spotify rate limit hit, try again in $wait."
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
        return when (code) {
            403 -> "Spotify denied access (403). Make sure your account is in the app's User Management. ${msg.orEmpty()}"
            429 -> "Too many requests to Spotify, try again in a bit."
            else -> "Spotify error $code: ${msg ?: body.take(200)}"
        }
    }

    // --- Parsing ---

    private fun parseTrack(t: JSONObject): Track {
        val album = t.optJSONObject("album")
        return Track(
            id = t.optString("id"),
            uri = t.getString("uri"),
            name = t.getString("name"),
            artists = parseArtistRefs(t.getJSONArray("artists")),
            albumName = album?.optString("name").orEmpty(),
            albumUri = album?.optString("uri"),
            imageUrl = album?.optJSONArray("images")?.smallestImage(),
            durationMs = t.optLong("duration_ms"),
            releaseDate = album?.optString("release_date"),
            // Only first-disc positions match the album's track index.
            albumIndex = t.optInt("track_number").takeIf { it > 0 && t.optInt("disc_number", 1) == 1 }?.minus(1),
        )
    }

    private fun parseArtist(a: JSONObject) = Artist(
        id = a.getString("id"),
        name = a.getString("name"),
        imageUrl = a.optJSONArray("images")?.smallestImage(),
    )

    private fun parseAlbum(a: JSONObject) = Album(
        id = a.getString("id"),
        name = a.getString("name"),
        type = a.optString("album_type"),
        releaseDate = a.optString("release_date"),
        imageUrl = a.optJSONArray("images")?.smallestImage(),
        artists = parseArtistRefs(a.getJSONArray("artists")),
    )

    private fun parseArtistRefs(arr: JSONArray) = arr.mapObjects { ArtistRef(it.getString("id"), it.getString("name")) }

    /** Picks the smallest image that is still at least 160px, good enough for list rows. */
    private fun JSONArray.smallestImage(): String? {
        val images = mapObjects { it }
        if (images.isEmpty()) return null
        return (images.filter { it.optInt("width") >= 160 }.minByOrNull { it.optInt("width") } ?: images.first())
            .optString("url")
    }
}

private fun String?.isNotNull() = !isNullOrEmpty() && this != "null"

internal fun <T> JSONArray.mapObjects(f: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { i -> optJSONObject(i)?.let { runCatching { f(it) }.getOrNull() } }

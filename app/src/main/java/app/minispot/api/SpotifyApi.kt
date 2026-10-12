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

class ApiException(val code: Int, message: String) : Exception(message)

/**
 * Thin Web API client. Only endpoints still available to development-mode apps after the
 * February 2026 changes are used: search is capped at 10 results per request.
 */
class SpotifyApi(private val auth: SpotifyAuth) {

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

    private suspend fun get(path: String, vararg params: Pair<String, String>): JSONObject {
        val query = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val url = "https://api.spotify.com/v1$path" + if (query.isNotEmpty()) "?$query" else ""
        var attempt = 0
        var forceRefresh = false
        while (true) {
            val token = auth.accessToken(forceRefresh)
            val (code, body, retryAfter) = withContext(Dispatchers.IO) {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                Triple(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty(), conn.getHeaderField("Retry-After"))
            }
            attempt++
            when {
                code in 200..299 -> return if (body.isBlank()) JSONObject() else JSONObject(body)
                code == 401 && attempt == 1 -> forceRefresh = true
                code == 429 && attempt <= 3 -> delay(((retryAfter?.toLongOrNull() ?: 2L).coerceAtMost(15L)) * 1000)
                code >= 500 && attempt <= 2 -> delay(1000)
                else -> throw ApiException(code, errorMessage(code, body))
            }
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
        return when (code) {
            403 -> "Spotify отказал в доступе (403). Проверь, что аккаунт добавлен в User Management приложения. $msg"
            429 -> "Слишком много запросов к Spotify, попробуй чуть позже."
            else -> "Ошибка Spotify $code: ${msg ?: body.take(200)}"
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

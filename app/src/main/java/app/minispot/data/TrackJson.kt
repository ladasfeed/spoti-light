package app.minispot.data

import app.minispot.api.ArtistRef
import app.minispot.api.Track
import org.json.JSONArray
import org.json.JSONObject

internal fun Track.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("uri", uri)
    .put("name", name)
    .put("artists", JSONArray().apply { artists.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
    .put("albumName", albumName)
    .put("albumUri", albumUri)
    .put("imageUrl", imageUrl)
    .put("durationMs", durationMs)
    .put("releaseDate", releaseDate)
    .put("albumIndex", albumIndex ?: -1)

internal fun JSONObject.toTrack(): Track {
    val artistsJson = getJSONArray("artists")
    return Track(
        id = getString("id"),
        uri = getString("uri"),
        name = getString("name"),
        artists = (0 until artistsJson.length()).map {
            val a = artistsJson.getJSONObject(it)
            ArtistRef(a.getString("id"), a.getString("name"))
        },
        albumName = optString("albumName"),
        albumUri = optNullableString("albumUri"),
        imageUrl = optNullableString("imageUrl"),
        durationMs = optLong("durationMs"),
        releaseDate = optNullableString("releaseDate"),
        albumIndex = optInt("albumIndex", -1).takeIf { it >= 0 },
    )
}

internal fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

package app.minispot.api

data class ArtistRef(val id: String, val name: String) {
    val uri get() = "spotify:artist:$id"
}

data class Track(
    val id: String,
    val uri: String,
    val name: String,
    val artists: List<ArtistRef>,
    val albumName: String,
    val albumUri: String?,
    val imageUrl: String?,
    val durationMs: Long,
    val releaseDate: String? = null,
    /** Position inside its album, used to start the album at this track. */
    val albumIndex: Int? = null,
) {
    val artistLine get() = artists.joinToString(", ") { it.name }
}

data class Artist(val id: String, val name: String, val imageUrl: String?) {
    val uri get() = "spotify:artist:$id"
}

data class Album(
    val id: String,
    val name: String,
    val type: String,
    val releaseDate: String,
    val imageUrl: String?,
    val artists: List<ArtistRef>,
) {
    val uri get() = "spotify:album:$id"
}

data class Page<T>(val items: List<T>, val hasMore: Boolean)

data class PlayedTrack(val playedAt: Long, val track: Track)

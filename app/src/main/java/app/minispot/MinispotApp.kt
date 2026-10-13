package app.minispot

import android.app.Application
import android.util.Log
import app.minispot.api.SpotifyApi
import app.minispot.api.Track
import app.minispot.auth.SpotifyAuth
import app.minispot.data.LikedRepo
import app.minispot.data.Prefs
import app.minispot.data.RecentsStore
import app.minispot.data.ReleasesRepo
import app.minispot.player.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container; the app is small enough not to need DI. */
class MinispotApp : Application() {
    lateinit var prefs: Prefs
    lateinit var auth: SpotifyAuth
    lateinit var api: SpotifyApi
    lateinit var player: RemotePlayer
    lateinit var recents: RecentsStore
    lateinit var releases: ReleasesRepo
    lateinit var liked: LikedRepo
    /** Work that outlives screens, like the daily new-releases check. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        auth = SpotifyAuth(prefs)
        api = SpotifyApi(auth)
        player = RemotePlayer(this, prefs)
        recents = RecentsStore(this, api)
        releases = ReleasesRepo(this, api)
        liked = LikedRepo(this, api)
        player.onLikedChanged = { uri, isLiked -> liked.onLikedChanged(uri, isLiked) }
        releases.start(appScope, auth.loggedIn)
    }

    suspend fun userId(): String = prefs.userId ?: api.me().getString("id").also { prefs.userId = it }

    /** Liked Songs as a playable context for App Remote. */
    suspend fun likedSongsUri(): String = player.likedContextUri ?: "spotify:user:${userId()}:collection"

    /**
     * Starts Liked Songs at [track] so the following tracks come from Liked Songs too. The Web API
     * accepts Liked Songs plus a starting track; App Remote has no documented Liked Songs URI and is
     * only the backup. Each step is checked against what actually plays; the track alone is the last resort.
     */
    suspend fun playLikedAt(track: Track, index: Int) {
        player.setShuffleNow(false)
        val viaWebApi = runCatching { api.startPlayback("spotify:user:${userId()}:collection", track.uri) }
            .onFailure { Log.w("Minispot", "Web API play failed", it) }
            .isSuccess
        if (viaWebApi && player.awaitTrack(track.uri, track.name, 4000)) return
        val likedUri = runCatching { likedSongsUri() }.getOrNull()
        if (likedUri != null && player.trySkipToIndex(likedUri, index) && player.awaitTrack(track.uri, track.name, 3000)) return
        player.play(track.uri)
    }

    companion object {
        lateinit var instance: MinispotApp
            private set
    }
}

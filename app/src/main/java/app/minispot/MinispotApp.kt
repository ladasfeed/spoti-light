package app.minispot

import android.app.Application
import app.minispot.api.SpotifyApi
import app.minispot.auth.SpotifyAuth
import app.minispot.data.LikedRepo
import app.minispot.data.Prefs
import app.minispot.data.RecentsStore
import app.minispot.data.ReleasesRepo
import app.minispot.player.RemotePlayer

/** Manual dependency container; the app is small enough not to need DI. */
class MinispotApp : Application() {
    lateinit var prefs: Prefs
    lateinit var auth: SpotifyAuth
    lateinit var api: SpotifyApi
    lateinit var player: RemotePlayer
    lateinit var recents: RecentsStore
    lateinit var releases: ReleasesRepo
    lateinit var liked: LikedRepo

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
    }

    /** Liked Songs as a playable context for App Remote. */
    suspend fun likedSongsUri(): String {
        val id = prefs.userId ?: api.me().getString("id").also { prefs.userId = it }
        return "spotify:user:$id:collection"
    }

    companion object {
        lateinit var instance: MinispotApp
            private set
    }
}

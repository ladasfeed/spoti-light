package app.minispot.player

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import app.minispot.api.ArtistRef
import app.minispot.auth.REDIRECT_URI
import app.minispot.data.Prefs
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.ContentApi
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.ListItem
import com.spotify.protocol.types.PlayerContext
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class NowPlaying(
    val trackUri: String,
    val name: String,
    val artists: List<ArtistRef>,
    val albumName: String,
    val durationMs: Long,
    val positionMs: Long,
    /** elapsedRealtime when positionMs was reported, so the UI can tick the slider locally. */
    val positionAt: Long,
    val isPaused: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrev: Boolean,
    val canSeek: Boolean,
) {
    fun currentPosition(now: Long = SystemClock.elapsedRealtime()): Long =
        if (isPaused) positionMs else (positionMs + (now - positionAt)).coerceAtMost(durationMs)
}

sealed interface RemoteStatus {
    data object Disconnected : RemoteStatus
    data object Connecting : RemoteStatus
    data object Connected : RemoteStatus
    data object SpotifyNotInstalled : RemoteStatus
    data class Failed(val message: String) : RemoteStatus
}

/**
 * Wraps the Spotify App Remote SDK. The official Spotify app does the actual playback;
 * this class only sends commands and mirrors its player state.
 */
class RemotePlayer(private val appContext: Context, private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var remote: SpotifyAppRemote? = null
    private var stateSub: Subscription<PlayerState>? = null
    private var contextSub: Subscription<PlayerContext>? = null
    private val pending = mutableListOf<(SpotifyAppRemote) -> Unit>()

    private val _status = MutableStateFlow<RemoteStatus>(RemoteStatus.Disconnected)
    val status: StateFlow<RemoteStatus> = _status

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val _cover = MutableStateFlow<Bitmap?>(null)
    val cover: StateFlow<Bitmap?> = _cover

    /** Whether the current track is in Liked Songs; null while unknown. */
    private val _liked = MutableStateFlow<Boolean?>(null)
    val liked: StateFlow<Boolean?> = _liked

    /** What is playing as a whole (playlist, album…), with the title the Spotify app shows. */
    private val _context = MutableStateFlow<PlayerContext?>(null)
    val context: StateFlow<PlayerContext?> = _context

    /** Command failures, shown to the user as a short message instead of failing silently. */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors

    private fun report(action: String): (Throwable) -> Unit = { e ->
        Log.w("Minispot", "$action failed", e)
        _errors.tryEmit("$action failed: ${e.message ?: e.javaClass.simpleName}")
    }

    /** Called after a like/unlike from the player succeeds, so local caches can follow. */
    var onLikedChanged: ((uri: String, liked: Boolean) -> Unit)? = null

    private var lastActivity: WeakReference<Context>? = null
    private var lastTrackUri: String? = null
    private var lastImageUri: String? = null

    fun connect(activityContext: Context) {
        if (activityContext !== appContext) lastActivity = WeakReference(activityContext)
        if (remote?.isConnected == true || _status.value == RemoteStatus.Connecting) return
        if (!SpotifyAppRemote.isSpotifyInstalled(appContext)) {
            _status.value = RemoteStatus.SpotifyNotInstalled
            return
        }
        if (prefs.clientId.isBlank()) return
        _status.value = RemoteStatus.Connecting
        val params = ConnectionParams.Builder(prefs.clientId)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(activityContext, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                remote = appRemote
                _status.value = RemoteStatus.Connected
                subscribe(appRemote)
                val queued = pending.toList()
                pending.clear()
                queued.forEach { runCatching { it(appRemote) } }
            }

            override fun onFailure(error: Throwable) {
                Log.w("Minispot", "App Remote connect failed", error)
                remote = null
                pending.clear()
                _status.value = RemoteStatus.Failed(error.message ?: error.javaClass.simpleName)
            }
        })
    }

    fun disconnect() {
        stateSub?.cancel()
        stateSub = null
        contextSub?.cancel()
        contextSub = null
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        _status.value = RemoteStatus.Disconnected
    }

    private fun subscribe(appRemote: SpotifyAppRemote) {
        contextSub?.cancel()
        contextSub = appRemote.playerApi.subscribeToPlayerContext().setEventCallback { _context.value = it }
        stateSub?.cancel()
        stateSub = appRemote.playerApi.subscribeToPlayerState().setEventCallback { state ->
            val track = state.track
            if (track == null) {
                _nowPlaying.value = null
                return@setEventCallback
            }
            val artists = (track.artists ?: listOfNotNull(track.artist)).map {
                ArtistRef(it.uri?.substringAfterLast(':').orEmpty(), it.name.orEmpty())
            }
            _nowPlaying.value = NowPlaying(
                trackUri = track.uri,
                name = track.name.orEmpty(),
                artists = artists,
                albumName = track.album?.name.orEmpty(),
                durationMs = track.duration,
                positionMs = state.playbackPosition,
                positionAt = SystemClock.elapsedRealtime(),
                isPaused = state.isPaused,
                canSkipNext = state.playbackRestrictions?.canSkipNext ?: true,
                canSkipPrev = state.playbackRestrictions?.canSkipPrev ?: true,
                canSeek = state.playbackRestrictions?.canSeek ?: true,
            )
            if (track.uri != lastTrackUri) {
                lastTrackUri = track.uri
                refreshLiked(track.uri)
            }
            val imageUri = track.imageUri
            if (imageUri != null && imageUri.raw != lastImageUri) {
                lastImageUri = imageUri.raw
                appRemote.imagesApi.getImage(imageUri, Image.Dimension.LARGE)
                    .setResultCallback { _cover.value = it }
            }
        }
    }

    private fun refreshLiked(uri: String) {
        _liked.value = null
        withRemote { r ->
            r.userApi.getLibraryState(uri).setResultCallback { _liked.value = it.isAdded }
        }
    }

    /** Runs the command now, or connects first and runs it once connected. */
    private fun withRemote(block: (SpotifyAppRemote) -> Unit) {
        val r = remote
        if (r != null && r.isConnected) {
            block(r)
        } else {
            pending += block
            connect(lastActivity?.get() ?: appContext)
        }
    }

    // --- Commands ---

    fun play(uri: String, shuffle: Boolean? = null) = withRemote { r ->
        val start = { r.playerApi.play(uri).setErrorCallback(report("Play")) }
        if (shuffle != null) {
            r.playerApi.setShuffle(shuffle)
                .setResultCallback { start() }
                .setErrorCallback { start() }
        } else {
            start()
        }
    }

    /**
     * Starts a context (album/playlist) at a given position, so playback continues inside it.
     * Spotify sometimes accepts the command and plays nothing, so the result is checked against
     * what the app actually reports, and the track itself is played if it didn't start.
     */
    fun playAt(contextUri: String, index: Int, fallbackTrackUri: String? = null, expectedName: String? = null) {
        scope.launch {
            setShuffleNow(false)
            val started = trySkipToIndex(contextUri, index) &&
                (fallbackTrackUri == null || awaitTrack(fallbackTrackUri, expectedName, 3000))
            when {
                started -> Unit
                fallbackTrackUri != null -> play(fallbackTrackUri)
                else -> _errors.tryEmit("Play failed")
            }
        }
    }

    suspend fun setShuffleNow(on: Boolean) {
        runCatching { awaitRemote().playerApi.setShuffle(on).awaitCall() }
    }

    suspend fun trySkipToIndex(contextUri: String, index: Int): Boolean =
        runCatching { awaitRemote().playerApi.skipToIndex(contextUri, index).awaitCall() }
            .onFailure { Log.w("Minispot", "skipToIndex($contextUri, $index) failed", it) }
            .isSuccess

    /** Waits until the Spotify app reports this track as playing (not paused). */
    suspend fun awaitTrack(uri: String, name: String?, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            nowPlaying.first { np -> np != null && !np.isPaused && (np.trackUri == uri || (name != null && np.name == name)) }
        } != null

    /** The Liked Songs URI that Spotify accepted last time, reused to start at a given track. */
    var likedContextUri: String? = null
        private set

    /**
     * Plays Liked Songs. Spotify has no documented URI for it, so known forms are tried in turn,
     * then the Liked Songs entry from the Spotify app's own library browser.
     */
    fun playLiked(userId: String, shuffle: Boolean) = withRemote { r ->
        val candidates = listOfNotNull(likedContextUri, "spotify:user:$userId:collection", "spotify:collection:tracks").distinct()
        fun viaLibrary(lastError: Throwable) {
            scope.launch {
                val item = runCatching { findLikedSongsItem() }.getOrNull()
                if (item != null) {
                    likedContextUri = item.uri
                    r.contentApi.playContentItem(item).setErrorCallback(report("Play Liked Songs"))
                } else {
                    report("Play Liked Songs")(lastError)
                }
            }
        }
        fun attempt(i: Int, lastError: Throwable?) {
            if (i >= candidates.size) return viaLibrary(lastError ?: IllegalStateException("no URI worked"))
            r.playerApi.play(candidates[i])
                .setResultCallback { likedContextUri = candidates[i] }
                .setErrorCallback { attempt(i + 1, it) }
        }
        r.playerApi.setShuffle(shuffle)
            .setResultCallback { attempt(0, null) }
            .setErrorCallback { attempt(0, null) }
    }

    private suspend fun findLikedSongsItem(): ListItem? {
        fun ListItem.isLiked() = uri.orEmpty().endsWith(":collection") || uri.orEmpty().contains("collection:tracks")
        val top = recommendedItems()
        top.firstOrNull { it.isLiked() }?.let { return it }
        for (section in top.filter { it.hasChildren }.take(10)) {
            runCatching { childrenOf(section) }.getOrDefault(emptyList()).firstOrNull { it.isLiked() }?.let { return it }
        }
        return null
    }

    /** Skips forward [count] tracks, one after another, staying in the current context. */
    fun skipAhead(count: Int) = withRemote { r ->
        fun step(left: Int) {
            if (left <= 0) return
            r.playerApi.skipNext()
                .setResultCallback { step(left - 1) }
                .setErrorCallback(report("Skip"))
        }
        step(count)
    }

    fun togglePause() = withRemote { r ->
        if (_nowPlaying.value?.isPaused != false) r.playerApi.resume() else r.playerApi.pause()
    }

    fun next() = withRemote { it.playerApi.skipNext() }
    fun previous() = withRemote { it.playerApi.skipPrevious() }
    fun seekTo(ms: Long) = withRemote { it.playerApi.seekTo(ms) }

    fun toggleLiked() {
        val uri = _nowPlaying.value?.trackUri ?: return
        val wasLiked = _liked.value ?: return
        _liked.value = !wasLiked
        withRemote { r ->
            val call = if (wasLiked) r.userApi.removeFromLibrary(uri) else r.userApi.addToLibrary(uri)
            call.setResultCallback { onLikedChanged?.invoke(uri, !wasLiked) }
            call.setErrorCallback { _liked.value = wasLiked }
        }
    }

    // --- Content (served by the Spotify app itself, not the Web API) ---

    suspend fun recommendedItems(): List<ListItem> =
        awaitRemote().contentApi.getRecommendedContentItems(ContentApi.ContentType.DEFAULT).awaitCall().items.toList()

    suspend fun childrenOf(item: ListItem, limit: Int = 50): List<ListItem> =
        awaitRemote().contentApi.getChildrenOfItem(item, limit, 0).awaitCall().items.toList()

    fun playItem(item: ListItem) = withRemote { it.contentApi.playContentItem(item).setErrorCallback(report("Play")) }

    private suspend fun awaitRemote(): SpotifyAppRemote = withTimeout(20_000) {
        suspendCancellableCoroutine { cont ->
            scope.launch {
                withRemote { if (cont.isActive) cont.resume(it) }
                if (_status.value is RemoteStatus.Failed || _status.value == RemoteStatus.SpotifyNotInstalled) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Can't reach the Spotify app"))
                }
            }
        }
    }
}

suspend fun <T> CallResult<T>.awaitCall(): T = suspendCancellableCoroutine { cont ->
    setResultCallback { if (cont.isActive) cont.resume(it) }
    setErrorCallback { if (cont.isActive) cont.resumeWithException(it) }
}

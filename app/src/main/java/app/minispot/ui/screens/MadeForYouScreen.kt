package app.minispot.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minispot.MinispotApp
import app.minispot.ui.components.EmptyText
import app.minispot.ui.components.ErrorBox
import app.minispot.ui.components.Loading
import app.minispot.ui.components.SectionHeader
import app.minispot.ui.components.TopBar
import com.spotify.protocol.types.ListItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class DaylistState(
    val loading: Boolean = true,
    /** Name of the current daylist, e.g. "early morning darkwave synth". */
    val title: String? = null,
    val contextUri: String? = null,
    /** The daylist as a content item, when found through the Spotify app. */
    val item: ListItem? = null,
    val tracks: List<ListItem> = emptyList(),
    /** Fallback when no daylist was found: what the Spotify app offers as "made for you". */
    val others: List<ListItem> = emptyList(),
    val error: String? = null,
)

/**
 * Opens and immediately plays the daylist. Spotify's own playlists are hidden from the Web API
 * for new apps, so the daylist (with its name and tracks) comes from App Remote's content
 * browser, which the Spotify app itself serves. A link pasted in Settings is the fallback.
 */
class MadeForYouViewModel : ViewModel() {
    private val app = MinispotApp.instance
    private val player = app.player
    val state = MutableStateFlow(DaylistState())
    private var autoplayed = false

    init { load() }

    fun load() {
        viewModelScope.launch {
            state.value = DaylistState(loading = true)
            state.value = try {
                findDaylist() ?: fromSavedLink() ?: notFound()
            } catch (e: Exception) {
                fromSavedLink() ?: DaylistState(loading = false, error = e.message)
            }
        }
    }

    fun play() {
        val s = state.value
        when {
            s.item != null -> player.playItem(s.item)
            s.contextUri != null -> player.play(s.contextUri)
        }
    }

    fun playTrack(index: Int, track: ListItem) {
        val uri = state.value.contextUri
        if (uri != null && track.uri.orEmpty().startsWith("spotify:track:")) player.playAt(uri, index)
        else player.playItem(track)
    }

    private fun autoplay() {
        if (autoplayed) return
        autoplayed = true
        play()
    }

    private suspend fun findDaylist(): DaylistState? {
        val top = player.recommendedItems()
        var daylist = top.firstOrNull { it.isDaylist() }
        if (daylist == null) {
            for (section in top.filter { it.hasChildren }.take(10)) {
                daylist = runCatching { player.childrenOf(section) }.getOrDefault(emptyList()).firstOrNull { it.isDaylist() }
                if (daylist != null) break
            }
        }
        daylist ?: return null
        val tracks = if (daylist.hasChildren) runCatching { player.childrenOf(daylist, 100) }.getOrDefault(emptyList()) else emptyList()
        val result = DaylistState(
            loading = false,
            title = daylistName(daylist.title, daylist.subtitle),
            contextUri = daylist.uri,
            item = daylist,
            tracks = tracks,
        )
        state.value = result
        autoplay()
        return result
    }

    private suspend fun fromSavedLink(): DaylistState? {
        val uri = playlistUriFromLink(app.prefs.daylistLink) ?: return null
        val id = uri.substringAfterLast(':')
        val result = DaylistState(
            loading = false,
            title = daylistName(app.api.playlistName(id), null),
            contextUri = uri,
            tracks = runCatching { player.childrenOf(ListItem(uri, uri, null, "", "", false, true), 100) }
                .getOrDefault(emptyList()),
        )
        state.value = result
        autoplay()
        return result
    }

    private suspend fun notFound(): DaylistState {
        // Nothing to play: show what the Spotify app offers in its "made for you" shelf instead.
        val others = runCatching {
            val top = player.recommendedItems()
            top.filter { it.title.orEmpty().contains("made for", ignoreCase = true) && it.hasChildren }
                .flatMap { player.childrenOf(it) }
        }.getOrDefault(emptyList())
        return DaylistState(loading = false, others = others)
    }

    private fun ListItem.isDaylist() =
        title.orEmpty().contains("daylist", ignoreCase = true) || subtitle.orEmpty().contains("daylist", ignoreCase = true)
}

/** "daylist • early morning darkwave synth" → "early morning darkwave synth". */
fun daylistName(title: String?, subtitle: String?): String? {
    fun clean(s: String?) = s.orEmpty()
        .replace(Regex("^\\s*daylist\\s*[•·:\\-–—|]?\\s*", RegexOption.IGNORE_CASE), "")
        .trim()
    return clean(title).ifEmpty { clean(subtitle) }.ifEmpty { null }
}

/** Turns "https://open.spotify.com/playlist/ID?si=..." or "spotify:playlist:ID" into a URI. */
fun playlistUriFromLink(link: String): String? {
    val trimmed = link.trim()
    if (trimmed.startsWith("spotify:playlist:")) return trimmed
    val id = Regex("playlist/([A-Za-z0-9]+)").find(trimmed)?.groupValues?.get(1) ?: return null
    return "spotify:playlist:$id"
}

@Composable
fun MadeForYouScreen(onBack: () -> Unit, vm: MadeForYouViewModel = viewModel()) {
    val app = MinispotApp.instance
    val state by vm.state.collectAsState()
    val nowPlaying by app.player.nowPlaying.collectAsState()
    val playerContext by app.player.context.collectAsState()

    // The Spotify app reports the playing context's title too; prefer it once the daylist is playing.
    val liveTitle = playerContext?.takeIf { it.uri == state.contextUri }?.let { daylistName(it.title, it.subtitle) }
    val title = liveTitle ?: state.title

    Column(Modifier.fillMaxSize()) {
        TopBar("", onBack) {
            if (state.contextUri != null) {
                IconButton(onClick = vm::play) { Icon(Icons.Filled.PlayArrow, "Play daylist") }
            }
            IconButton(onClick = vm::load) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        when {
            state.loading -> Loading("Finding your daylist…")
            state.contextUri != null -> {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("daylist", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(
                        title ?: "daylist",
                        fontSize = 32.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (state.tracks.isEmpty()) {
                    EmptyText("Spotify didn't share the track list for this playlist, but it's playing.")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(state.tracks, key = { i, it -> "$i-${it.id}" }) { i, item ->
                            ContentRow(item, isCurrent = nowPlaying?.trackUri == item.uri) { vm.playTrack(i, item) }
                        }
                    }
                }
            }
            state.error != null -> ErrorBox(state.error!!, vm::load)
            else -> {
                EmptyText(
                    "Spotify didn't return your daylist. Open it in the Spotify app, tap Share → Copy link, and paste the link in Settings."
                )
                if (state.others.isNotEmpty()) {
                    SectionHeader("More for you")
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(state.others, key = { i, it -> "$i-${it.id}" }) { _, item ->
                            ContentRow(item, isCurrent = false) { app.player.playItem(item) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentRow(item: ListItem, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.title.orEmpty(),
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp,
            )
            if (!item.subtitle.isNullOrBlank()) {
                Text(
                    item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                )
            }
        }
    }
}

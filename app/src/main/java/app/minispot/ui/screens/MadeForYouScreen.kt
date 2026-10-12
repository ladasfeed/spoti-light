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
import androidx.compose.material3.Button
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
    val daylist: ListItem? = null,
    val tracks: List<ListItem> = emptyList(),
    /** Fallback when no daylist was found: what the Spotify app offers as "made for you". */
    val others: List<ListItem> = emptyList(),
    val error: String? = null,
)

/**
 * Spotify's own playlists (daylist included) are hidden from the Web API for new apps, so the
 * daylist is looked up through App Remote's content browser, which the Spotify app itself serves.
 * A daylist link pasted in settings is used as a fallback.
 */
class MadeForYouViewModel : ViewModel() {
    private val player = MinispotApp.instance.player
    val state = MutableStateFlow(DaylistState())

    init { load() }

    fun load() {
        viewModelScope.launch {
            state.value = DaylistState(loading = true)
            state.value = try {
                val top = player.recommendedItems()
                var daylist = top.firstOrNull { it.isDaylist() }
                val others = mutableListOf<ListItem>()
                if (daylist == null) {
                    for (section in top.filter { it.hasChildren }.take(10)) {
                        val children = runCatching { player.childrenOf(section) }.getOrDefault(emptyList())
                        daylist = children.firstOrNull { it.isDaylist() }
                        if (daylist != null) break
                        if (section.title.orEmpty().contains("made for", ignoreCase = true)) others += children
                    }
                }
                val tracks = daylist?.takeIf { it.hasChildren }
                    ?.let { runCatching { player.childrenOf(it, 100) }.getOrDefault(emptyList()) }
                    .orEmpty()
                DaylistState(loading = false, daylist = daylist, tracks = tracks, others = others)
            } catch (e: Exception) {
                DaylistState(loading = false, error = e.message)
            }
        }
    }

    private fun ListItem.isDaylist() =
        title.orEmpty().contains("daylist", ignoreCase = true) || subtitle.orEmpty().contains("daylist", ignoreCase = true)
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
    val savedLink = playlistUriFromLink(app.prefs.daylistLink)

    Column(Modifier.fillMaxSize()) {
        TopBar("Made for you", onBack) {
            IconButton(onClick = vm::load) { Icon(Icons.Filled.Refresh, "Обновить") }
        }
        val daylist = state.daylist
        when {
            state.loading -> Loading("Ищу твой daylist…")
            daylist != null -> {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(daylist.title.orEmpty(), fontSize = 28.sp, fontWeight = FontWeight.Black, lineHeight = 32.sp)
                    if (!daylist.subtitle.isNullOrBlank()) {
                        Text(daylist.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { app.player.playItem(daylist) }) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Text("Play daylist")
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(state.tracks, key = { i, it -> "$i-${it.id}" }) { i, item ->
                        ContentRow(item, isCurrent = nowPlaying?.trackUri == item.uri) {
                            if (item.uri.orEmpty().startsWith("spotify:track:")) app.player.playAt(daylist.uri, i)
                            else app.player.playItem(item)
                        }
                    }
                }
            }
            savedLink != null -> Column(Modifier.padding(16.dp)) {
                Text("Daylist по ссылке из настроек", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { app.player.play(savedLink) }) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Text("Play daylist")
                }
            }
            else -> {
                if (state.error != null) {
                    ErrorBox(state.error!!, vm::load)
                } else {
                    EmptyText(
                        "Spotify не отдал daylist. Открой daylist в приложении Spotify, нажми «Поделиться → Копировать ссылку» и вставь её в настройках Minispot."
                    )
                }
                if (state.others.isNotEmpty()) {
                    SectionHeader("Ещё для тебя")
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

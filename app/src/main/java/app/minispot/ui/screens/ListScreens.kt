package app.minispot.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minispot.MinispotApp
import app.minispot.api.PlayedTrack
import app.minispot.api.Track
import app.minispot.ui.components.EmptyText
import app.minispot.ui.components.ErrorBox
import app.minispot.ui.components.Loading
import app.minispot.ui.components.SectionHeader
import app.minispot.ui.components.TopBar
import app.minispot.ui.components.TrackRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minispot.data.ReleasesState
import kotlinx.coroutines.Job
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.itemsIndexed
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ListState<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val progress: String? = null,
)

// ---------- Recents ----------

class RecentsViewModel : ViewModel() {
    val state = MutableStateFlow(ListState<PlayedTrack>())

    init { load() }

    fun load() {
        viewModelScope.launch {
            state.value = state.value.copy(loading = true, error = null)
            state.value = try {
                ListState(MinispotApp.instance.recents.lastWeek(), loading = false)
            } catch (e: Exception) {
                ListState(loading = false, error = e.message)
            }
        }
    }
}

@Composable
fun RecentsScreen(onBack: () -> Unit, vm: RecentsViewModel = viewModel()) {
    val app = MinispotApp.instance
    val state by vm.state.collectAsState()
    val nowPlaying by app.player.nowPlaying.collectAsState()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val dayFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    Column(Modifier.fillMaxSize()) {
        TopBar("Recents", onBack) {
            IconButton(onClick = vm::load) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        when {
            state.loading && state.items.isEmpty() -> Loading()
            state.error != null -> ErrorBox(state.error!!, vm::load)
            state.items.isEmpty() -> EmptyText("Nothing played this week")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                val byDay = state.items.groupBy { Instant.ofEpochMilli(it.playedAt).atZone(zone).toLocalDate() }
                byDay.forEach { (day, plays) ->
                    item(key = "day-$day") {
                        SectionHeader(
                            when (day) {
                                today -> "Today"
                                today.minusDays(1) -> "Yesterday"
                                else -> day.format(dayFormat).replaceFirstChar { it.uppercase() }
                            }
                        )
                    }
                    items(plays, key = { "p-${it.playedAt}" }) { p ->
                        TrackRow(
                            p.track,
                            isCurrent = nowPlaying?.trackUri == p.track.uri,
                            onClick = { app.player.play(p.track.uri) },
                            trailing = Instant.ofEpochMilli(p.playedAt).atZone(zone).format(timeFormat),
                        )
                    }
                }
            }
        }
    }
}

// ---------- Last releases ----------

/** Filled by the daily background check (see ReleasesRepo); the screen only shows its progress. */
@Composable
fun ReleasesScreen(onBack: () -> Unit) {
    val app = MinispotApp.instance
    val state by app.releases.state.collectAsState()
    val nowPlaying by app.player.nowPlaying.collectAsState()
    val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    Column(Modifier.fillMaxSize()) {
        TopBar("Last releases", onBack) {
            IconButton(onClick = app.releases::refreshNow, enabled = !state.scanning) {
                Icon(Icons.Filled.Refresh, "Check again")
            }
        }
        ReleasesStatus(state, onRetry = app.releases::refreshNow)
        when {
            !state.loaded -> Loading()
            state.tracks.isEmpty() && !state.scanning && state.error == null && state.completedAt > 0 ->
                EmptyText("Nothing new from your artists in the last month")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.tracks, key = { it.uri }) { t ->
                    TrackRow(
                        t,
                        isCurrent = nowPlaying?.trackUri == t.uri,
                        onClick = {
                            val album = t.albumUri
                            val index = t.albumIndex
                            if (album != null && index != null) app.player.playAt(album, index, fallbackTrackUri = t.uri, expectedName = t.name)
                            else app.player.play(t.uri)
                        },
                        trailing = t.releaseDate?.let { runCatching { LocalDate.parse(it).format(dateFormat) }.getOrNull() },
                    )
                }
            }
        }
    }
}

/** Progress of the running check, its error, or when the list was last updated; always above the list. */
@Composable
private fun ReleasesStatus(state: ReleasesState, onRetry: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
        when {
            state.scanning -> {
                if (state.totalArtists > 0) {
                    LinearProgressIndicator(
                        progress = { state.checkedArtists.toFloat() / state.totalArtists },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(
                    if (state.totalArtists > 0) "Checking artists: ${state.checkedArtists} of ${state.totalArtists}"
                    else "Loading followed artists…",
                    modifier = Modifier.padding(top = 6.dp),
                    color = muted,
                    fontSize = 12.sp,
                )
            }
            state.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.error, modifier = Modifier.weight(1f), color = muted, fontSize = 12.sp)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            state.completedAt > 0 -> Text("Updated ${formatUpdated(state.completedAt)}", color = muted, fontSize = 12.sp)
        }
    }
}

private fun formatUpdated(millis: Long): String {
    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val clock = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
    return when (time.toLocalDate()) {
        LocalDate.now() -> "today at $clock"
        LocalDate.now().minusDays(1) -> "yesterday at $clock"
        else -> time.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) + " at $clock"
    }
}

// ---------- My tracks (Liked Songs) ----------

class MyTracksViewModel : ViewModel() {
    private val repo = MinispotApp.instance.liked
    val state = MutableStateFlow(ListState<Track>())

    init {
        viewModelScope.launch { repo.tracks.collect { t -> if (t != null) state.value = state.value.copy(items = t, loading = false) } }
        load(force = false)
    }

    fun load(force: Boolean) {
        viewModelScope.launch {
            state.value = state.value.copy(loading = state.value.items.isEmpty(), error = null)
            try {
                repo.ensureLoaded()
                repo.sync(force)
                state.value = state.value.copy(loading = false)
            } catch (e: Exception) {
                state.value = state.value.copy(loading = false, error = if (state.value.items.isEmpty()) e.message else null)
            }
        }
    }
}

@Composable
fun MyTracksScreen(onBack: () -> Unit, vm: MyTracksViewModel = viewModel()) {
    val app = MinispotApp.instance
    val state by vm.state.collectAsState()
    val nowPlaying by app.player.nowPlaying.collectAsState()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TopBar("My tracks", onBack) {
            IconButton(onClick = { vm.load(force = true) }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        when {
            state.loading && state.items.isEmpty() -> Loading("Loading your tracks…")
            state.error != null -> ErrorBox(state.error!!) { vm.load(force = true) }
            state.items.isEmpty() -> EmptyText("No saved tracks yet")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(state.items, key = { _, t -> t.uri }) { index, t ->
                    TrackRow(
                        t,
                        isCurrent = nowPlaying?.trackUri == t.uri,
                        onClick = {
                            // Play inside Liked Songs so the next tracks follow in the same order.
                            scope.launch { app.playLikedAt(t, index) }
                        },
                    )
                }
            }
        }
    }
}

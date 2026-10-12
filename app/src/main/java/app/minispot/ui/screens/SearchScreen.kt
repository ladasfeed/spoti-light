package app.minispot.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minispot.MinispotApp
import app.minispot.api.Artist
import app.minispot.api.Track
import app.minispot.ui.components.Cover
import app.minispot.ui.components.EmptyText
import app.minispot.ui.components.ErrorBox
import app.minispot.ui.components.Loading
import app.minispot.ui.components.TopBar
import app.minispot.ui.components.TrackRow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

enum class SearchMode { Tracks, Artists }

data class SearchState(
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class)
class SearchViewModel : ViewModel() {
    private val api = MinispotApp.instance.api
    val query = MutableStateFlow("")
    val mode = MutableStateFlow(SearchMode.Tracks)
    val state = MutableStateFlow(SearchState())
    private var moreJob: Job? = null

    init {
        viewModelScope.launch {
            combine(query.debounce(350), mode) { q, m -> q.trim() to m }
                .distinctUntilChanged()
                .collectLatest { (q, m) ->
                    moreJob?.cancel()
                    if (q.isEmpty()) {
                        state.value = SearchState()
                        return@collectLatest
                    }
                    state.value = SearchState(loading = true)
                    state.value = try {
                        if (m == SearchMode.Tracks) {
                            val page = api.searchTracks(q, 0)
                            SearchState(tracks = page.items, hasMore = page.hasMore)
                        } else {
                            val page = api.searchArtists(q, 0)
                            SearchState(artists = page.items, hasMore = page.hasMore)
                        }
                    } catch (e: Exception) {
                        SearchState(error = e.message)
                    }
                }
        }
    }

    fun loadMore() {
        val s = state.value
        if (s.loading || !s.hasMore || moreJob?.isActive == true) return
        val q = query.value.trim()
        val m = mode.value
        moreJob = viewModelScope.launch {
            try {
                state.value = if (m == SearchMode.Tracks) {
                    val page = api.searchTracks(q, s.tracks.size)
                    s.copy(tracks = (s.tracks + page.items).distinctBy { it.uri }, hasMore = page.hasMore && page.items.isNotEmpty())
                } else {
                    val page = api.searchArtists(q, s.artists.size)
                    s.copy(artists = (s.artists + page.items).distinctBy { it.id }, hasMore = page.hasMore && page.items.isNotEmpty())
                }
            } catch (_: Exception) {
                state.value = s.copy(hasMore = false)
            }
        }
    }
}

@Composable
fun SearchScreen(onBack: () -> Unit, onArtist: (Artist) -> Unit, vm: SearchViewModel = viewModel()) {
    val app = MinispotApp.instance
    val query by vm.query.collectAsState()
    val mode by vm.mode.collectAsState()
    val state by vm.state.collectAsState()
    val nowPlaying by app.player.nowPlaying.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (vm.query.value.isEmpty()) focus.requestFocus() }

    Column(Modifier.fillMaxSize()) {
        TopBar("Search", onBack)
        OutlinedTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .focusRequester(focus),
            placeholder = { Text("Что будем слушать?") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Clear, "Очистить") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchMode.entries.forEach { m ->
                Row(
                    Modifier
                        .clickable { vm.mode.value = m }
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == m, onClick = { vm.mode.value = m })
                    Text(m.name)
                }
            }
        }

        val listState = rememberLazyListState()
        val nearEnd by remember {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= listState.layoutInfo.totalItemsCount - 3
            }
        }
        LaunchedEffect(nearEnd, state.hasMore) { if (nearEnd && state.hasMore) vm.loadMore() }

        when {
            state.loading -> Loading()
            state.error != null -> ErrorBox(state.error!!, null)
            query.isNotBlank() && state.tracks.isEmpty() && state.artists.isEmpty() -> EmptyText("Ничего не нашлось")
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (mode == SearchMode.Tracks) {
                    items(state.tracks, key = { it.uri }) { t ->
                        TrackRow(t, isCurrent = nowPlaying?.trackUri == t.uri, onClick = { app.player.play(t.uri) })
                    }
                } else {
                    items(state.artists, key = { it.id }) { a -> ArtistRow(a) { onArtist(a) } }
                }
            }
        }
    }
}

@Composable
fun ArtistRow(artist: Artist, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(artist.imageUrl, 48.dp, Modifier.clip(CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(artist.name, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

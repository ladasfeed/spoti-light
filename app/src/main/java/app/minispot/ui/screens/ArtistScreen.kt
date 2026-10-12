package app.minispot.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minispot.MinispotApp
import app.minispot.api.Album
import app.minispot.api.Track
import app.minispot.ui.components.EmptyText
import app.minispot.ui.components.ErrorBox
import app.minispot.ui.components.Loading
import app.minispot.ui.components.TopBar
import app.minispot.ui.components.TrackRow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

data class ArtistState(
    val name: String,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

data class ArtistSearchState(
    val open: Boolean = false,
    val query: String = "",
    val results: List<Track> = emptyList(),
    val loading: Boolean = false,
)

/**
 * Artist discography newest first. Albums and singles are listed first (cheap), then their
 * tracks are fetched a few albums at a time as the list scrolls.
 */
@OptIn(FlowPreview::class)
class ArtistViewModel(private val artistId: String, initialName: String) : ViewModel() {
    private val api = MinispotApp.instance.api
    val state = MutableStateFlow(ArtistState(initialName))
    val search = MutableStateFlow(ArtistSearchState())
    private val searchQuery = MutableStateFlow("")
    private var albums: List<Album> = emptyList()
    private var nextAlbum = 0
    private var moreJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            searchQuery.debounce(350).collectLatest { q -> runSearch(q.trim()) }
        }
    }

    fun load() {
        viewModelScope.launch {
            state.value = state.value.copy(loading = true, error = null)
            try {
                if (state.value.name.isEmpty()) {
                    state.value = state.value.copy(name = api.artist(artistId).name)
                }
                val all = mutableListOf<Album>()
                var offset = 0
                while (true) {
                    val page = api.artistAlbums(artistId, "album,single", offset)
                    all += page.items
                    if (!page.hasMore || page.items.isEmpty()) break
                    offset += page.items.size
                }
                albums = all.distinctBy { it.id }.sortedByDescending { it.releaseDate }
                nextAlbum = 0
                state.value = state.value.copy(tracks = emptyList(), loading = false)
                loadMore()
            } catch (e: Exception) {
                state.value = state.value.copy(loading = false, error = e.message)
            }
        }
    }

    val hasMore get() = nextAlbum < albums.size

    fun loadMore() {
        if (!hasMore || moreJob?.isActive == true) return
        moreJob = viewModelScope.launch {
            state.value = state.value.copy(loadingMore = true)
            val batch = albums.subList(nextAlbum, minOf(nextAlbum + 4, albums.size))
            val tracks = mutableListOf<Track>()
            try {
                for (album in batch) {
                    // Keep only tracks this artist actually plays on (skips other artists on compilations).
                    tracks += api.albumTracks(album).filter { t -> t.artists.any { it.id == artistId } }
                }
                nextAlbum += batch.size
                state.value = state.value.copy(tracks = state.value.tracks + tracks, loadingMore = false)
            } catch (e: Exception) {
                state.value = state.value.copy(loadingMore = false, error = e.message)
            }
        }
    }

    fun setSearchOpen(open: Boolean) {
        search.value = if (open) search.value.copy(open = true) else ArtistSearchState()
        if (!open) searchQuery.value = ""
    }

    fun setQuery(q: String) {
        search.value = search.value.copy(query = q)
        searchQuery.value = q
    }

    private suspend fun runSearch(q: String) {
        if (q.isEmpty()) {
            search.value = search.value.copy(results = emptyList(), loading = false)
            return
        }
        search.value = search.value.copy(loading = true)
        val name = state.value.name
        val results = mutableListOf<Track>()
        try {
            // Search is capped at 10 per request, so read a few pages and keep this artist's tracks.
            for (offset in listOf(0, 10, 20)) {
                val page = api.searchTracks("$q artist:\"$name\"", offset)
                results += page.items.filter { t -> t.artists.any { it.id == artistId } }
                if (!page.hasMore) break
            }
        } catch (_: Exception) {
        }
        search.value = search.value.copy(results = results.distinctBy { it.uri }, loading = false)
    }
}

@Composable
fun ArtistScreen(artistId: String, initialName: String, onBack: () -> Unit) {
    val vm: ArtistViewModel = viewModel(key = "artist-$artistId") {
        ArtistViewModel(artistId, initialName)
    }
    val app = MinispotApp.instance
    val state by vm.state.collectAsState()
    val search by vm.search.collectAsState()
    val nowPlaying by app.player.nowPlaying.collectAsState()

    fun playTrack(t: Track) {
        val albumUri = t.albumUri
        val index = t.albumIndex
        if (albumUri != null && index != null) app.player.playAt(albumUri, index, fallbackTrackUri = t.uri, expectedName = t.name) else app.player.play(t.uri)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("", onBack) {
            IconButton(onClick = { vm.setSearchOpen(!search.open) }) {
                Icon(if (search.open) Icons.Filled.Close else Icons.Filled.Search, "Search this artist")
            }
        }
        Text(
            state.name,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
            fontSize = 40.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (search.open) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                value = search.query,
                onValueChange = vm::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .focusRequester(focus),
                placeholder = { Text("Search ${state.name}...") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
            )
            when {
                search.loading -> Loading()
                search.query.isNotBlank() && search.results.isEmpty() -> EmptyText("Nothing found")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(search.results, key = { it.uri }) { t ->
                        TrackRow(t, nowPlaying?.trackUri == t.uri, onClick = { app.player.play(t.uri) })
                    }
                }
            }
            return@Column
        }

        val listState = rememberLazyListState()
        val nearEnd by remember {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= listState.layoutInfo.totalItemsCount - 5
            }
        }
        LaunchedEffect(nearEnd, state.tracks.size, state.loading) {
            if (nearEnd && !state.loading) vm.loadMore()
        }

        when {
            state.loading -> Loading()
            state.error != null && state.tracks.isEmpty() -> ErrorBox(state.error!!, vm::load)
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.tracks, key = { it.uri + it.albumUri }) { t ->
                    TrackRow(
                        t,
                        isCurrent = nowPlaying?.trackUri == t.uri,
                        onClick = { playTrack(t) },
                        trailing = t.releaseDate?.take(4),
                    )
                }
                if (state.loadingMore) item { Loading() }
            }
        }
    }
}

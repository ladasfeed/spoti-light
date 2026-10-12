package app.minispot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.minispot.ui.components.PlayerBar
import app.minispot.ui.components.PlayerSheet
import app.minispot.ui.screens.ArtistScreen
import app.minispot.ui.screens.HomeScreen
import app.minispot.ui.screens.MadeForYouScreen
import app.minispot.ui.screens.RecentsScreen
import app.minispot.ui.screens.ReleasesScreen
import app.minispot.ui.screens.SearchScreen
import app.minispot.ui.screens.SettingsScreen
import app.minispot.ui.screens.SetupScreen
import app.minispot.ui.theme.MinispotTheme
import app.minispot.ui.theme.themeById
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as MinispotApp
    private var loginError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val themeId by app.prefs.theme.collectAsState()
            val theme = themeById(themeId)
            val loggedIn by app.auth.loggedIn.collectAsState()

            val bar = theme.background.toArgb()
            LaunchedEffect(bar) { enableEdgeToEdge(SystemBarStyle.dark(bar), SystemBarStyle.dark(bar)) }

            MinispotTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) {
                        if (loggedIn) MainContent() else SetupScreen(loginError)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (app.auth.loggedIn.value) app.player.connect(this)
    }

    override fun onStop() {
        super.onStop()
        // Playback keeps going in the Spotify app; we just stop listening to it.
        app.player.disconnect()
    }

    private fun handleIntent(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "minispot") return
        lifecycleScope.launch {
            loginError = app.auth.handleRedirect(data)?.let { "Не удалось войти: $it" }
            if (loginError == null) app.player.connect(this@MainActivity)
        }
    }

    @Composable
    private fun MainContent() {
        val nav = rememberNavController()
        val player = app.player
        val nowPlaying by player.nowPlaying.collectAsState()
        val status by player.status.collectAsState()
        val cover by player.cover.collectAsState()
        val liked by player.liked.collectAsState()
        var sheetOpen by remember { mutableStateOf(false) }

        fun openArtist(id: String, name: String) =
            nav.navigate("artist/$id?name=${Uri.encode(name)}") { launchSingleTop = true }

        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onSearch = { nav.navigate("search") },
                            onRecents = { nav.navigate("recents") },
                            onMadeForYou = { nav.navigate("madeforyou") },
                            onReleases = { nav.navigate("releases") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("search") {
                        Box(Modifier.imePadding()) {
                            SearchScreen(onBack = { nav.popBackStack() }, onArtist = { openArtist(it.id, it.name) })
                        }
                    }
                    composable(
                        "artist/{id}?name={name}",
                        arguments = listOf(
                            navArgument("id") { type = NavType.StringType },
                            navArgument("name") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) { entry ->
                        ArtistScreen(
                            artistId = entry.arguments?.getString("id").orEmpty(),
                            initialName = entry.arguments?.getString("name").orEmpty(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("recents") { RecentsScreen(onBack = { nav.popBackStack() }) }
                    composable("madeforyou") { MadeForYouScreen(onBack = { nav.popBackStack() }) }
                    composable("releases") { ReleasesScreen(onBack = { nav.popBackStack() }) }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
            PlayerBar(
                nowPlaying = nowPlaying,
                status = status,
                cover = cover,
                liked = liked,
                onOpen = { sheetOpen = true },
                onTogglePause = player::togglePause,
                onNext = player::next,
                onPrev = player::previous,
                onToggleLike = player::toggleLiked,
                onReconnect = { player.connect(this@MainActivity) },
            )
        }

        val np = nowPlaying
        if (sheetOpen && np != null) {
            PlayerSheet(
                nowPlaying = np,
                cover = cover,
                liked = liked,
                onDismiss = { sheetOpen = false },
                onArtist = {
                    sheetOpen = false
                    openArtist(it.id, it.name)
                },
                onTogglePause = player::togglePause,
                onNext = player::next,
                onPrev = player::previous,
                onSeek = player::seekTo,
                onToggleLike = player::toggleLiked,
            )
        }
    }
}

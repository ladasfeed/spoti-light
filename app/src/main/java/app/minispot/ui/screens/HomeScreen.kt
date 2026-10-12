package app.minispot.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.minispot.MinispotApp
import app.minispot.ui.components.MusicFolderIcon
import kotlinx.coroutines.launch

/** Home is just round icon buttons, three per row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onMyTracks: () -> Unit,
    onSearch: () -> Unit,
    onRecents: () -> Unit,
    onMadeForYou: () -> Unit,
    onReleases: () -> Unit,
    onSettings: () -> Unit,
) {
    val app = MinispotApp.instance
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun playLiked(shuffle: Boolean) {
        scope.launch {
            try {
                app.player.play(app.likedSongsUri(), shuffle)
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Couldn't start playback", Toast.LENGTH_LONG).show()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        FlowRow(
            maxItemsInEachRow = 3,
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            RoundButton(Icons.Filled.PlayArrow, "Play", accent = true) { playLiked(false) }
            RoundButton(Icons.Filled.Shuffle, "Play shuffled") { playLiked(true) }
            RoundButton(MusicFolderIcon, "My tracks", onClick = onMyTracks)
            RoundButton(Icons.Filled.Search, "Search", onClick = onSearch)
            RoundButton(Icons.Filled.History, "Recents", onClick = onRecents)
            RoundButton(Icons.Filled.AutoAwesome, "Made for you", onClick = onMadeForYou)
            RoundButton(Icons.Filled.Notifications, "Last releases", onClick = onReleases)
            RoundButton(Icons.Filled.Settings, "Settings", dim = true, onClick = onSettings)
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    accent: Boolean = false,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(if (accent) colors.primary else colors.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = when {
                accent -> colors.onPrimary
                dim -> colors.onSurfaceVariant
                else -> colors.onSurface
            },
            modifier = Modifier.size(34.dp),
        )
    }
}

package app.minispot.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minispot.MinispotApp
import app.minispot.ui.components.MusicFolderIcon
import kotlinx.coroutines.launch

/**
 * Home is a list of icon + label rows at the bottom of the screen, lined up on the side of the
 * hand that holds the phone (Settings → Hand), so every entry is within thumb reach.
 */
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
    val hand by app.prefs.hand.collectAsState()
    val right = hand == "right"

    fun playLiked(shuffle: Boolean) {
        scope.launch {
            try {
                app.player.playLiked(app.userId(), shuffle)
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Couldn't start playback", Toast.LENGTH_LONG).show()
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
        ) {
            HomeRow(Icons.Filled.PlayArrow, "Play", right, accent = true) { playLiked(false) }
            HomeRow(Icons.Filled.Shuffle, "Play shuffled", right) { playLiked(true) }
            HomeRow(MusicFolderIcon, "My tracks", right, onClick = onMyTracks)
            HomeRow(Icons.Filled.Search, "Search", right, onClick = onSearch)
            HomeRow(Icons.Filled.History, "Recents", right, onClick = onRecents)
            HomeRow(Icons.Filled.AutoAwesome, "Made for you", right, onClick = onMadeForYou)
            HomeRow(Icons.Filled.Notifications, "Last releases", right, onClick = onReleases)
            Spacer(Modifier.height(40.dp))
            HomeRow(Icons.Filled.Settings, "Settings", right, dim = true, onClick = onSettings)
        }
    }
}

@Composable
private fun HomeRow(
    icon: ImageVector,
    label: String,
    right: Boolean,
    accent: Boolean = false,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = if (right) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val circle = @Composable {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (accent) colors.primary else colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = when {
                        accent -> colors.onPrimary
                        dim -> colors.onSurfaceVariant
                        else -> colors.onSurface
                    },
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        val text = @Composable {
            Text(
                label,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = if (dim) colors.onSurfaceVariant else colors.onSurface,
            )
        }
        // The icon sits at the screen edge, closest to the thumb.
        if (right) {
            text()
            Spacer(Modifier.width(16.dp))
            circle()
        } else {
            circle()
            Spacer(Modifier.width(16.dp))
            text()
        }
    }
}

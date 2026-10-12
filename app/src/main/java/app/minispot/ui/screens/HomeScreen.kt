package app.minispot.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minispot.MinispotApp
import app.minispot.ui.components.TopBar
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
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
                Toast.makeText(context, e.message ?: "Не получилось", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar("Minispot", onBack = null) {
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Настройки") }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BigButton("Play", Icons.Filled.PlayArrow, filled = true, Modifier.weight(1f)) { playLiked(false) }
            BigButton("Play shuffled", Icons.Filled.Shuffle, filled = false, Modifier.weight(1f)) { playLiked(true) }
        }
        Spacer(Modifier.height(12.dp))
        NavTile("Search", Icons.Filled.Search, onSearch)
        NavTile("Recents", Icons.Filled.History, onRecents)
        NavTile("Made for you", Icons.Filled.AutoAwesome, onMadeForYou)
        NavTile("Last releases", Icons.Filled.NewReleases, onReleases)
    }
}

@Composable
private fun BigButton(text: String, icon: ImageVector, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .height(64.dp)
            .clip(shape)
            .then(if (filled) Modifier.background(colors.primary) else Modifier.border(1.5.dp, colors.primary, shape))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (filled) colors.onPrimary else colors.primary
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(text, color = tint, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun NavTile(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.Unspecified)
    }
}

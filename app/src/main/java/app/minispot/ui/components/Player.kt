package app.minispot.ui.components

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minispot.api.ArtistRef
import app.minispot.player.NowPlaying
import app.minispot.player.RemoteStatus
import kotlinx.coroutines.delay

/** Ticks once per half second while playing so the progress follows the Spotify app. */
@Composable
private fun rememberPosition(np: NowPlaying): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(np) {
        while (!np.isPaused) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
        now = SystemClock.elapsedRealtime()
    }
    return np.currentPosition(now)
}

@Composable
private fun CoverBitmap(bitmap: Bitmap?, size: Dp?, modifier: Modifier = Modifier) {
    val m = (if (size != null) modifier.size(size) else modifier)
        .clip(RoundedCornerShape(if (size != null && size < 80.dp) 4.dp else 8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
    Box(m, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LikeButton(liked: Boolean?, onToggle: () -> Unit, size: Dp = 24.dp) {
    IconButton(onClick = onToggle, enabled = liked != null) {
        Icon(
            if (liked == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (liked == true) "Удалить из любимых" else "Добавить в любимые",
            tint = if (liked == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(size),
        )
    }
}

@Composable
fun PlayerBar(
    nowPlaying: NowPlaying?,
    status: RemoteStatus,
    cover: Bitmap?,
    liked: Boolean?,
    onOpen: () -> Unit,
    onTogglePause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onToggleLike: () -> Unit,
    onReconnect: () -> Unit,
) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    if (nowPlaying == null) {
        val message = when (status) {
            RemoteStatus.SpotifyNotInstalled -> "Установи приложение Spotify: оно воспроизводит музыку"
            is RemoteStatus.Failed -> "Нет связи со Spotify: ${status.message}"
            RemoteStatus.Connecting -> "Подключаюсь к Spotify…"
            else -> null
        } ?: return
        Row(
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status is RemoteStatus.Failed) TextButton(onClick = onReconnect) { Text("Ещё раз") }
        }
        return
    }

    val position = rememberPosition(nowPlaying)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverBitmap(cover, 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(nowPlaying.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    nowPlaying.artists.joinToString(", ") { it.name },
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LikeButton(liked, onToggleLike)
            IconButton(onClick = onPrev, enabled = nowPlaying.canSkipPrev) { Icon(Icons.Filled.SkipPrevious, "Назад") }
            IconButton(onClick = onTogglePause) {
                Icon(if (nowPlaying.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, "Пауза/плей")
            }
            IconButton(onClick = onNext, enabled = nowPlaying.canSkipNext) { Icon(Icons.Filled.SkipNext, "Вперёд") }
        }
        LinearProgressIndicator(
            progress = { if (nowPlaying.durationMs > 0) position.toFloat() / nowPlaying.durationMs else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = MaterialTheme.colorScheme.onSurface,
            trackColor = MaterialTheme.colorScheme.outline,
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheet(
    nowPlaying: NowPlaying,
    cover: Bitmap?,
    liked: Boolean?,
    onDismiss: () -> Unit,
    onArtist: (ArtistRef) -> Unit,
    onTogglePause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleLike: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CoverBitmap(cover, null, Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true))
            }
            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        nowPlaying.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row {
                        nowPlaying.artists.forEachIndexed { i, artist ->
                            Text(
                                artist.name + if (i < nowPlaying.artists.lastIndex) ", " else "",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 16.sp,
                                maxLines = 1,
                                modifier = Modifier.clickable(enabled = artist.id.isNotEmpty()) { onArtist(artist) },
                            )
                        }
                    }
                }
                LikeButton(liked, onToggleLike, size = 28.dp)
            }
            Spacer(Modifier.height(12.dp))

            val position = rememberPosition(nowPlaying)
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val duration = nowPlaying.durationMs.coerceAtLeast(1L)
            Slider(
                value = if (dragging) dragValue else position.toFloat() / duration,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    onSeek((dragValue * duration).toLong())
                    dragging = false
                },
                enabled = nowPlaying.canSeek,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurface,
                    inactiveTrackColor = MaterialTheme.colorScheme.outline,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val shown = if (dragging) (dragValue * duration).toLong() else position
                Text(formatTime(shown), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(nowPlaying.durationMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrev, enabled = nowPlaying.canSkipPrev, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipPrevious, "Назад", Modifier.size(36.dp))
                }
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onTogglePause),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (nowPlaying.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = "Пауза/плей",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = onNext, enabled = nowPlaying.canSkipNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipNext, "Вперёд", Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

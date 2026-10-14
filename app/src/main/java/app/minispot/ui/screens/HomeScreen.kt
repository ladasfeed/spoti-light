package app.minispot.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenWith
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minispot.MinispotApp
import app.minispot.ui.components.MusicFolderIcon
import kotlinx.coroutines.launch

private class HomeEntry(val icon: ImageVector, val label: String, val accent: Boolean = false, val onClick: () -> Unit)

/** Space for the drag strip above the rows, in dp. */
private const val HANDLE_DP = 28f
/** The gap before Settings, as a share of one row's height. */
private const val GAP_ROWS = 0.6f
/** Seven entries plus Settings. */
private const val ROWS = 8
private const val MIN_ROW_DP = 36f
private const val DEFAULT_ROW_DP = 60f

/**
 * Home is a panel of icon + label rows, lined up on the side of the hand that holds the phone
 * (Settings → Hand). Holding the strip above the rows enters edit mode: drag the panel to move it,
 * drag its top or bottom edge to resize it. Icons scale with the rows; text keeps its size.
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
    val haptics = LocalHapticFeedback.current
    val pxPerDp = LocalDensity.current.density
    val hand by app.prefs.hand.collectAsState()
    val right = hand == "right"

    var editing by remember { mutableStateOf(false) }
    var savedHeight by remember { mutableFloatStateOf(app.prefs.homePanelHeight) }
    var savedBottom by remember { mutableFloatStateOf(app.prefs.homePanelBottom) }
    BackHandler(enabled = editing) { editing = false }

    fun playLiked(shuffle: Boolean) {
        scope.launch {
            try {
                app.player.playLiked(app.userId(), shuffle)
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Couldn't start playback", Toast.LENGTH_LONG).show()
            }
        }
    }

    val entries = listOf(
        HomeEntry(Icons.Filled.PlayArrow, "Play", accent = true) { playLiked(false) },
        HomeEntry(Icons.Filled.Shuffle, "Play shuffled") { playLiked(true) },
        HomeEntry(MusicFolderIcon, "My tracks", onClick = onMyTracks),
        HomeEntry(Icons.Filled.Search, "Search", onClick = onSearch),
        HomeEntry(Icons.Filled.History, "Recents", onClick = onRecents),
        HomeEntry(Icons.Filled.AutoAwesome, "Made for you", onClick = onMadeForYou),
        HomeEntry(Icons.Filled.Notifications, "Last releases", onClick = onReleases),
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val avail = maxHeight.value
        val minHeight = minOf(HANDLE_DP + (ROWS + GAP_ROWS) * MIN_ROW_DP, avail)

        // Sizes are kept in dp and measured from the bottom, so the panel stays in thumb reach.
        fun height() = (if (savedHeight > 0) savedHeight else HANDLE_DP + (ROWS + GAP_ROWS) * DEFAULT_ROW_DP)
            .coerceIn(minHeight, avail)
        fun bottom() = savedBottom.coerceIn(0f, avail - height())

        fun move(dyPx: Float) {
            val h = height()
            savedHeight = h
            savedBottom = (bottom() - dyPx / pxPerDp).coerceIn(0f, avail - h)
        }
        fun resizeTop(dyPx: Float) {
            val b = bottom()
            savedHeight = (height() - dyPx / pxPerDp).coerceIn(minHeight, avail - b)
            savedBottom = b
        }
        fun resizeBottom(dyPx: Float) {
            val top = avail - bottom() - height()
            val h = (height() + dyPx / pxPerDp).coerceIn(minHeight, avail - top)
            savedHeight = h
            savedBottom = avail - top - h
        }
        fun commit() {
            app.prefs.homePanelHeight = height()
            app.prefs.homePanelBottom = bottom()
        }

        val height = height()
        val top = avail - bottom() - height
        val rowHeight = ((height - HANDLE_DP) / (ROWS + GAP_ROWS)).dp

        if (editing) {
            // Tapping outside the panel finishes editing.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { editing = false } })
        }
        Box(
            Modifier
                .fillMaxWidth()
                .offset(y = top.dp)
                .height(height.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(HANDLE_DP.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                editing = true
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    )
                }
                entries.forEach { HomeRow(it.icon, it.label, right, rowHeight, accent = it.accent, onClick = it.onClick) }
                Spacer(Modifier.height(rowHeight * GAP_ROWS))
                HomeRow(Icons.Filled.Settings, null, right, rowHeight, dim = true, onClick = onSettings)
            }
            if (editing) {
                EditOverlay(
                    onMove = ::move,
                    onResizeTop = ::resizeTop,
                    onResizeBottom = ::resizeBottom,
                    onCommit = ::commit,
                    onDone = { editing = false },
                )
            }
        }
    }
}

@Composable
private fun HomeRow(
    icon: ImageVector,
    label: String?,
    right: Boolean,
    rowHeight: Dp,
    accent: Boolean = false,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val circleSize = rowHeight * 0.82f
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = if (right) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val circle = @Composable {
            Box(
                Modifier
                    .size(circleSize)
                    .clip(CircleShape)
                    .background(if (accent) colors.primary else colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = label ?: "Settings",
                    tint = when {
                        accent -> colors.onPrimary
                        dim -> colors.onSurfaceVariant
                        else -> colors.onSurface
                    },
                    modifier = Modifier.size(circleSize * 0.5f),
                )
            }
        }
        val text = @Composable {
            if (label != null) {
                Text(
                    label,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = 1,
                )
            }
        }
        // The icon sits at the screen edge, closest to the thumb.
        val gap = if (label != null) 16.dp else 0.dp
        if (right) {
            text()
            Spacer(Modifier.width(gap))
            circle()
        } else {
            circle()
            Spacer(Modifier.width(gap))
            text()
        }
    }
}

/** Covers the panel in edit mode: drag the middle to move it, the top or bottom bar to resize it. */
@Composable
private fun EditOverlay(
    onMove: (Float) -> Unit,
    onResizeTop: (Float) -> Unit,
    onResizeBottom: (Float) -> Unit,
    onCommit: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val move by rememberUpdatedState(onMove)
    val commit by rememberUpdatedState(onCommit)
    val done by rememberUpdatedState(onDone)
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(colors.surfaceVariant.copy(alpha = 0.75f))
            .border(2.dp, colors.primary, shape)
            // A tap finishes editing; once a finger moves past the touch slop the drag takes over.
            .pointerInput(Unit) { detectTapGestures { done() } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = { commit() }, onDragCancel = { commit() }) { change, dy ->
                    change.consume()
                    move(dy)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.OpenWith, contentDescription = "Move or resize", tint = colors.primary, modifier = Modifier.size(44.dp))
        EdgeBar(Modifier.align(Alignment.TopCenter), onResizeTop, onCommit)
        EdgeBar(Modifier.align(Alignment.BottomCenter), onResizeBottom, onCommit)
    }
}

@Composable
private fun EdgeBar(modifier: Modifier, onDrag: (Float) -> Unit, onCommit: () -> Unit) {
    val drag by rememberUpdatedState(onDrag)
    val commit by rememberUpdatedState(onCommit)
    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = { commit() }, onDragCancel = { commit() }) { change, dy ->
                    change.consume()
                    drag(dy)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 56.dp, height = 5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

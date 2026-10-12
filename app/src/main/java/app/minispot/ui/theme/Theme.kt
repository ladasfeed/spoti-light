package app.minispot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

data class AppTheme(
    val id: String,
    val title: String,
    val accent: Color,
    val onAccent: Color,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val text: Color,
    val textDim: Color,
)

val Themes = listOf(
    AppTheme(
        id = "spotify", title = "Spotify",
        accent = Color(0xFF1DB954), onAccent = Color(0xFF000000),
        background = Color(0xFF121212), surface = Color(0xFF181818), surfaceHigh = Color(0xFF282828),
        text = Color(0xFFFFFFFF), textDim = Color(0xFFB3B3B3),
    ),
    AppTheme(
        id = "midnight", title = "Midnight",
        accent = Color(0xFF5B9BFF), onAccent = Color(0xFF041026),
        background = Color(0xFF0B1020), surface = Color(0xFF121A2E), surfaceHigh = Color(0xFF1D2740),
        text = Color(0xFFEAF0FF), textDim = Color(0xFF9AA7C4),
    ),
    AppTheme(
        id = "violet", title = "Violet",
        accent = Color(0xFFB794F6), onAccent = Color(0xFF1C0F33),
        background = Color(0xFF13101A), surface = Color(0xFF1B1724), surfaceHigh = Color(0xFF2A2436),
        text = Color(0xFFF3EEFF), textDim = Color(0xFFAFA4C2),
    ),
    AppTheme(
        id = "ember", title = "Ember",
        accent = Color(0xFFFF8A4C), onAccent = Color(0xFF2A1103),
        background = Color(0xFF141110), surface = Color(0xFF1D1917), surfaceHigh = Color(0xFF2C2522),
        text = Color(0xFFFFF3EC), textDim = Color(0xFFC2B1A8),
    ),
)

fun themeById(id: String) = Themes.firstOrNull { it.id == id } ?: Themes.first()

@Composable
fun MinispotTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = theme.accent,
        onPrimary = theme.onAccent,
        secondary = theme.accent,
        onSecondary = theme.onAccent,
        background = theme.background,
        onBackground = theme.text,
        surface = theme.surface,
        onSurface = theme.text,
        surfaceVariant = theme.surfaceHigh,
        onSurfaceVariant = theme.textDim,
        surfaceContainer = theme.surface,
        surfaceContainerLow = theme.surface,
        surfaceContainerHigh = theme.surfaceHigh,
        surfaceContainerHighest = theme.surfaceHigh,
        outline = theme.textDim.copy(alpha = 0.4f),
        outlineVariant = theme.surfaceHigh,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

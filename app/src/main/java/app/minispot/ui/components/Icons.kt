package app.minispot.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Folder with a note: "My tracks" (Liked Songs). Drawn to match the bold SF-style reference. */
val MusicFolderIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MusicFolder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Folder outline with the tab on the top left.
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(3f, 7f)
            curveTo(3f, 5.9f, 3.9f, 5f, 5f, 5f)
            horizontalLineTo(9f)
            lineTo(11f, 6.8f)
            horizontalLineTo(19f)
            curveTo(20.1f, 6.8f, 21f, 7.7f, 21f, 8.8f)
            verticalLineTo(17f)
            curveTo(21f, 18.1f, 20.1f, 19f, 19f, 19f)
            horizontalLineTo(5f)
            curveTo(3.9f, 19f, 3f, 18.1f, 3f, 17f)
            close()
        }
        // Divider under the tab.
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f) {
            moveTo(3f, 10.2f)
            horizontalLineTo(21f)
        }
        // Note: head, stem and flag.
        path(fill = SolidColor(Color.Black)) {
            moveTo(13.9f, 12.2f)
            lineTo(16.4f, 11.7f)
            verticalLineTo(13.1f)
            lineTo(14.9f, 13.4f)
            verticalLineTo(16.1f)
            curveTo(14.9f, 17.1f, 14.1f, 17.8f, 13.1f, 17.8f)
            curveTo(12.2f, 17.8f, 11.5f, 17.1f, 11.5f, 16.3f)
            curveTo(11.5f, 15.4f, 12.3f, 14.7f, 13.2f, 14.7f)
            curveTo(13.4f, 14.7f, 13.7f, 14.8f, 13.9f, 14.9f)
            close()
        }
    }.build()
}

package app.minispot.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.minispot.R

data class AppFont(val id: String, val title: String, val family: FontFamily)

/** One variable font file serves every weight the app uses. */
@OptIn(ExperimentalTextApi::class)
private fun variableFamily(res: Int) = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.Black).map {
        Font(res, it, variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)))
    }
)

/** Roboto is Android's own font; the other three ship with the app (SIL Open Font License). */
val Fonts = listOf(
    AppFont("roboto", "Roboto", FontFamily.Default),
    AppFont("inter", "Inter", variableFamily(R.font.inter)),
    AppFont("opensans", "Open Sans", variableFamily(R.font.open_sans)),
    AppFont("montserrat", "Montserrat", variableFamily(R.font.montserrat)),
)

fun fontById(id: String) = Fonts.firstOrNull { it.id == id } ?: Fonts.first()

/** Text size choices: multiplier to label. */
val FontScales = listOf(0.9f to "S", 1f to "M", 1.15f to "L", 1.3f to "XL")

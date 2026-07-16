package dev.njr.zync.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.njr.zync.R

/** The three vendored voices (typography decision, native-home spec 2026-07-16). */
val BigShoulders = FontFamily(Font(R.font.big_shoulders_900, FontWeight.Black))

val CharonMono = FontFamily(
    Font(R.font.iosevka_charon_mono, FontWeight.Normal),
    Font(R.font.iosevka_charon_mono_bold, FontWeight.Bold),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Geomini = FontFamily(
    Font(R.font.geomini, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.geomini, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.geomini, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

/** The shared dark palette (pico tokens + the validated calendar pair). */
object ZyncColors {
    val Surface = Color(0xFF13171F)
    val Card = Color(0xFF1A212B)
    val Border = Color(0xFF2A313C)
    val Ink = Color(0xFFC2C7D0)
    val Ink2 = Color(0xFF8A91A0)
    val Ink3 = Color(0xFF5C6470)
    val Accent = Color(0xFF4A90C2)
    val Work = Color(0xFF3987E5)
    val Home = Color(0xFF199E70)
}

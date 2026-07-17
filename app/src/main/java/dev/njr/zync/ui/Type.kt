package dev.njr.zync.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.njr.zync.R

/**
 * Two vendored accent voices (typography decision, native-home spec 2026-07-16) +
 * the SYSTEM base voice: Pixel's Google Sans, resolved by device family name so it
 * blends with the rest of the Pixel surfaces (falls back to the platform default
 * sans on devices without it — Google Sans is proprietary, we can't vendor it).
 */
val BigShoulders = FontFamily(Font(R.font.big_shoulders_900, FontWeight.Black))

val CharonMono = FontFamily(
    Font(R.font.iosevka_charon_mono, FontWeight.Normal),
    Font(R.font.iosevka_charon_mono_bold, FontWeight.Bold),
)

val ZyncSans = FontFamily(
    Font(DeviceFontFamilyName("google-sans"), weight = FontWeight.Normal),
    Font(DeviceFontFamilyName("google-sans"), weight = FontWeight.Medium),
    Font(DeviceFontFamilyName("google-sans"), weight = FontWeight.SemiBold),
    Font(DeviceFontFamilyName("google-sans"), weight = FontWeight.Bold),
)

/** The shared dark palette (pico tokens + the validated calendar pair). */
object ZyncColors {
    /** Pure black: AMOLED power (device feedback 2026-07-16). Cards stay elevated. */
    val Surface = Color(0xFF000000)
    val Card = Color(0xFF1A212B)
    val Border = Color(0xFF2A313C)
    val Ink = Color(0xFFC2C7D0)
    val Ink2 = Color(0xFF8A91A0)
    val Ink3 = Color(0xFF5C6470)
    val Accent = Color(0xFF4A90C2)
    val Work = Color(0xFF3987E5)
    val Home = Color(0xFF199E70)
}

package dev.njr.zync.ui

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/**
 * Work-profile app icons render colour-INVERTED everywhere they appear (search drawer, the
 * action bar, submenus, the bar-settings pickers) so a work app reads differently from a
 * personal one at a glance. Applied as a [ColorFilter] on the icon [androidx.compose.foundation.Image].
 */
val WorkIconInvert: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** The filter for an app icon: inverted for work-profile apps ([userSerial] != null), else none. */
fun workIconFilter(userSerial: Long?): ColorFilter? = if (userSerial != null) WorkIconInvert else null

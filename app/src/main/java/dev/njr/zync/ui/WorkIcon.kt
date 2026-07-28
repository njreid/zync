package dev.njr.zync.ui

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontStyle

/**
 * App icons now render with their true colours everywhere; a work-profile app instead reads
 * differently by ITALICIZING its NAME (see [workNameStyle]). This stays a no-op filter so the
 * icon call sites don't need to change.
 */
fun workIconFilter(@Suppress("UNUSED_PARAMETER") userSerial: Long?): ColorFilter? = null

/** Italic for a work-profile app name ([userSerial] != null), upright otherwise. */
fun workNameStyle(userSerial: Long?): FontStyle = if (userSerial != null) FontStyle.Italic else FontStyle.Normal

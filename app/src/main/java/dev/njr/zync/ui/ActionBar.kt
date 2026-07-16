package dev.njr.zync.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import dev.njr.zync.R

/** What a bar tap asks the host to do (the host owns intents/bridge calls). */
enum class BarAction { Messages, Calendar, Phone, CaptureText, CaptureVoice, CaptureScan, CaptureUpload }

// Minimal palette, matching the :web dark theme (pico dark tokens).
private val BarBackground = Color(0xFF13171F)
private val BarBorder = Color(0xFF2A313C)
private val BarMuted = Color(0xFF8A91A0)

/**
 * The launcher action bar (spec L1): Messages · Calendar · Phone · Search · Capture,
 * where Capture expands in place to the v0.2 capture modes (Text/Voice/Scan/Upload).
 * Swiping left across the bar (or the Search slot) opens the app+web search overlay
 * (spec L3). The context slot appears once context→app bindings exist.
 */
@Composable
fun ZyncActionBar(onAction: (BarAction) -> Unit, onSearch: () -> Unit = {}) {
    var captureOpen by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(BarBackground)
            // Any decisive horizontal swipe on the bar opens search (either direction —
            // device feedback: "from the left" reads as a rightward drag).
            .pointerInput(Unit) {
                val threshold = 48.dp.toPx()
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { _, dx -> total += dx },
                    onDragEnd = { if (kotlin.math.abs(total) > threshold) onSearch() },
                )
            }
            // The bar owns the nav-bar strip: background extends behind it, icons above it.
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(Modifier.fillMaxWidth().height(1.dp).background(BarBorder)) {}
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (captureOpen) {
                barSlot(R.drawable.ic_text, "Text", Modifier.weight(1f)) { captureOpen = false; onAction(BarAction.CaptureText) }
                barSlot(R.drawable.ic_voice, "Voice", Modifier.weight(1f)) { captureOpen = false; onAction(BarAction.CaptureVoice) }
                barSlot(R.drawable.ic_scan, "Scan", Modifier.weight(1f)) { captureOpen = false; onAction(BarAction.CaptureScan) }
                barSlot(R.drawable.ic_upload, "Upload", Modifier.weight(1f)) { captureOpen = false; onAction(BarAction.CaptureUpload) }
                barSlot(R.drawable.ic_close, "Back", Modifier.weight(1f)) { captureOpen = false }
            } else {
                barSlot(R.drawable.ic_messages, "Messages", Modifier.weight(1f)) { onAction(BarAction.Messages) }
                barSlot(R.drawable.ic_calendar, "Calendar", Modifier.weight(1f)) { onAction(BarAction.Calendar) }
                barSlot(R.drawable.ic_phone, "Phone", Modifier.weight(1f)) { onAction(BarAction.Phone) }
                barSlot(R.drawable.ic_search, "Search", Modifier.weight(1f)) { onSearch() }
                barSlot(R.drawable.ic_capture, "Capture", Modifier.weight(1f)) { captureOpen = true }
            }
        }
    }
}

@Composable
private fun barSlot(@DrawableRes icon: Int, label: String, modifier: Modifier, onTap: () -> Unit) {
    Column(
        modifier.clickable(onClick = onTap).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = label,
            modifier = Modifier.size(30.dp),
            colorFilter = ColorFilter.tint(BarMuted),
        )
        BasicText(label, style = TextStyle(color = BarMuted, fontSize = 11.sp))
    }
}

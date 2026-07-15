package dev.njr.zync.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
            .pointerInput(Unit) {
                val threshold = 48.dp.toPx()
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { _, dx -> total += dx },
                    onDragEnd = { if (total < -threshold) onSearch() },
                )
            },
    ) {
        Row(Modifier.fillMaxWidth().height(1.dp).background(BarBorder)) {}
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (captureOpen) {
                barSlot(R.drawable.ic_text, "Text") { captureOpen = false; onAction(BarAction.CaptureText) }
                barSlot(R.drawable.ic_voice, "Voice") { captureOpen = false; onAction(BarAction.CaptureVoice) }
                barSlot(R.drawable.ic_scan, "Scan") { captureOpen = false; onAction(BarAction.CaptureScan) }
                barSlot(R.drawable.ic_upload, "Upload") { captureOpen = false; onAction(BarAction.CaptureUpload) }
                barSlot(R.drawable.ic_close, "Back") { captureOpen = false }
            } else {
                barSlot(R.drawable.ic_messages, "Messages") { onAction(BarAction.Messages) }
                barSlot(R.drawable.ic_calendar, "Calendar") { onAction(BarAction.Calendar) }
                barSlot(R.drawable.ic_phone, "Phone") { onAction(BarAction.Phone) }
                barSlot(R.drawable.ic_search, "Search") { onSearch() }
                barSlot(R.drawable.ic_capture, "Capture") { captureOpen = true }
            }
        }
    }
}

@Composable
private fun barSlot(@DrawableRes icon: Int, label: String, onTap: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onTap).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = label,
            modifier = Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(BarMuted),
        )
        BasicText(label, style = TextStyle(color = BarMuted, fontSize = 11.sp))
    }
}

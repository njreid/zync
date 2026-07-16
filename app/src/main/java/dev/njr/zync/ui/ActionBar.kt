package dev.njr.zync.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import dev.njr.zync.R
import dev.njr.zync.launcher.BarApp
import dev.njr.zync.launcher.BarRole

/** What a bar tap asks the host to do (the host owns intents/screens). */
enum class BarAction { Messages, Calendar, Phone, Capture }

// Minimal palette, matching the :web dark theme (pico dark tokens).
private val BarBackground = Color(0xFF13171F)
private val BarBorder = Color(0xFF2A313C)
private val BarMuted = Color(0xFF8A91A0)
private val BarInk = Color(0xFFC2C7D0)
private val BarCard = Color(0xFF1A212B)
private val BarAccent = Color(0xFF4A90C2)

/**
 * The launcher action bar (spec L1): Messages · Calendar · Phone · Search · Capture.
 * Messages/Calendar honor the user's configured app lists (settings screen): plain
 * tap = the first (primary) app; long-press → slide → release picks from the
 * submenu, whose last row opens the settings editor. Any decisive horizontal swipe
 * opens search. The bar owns the nav-bar strip.
 */
@Composable
fun ZyncActionBar(
    onAction: (BarAction) -> Unit,
    onSearch: () -> Unit = {},
    barApps: (BarRole) -> List<BarApp> = { emptyList() },
    onLaunchApp: (BarApp) -> Unit = {},
    onEditRole: (BarRole) -> Unit = {},
) {
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
                    onDragEnd = { if (kotlin.math.abs(total) > threshold) onSearch() },
                )
            }
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(Modifier.fillMaxWidth().height(1.dp).background(BarBorder)) {}
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            configurableSlot(R.drawable.ic_messages, BarRole.Messages, Modifier.weight(1f), barApps, onLaunchApp, onEditRole) {
                onAction(BarAction.Messages)
            }
            configurableSlot(R.drawable.ic_calendar, BarRole.Calendar, Modifier.weight(1f), barApps, onLaunchApp, onEditRole) {
                onAction(BarAction.Calendar)
            }
            barSlot(R.drawable.ic_phone, "Phone", Modifier.weight(1f)) { onAction(BarAction.Phone) }
            barSlot(R.drawable.ic_search, "Search", Modifier.weight(1f)) { onSearch() }
            barSlot(R.drawable.ic_capture, "Capture", Modifier.weight(1f)) { onAction(BarAction.Capture) }
        }
    }
}

/** A slot with a user-configured app list: tap = primary; long-press+slide = submenu. */
@Composable
private fun configurableSlot(
    @DrawableRes icon: Int,
    role: BarRole,
    modifier: Modifier,
    barApps: (BarRole) -> List<BarApp>,
    onLaunchApp: (BarApp) -> Unit,
    onEditRole: (BarRole) -> Unit,
    fallback: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var hovered by remember { mutableIntStateOf(-1) }
    val itemHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.toPx() }
    val apps = barApps(role)

    Column(
        modifier
            .pointerInput(role, apps) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { menuOpen = true; hovered = -1 },
                    onDrag = { change, _ ->
                        change.consume()
                        // Items stack upward from the slot: row 0 sits just above the bar.
                        val dy = change.position.y
                        hovered = if (dy < 0) ((-dy) / itemHeightPx).toInt() else -1
                    },
                    onDragEnd = {
                        val rows = apps.size + 1 // +1 = Edit… row
                        if (hovered in 0 until rows) {
                            // Rows render top-down: apps first, Edit… last; hovered counts
                            // from the BOTTOM (closest to the finger) — invert.
                            val index = rows - 1 - hovered
                            if (index < apps.size) onLaunchApp(apps[index]) else onEditRole(role)
                        }
                        menuOpen = false
                        hovered = -1
                    },
                    onDragCancel = { menuOpen = false; hovered = -1 },
                )
            }
            .clickable { apps.firstOrNull()?.let(onLaunchApp) ?: fallback() }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = role.title,
            modifier = Modifier.size(30.dp),
            colorFilter = ColorFilter.tint(BarMuted),
        )
        BasicText(role.title, style = TextStyle(color = BarMuted, fontSize = 11.sp))

        if (menuOpen) {
            val rows = apps.size + 1
            Popup(offset = IntOffset(0, (-(rows * 44 + 56) * androidx.compose.ui.platform.LocalDensity.current.density).toInt())) {
                Column(
                    Modifier
                        .background(BarCard, RoundedCornerShape(12.dp))
                        .border(1.dp, BarBorder, RoundedCornerShape(12.dp))
                        .width(200.dp),
                ) {
                    apps.forEachIndexed { i, app ->
                        MenuRow(if (i == 0) "${app.label} ●" else app.label, hot = hovered == rows - 1 - i)
                    }
                    MenuRow("Edit…", hot = hovered == 0) // bottom row, nearest the finger
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, hot: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (hot) BarAccent.copy(alpha = .25f) else BarCard)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = TextStyle(color = if (hot) BarInk else BarMuted, fontSize = 14.sp, fontWeight = if (hot) FontWeight.SemiBold else FontWeight.Normal))
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

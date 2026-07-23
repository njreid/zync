package dev.njr.zync.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import dev.njr.zync.home.AgendaRow
import dev.njr.zync.ui.BigShoulders
import dev.njr.zync.ui.CharonMono
import dev.njr.zync.ui.ZyncSans
import dev.njr.zync.ui.ZyncColors as C
import dev.njr.zync.web.content.ContextView

/** Everything the home screen renders; assembled by the host (MainActivity). */
data class HomeState(
    val clockHours: String,
    val clockMinutes: String,
    val dateLine: String,
    val weatherLine: String?, // null = tappable "enable weather"
    val contextName: String?, // null = all contexts
    val contexts: List<ContextView>,
    val inboxCount: Int,
    val todayCount: Int,
    val waitingCount: Int,
    val sync: dev.njr.zync.sync.SyncState,
    val agenda: dev.njr.zync.home.AgendaView,
    val calendarPermission: Boolean,
    val notificationsEnabled: Boolean,
)

/** Which display box is selected (drives the WebView destination on tap). */
enum class HomeTile { Inbox, Today, Waiting, Sync }

@Composable
fun HomeScreen(
    state: HomeState,
    onTileTap: (HomeTile) -> Unit,
    onContextSelect: (ContextView?) -> Unit,
    onCompleteTask: (dev.njr.zync.web.content.NodeView) -> Unit,
    onEnableWeather: () -> Unit,
    onEnableCalendar: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenGoogleSearch: () -> Unit = {},
    onOpenEvent: (dev.njr.zync.home.CalEvent) -> Unit = {},
    onEnableNotifications: () -> Unit = {},
    onSwipeLaunch: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(C.Surface)
            // Home gestures (device-tuned): rightward drag / hard-left-edge = the swipe app (Newz);
            // leftward drag / right edge = Google web search. (Swipe-up = our drawer, handled by the
            // OS home gesture in MainActivity.handleHomeIntent, so no vertical detector here.)
            .pointerInput(Unit) {
                val threshold = 64.dp.toPx()
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { _, dx -> total += dx },
                    onDragEnd = {
                        if (total > threshold) onSwipeLaunch()
                        else if (total < -threshold) onOpenGoogleSearch()
                    },
                )
            },
        // Swipe-up = our custom drawer, but that's the OS home gesture (handled in
        // MainActivity.handleHomeIntent) — no in-content vertical detector here, so the agenda scrolls.
    ) {
        TileRow(state, onTap = onTileTap)
        Hero(state, onContextSelect, onEnableWeather)
        Agenda(state, onCompleteTask, onEnableCalendar, onOpenEvent, onEnableNotifications)
    }
}

// ---- display boxes: just below the status-bar line (device feedback) --------------

@Composable
private fun TileRow(state: HomeState, onTap: (HomeTile) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tile(HomeTile.Inbox, state.inboxCount, "unsorted", Modifier.weight(1f)) { onTap(HomeTile.Inbox) }
        Tile(HomeTile.Today, state.todayCount, "due", Modifier.weight(1f)) { onTap(HomeTile.Today) }
        Tile(HomeTile.Waiting, state.waitingCount, "waiting", Modifier.weight(1f)) { onTap(HomeTile.Waiting) }
        SyncTile(state.sync, Modifier.weight(1f)) { onTap(HomeTile.Sync) }
    }
}

@Composable
private fun Tile(tile: HomeTile, count: Int, hint: String, modifier: Modifier, onTap: () -> Unit) {
    Column(
        modifier
            .border(1.dp, if (tile == HomeTile.Inbox) C.Accent else C.Border, RoundedCornerShape(10.dp))
            .background(C.Card, RoundedCornerShape(10.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        BasicText(tile.name, style = TextStyle(color = C.Ink2, fontSize = 11.sp, fontFamily = ZyncSans))
        BasicText(
            "$count",
            style = TextStyle(color = C.Ink, fontSize = 20.sp, fontFamily = ZyncSans, fontWeight = FontWeight.SemiBold),
        )
        BasicText(hint, style = TextStyle(color = C.Ink3, fontSize = 10.sp, fontFamily = ZyncSans))
    }
}

/**
 * The sync-status box: a glyph per state. Tap routing lives in the host (pair /
 * network settings / event log, by state).
 */
@Composable
private fun SyncTile(state: dev.njr.zync.sync.SyncState, modifier: Modifier, onTap: () -> Unit) {
    val (glyph, hint, tone) = when (state) {
        dev.njr.zync.sync.SyncState.Connected -> Triple("\u2713", "ok", C.Ink)
        dev.njr.zync.sync.SyncState.Syncing -> Triple("\u27F3", "syncing", C.Accent)
        dev.njr.zync.sync.SyncState.ServerUnreachable -> Triple("\u26A0", "server?", C.Accent)
        dev.njr.zync.sync.SyncState.NoNetwork -> Triple("\u2298", "offline", C.Ink2)
        dev.njr.zync.sync.SyncState.Unpaired -> Triple("\u25CC", "unpaired", C.Accent)
    }
    Column(
        modifier
            .border(1.dp, if (state == dev.njr.zync.sync.SyncState.Connected) C.Border else C.Accent, RoundedCornerShape(10.dp))
            .background(C.Card, RoundedCornerShape(10.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        BasicText("Sync", style = TextStyle(color = C.Ink2, fontSize = 11.sp, fontFamily = ZyncSans))
        BasicText(glyph, style = TextStyle(color = tone, fontSize = 20.sp, fontWeight = FontWeight.SemiBold))
        BasicText(hint, style = TextStyle(color = C.Ink3, fontSize = 10.sp, fontFamily = ZyncSans))
    }
}

// ---- hero: clock · context · date · weather --------------------------------------

@Composable
private fun Hero(state: HomeState, onContextSelect: (ContextView?) -> Unit, onEnableWeather: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    // Tight horizontal padding: the clock + 5-glyph context must share one line (no wrap).
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.Bottom) {
                // 24h, no colon: solid hours, outlined minutes (device feedback 2026-07-16).
                BasicText(
                    state.clockHours,
                    style = TextStyle(color = C.Ink, fontSize = 78.sp, fontFamily = BigShoulders, fontWeight = FontWeight.Black),
                )
                BasicText(
                    state.clockMinutes,
                    style = TextStyle(
                        color = C.Ink,
                        fontSize = 78.sp,
                        fontFamily = BigShoulders,
                        fontWeight = FontWeight.Black,
                        drawStyle = Stroke(width = 4f),
                    ),
                )
            }
            Box {
                // @ + first 4 chars (contexts keep their first four unique by convention);
                // smaller than the clock so five glyphs always fit beside it.
                BasicText(
                    "@" + (state.contextName ?: "all").take(4),
                    style = TextStyle(color = C.Ink2, fontSize = 48.sp, fontFamily = BigShoulders, fontWeight = FontWeight.Black),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.clickable { menuOpen = true },
                )
                if (menuOpen) {
                    Popup(onDismissRequest = { menuOpen = false }) {
                        Column(
                            Modifier
                                .background(C.Card, RoundedCornerShape(10.dp))
                                .border(1.dp, C.Border, RoundedCornerShape(10.dp))
                                .padding(vertical = 4.dp),
                        ) {
                            ContextMenuRow("@all", state.contextName == null) { menuOpen = false; onContextSelect(null) }
                            state.contexts.forEach { c ->
                                ContextMenuRow(
                                    "@${(c.name ?: "unnamed").removePrefix("@")}",
                                    state.contextName == (c.name ?: "").removePrefix("@"),
                                ) { menuOpen = false; onContextSelect(c) }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicText(state.dateLine, style = TextStyle(color = C.Ink2, fontSize = 16.sp, fontFamily = ZyncSans))
            // Always tappable: enables permission the first time, retries the fetch after.
            BasicText(
                state.weatherLine ?: "enable weather",
                style = TextStyle(
                    color = if (state.weatherLine == null || state.weatherLine.startsWith("retry")) C.Ink3 else C.Ink2,
                    fontSize = 16.sp,
                    fontFamily = ZyncSans,
                    textDecoration = if (state.weatherLine == null) TextDecoration.Underline else null,
                ),
                modifier = Modifier.clickable(onClick = onEnableWeather),
            )
        }
    }
}

@Composable
private fun ContextMenuRow(label: String, selected: Boolean, onTap: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(
            color = if (selected) C.Ink else C.Ink2,
            fontSize = 17.sp,
            fontFamily = CharonMono,
            fontWeight = FontWeight.Bold,
        ),
        modifier = Modifier.clickable(onClick = onTap).padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

// ---- agenda ----------------------------------------------------------------------

@Composable
private fun Agenda(
    state: HomeState,
    onCompleteTask: (dev.njr.zync.web.content.NodeView) -> Unit,
    onEnableCalendar: () -> Unit,
    onOpenEvent: (dev.njr.zync.home.CalEvent) -> Unit,
    onEnableNotifications: () -> Unit,
) {
    val agenda = state.agenda
    if (!state.calendarPermission) {
        BasicText(
            "Grant calendar access to see your agenda",
            style = TextStyle(color = C.Ink3, fontSize = 14.sp, fontFamily = ZyncSans, textDecoration = TextDecoration.Underline),
            modifier = Modifier.clickable(onClick = onEnableCalendar).padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 18.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp)) {
        // 1. all-day events: their own discrete section on top
        if (agenda.allDay.isNotEmpty()) {
            items(agenda.allDay.size) { i ->
                val e = agenda.allDay[i]
                Row(Modifier.fillMaxWidth().clickable { onOpenEvent(e) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("all day", style = TextStyle(color = C.Ink3, fontSize = 12.sp, fontFamily = ZyncSans), modifier = Modifier.width(58.dp))
                    ProfileBar(e, 1f)
                    BasicText(e.title, style = TextStyle(color = C.Ink2, fontSize = 14.sp, fontFamily = ZyncSans), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                }
            }
            item(key = "allday-divider") {
                Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border))
            }
        }

        // 2. free-time header + doable tasks (only when not inside an event)
        if (agenda.free) {
            item(key = "free-header") {
                BasicText(
                    agenda.freeMinutes?.let { "free for $it min" } ?: "free",
                    style = TextStyle(color = C.Accent, fontSize = 13.sp, fontFamily = CharonMono, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            items(agenda.suggestions.size) { i ->
                val task = agenda.suggestions[i]
                Row(
                    Modifier.fillMaxWidth().clickable { onCompleteTask(task) }.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(16.dp).border(1.5.dp, C.Ink3, RoundedCornerShape(5.dp)))
                    BasicText(task.title ?: "(untitled)", style = TextStyle(color = C.Ink, fontSize = 15.sp, fontFamily = ZyncSans))
                }
            }
        }

        // 3. the timed events with the NOW divider
        items(agenda.rows.size) { i ->
            when (val row = agenda.rows[i]) {
                is AgendaRow.Event -> EventRow(row, onOpenEvent)
                is AgendaRow.Now -> NowRow()
            }
        }

        // 4. the look-ahead: subsequent days (label + their events, dimmed)
        agenda.upcoming.forEach { day ->
            item(key = "day-" + day.label) {
                BasicText(
                    day.label,
                    style = TextStyle(color = C.Ink3, fontSize = 13.sp, fontFamily = CharonMono, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
            }
            items(day.allDay.size) { i ->
                val e = day.allDay[i]
                Row(Modifier.fillMaxWidth().clickable { onOpenEvent(e) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("all day", style = TextStyle(color = C.Ink3, fontSize = 12.sp, fontFamily = ZyncSans), modifier = Modifier.width(58.dp))
                    ProfileBar(e, 1f)
                    BasicText(e.title, style = TextStyle(color = C.Ink2, fontSize = 14.sp, fontFamily = ZyncSans), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                }
            }
            items(day.timed.size) { i ->
                EventRow(AgendaRow.Event(day.timed[i], past = false, startingSoon = false), onOpenEvent)
            }
        }

        if (!state.notificationsEnabled) {
            item(key = "notif-enable") {
                BasicText(
                    "Enable notification access for non-calendar reminders",
                    style = TextStyle(color = C.Ink3, fontSize = 12.sp, fontFamily = ZyncSans, textDecoration = TextDecoration.Underline),
                    modifier = Modifier.clickable(onClick = onEnableNotifications).padding(vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ProfileBar(event: dev.njr.zync.home.CalEvent, alpha: Float) {
    Box(
        Modifier
            .width(7.dp)
            .height(20.dp)
            .background(
                (if (event.profile == dev.njr.zync.home.CalEvent.Profile.WORK) C.Work else C.Home).copy(alpha = alpha),
                RoundedCornerShape(2.dp),
            ),
    )
}

@Composable
private fun EventRow(row: AgendaRow.Event, onOpenEvent: (dev.njr.zync.home.CalEvent) -> Unit) {
    val alpha = if (row.past) 0.45f else 1f
    // Starting within five minutes: inverted (light background, dark text).
    val inverted = row.startingSoon
    val bg = if (inverted) C.Ink else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (inverted) C.Surface else C.Ink.copy(alpha = alpha)
    val fg2 = if (inverted) C.Surface.copy(alpha = .7f) else C.Ink2.copy(alpha = alpha)
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .clickable { onOpenEvent(row.event) }
            .padding(vertical = 8.dp, horizontal = if (inverted) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            formatTime(row.event.beginMillis),
            style = TextStyle(color = fg2, fontSize = 14.sp, fontFamily = ZyncSans),
            modifier = Modifier.width(58.dp),
        )
        ProfileBar(row.event, alpha)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            BasicText(
                row.event.title,
                style = TextStyle(color = fg, fontSize = 15.sp, fontFamily = ZyncSans, fontWeight = if (inverted) FontWeight.SemiBold else FontWeight.Normal),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Location under the title, quieter; a URL inside becomes a "join" link.
            val join = row.event.joinUrl
            val locContext = LocalContext.current
            when {
                join != null -> BasicText(
                    "join",
                    style = TextStyle(color = C.Accent.copy(alpha = alpha), fontSize = 12.sp, fontFamily = ZyncSans, textDecoration = TextDecoration.Underline),
                    modifier = Modifier.clickable {
                        runCatching {
                            locContext.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(join)),
                            )
                        }
                    },
                )
                row.event.location != null -> BasicText(
                    row.event.location!!,
                    style = TextStyle(color = fg2, fontSize = 12.sp, fontFamily = ZyncSans),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BasicText(
            when {
                row.event.fromNotification -> "Notif"
                row.event.profile == dev.njr.zync.home.CalEvent.Profile.WORK -> "Work"
                else -> "Home"
            },
            style = TextStyle(color = fg2, fontSize = 11.sp, fontFamily = CharonMono, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun NowRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(C.Accent.copy(alpha = .5f)))
        BasicText("now", style = TextStyle(color = C.Accent, fontSize = 11.sp, fontFamily = ZyncSans), modifier = Modifier.padding(horizontal = 8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(C.Accent.copy(alpha = .5f)))
    }
}

private fun formatTime(millis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    return "$h:${if (m < 10) "0$m" else "$m"}"
}

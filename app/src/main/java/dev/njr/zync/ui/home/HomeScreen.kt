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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import dev.njr.zync.home.AgendaRow
import dev.njr.zync.ui.BigShoulders
import dev.njr.zync.ui.CharonMono
import dev.njr.zync.ui.Geomini
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
    val nextCount: Int,
    val waitingCount: Int,
    val agenda: List<AgendaRow>,
    val calendarPermission: Boolean,
)

/** Which display box is selected (drives the WebView destination on tap). */
enum class HomeTile { Inbox, Today, Next, Waiting }

@Composable
fun HomeScreen(
    state: HomeState,
    onTileTap: (HomeTile) -> Unit,
    onContextSelect: (ContextView?) -> Unit,
    onCompleteTask: (dev.njr.zync.web.content.NodeView) -> Unit,
    onEnableWeather: () -> Unit,
    onEnableCalendar: () -> Unit,
    onOpenSearch: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(C.Surface)
            // Swipe from the left (rightward drag) anywhere on the home surface = search.
            .pointerInput(Unit) {
                val threshold = 64.dp.toPx()
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { _, dx -> total += dx },
                    onDragEnd = { if (total > threshold) onOpenSearch() },
                )
            },
    ) {
        TileRow(state, onTap = onTileTap)
        Hero(state, onContextSelect, onEnableWeather)
        Agenda(state, onCompleteTask, onEnableCalendar)
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
        Tile(HomeTile.Next, state.nextCount, "actions", Modifier.weight(1f)) { onTap(HomeTile.Next) }
        Tile(HomeTile.Waiting, state.waitingCount, "waiting", Modifier.weight(1f)) { onTap(HomeTile.Waiting) }
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
        BasicText(tile.name, style = TextStyle(color = C.Ink2, fontSize = 11.sp, fontFamily = Geomini))
        BasicText(
            "$count",
            style = TextStyle(color = C.Ink, fontSize = 20.sp, fontFamily = Geomini, fontWeight = FontWeight.SemiBold),
        )
        BasicText(hint, style = TextStyle(color = C.Ink3, fontSize = 10.sp, fontFamily = Geomini))
    }
}

// ---- hero: clock · context · date · weather --------------------------------------

@Composable
private fun Hero(state: HomeState, onContextSelect: (ContextView?) -> Unit, onEnableWeather: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
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
                    style = TextStyle(color = C.Ink2, fontSize = 54.sp, fontFamily = BigShoulders, fontWeight = FontWeight.Black),
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
            BasicText(state.dateLine, style = TextStyle(color = C.Ink2, fontSize = 16.sp, fontFamily = Geomini))
            BasicText(
                state.weatherLine ?: "enable weather",
                style = TextStyle(
                    color = if (state.weatherLine == null) C.Ink3 else C.Ink2,
                    fontSize = 16.sp,
                    fontFamily = Geomini,
                    textDecoration = if (state.weatherLine == null) TextDecoration.Underline else null,
                ),
                modifier = if (state.weatherLine == null) Modifier.clickable(onClick = onEnableWeather) else Modifier,
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
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        BasicText(
            "TODAY",
            style = TextStyle(color = C.Ink2, fontSize = 12.sp, fontFamily = Geomini, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
        )
    }
    if (!state.calendarPermission) {
        BasicText(
            "Grant calendar access to see your agenda",
            style = TextStyle(color = C.Ink3, fontSize = 14.sp, fontFamily = Geomini, textDecoration = TextDecoration.Underline),
            modifier = Modifier.clickable(onClick = onEnableCalendar).padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 18.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp)) {
        items(state.agenda.size) { i ->
            when (val row = state.agenda[i]) {
                is AgendaRow.Event -> EventRow(row)
                is AgendaRow.Now -> NowRow()
                is AgendaRow.Gap -> GapRow(row, onCompleteTask)
            }
        }
    }
}

@Composable
private fun EventRow(row: AgendaRow.Event) {
    val alpha = if (row.past) 0.45f else 1f
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            formatTime(row.event.beginMillis),
            style = TextStyle(color = C.Ink2.copy(alpha = alpha), fontSize = 14.sp, fontFamily = Geomini),
            modifier = Modifier.width(58.dp),
        )
        Box(
            Modifier
                .width(7.dp)
                .height(20.dp)
                .background(
                    (if (row.event.profile == dev.njr.zync.home.CalEvent.Profile.WORK) C.Work else C.Home).copy(alpha = alpha),
                    RoundedCornerShape(2.dp),
                ),
        )
        BasicText(
            row.event.title,
            style = TextStyle(color = C.Ink.copy(alpha = alpha), fontSize = 15.sp, fontFamily = Geomini),
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        BasicText(
            if (row.event.profile == dev.njr.zync.home.CalEvent.Profile.WORK) "Work" else "Home",
            style = TextStyle(color = C.Ink2.copy(alpha = alpha), fontSize = 11.sp, fontFamily = CharonMono, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun NowRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(C.Accent.copy(alpha = .5f)))
        BasicText("now", style = TextStyle(color = C.Accent, fontSize = 11.sp, fontFamily = Geomini), modifier = Modifier.padding(horizontal = 8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(C.Accent.copy(alpha = .5f)))
    }
}

@Composable
private fun GapRow(row: AgendaRow.Gap, onComplete: (dev.njr.zync.web.content.NodeView) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 66.dp, top = 6.dp, bottom = 6.dp)
            .border(1.dp, C.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicText(
            "${row.minutes} min until ${row.nextTitle}:",
            style = TextStyle(color = C.Ink3, fontSize = 12.sp, fontFamily = Geomini),
        )
        row.tasks.forEach { task ->
            Row(
                Modifier.fillMaxWidth().clickable { onComplete(task) }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(16.dp).border(1.5.dp, C.Ink3, RoundedCornerShape(5.dp)))
                BasicText(task.title ?: "(untitled)", style = TextStyle(color = C.Ink, fontSize = 15.sp, fontFamily = Geomini))
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    return "$h:${if (m < 10) "0$m" else "$m"}"
}

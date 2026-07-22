package dev.njr.zync.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.njr.zync.launcher.AppLaunch
import dev.njr.zync.launcher.AppSearch
import dev.njr.zync.launcher.BarApp
import dev.njr.zync.launcher.BarApps
import dev.njr.zync.launcher.BarRole
import dev.njr.zync.launcher.ContextApps
import dev.njr.zync.ui.ZyncSans
import dev.njr.zync.ui.ZyncColors as C

/** The configurable behaviors the settings screen edits. */
enum class BarTab(val title: String) {
    Messages("Messages"),
    Calendar("Calendar"),
    Context("Context"),
    Swipe("Swipe"),
    Agenda("Agenda"),
}

/**
 * Native settings for the action bar: Messages/Calendar app lists (FIRST = plain
 * tap; the rest = long-press → slide → release submenu), the center slot's app
 * per GTD context (defaults: @town→Maps, @home→Google Home, @work→work Drive),
 * and the app a right swipe launches (default: Harmonic).
 */
@Composable
fun BarSettingsScreen(initialTab: BarTab, contexts: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(initialTab) }
    var adding by remember { mutableStateOf(false) } // list tabs: picker open
    var pickingFor by remember { mutableStateOf<String?>(null) } // context-name / "swipe"
    var tick by remember { mutableIntStateOf(0) } // bump after any save

    BackHandler(onBack = {
        when {
            adding -> adding = false
            pickingFor != null -> pickingFor = null
            else -> onDismiss()
        }
    })

    Column(
        Modifier
            .fillMaxSize()
            .background(C.Surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("Settings", style = TextStyle(color = C.Ink, fontSize = 17.sp, fontFamily = ZyncSans, fontWeight = FontWeight.SemiBold))
            BasicText("✕", style = TextStyle(color = C.Ink3, fontSize = 20.sp), modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarTab.entries.forEach { t ->
                BasicText(
                    t.title,
                    style = TextStyle(
                        color = if (t == tab) C.Surface else C.Ink2,
                        fontSize = 13.sp,
                        fontFamily = ZyncSans,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier
                        .background(if (t == tab) C.Ink else C.Surface, RoundedCornerShape(999.dp))
                        .border(1.dp, if (t == tab) C.Ink else C.Border, RoundedCornerShape(999.dp))
                        .clickable { tab = t; adding = false; pickingFor = null }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        when (tab) {
            BarTab.Messages, BarTab.Calendar -> {
                val role = if (tab == BarTab.Messages) BarRole.Messages else BarRole.Calendar
                key(role, tick) { RoleList(role, adding, onAdding = { adding = it }, onSaved = { tick++ }) }
            }
            BarTab.Context -> {
                val target = pickingFor
                if (target != null) {
                    AppPicker(exclude = emptySet(), onPick = { picked ->
                        ContextApps.save(context, target, picked)
                        pickingFor = null
                        tick++
                    })
                } else {
                    BasicText(
                        "The bar's center slot launches this app for the active context",
                        style = TextStyle(color = C.Ink3, fontSize = 12.sp, fontFamily = ZyncSans),
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                    val explicit = remember(tick) { ContextApps.explicit(context) }
                    LazyColumn(Modifier.weight(1f)) {
                        items(contexts, key = { it }) { name ->
                            val at = "@" + name.removePrefix("@")
                            val chosen = explicit[at]
                            val effective = chosen ?: ContextApps.defaultFor(context, at)
                            Row(
                                Modifier.fillMaxWidth().clickable { pickingFor = at }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AppIcon(effective)
                                Column(Modifier.weight(1f)) {
                                    BasicText(at, style = TextStyle(color = C.Ink, fontSize = 15.sp, fontFamily = ZyncSans))
                                    BasicText(
                                        when {
                                            chosen != null -> chosen.label + workTag(chosen)
                                            effective != null -> "default: ${effective.label}" + workTag(effective)
                                            else -> "tap to choose an app"
                                        },
                                        style = TextStyle(color = C.Ink3, fontSize = 12.sp, fontFamily = ZyncSans),
                                    )
                                }
                                if (chosen != null) {
                                    RowButton("✕") { ContextApps.save(context, at, null); tick++ }
                                }
                            }
                        }
                    }
                }
            }
            BarTab.Agenda -> {
                BasicText(
                    "Calendars shown on the agenda",
                    style = TextStyle(color = C.Ink3, fontSize = 12.sp, fontFamily = ZyncSans),
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                val cals = remember { dev.njr.zync.home.CalendarSource.availableCalendars(context) }
                val excluded = remember(tick) { dev.njr.zync.home.CalendarChoices.excluded(context) }
                if (cals.isEmpty()) {
                    BasicText(
                        "No calendars (grant calendar access from the home agenda first)",
                        style = TextStyle(color = C.Ink3, fontSize = 13.sp, fontFamily = ZyncSans),
                    )
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(cals, key = { it.id }) { cal ->
                            val on = cal.id !in excluded
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        dev.njr.zync.home.CalendarChoices.setExcluded(
                                            context,
                                            if (on) excluded + cal.id else excluded - cal.id,
                                        )
                                        tick++
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                BasicText(
                                    if (on) "\u2611" else "\u2610",
                                    style = TextStyle(color = if (on) C.Accent else C.Ink3, fontSize = 18.sp),
                                )
                                Column(Modifier.weight(1f)) {
                                    BasicText(cal.name, style = TextStyle(color = C.Ink, fontSize = 15.sp, fontFamily = ZyncSans))
                                    if (cal.account.isNotBlank()) {
                                        BasicText(cal.account, style = TextStyle(color = C.Ink3, fontSize = 12.sp, fontFamily = ZyncSans))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            BarTab.Swipe -> {
                if (pickingFor == "swipe") {
                    AppPicker(exclude = emptySet(), onPick = { picked ->
                        ContextApps.saveSwipe(context, picked)
                        pickingFor = null
                        tick++
                    })
                } else {
                    BasicText(
                        "Swiping from the right opens the newz feed by default — or pick an app instead; from the left opens search",
                        style = TextStyle(color = C.Ink3, fontSize = 12.sp, fontFamily = ZyncSans),
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                    val app = remember(tick) { ContextApps.swipeApp(context) }
                    Row(
                        Modifier.fillMaxWidth().clickable { pickingFor = "swipe" }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppIcon(app)
                        BasicText(
                            app?.let { it.label + workTag(it) } ?: "Newz feed · built-in",
                            style = TextStyle(color = C.Ink, fontSize = 15.sp, fontFamily = ZyncSans),
                            modifier = Modifier.weight(1f),
                        )
                        if (app != null) RowButton("✕") { ContextApps.saveSwipe(context, null); tick++ }
                    }
                }
            }
        }
    }
}

/** The Messages/Calendar ordered list (● first = primary tap). */
@Composable
private fun RoleList(role: BarRole, adding: Boolean, onAdding: (Boolean) -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    var apps by remember(role) { mutableStateOf(BarApps.load(context, role)) }

    fun persist(next: List<BarApp>) {
        apps = next
        BarApps.save(context, role, next)
        onSaved()
    }

    if (adding) {
        AppPicker(
            exclude = apps.map { it.packageName + it.activityName + (it.userSerial ?: 0) }.toSet(),
            onPick = { picked -> persist(apps + picked); onAdding(false) },
        )
    } else {
        BasicText(
            "First app = tap · rest = long-press, slide, release",
            style = TextStyle(color = C.Ink3, fontSize = 12.sp, fontFamily = ZyncSans),
            modifier = Modifier.padding(vertical = 10.dp),
        )
        LazyColumn {
            items(apps.size) { i ->
                val app = apps[i]
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppIcon(app)
                    BasicText(
                        (if (i == 0) "● ${app.label}" else app.label) + workTag(app),
                        style = TextStyle(color = workInk(app, C.Ink), fontSize = 15.sp, fontFamily = ZyncSans),
                        modifier = Modifier.weight(1f),
                    )
                    if (i > 0) RowButton("↑") { persist(apps.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                    if (i < apps.size - 1) RowButton("↓") { persist(apps.toMutableList().apply { add(i + 1, removeAt(i)) }) }
                    RowButton("✕") { persist(apps.toMutableList().apply { removeAt(i) }) }
                }
            }
            item(key = "add") {
                BasicText(
                    "+ Add app",
                    style = TextStyle(color = C.Accent, fontSize = 15.sp, fontFamily = ZyncSans),
                    modifier = Modifier.clickable { onAdding(true) }.padding(vertical = 12.dp),
                )
            }
        }
    }
}

/** " · work" marker — the badge on the icon is easy to miss at list sizes. */
private fun workTag(app: BarApp): String = if (app.userSerial != null) " · work" else ""

/** Work-profile rows read in a blue-leaning ink. */
internal fun workInk(app: BarApp?, default: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
    if (app?.userSerial != null) androidx.compose.ui.graphics.Color(0xFF9FBEE3) else default

@Composable
private fun AppIcon(app: BarApp?) {
    val context = LocalContext.current
    val bitmap: ImageBitmap? = remember(app) {
        app?.let {
            AppLaunch.icon(context, it.packageName, it.activityName, it.userSerial)
                ?.toBitmap(96, 96)?.asImageBitmap()
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(34.dp), colorFilter = dev.njr.zync.ui.workIconFilter(app?.userSerial))
    } else {
        Spacer(Modifier.size(34.dp))
    }
}

@Composable
private fun RowButton(glyph: String, onTap: () -> Unit) {
    BasicText(
        glyph,
        style = TextStyle(color = C.Ink2, fontSize = 16.sp),
        modifier = Modifier
            .border(1.dp, C.Border, RoundedCornerShape(8.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun AppPicker(exclude: Set<String>, onPick: (BarApp) -> Unit) {
    val context = LocalContext.current
    val all = remember { AppSearch.launchableApps(context) }
    var query by remember { mutableStateOf("") }
    BasicTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        textStyle = TextStyle(color = C.Ink, fontSize = 15.sp, fontFamily = ZyncSans),
        cursorBrush = SolidColor(C.Ink),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .background(C.Card, RoundedCornerShape(10.dp))
            .padding(12.dp),
    )
    LazyColumn {
        items(
            AppSearch.filter(all, query).filterNot { it.packageName + it.activityName + (it.userSerial ?: 0) in exclude },
            key = { it.packageName + it.activityName + (it.userSerial ?: 0) },
        ) { app ->
            val bar = BarApp(app.label, app.packageName, app.activityName, app.userSerial)
            Row(
                Modifier.fillMaxWidth().clickable { onPick(bar) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(bar)
                BasicText(
                    app.label + workTag(bar),
                    style = TextStyle(color = workInk(bar, C.Ink), fontSize = 15.sp, fontFamily = ZyncSans),
                )
            }
        }
    }
}

package dev.njr.zync.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
fun BarSettingsScreen(
    initialTab: BarTab,
    contexts: List<String>,
    onDismiss: () -> Unit,
    eventSources: List<Pair<String, String>> = emptyList(),
) {
    val context = LocalContext.current
    // One long scrollable page (initialTab is ignored now that there are no tabs). Choosing an app
    // for any slot opens the shared AppPicker as a full-screen overlay; `picker` says which slot.
    @Suppress("UNUSED_PARAMETER") val ignoredTab = initialTab
    var picker by remember { mutableStateOf<SettingsPick?>(null) }
    var tick by remember { mutableIntStateOf(0) } // bump after any save

    BackHandler(onBack = { if (picker != null) picker = null else onDismiss() })

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

        val p = picker
        if (p != null) {
            AppPicker(
                exclude = if (p is PickRole) {
                    BarApps.load(context, p.role).map { it.packageName + it.activityName + (it.userSerial ?: 0) }.toSet()
                } else {
                    emptySet()
                },
                onPick = { picked ->
                    when (p) {
                        is PickRole -> BarApps.save(context, p.role, BarApps.load(context, p.role) + picked)
                        is PickContext -> ContextApps.save(context, p.at, picked)
                        PickSwipe -> ContextApps.saveSwipe(context, picked)
                    }
                    picker = null
                    tick++
                },
            )
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()).weight(1f)) {
                // --- Context apps ---
                SettingsSection("Context app — the bar's center slot launches this for the active context")
                val explicit = remember(tick) { ContextApps.explicit(context) }
                contexts.forEach { name ->
                    val at = "@" + name.removePrefix("@")
                    val chosen = explicit[at]
                    val effective = chosen ?: ContextApps.defaultFor(context, at)
                    Row(
                        Modifier.fillMaxWidth().clickable { picker = PickContext(at) }.padding(vertical = 8.dp),
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
                        if (chosen != null) RowButton("✕") { ContextApps.save(context, at, null); tick++ }
                    }
                }

                // --- Calendar / Messages bar apps ---
                SettingsSection("Calendar bar app — first = tap, rest = long-press, slide, release")
                RoleList(BarRole.Calendar, tick) { picker = PickRole(BarRole.Calendar) }
                SettingsSection("Messages bar app")
                RoleList(BarRole.Messages, tick) { picker = PickRole(BarRole.Messages) }

                // --- Agenda ---
                SettingsSection("Calendars shown on the agenda")
                val cals = remember { dev.njr.zync.home.CalendarSource.availableCalendars(context) }
                val excluded = remember(tick) { dev.njr.zync.home.CalendarChoices.excluded(context) }
                val hidden = remember(tick) { dev.njr.zync.home.EventFilters.hidden(context) }
                if (cals.isEmpty()) {
                    BasicText(
                        "No calendars (grant calendar access from the home agenda first)",
                        style = TextStyle(color = C.Ink3, fontSize = 13.sp, fontFamily = ZyncSans),
                    )
                } else {
                    cals.forEach { cal ->
                        val on = cal.id !in excluded
                        CheckRow(on, cal.name, cal.account.takeIf { it.isNotBlank() }) {
                            dev.njr.zync.home.CalendarChoices.setExcluded(context, if (on) excluded + cal.id else excluded - cal.id)
                            tick++
                        }
                    }
                }
                if (eventSources.isNotEmpty()) {
                    SettingsSection("Hide specific events (by calendar + title)")
                    eventSources.forEach { (source, title) ->
                        val k = dev.njr.zync.home.EventFilters.key(source, title)
                        val on = k !in hidden // checked = shown
                        CheckRow(on, title, source) {
                            dev.njr.zync.home.EventFilters.setHidden(context, if (on) hidden + k else hidden - k)
                            tick++
                        }
                    }
                }

                // --- Swipe app ---
                SettingsSection("Swipe-right app — the default is the built-in Newz feed; left opens search")
                val swipeApp = remember(tick) { ContextApps.swipeApp(context) }
                Row(
                    Modifier.fillMaxWidth().clickable { picker = PickSwipe }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppIcon(swipeApp)
                    BasicText(
                        swipeApp?.let { it.label + workTag(it) } ?: "Newz feed · built-in",
                        style = TextStyle(color = C.Ink, fontSize = 15.sp, fontFamily = ZyncSans),
                        modifier = Modifier.weight(1f),
                    )
                    if (swipeApp != null) RowButton("✕") { ContextApps.saveSwipe(context, null); tick++ }
                }
            }
        }
    }
}

/** The Messages/Calendar ordered list (● first = primary tap). */
/** Which bar slot the shared app-picker overlay is choosing for. */
private sealed interface SettingsPick
private data class PickRole(val role: BarRole) : SettingsPick
private data class PickContext(val at: String) : SettingsPick
private data object PickSwipe : SettingsPick

/** A settings section header inside the single scroll page. */
@Composable
private fun SettingsSection(title: String) {
    BasicText(
        title,
        style = TextStyle(color = C.Ink3, fontSize = 12.sp, fontFamily = ZyncSans),
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * The ordered Messages/Calendar app list (● first = primary tap), rendered inline (no LazyColumn,
 * since it lives inside the settings scroll page). [onAdd] opens the shared picker overlay.
 */
@Composable
private fun RoleList(role: BarRole, tick: Int, onAdd: () -> Unit) {
    val context = LocalContext.current
    var apps by remember(role, tick) { mutableStateOf(BarApps.load(context, role)) }
    fun persist(next: List<BarApp>) { apps = next; BarApps.save(context, role, next) }

    apps.forEachIndexed { i, app ->
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
    BasicText(
        "+ Add app",
        style = TextStyle(color = C.Accent, fontSize = 15.sp, fontFamily = ZyncSans),
        modifier = Modifier.clickable(onClick = onAdd).padding(vertical = 12.dp),
    )
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

/** A checkbox row (checked = shown/included); tap toggles. Shared by the calendar + event pickers. */
@Composable
private fun CheckRow(on: Boolean, title: String, subtitle: String?, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(if (on) "☑" else "☐", style = TextStyle(color = if (on) C.Accent else C.Ink3, fontSize = 18.sp))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(color = C.Ink, fontSize = 15.sp, fontFamily = ZyncSans))
            subtitle?.let { BasicText(it, style = TextStyle(color = C.Ink3, fontSize = 12.sp, fontFamily = ZyncSans)) }
        }
    }
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

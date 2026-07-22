package dev.njr.zync.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.njr.zync.launcher.AppEntry
import dev.njr.zync.launcher.AppLaunch
import dev.njr.zync.launcher.AppSearch
import dev.njr.zync.launcher.CalcEval
import dev.njr.zync.launcher.ContactSearch
import dev.njr.zync.launcher.RecentItem
import dev.njr.zync.launcher.SearchHistory

private val OverlayBackground = Color(0xF2000000) // near-opaque pure black (AMOLED)
private val TextPrimary = Color(0xFFC2C7D0)
private val TextMuted = Color(0xFF8A91A0)
private val TextFaint = Color(0xFF5C6470)
private val TextWork = Color(0xFF9FBEE3) // blue-leaning ink: work-profile rows read differently at a glance
private val FieldBackground = Color(0xFF1A212B)

/**
 * The custom multi-search drawer (launcher spec L3 + device feedback): opens on a home swipe-UP.
 * Recent APPS head the drawer as a 4-col icon grid, then every app by launch frequency; the
 * query box sits at the BOTTOM, near the thumb, and (no auto-focus) opens onto the grid. Typing
 * turns it into full multi-search — apps + settings + contacts + a web handoff.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchOverlay(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps = remember { AppSearch.launchableApps(context) }
    var query by remember { mutableStateOf("") }
    var contactsGranted by remember { mutableStateOf(ContactSearch.hasPermission(context)) }
    val contactsPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> contactsGranted = granted }

    // Contacts lookup is a cross-process ContentResolver query — off the main thread, debounced.
    var contacts by remember { mutableStateOf<List<dev.njr.zync.launcher.ContactHit>>(emptyList()) }
    LaunchedEffect(query, contactsGranted) {
        contacts = if (contactsGranted && query.trim().length >= 2) {
            kotlinx.coroutines.delay(120)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ContactSearch.search(context, query) }
        } else {
            emptyList()
        }
    }

    BackHandler(onBack = onDismiss)

    fun launchRecent(item: RecentItem) {
        when (item.kind) {
            RecentItem.Kind.App ->
                AppLaunch.launch(context, AppEntry(item.label, item.packageName!!, item.activityName!!, item.userSerial))
            RecentItem.Kind.Setting ->
                runCatching { context.startActivity(android.content.Intent(item.settingsAction!!).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            RecentItem.Kind.Web ->
                runCatching { context.startActivity(AppSearch.webSearch(item.webQuery ?: item.label)) }
        }
        SearchHistory.recordItem(context, item)
        onDismiss()
    }

    val usage = remember { SearchHistory.usageCounts(context) }
    val recentApps = remember { SearchHistory.recentApps(context).take(8) } // 8 recent apps, newest first
    val appMatches = AppSearch.filter(apps, query, usage) // blank query = all apps, most-launched first
    val settingsMatches = AppSearch.settingsMatches(query)
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val fewLocalMatches = AppSearch.showWebSearch(query, appMatches.size + settingsMatches.size)
    LaunchedEffect(query, fewLocalMatches) {
        // Chrome-style live web suggestions once local matches thin out; debounced.
        if (query.length >= 2 && fewLocalMatches) {
            kotlinx.coroutines.delay(150)
            suggestions = dev.njr.zync.launcher.WebSuggest.fetch(query)
        } else {
            suggestions = emptyList()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(OverlayBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
    ) {
        // TOP: recent apps as a 4-col icon grid (apps only; icons, no text) — blank query only.
        if (query.isBlank() && recentApps.isNotEmpty()) {
            RecentAppsGrid(recentApps) { launchRecent(it) }
        }

        // MIDDLE: results. Blank query → every app by launch frequency; typing → the full
        // multi-search (calc + settings + contacts + web handoff + apps).
        LazyColumn(Modifier.weight(1f)) {
            // 0. inline calculator: "17*23" answers in place; tap copies
            if (CalcEval.looksLikeMath(query)) {
                CalcEval.eval(query)?.let { result ->
                    item(key = "calc") {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("result", result))
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            BasicText("=", style = TextStyle(color = TextFaint, fontSize = 18.sp))
                            BasicText(result, style = TextStyle(color = TextPrimary, fontSize = 19.sp))
                            BasicText("tap to copy", style = TextStyle(color = TextFaint, fontSize = 11.sp))
                        }
                    }
                }
            }
            // 1. web handoff when few local results match
            if (AppSearch.showWebSearch(query, appMatches.size + settingsMatches.size)) {
                items(listOf("web-search")) { _ ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                SearchHistory.recordQuery(context, query)
                                launchRecent(RecentItem(RecentItem.Kind.Web, "Web: $query", webQuery = query))
                            }
                            .padding(vertical = 12.dp),
                    ) {
                        BasicText("Search the web for “$query”", style = TextStyle(color = TextPrimary, fontSize = 16.sp))
                    }
                }
            }
            // 1b. live suggestions (tap = straight to a web search for that text)
            if (query.length >= 2 && fewLocalMatches) {
                items(suggestions.filter { it != query }, key = { "g:$it" }) { sug ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                SearchHistory.recordQuery(context, sug)
                                launchRecent(RecentItem(RecentItem.Kind.Web, sug, webQuery = sug))
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        BasicText("🔎", style = TextStyle(color = TextFaint, fontSize = 15.sp))
                        BasicText(sug, style = TextStyle(color = TextMuted, fontSize = 15.sp))
                    }
                }
            }
            // 2. settings matches
            items(settingsMatches, key = { "s:" + it.action }) { setting ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            SearchHistory.recordQuery(context, query)
                            launchRecent(RecentItem(RecentItem.Kind.Setting, setting.label, settingsAction = setting.action))
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    BasicText("⚙", style = TextStyle(color = TextMuted, fontSize = 20.sp))
                    BasicText(setting.label, style = TextStyle(color = TextPrimary, fontSize = 16.sp))
                }
            }
            // 2b. contacts (once granted; an invite row asks the first time)
            if (query.trim().length >= 2) {
                if (contactsGranted) {
                    items(contacts, key = { "c:" + it.id }) { hit ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { runCatching { context.startActivity(ContactSearch.viewIntent(hit)) }; onDismiss() }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            BasicText("👤", style = TextStyle(color = TextMuted, fontSize = 17.sp))
                            BasicText(hit.name, style = TextStyle(color = TextPrimary, fontSize = 16.sp))
                        }
                    }
                } else {
                    item(key = "contacts-enable") {
                        BasicText(
                            "search contacts too…",
                            style = TextStyle(color = TextFaint, fontSize = 13.sp),
                            modifier = Modifier
                                .clickable { contactsPermission.launch(android.Manifest.permission.READ_CONTACTS) }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
            // 3. apps (all profiles; blank query = every app by frequency, recents included)
            items(appMatches, key = { it.packageName + it.activityName + (it.userSerial ?: 0) }) { app ->
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (query.isNotBlank()) SearchHistory.recordQuery(context, query)
                                launchRecent(
                                    RecentItem(RecentItem.Kind.App, app.label, app.packageName, app.activityName, app.userSerial),
                                )
                            },
                            // The standard launcher escape hatch: App info (uninstall/force-stop).
                            onLongClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            android.net.Uri.parse("package:" + app.packageName),
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AppIcon(app.packageName, app.activityName, app.userSerial)
                    BasicText(
                        app.label,
                        style = TextStyle(color = if (app.userSerial != null) TextWork else TextPrimary, fontSize = 16.sp),
                    )
                }
            }
        }

        // BOTTOM: the multi-search query box, near the thumb. No auto-focus — tap to type.
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onGo = {
                        // Enter = top result: best app, else settings, else web search.
                        val top = AppSearch.filter(apps, query, SearchHistory.usageCounts(context)).firstOrNull()
                        val setting = AppSearch.settingsMatches(query).firstOrNull()
                        when {
                            top != null -> launchRecent(
                                RecentItem(RecentItem.Kind.App, top.label, top.packageName, top.activityName, top.userSerial),
                            )
                            setting != null -> launchRecent(
                                RecentItem(RecentItem.Kind.Setting, setting.label, settingsAction = setting.action),
                            )
                            query.isNotBlank() -> launchRecent(RecentItem(RecentItem.Kind.Web, "Web: $query", webQuery = query))
                        }
                        if (query.isNotBlank()) SearchHistory.recordQuery(context, query)
                    },
                ),
                textStyle = TextStyle(color = TextPrimary, fontSize = 19.sp),
                cursorBrush = SolidColor(TextPrimary),
                modifier = Modifier
                    .weight(1f)
                    .background(FieldBackground)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
            if (query.isNotEmpty()) {
                BasicText(
                    "✕",
                    style = TextStyle(color = TextMuted, fontSize = 16.sp),
                    modifier = Modifier.clickable { query = "" }.padding(6.dp),
                )
            }
            BasicText(
                "Close",
                style = TextStyle(color = TextMuted, fontSize = 14.sp),
                modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
            )
        }
    }
}

/** Recent apps as a 4-column icon grid (icons only, no labels): tap to launch. */
@Composable
private fun RecentAppsGrid(apps: List<RecentItem>, onLaunch: (RecentItem) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        apps.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    Box(Modifier.weight(1f).clickable { onLaunch(item) }, contentAlignment = Alignment.Center) {
                        AppIcon(item.packageName!!, item.activityName!!, item.userSerial, size = 46.dp)
                    }
                }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) {} } // pad the last row to 4 columns
            }
        }
    }
}

@Composable
private fun AppIcon(packageName: String, activityName: String, userSerial: Long?, size: Dp = 34.dp) {
    val context = LocalContext.current
    val icon = remember(packageName, activityName, userSerial) {
        AppLaunch.icon(context, packageName, activityName, userSerial)?.toBitmap(96, 96)?.asImageBitmap()
    }
    icon?.let {
        Image(bitmap = it, contentDescription = null, modifier = Modifier.size(size), colorFilter = workIconFilter(userSerial))
    }
}

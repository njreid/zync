package dev.njr.zync.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.njr.zync.launcher.AppEntry
import dev.njr.zync.launcher.AppLaunch
import dev.njr.zync.launcher.AppSearch
import dev.njr.zync.launcher.RecentItem
import dev.njr.zync.launcher.SearchHistory

private val OverlayBackground = Color(0xF2000000) // near-opaque pure black (AMOLED)
private val TextPrimary = Color(0xFFC2C7D0)
private val TextMuted = Color(0xFF8A91A0)
private val TextFaint = Color(0xFF5C6470)
private val FieldBackground = Color(0xFF1A212B)

/**
 * The search surface (launcher spec L3 + device feedback): apps across ALL profiles,
 * settings pages, web handoff (≤5 local matches), the five most recent selections at
 * the top of the blank list, and the five most recent query texts under the field.
 */
@Composable
fun SearchOverlay(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps = remember { AppSearch.launchableApps(context) }
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { focus.requestFocus() }

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

    Column(
        Modifier
            .fillMaxSize()
            .background(OverlayBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 18.sp),
                cursorBrush = SolidColor(TextPrimary),
                modifier = Modifier
                    .weight(1f)
                    .background(FieldBackground)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .focusRequester(focus),
            )
            BasicText(
                "Close",
                style = TextStyle(color = TextMuted, fontSize = 14.sp),
                modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
            )
        }

        // recent query texts, shown while the field is empty (tap to re-run)
        if (query.isBlank()) {
            val recentQueries = remember { SearchHistory.recentQueries(context) }
            if (recentQueries.isNotEmpty()) {
                Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recentQueries.forEach { q ->
                        BasicText(
                            q,
                            style = TextStyle(color = TextMuted, fontSize = 13.sp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(FieldBackground, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .clickable { query = q }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

        val appMatches = AppSearch.filter(apps, query)
        val settingsMatches = AppSearch.settingsMatches(query)
        LazyColumn(Modifier.weight(1f)) {
            // 1. five most recent selections head the blank-query list
            if (query.isBlank()) {
                val recents = SearchHistory.recentItems(context)
                if (recents.isNotEmpty()) {
                    items(recents, key = { "r:" + it.label + it.kind + (it.userSerial ?: 0) }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { launchRecent(item) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            when (item.kind) {
                                RecentItem.Kind.App -> AppIcon(item.packageName!!, item.activityName!!, item.userSerial)
                                RecentItem.Kind.Setting -> BasicText("⚙", style = TextStyle(color = TextMuted, fontSize = 20.sp))
                                RecentItem.Kind.Web -> BasicText("🔎", style = TextStyle(color = TextMuted, fontSize = 17.sp))
                            }
                            BasicText(item.label, style = TextStyle(color = TextPrimary, fontSize = 15.sp))
                            BasicText("recent", style = TextStyle(color = TextFaint, fontSize = 11.sp))
                        }
                    }
                }
            }
            // 2. web handoff when few local results match
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
                        BasicText("Search the web for “$query”", style = TextStyle(color = TextPrimary, fontSize = 15.sp))
                    }
                }
            }
            // 3. settings matches
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
                    BasicText(setting.label, style = TextStyle(color = TextPrimary, fontSize = 15.sp))
                }
            }
            // 4. apps (all profiles; work apps carry badged icons)
            items(appMatches, key = { it.packageName + it.activityName + (it.userSerial ?: 0) }) { app ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            if (query.isNotBlank()) SearchHistory.recordQuery(context, query)
                            launchRecent(
                                RecentItem(RecentItem.Kind.App, app.label, app.packageName, app.activityName, app.userSerial),
                            )
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AppIcon(app.packageName, app.activityName, app.userSerial)
                    BasicText(app.label, style = TextStyle(color = TextPrimary, fontSize = 15.sp))
                }
            }
        }
    }
}

@Composable
private fun AppIcon(packageName: String, activityName: String, userSerial: Long?) {
    val context = LocalContext.current
    val icon = remember(packageName, activityName, userSerial) {
        AppLaunch.icon(context, packageName, activityName, userSerial)?.toBitmap(96, 96)?.asImageBitmap()
    }
    icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(34.dp)) }
}

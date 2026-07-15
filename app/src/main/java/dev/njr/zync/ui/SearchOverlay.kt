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
import dev.njr.zync.launcher.AppSearch

private val OverlayBackground = Color(0xF213171F) // near-opaque pico dark
private val TextPrimary = Color(0xFFC2C7D0)
private val TextMuted = Color(0xFF8A91A0)
private val FieldBackground = Color(0xFF1A212B)

/**
 * The swipe-left surface (launcher spec L3): one field searching installed apps
 * (blank = the full app drawer) with a web-search handoff row. The escape hatch that
 * makes zync livable as HOME.
 */
@Composable
fun SearchOverlay(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps = remember { AppSearch.launchableApps(context.packageManager) }
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { focus.requestFocus() }

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
        LazyColumn(Modifier.weight(1f)) {
            if (query.isNotBlank()) {
                item(key = "web-search") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching { context.startActivity(AppSearch.webSearch(query)) }
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                    ) {
                        BasicText(
                            "Search the web for “$query”",
                            style = TextStyle(color = TextPrimary, fontSize = 15.sp),
                        )
                    }
                }
            }
            items(AppSearch.filter(apps, query), key = { it.packageName + it.activityName }) { app ->
                AppRow(app) {
                    runCatching { context.startActivity(app.launchIntent()) }
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, onTap: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(app.packageName, app.activityName) {
        runCatching {
            context.packageManager
                .getActivityIcon(app.launchIntent().component!!)
                .toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(34.dp)) }
        BasicText(app.label, style = TextStyle(color = TextPrimary, fontSize = 15.sp))
    }
}

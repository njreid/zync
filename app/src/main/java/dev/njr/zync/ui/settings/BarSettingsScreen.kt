package dev.njr.zync.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.njr.zync.launcher.AppSearch
import dev.njr.zync.launcher.BarApp
import dev.njr.zync.launcher.BarApps
import dev.njr.zync.launcher.BarRole
import dev.njr.zync.ui.Geomini
import dev.njr.zync.ui.ZyncColors as C

/**
 * Native settings: which apps live in the Messages/Calendar bar submenus and their
 * order — the FIRST is the primary (plain tap); the rest surface on
 * long-press → slide → release (device feedback 2026-07-16).
 */
@Composable
fun BarSettingsScreen(initialRole: BarRole, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var role by remember { mutableStateOf(initialRole) }
    var apps by remember(role) { mutableStateOf(BarApps.load(context, role)) }
    var adding by remember { mutableStateOf(false) }

    fun persist(next: List<BarApp>) {
        apps = next
        BarApps.save(context, role, next)
    }

    BackHandler(onBack = { if (adding) adding = false else onDismiss() })

    Column(
        Modifier
            .fillMaxSize()
            .background(C.Surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("Action bar apps", style = TextStyle(color = C.Ink, fontSize = 17.sp, fontFamily = Geomini, fontWeight = FontWeight.SemiBold))
            BasicText("✕", style = TextStyle(color = C.Ink3, fontSize = 20.sp), modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarRole.entries.forEach { r ->
                BasicText(
                    r.title,
                    style = TextStyle(
                        color = if (r == role) C.Surface else C.Ink2,
                        fontSize = 13.sp,
                        fontFamily = Geomini,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier
                        .background(if (r == role) C.Ink else C.Surface, RoundedCornerShape(999.dp))
                        .border(1.dp, if (r == role) C.Ink else C.Border, RoundedCornerShape(999.dp))
                        .clickable { role = r; adding = false }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }

        if (adding) {
            AppPicker(
                exclude = apps.map { it.packageName + it.activityName + (it.userSerial ?: 0) }.toSet(),
                onPick = { picked -> persist(apps + picked); adding = false },
            )
        } else {
            BasicText(
                "First app = tap · rest = long-press, slide, release",
                style = TextStyle(color = C.Ink3, fontSize = 12.sp, fontFamily = Geomini),
                modifier = Modifier.padding(vertical = 10.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                items(apps.size) { i ->
                    val app = apps[i]
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BasicText(
                            if (i == 0) "● ${app.label}" else app.label,
                            style = TextStyle(color = C.Ink, fontSize = 15.sp, fontFamily = Geomini),
                            modifier = Modifier.weight(1f),
                        )
                        if (i > 0) RowButton("↑") { persist(apps.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                        if (i < apps.size - 1) RowButton("↓") { persist(apps.toMutableList().apply { add(i + 1, removeAt(i)) }) }
                        RowButton("✕") { persist(apps.toMutableList().apply { removeAt(i) }) }
                    }
                }
                items(listOf("add")) { _ ->
                    BasicText(
                        "+ Add app",
                        style = TextStyle(color = C.Accent, fontSize = 15.sp, fontFamily = Geomini),
                        modifier = Modifier.clickable { adding = true }.padding(vertical = 12.dp),
                    )
                }
            }
        }
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
        textStyle = TextStyle(color = C.Ink, fontSize = 15.sp, fontFamily = Geomini),
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
            BasicText(
                app.label,
                style = TextStyle(color = C.Ink, fontSize = 15.sp, fontFamily = Geomini),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(BarApp(app.label, app.packageName, app.activityName, app.userSerial)) }
                    .padding(vertical = 10.dp),
            )
        }
    }
}

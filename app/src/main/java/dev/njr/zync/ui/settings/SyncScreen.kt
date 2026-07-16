package dev.njr.zync.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.njr.zync.sync.SyncMonitor
import dev.njr.zync.sync.SyncScheduler
import dev.njr.zync.ui.CharonMono
import dev.njr.zync.ui.Geomini
import dev.njr.zync.ui.ZyncColors as C
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The sync log behind the home sync tile: recent sync events (newest first) with
 * timestamps, plus a manual "Sync now". The tile itself routes Unpaired taps to
 * pairing and NoNetwork taps to the network panel — by the time this screen shows,
 * the phone is paired and online (or thinks it is).
 */
@Composable
fun SyncScreen(serverAddress: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val events by SyncMonitor.events.collectAsState()
    val syncing by SyncMonitor.syncing.collectAsState()
    val times = SimpleDateFormat("EEE HH:mm", Locale.US)

    BackHandler(onBack = onDismiss)

    Column(
        Modifier
            .fillMaxSize()
            .background(C.Surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("Sync", style = TextStyle(color = C.Ink, fontSize = 17.sp, fontFamily = Geomini, fontWeight = FontWeight.SemiBold))
            BasicText("✕", style = TextStyle(color = C.Ink3, fontSize = 20.sp), modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp))
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                serverAddress ?: "not paired",
                style = TextStyle(color = C.Ink2, fontSize = 13.sp, fontFamily = CharonMono),
            )
            BasicText(
                if (syncing) "syncing…" else "Sync now",
                style = TextStyle(color = if (syncing) C.Ink3 else C.Accent, fontSize = 13.sp, fontFamily = Geomini, fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .border(1.dp, C.Border, RoundedCornerShape(999.dp))
                    .clickable(enabled = !syncing) { SyncScheduler.requestSync(context) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        if (events.isEmpty()) {
            BasicText("No sync activity yet.", style = TextStyle(color = C.Ink3, fontSize = 13.sp, fontFamily = Geomini))
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(events) { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        BasicText(
                            times.format(Date(e.atMillis)),
                            style = TextStyle(color = C.Ink3, fontSize = 12.sp, fontFamily = CharonMono),
                            modifier = Modifier.width(84.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        BasicText(
                            e.message,
                            style = TextStyle(color = if (e.ok) C.Ink else C.Accent, fontSize = 13.sp, fontFamily = Geomini),
                        )
                    }
                }
            }
        }
    }
}

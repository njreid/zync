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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.njr.zync.ui.CharonMono
import dev.njr.zync.ui.ZyncSans
import dev.njr.zync.ui.ZyncColors as C

/**
 * Native pairing screen (behind the sync tile when unpaired). Pairing is a QR
 * ceremony — the server's Settings → Pairing page mints a one-time invite — so
 * this screen explains that path and accepts a pasted invite link as the
 * camera-free fallback. Accepts both the `zync://pair?…` payload and the
 * `https://…/pair/open?…` handoff URL the QR actually encodes.
 */
@Composable
fun PairingScreen(onPair: (String) -> Unit, onDismiss: () -> Unit) {
    var link by remember { mutableStateOf("") }
    BackHandler(onBack = onDismiss)

    Column(
        Modifier
            .fillMaxSize()
            .background(C.Surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("Pair with server", style = TextStyle(color = C.Ink, fontSize = 17.sp, fontFamily = ZyncSans, fontWeight = FontWeight.SemiBold))
            BasicText("✕", style = TextStyle(color = C.Ink3, fontSize = 20.sp), modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp))
        }

        BasicText(
            "On another device, sign in to your zync server and open Settings → Pairing. " +
                "Scan the QR code there with this phone's camera — or paste the invite link below.",
            style = TextStyle(color = C.Ink2, fontSize = 14.sp, fontFamily = ZyncSans, lineHeight = 20.sp),
        )

        Spacer(Modifier.height(18.dp))
        BasicTextField(
            value = link,
            onValueChange = { link = it },
            singleLine = true,
            textStyle = TextStyle(color = C.Ink, fontSize = 13.sp, fontFamily = CharonMono),
            cursorBrush = SolidColor(C.Ink),
            decorationBox = { inner ->
                if (link.isEmpty()) {
                    BasicText("zync://pair?…  or  https://…/pair/open?…", style = TextStyle(color = C.Ink3, fontSize = 13.sp, fontFamily = CharonMono))
                }
                inner()
            },
            modifier = Modifier
                .fillMaxWidth()
                .background(C.Card, RoundedCornerShape(10.dp))
                .padding(12.dp),
        )

        Spacer(Modifier.height(12.dp))
        val invite = normalizeInvite(link)
        BasicText(
            "Pair",
            style = TextStyle(
                color = if (invite != null) C.Surface else C.Ink3,
                fontSize = 15.sp,
                fontFamily = ZyncSans,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier
                .background(if (invite != null) C.Ink else C.Card, RoundedCornerShape(10.dp))
                .border(1.dp, C.Border, RoundedCornerShape(10.dp))
                .clickable(enabled = invite != null) { invite?.let(onPair) }
                .padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}

/** Pasted-link tolerance: the QR encodes an https handoff whose query IS the invite. */
internal fun normalizeInvite(raw: String): String? {
    val text = raw.trim()
    return when {
        text.startsWith("zync://pair?") -> text
        text.contains("/pair/open?") -> "zync://pair?" + text.substringAfter("/pair/open?")
        else -> null
    }
}

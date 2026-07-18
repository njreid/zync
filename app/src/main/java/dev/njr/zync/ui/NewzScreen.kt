package dev.njr.zync.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.njr.zync.ui.ZyncColors as C

private sealed interface NewzPhase {
    data object Loading : NewzPhase
    data class Ready(val handoffUrl: String) : NewzPhase
    data class Failed(val message: String, val unpaired: Boolean) : NewzPhase
}

/**
 * The newz feed surface (spec ../newz/zync-integration-spec.md): mint a one-time
 * handoff immediately, then load it in a FRESH WebView (never the loopback one).
 * History is cleared once the redirect lands on /newz/ so the token URL survives
 * nowhere. Failures show a native recovery card — an unauthenticated newz page is
 * never shown.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NewzScreen(
    mint: suspend () -> Result<String>,
    onOpenPairing: () -> Unit,
    onDismiss: () -> Unit,
) {
    var phase by remember { mutableStateOf<NewzPhase>(NewzPhase.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(attempt) {
        phase = NewzPhase.Loading
        phase = mint().fold(
            onSuccess = { NewzPhase.Ready(it) },
            onFailure = {
                val unpaired = it is NewzNotPaired
                NewzPhase.Failed(
                    if (unpaired) "Pair with your server to read newz" else (it.message ?: "handoff failed"),
                    unpaired,
                )
            },
        )
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onDismiss()
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy(); webView = null } }

    Box(Modifier.fillMaxSize().background(C.Surface)) {
        when (val p = phase) {
            is NewzPhase.Loading -> Center { Muted("opening newz…") }
            is NewzPhase.Failed -> Center {
                Muted(p.message)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (p.unpaired) Pill("Pair", primary = true) { onDismiss(); onOpenPairing() }
                    else Pill("Retry", primary = true) { attempt++ }
                    Pill("Close", primary = false, onTap = onDismiss)
                }
            }
            is NewzPhase.Ready -> AndroidView(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        val origin = p.handoffUrl.substringBefore("/newz/")
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                // The feed stays in-surface; anything off-origin opens externally.
                                return if (url.startsWith(origin)) false else {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(android.content.Intent.ACTION_VIEW, request.url),
                                        )
                                    }
                                    true
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                // Post-redemption the visible URL is /newz/ — drop the token
                                // page from history so back/copy never resurface it.
                                if (url != null && !url.contains("token=")) view.clearHistory()
                            }
                        }
                        loadUrl(p.handoffUrl)
                        webView = this
                    }
                },
            )
        }
    }
}

/** Marker failure: the phone isn't paired, so there is no identity to hand off. */
class NewzNotPaired : Exception("not paired")

@Composable
private fun Center(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun Muted(text: String) {
    BasicText(text, style = TextStyle(color = C.Ink2, fontSize = 15.sp, fontFamily = ZyncSans))
}

@Composable
private fun Pill(label: String, primary: Boolean, onTap: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(
            color = if (primary) C.Surface else C.Ink2,
            fontSize = 14.sp,
            fontFamily = ZyncSans,
            fontWeight = FontWeight.SemiBold,
        ),
        modifier = Modifier
            .background(if (primary) C.Ink else C.Surface, RoundedCornerShape(999.dp))
            .border(1.dp, if (primary) C.Ink else C.Border, RoundedCornerShape(999.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

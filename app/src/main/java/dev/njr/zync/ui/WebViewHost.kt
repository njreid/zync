package dev.njr.zync.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import dev.njr.zync.MainActivity
import dev.njr.zync.capture.CaptureSettingsBridge
import dev.njr.zync.ui.home.HomeScreen
import dev.njr.zync.ui.home.HomeState
import dev.njr.zync.ui.home.HomeTile
import dev.njr.zync.web.content.ContextView
import dev.njr.zync.web.content.NodeView

/** The loopback URL the in-app WebView loads once at launch (per-boot token in the query). */
fun loopbackUrl(port: Int, token: String): String = "http://127.0.0.1:$port/?token=$token"

/** The two shell destinations: the native home surface, and the loopback :web content. */
enum class ZyncScreen { Home, Web }

/**
 * Builds THE single WebView the app hosts for its whole lifetime: JavaScript + DOM storage on,
 * with the `ZyncCapture` bridge wired. Created parent-less so a Compose [AndroidView] can adopt
 * it without a reparent conflict. It must never be rebuilt — the loopback server connection the
 * WebView holds must persist for the life of the process (see [dev.njr.zync.ZyncApp]).
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
fun createZyncWebView(
    activity: MainActivity,
    requestRecordAudio: ((Boolean) -> Unit) -> Unit,
): WebView = WebView(activity).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    webChromeClient = WebChromeClient()
    webViewClient = WebViewClientCompat()
    // Let :web content follow the system dark theme where it doesn't define its own. Guarded:
    // the feature check can be optimistic and a provider may still throw at call time (e.g. a
    // stub WebView under Robolectric), which must never crash app startup.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true) }
    }
    addJavascriptInterface(CaptureSettingsBridge(activity, this, requestRecordAudio), "ZyncCapture")
}

/**
 * The native Compose shell (native-home spec 2026-07-16): the HOME surface is native
 * (tiles · hero clock/context · agenda); the shared `:web` UI shows on [ZyncScreen.Web]
 * (tile taps). The action bar (spec L1) is pinned beneath both; the swipe-left search
 * overlay (L3) sits above everything. The WebView is the pre-built singleton — never
 * rebuilt, only re-parented.
 */
@Composable
fun ZyncShell(
    webView: WebView,
    screen: ZyncScreen,
    homeState: HomeState,
    onBarAction: (BarAction) -> Unit,
    onTileTap: (HomeTile) -> Unit,
    onContextSelect: (ContextView?) -> Unit,
    onCompleteTask: (NodeView) -> Unit,
    onEnableWeather: () -> Unit,
    onEnableCalendar: () -> Unit,
    barApps: (dev.njr.zync.launcher.BarRole) -> List<dev.njr.zync.launcher.BarApp> = { emptyList() },
    onLaunchApp: (dev.njr.zync.launcher.BarApp) -> Unit = {},
    onEditRole: (dev.njr.zync.launcher.BarRole) -> Unit = {},
    onOpenEvent: (dev.njr.zync.home.CalEvent) -> Unit = {},
    onEnableNotifications: () -> Unit = {},
    searchOpen: Boolean = false,
    onSearchOpenChange: (Boolean) -> Unit = {},
) {
    // The dark surface paints edge-to-edge FIRST; insets are then applied per-region
    // (home draws into the status-bar strip around the camera; the bar owns the
    // nav-bar strip) — full safeDrawing padding here was the white-bars bug.
    Box(Modifier.fillMaxSize().background(dev.njr.zync.ui.ZyncColors.Surface)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (screen == ZyncScreen.Web) {
                    AndroidView(
                        factory = { webView.also { v -> (v.parent as? ViewGroup)?.removeView(v) } },
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                    )
                } else {
                    HomeScreen(
                        homeState, onTileTap, onContextSelect, onCompleteTask, onEnableWeather, onEnableCalendar,
                        onOpenSearch = { onSearchOpenChange(true) },
                        onOpenEvent = onOpenEvent,
                        onEnableNotifications = onEnableNotifications,
                    )
                }
            }
            ZyncActionBar(
                onAction = onBarAction,
                onSearch = { onSearchOpenChange(true) },
                barApps = barApps,
                onLaunchApp = onLaunchApp,
                onEditRole = onEditRole,
            )
        }
        if (searchOpen) SearchOverlay(onDismiss = { onSearchOpenChange(false) })
    }
}

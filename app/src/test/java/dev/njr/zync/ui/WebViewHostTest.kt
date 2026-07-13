package dev.njr.zync.ui

import dev.njr.zync.MainActivity
import dev.njr.zync.capture.CaptureSettingsBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WebViewHostTest {

    @Test
    fun `loopbackUrl carries the port and token`() {
        assertEquals("http://127.0.0.1:8137/?token=abc", loopbackUrl(8137, "abc"))
    }

    @Test
    fun `createZyncWebView enables JavaScript and wires the ZyncCapture bridge`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val webView = createZyncWebView(activity) { }

        assertTrue("JavaScript must be enabled for the :web UI", webView.settings.javaScriptEnabled)
        assertTrue("DOM storage must be enabled", webView.settings.domStorageEnabled)
        val bridge = shadowOf(webView).getJavascriptInterface("ZyncCapture")
        assertTrue("the ZyncCapture bridge must be registered", bridge is CaptureSettingsBridge)
    }

    @Test
    fun `the activity hosts the Compose shell without crashing`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // Pump the main looper so the post-serverStart loadUrl coroutine settles.
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("the Compose shell (setContent { ZyncShell }) should inflate", !activity.isFinishing)
    }
}

package dev.njr.zync.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.ktor.http.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidAssetsTest {
    private val assets = androidAssets(
        ApplicationProvider.getApplicationContext<Context>().assets)

    @Test fun `serves index html with html content type`() {
        val (bytes, type) = assets("index.html")!!
        assertTrue(bytes.isNotEmpty())
        assertEquals(ContentType.Text.Html, type)
    }

    @Test fun `maps js and css content types`() {
        assertEquals(ContentType.parse("application/javascript"), assets("js/app.js")!!.second)
        assertEquals(ContentType.Text.CSS, assets("vendor/pico.min.css")!!.second)
    }

    @Test fun `missing asset returns null`() {
        assertNull(assets("nope.xyz"))
    }
}

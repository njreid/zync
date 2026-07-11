package dev.njr.zync.web.sse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatastarTest {
    @Test
    fun patchElementsDefaultMode() {
        val e = patchElementsEvent("""<div id="inbox">x</div>""")
        assertEquals("datastar-patch-elements", e.event)
        assertEquals("""elements <div id="inbox">x</div>""", e.data) // mode outer omitted (default)
    }

    @Test
    fun patchElementsWithSelectorAndMode() {
        val e = patchElementsEvent("<li>a</li>", selector = "#inbox", mode = "append")
        assertEquals(listOf("mode append", "selector #inbox", "elements <li>a</li>"), e.data.split("\n"))
    }

    @Test
    fun multilineHtmlSplitsAcrossElementsLines() {
        val e = patchElementsEvent("<ul>\n<li>a</li>\n</ul>")
        assertEquals(listOf("elements <ul>", "elements <li>a</li>", "elements </ul>"), e.data.split("\n"))
    }

    @Test
    fun patchSignals() {
        val e = patchSignalsEvent("""{"n":1}""")
        assertEquals("datastar-patch-signals", e.event)
        assertEquals("""signals {"n":1}""", e.data)
    }

    @Test
    fun notifierNotifiesWithoutError() {
        val notifier = ChangeNotifier()
        notifier.notifyChanged() // buffered; exercises the broadcast path
        assertTrue(true)
    }
}

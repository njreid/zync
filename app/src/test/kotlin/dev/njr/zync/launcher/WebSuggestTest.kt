package dev.njr.zync.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** The suggest endpoint's `["q", [suggestions…]]` body shape. */
class WebSuggestTest {
    @Test
    fun parsesSuggestBody() {
        val body = """["weath",["weather","weather today","weather tomorrow","weather radar","weather nyc"]]"""
        assertEquals(listOf("weather", "weather today", "weather tomorrow", "weather radar"), WebSuggest.parse(body))
    }

    @Test
    fun malformedBodiesDegradeToEmpty() {
        assertEquals(emptyList<String>(), WebSuggest.parse("<!doctype html>nope"))
        assertEquals(emptyList<String>(), WebSuggest.parse("""["q"]"""))
        assertEquals(emptyList<String>(), WebSuggest.parse(""))
    }
}

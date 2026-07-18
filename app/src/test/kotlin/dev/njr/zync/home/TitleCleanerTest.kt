package dev.njr.zync.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Nano output parsing: strict JSON, fenced JSON, junk. */
class TitleCleanerTest {
    @Test
    fun parsesPlainAndFencedJson() {
        assertEquals(
            TitleCleaner.Cleaned("Dentist", "12 High St"),
            TitleCleaner.parseCleaned("""{"title":"Dentist","location":"12 High St"}"""),
        )
        assertEquals(
            TitleCleaner.Cleaned("Standup", null),
            TitleCleaner.parseCleaned("```json\n{\"title\":\"Standup\",\"location\":null}\n```"),
        )
    }

    @Test
    fun junkDegradesToNull() {
        assertNull(TitleCleaner.parseCleaned("sorry, I can't help with that"))
        assertNull(TitleCleaner.parseCleaned("""{"location":"x"}"""))
        assertNull(TitleCleaner.parseCleaned(""))
    }
}

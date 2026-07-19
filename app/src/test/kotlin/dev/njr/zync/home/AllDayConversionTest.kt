package dev.njr.zync.home

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** All-day instances are UTC midnights; the agenda needs LOCAL midnights. */
class AllDayConversionTest {
    private fun utcMidnight(y: Int, m: Int, d: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m, d) }.timeInMillis

    private fun localMidnight(zone: TimeZone, y: Int, m: Int, d: Int): Long =
        Calendar.getInstance(zone).apply { clear(); set(y, m, d) }.timeInMillis

    @Test
    fun utcPlusZonesNoLongerLeakIntoTheNextDay() {
        val sydney = TimeZone.getTimeZone("Australia/Sydney")
        // A Saturday all-day event: UTC Sat 00:00 → UTC Sun 00:00 (exclusive end).
        val begin = CalendarSource.utcMidnightToLocalMidnight(utcMidnight(2026, 6, 18), sydney)
        val end = CalendarSource.utcMidnightToLocalMidnight(utcMidnight(2026, 6, 19), sydney)
        assertEquals(localMidnight(sydney, 2026, 6, 18), begin)
        assertEquals(localMidnight(sydney, 2026, 6, 19), end)
        // Sunday's local window starts exactly at `end` — overlap (end > start) is false.
        val sundayStart = localMidnight(sydney, 2026, 6, 19)
        assert(!(begin < sundayStart + 1 && end > sundayStart)) { "must not appear on Sunday" }
    }

    @Test
    fun utcMinusZonesToo() {
        val la = TimeZone.getTimeZone("America/Los_Angeles")
        val begin = CalendarSource.utcMidnightToLocalMidnight(utcMidnight(2026, 6, 18), la)
        assertEquals(localMidnight(la, 2026, 6, 18), begin)
    }
}

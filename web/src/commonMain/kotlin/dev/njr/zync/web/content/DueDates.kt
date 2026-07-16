package dev.njr.zync.web.content

/**
 * Due-date wire convention: a due DAY is stored as epoch millis at **UTC noon** of
 * that civil date. UTC noon keeps the rendered date identical across timezones up
 * to ±11h without shipping a datetime library into commonMain.
 */
object DueDates {
    private const val DAY_MILLIS = 86_400_000L
    private const val NOON_MILLIS = 43_200_000L

    /** `YYYY-MM-DD` → epoch millis at UTC noon; null for anything malformed. */
    fun parse(date: String): Long? {
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})").matchEntire(date.trim()) ?: return null
        val (y, mo, d) = m.destructured
        val year = y.toInt()
        val month = mo.toInt()
        val day = d.toInt()
        if (month !in 1..12 || day !in 1..31) return null
        return daysFromCivil(year, month, day) * DAY_MILLIS + NOON_MILLIS
    }

    /** Epoch millis (UTC-noon convention) → `YYYY-MM-DD`. */
    fun format(millis: Long): String {
        val (y, m, d) = civilFromDays(floorDiv(millis, DAY_MILLIS))
        return "$y-${pad(m)}-${pad(d)}"
    }

    private fun pad(n: Int) = if (n < 10) "0$n" else "$n"
    private fun floorDiv(a: Long, b: Long): Long = if (a >= 0) a / b else -((-a + b - 1) / b)

    // Howard Hinnant's civil-calendar algorithms (public domain).
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146097 + doe - 719468
    }

    private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = (z - era * 146097).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era.toInt() * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple(if (m <= 2) y + 1 else y, m, d)
    }
}

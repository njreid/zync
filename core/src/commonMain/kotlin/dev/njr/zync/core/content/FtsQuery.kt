package dev.njr.zync.core.content

/**
 * Turns raw user search input into a safe SQLite FTS5 `MATCH` string (GTD triage §7):
 * split on non-alphanumeric, lowercase, and prefix-match each token (`foo bar` →
 * `foo* bar*`, implicitly AND-ed). Stripping to alphanumerics keeps raw input from
 * throwing FTS5 syntax errors or being read as a boolean operator; the trailing `*`
 * gives search-as-you-type prefix behavior. Returns null when there is no usable term.
 */
object FtsQuery {
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    fun tokens(raw: String): List<String> =
        raw.lowercase().split(SPLIT).filter { it.isNotBlank() }

    fun toMatch(raw: String): String? {
        val tokens = tokens(raw)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }
}

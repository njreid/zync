package dev.njr.zync.capture

import dev.njr.zync.web.content.ContextView
import dev.njr.zync.web.content.DueDates
import dev.njr.zync.web.content.NodeView
import java.time.DayOfWeek
import java.time.LocalDate

/** What the capture screen's suggestion card proposes (native-capture spec). */
data class Suggestion(
    val title: String,
    val contextId: String?,     // matched context node id (as string)
    val contextName: String?,
    val dueMillis: Long?,
    val dueLabel: String?,
    val person: String?,
    /** Ranked file-under candidates (projects), best first. */
    val nodeCandidates: List<NodeCandidate>,
)

data class NodeCandidate(val id: String, val title: String, val why: String)

/** Pluggable extraction: rules today, Gemini Nano behind the same seam (Pixel 9+). */
interface CaptureExtractor {
    fun extract(text: String, contexts: List<ContextView>, projects: List<NodeView>, today: LocalDate): Suggestion
}

/**
 * Deterministic extraction so capture enrichment always works (spec: the fallback
 * beneath Nano): due-date phrases, @word / keyword context matching, a conservative
 * person guess, and title-word-overlap project candidates.
 */
object RulesExtractor : CaptureExtractor {
    override fun extract(
        text: String,
        contexts: List<ContextView>,
        projects: List<NodeView>,
        today: LocalDate,
    ): Suggestion {
        val due = parseDue(text, today)
        val context = matchContext(text, contexts)
        return Suggestion(
            title = cleanTitle(text),
            contextId = context?.id?.toString(),
            contextName = context?.name,
            dueMillis = due?.let { DueDates.parse(it.toString()) },
            dueLabel = due?.let { "${it.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase).take(3)}, ${it.monthValue}/${it.dayOfMonth}" },
            person = guessPerson(text),
            nodeCandidates = projectCandidates(text, projects),
        )
    }

    /** Tidy the transcript into a title: trim, strip a leading filler verb phrase, cap length. */
    fun cleanTitle(text: String): String {
        var t = text.trim().replace(Regex("\\s+"), " ")
        t = t.replace(Regex("^(remind me to|remember to|i need to|need to|don't forget to|todo:?)\\s+", RegexOption.IGNORE_CASE), "")
        if (t.isNotEmpty()) t = t.replaceFirstChar(Char::uppercase)
        return if (t.length <= 90) t else t.take(87).trimEnd() + "…"
    }

    /** "today" / "tomorrow" / weekday names ("by Friday", "next tuesday") → a civil date. */
    fun parseDue(text: String, today: LocalDate): LocalDate? {
        val t = text.lowercase()
        if (Regex("\\btoday\\b").containsMatchIn(t)) return today
        if (Regex("\\btomorrow\\b").containsMatchIn(t)) return today.plusDays(1)
        for (dow in DayOfWeek.entries) {
            val name = dow.name.lowercase()
            if (!Regex("\\b$name\\b").containsMatchIn(t)) continue
            var days = ((dow.value - today.dayOfWeek.value) + 7) % 7
            if (days == 0) days = 7 // "friday" said on a Friday means next week's
            if (Regex("\\bnext\\s+$name\\b").containsMatchIn(t) && days <= 3) days += 7
            return today.plusDays(days.toLong())
        }
        return null
    }

    /** An explicit @word beats keyword hints; keyword hints map to seeded contexts. */
    fun matchContext(text: String, contexts: List<ContextView>): ContextView? {
        val t = text.lowercase()
        Regex("@([a-z][a-z0-9-]*)").find(t)?.groupValues?.get(1)?.let { word ->
            contexts.firstOrNull { (it.name ?: "").removePrefix("@").equals(word, ignoreCase = true) }
                ?.let { return it }
        }
        val hints = mapOf(
            "call" to listOf("call", "phone", "ring"),
            "town" to listOf("buy", "pick up", "store", "shop", "errand"),
            "work" to listOf("meeting", "review", "contract", "client", "report"),
            "desk" to listOf("email", "code", "website", "file", "upload"),
        )
        for ((ctx, words) in hints) {
            if (words.any { Regex("\\b$it").containsMatchIn(t) }) {
                contexts.firstOrNull { (it.name ?: "").removePrefix("@").startsWith(ctx.removeSuffix("s")) }
                    ?.let { return it }
            }
        }
        return null
    }

    /** A capitalized token right after a people-preposition ("remind Dana", "with Sam"). */
    fun guessPerson(text: String): String? =
        Regex("\\b(?i:remind|ask|tell|with|from|for)\\s+([A-Z][a-z]+)\\b").find(text)
            ?.groupValues?.get(1)
            ?.takeIf { it !in NOT_NAMES }

    private val NOT_NAMES = setOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday", "Today", "Tomorrow", "The", "My", "Me")

    /** Projects ranked by title-word overlap with the transcript; top two. */
    fun projectCandidates(text: String, projects: List<NodeView>): List<NodeCandidate> {
        val words = Regex("[a-z]{4,}").findAll(text.lowercase()).map { it.value }.toSet()
        if (words.isEmpty()) return emptyList()
        return projects.mapNotNull { p ->
            val title = p.title ?: return@mapNotNull null
            val overlap = Regex("[a-z]{4,}").findAll(title.lowercase()).count { it.value in words }
            if (overlap > 0) NodeCandidate(p.id.toString(), title, "matches “${title.split(" ").first()}”") to overlap else null
        }.sortedByDescending { it.second }.take(2).map { it.first }
    }
}

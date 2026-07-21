package dev.njr.zync.capture

import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.web.content.ContextView
import dev.njr.zync.web.content.DueDates
import dev.njr.zync.web.content.NodeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/** The rules extractor beneath Nano: dates, contexts, people, project candidates. */
class ExtractionTest {
    private fun id(seed: Int) = Ulid.generate(Clock { 1L }, Random(seed))
    private val contexts = listOf(
        ContextView(id(1), "@home"), ContextView(id(2), "@work"),
        ContextView(id(3), "@errands"), ContextView(id(4), "@calls"),
    )
    private fun project(seed: Int, title: String) =
        NodeView(id(seed), "project", title, null, "ACTIVE", null, null, null, null, null, null, null, emptySet(), true)

    private val wednesday = LocalDate.of(2026, 7, 15) // a Wednesday

    @Test
    fun cleansFillerAndCapitalizes() {
        assertEquals("Call the plumber", RulesExtractor.cleanTitle("remind me to call the plumber"))
        assertEquals("Buy milk", RulesExtractor.cleanTitle("  buy   milk "))
    }

    @Test
    fun parsesRelativeDates() {
        assertEquals(wednesday, RulesExtractor.parseDue("finish today", wednesday))
        assertEquals(wednesday.plusDays(1), RulesExtractor.parseDue("do it tomorrow", wednesday))
        assertEquals(wednesday.plusDays(2), RulesExtractor.parseDue("signed before Friday", wednesday))
        assertEquals(wednesday.plusDays(7), RulesExtractor.parseDue("every wednesday", wednesday), )
        assertEquals(wednesday.plusDays(9), RulesExtractor.parseDue("next friday", wednesday))
        assertNull(RulesExtractor.parseDue("no date here", wednesday))
    }

    @Test
    fun matchesContextsByAtWordThenKeyword() {
        assertEquals("@errands", RulesExtractor.matchContext("stop by the store @errands", contexts)?.name)
        assertEquals("@calls", RulesExtractor.matchContext("call the dentist", contexts)?.name)
        assertEquals("@work", RulesExtractor.matchContext("prep the client review", contexts)?.name)
        assertNull(RulesExtractor.matchContext("water the plants", contexts))
    }

    @Test
    fun guessesPeopleConservatively() {
        assertEquals("Dana", RulesExtractor.guessPerson("Remind Dana about the contract"))
        assertEquals("Sam", RulesExtractor.guessPerson("lunch with Sam at noon"))
        assertNull(RulesExtractor.guessPerson("finish before Friday")) // weekdays aren't people
        assertNull(RulesExtractor.guessPerson("buy milk"))
    }

    @Test
    fun ranksProjectCandidatesByOverlap() {
        val projects = listOf(project(10, "Contract review"), project(11, "House renovation"), project(12, "Reading list"))
        val cands = RulesExtractor.projectCandidates("get the contract review signed", projects)
        assertEquals("Contract review", cands.first().title)
        assertTrue(cands.size <= 2)
        assertTrue(RulesExtractor.projectCandidates("zzz", projects).isEmpty())
    }

    @Test
    fun endToEndSuggestion() {
        val projects = listOf(project(10, "Contract review"))
        val s = RulesExtractor.extract("remind Dana about the contract review before friday", contexts, projects, wednesday)
        assertEquals("Dana", s.person)
        assertEquals("@work", s.contextName)
        assertEquals(DueDates.parse(wednesday.plusDays(2).toString()), s.dueMillis)
        assertEquals("Contract review", s.nodeCandidates.first().title)
    }
}

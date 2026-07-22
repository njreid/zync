package dev.njr.zync.core.merge

import dev.njr.zync.core.OpFactory
import dev.njr.zync.core.hlc
import dev.njr.zync.core.id
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.core.state.RegisterKey
import dev.njr.zync.core.str
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The external-op-api merge rule (spec §1): a Human register value is never overwritten by
 * a Bot, and always supersedes a Bot, regardless of HLC — so a bot can propose but never
 * clobber a human decision. Same-class writers keep plain LWW; the rule is order-independent.
 */
class BotMergeTest {
    private val ops = OpFactory()
    private val T = id(1)
    private val bot = Actor.Bot("newz")

    private fun value(vararg op: Op): String? =
        InMemoryStateStore().apply { op.forEach { apply(it, this) } }
            .getRegister(RegisterKey(T, "title"))?.value?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }

    @Test
    fun humanIsNotOverwrittenByALaterBot() {
        assertEquals(
            "human", // bot's higher HLC does not win
            value(
                ops.setField(T, "title", str("human"), hlc(5), Actor.Human),
                ops.setField(T, "title", str("bot"), hlc(50), bot),
            ),
        )
    }

    @Test
    fun humanSupersedesAnEarlierBotRegardlessOfHlc() {
        assertEquals(
            "human", // human wins even with a LOWER hlc than the bot
            value(
                ops.setField(T, "title", str("bot"), hlc(50), bot),
                ops.setField(T, "title", str("human"), hlc(5), Actor.Human),
            ),
        )
    }

    @Test
    fun orderIndependentAcrossThreeWriters() {
        val h1 = ops.setField(T, "title", str("h1"), hlc(3), Actor.Human)
        val b = ops.setField(T, "title", str("bot"), hlc(9), bot)
        val h2 = ops.setField(T, "title", str("h2"), hlc(6), Actor.Human)
        // Winner is the highest-HLC human (h2); the bot never wins, whatever the apply order.
        assertEquals("h2", value(h1, b, h2))
        assertEquals("h2", value(b, h2, h1))
        assertEquals("h2", value(h2, h1, b))
    }

    @Test
    fun convergesWhenAnOperatorSitsBetweenHumanAndBotByHlc() {
        // The tricky case: a non-Human authoritative writer (Operator) whose HLC is BETWEEN the
        // human's and the bot's. A pairwise "Human-beats-Bot" rule cycles here (Human>Bot by rule,
        // Bot>Operator by HLC, Operator>Human by HLC) and diverges by apply order. The advisory
        // rule (any non-Bot outranks a Bot; else HLC) makes the operator the unambiguous winner.
        val h = ops.setField(T, "title", str("human"), hlc(3), Actor.Human)
        val op = ops.setField(T, "title", str("operator"), hlc(6), Actor.Operator("summarize"))
        val b = ops.setField(T, "title", str("bot"), hlc(9), bot)
        assertEquals("operator", value(h, op, b))
        assertEquals("operator", value(b, h, op))
        assertEquals("operator", value(op, b, h))
        assertEquals("operator", value(b, op, h))
    }

    @Test
    fun botVsBotStillLww() {
        assertEquals(
            "newer",
            value(
                ops.setField(T, "title", str("older"), hlc(5), Actor.Bot("a")),
                ops.setField(T, "title", str("newer"), hlc(9), Actor.Bot("b")),
            ),
        )
    }

    @Test
    fun botSerializationRoundTrips() {
        val a: Actor = Actor.Bot("newz")
        val json = Json.encodeToString(Actor.serializer(), a)
        assertEquals("""{"type":"bot","id":"newz"}""", json)
        assertEquals(a, Json.decodeFromString(Actor.serializer(), json))
    }
}

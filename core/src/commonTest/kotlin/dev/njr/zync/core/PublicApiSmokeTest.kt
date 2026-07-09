package dev.njr.zync.core

import dev.njr.zync.core.clock.HlcGenerator
import dev.njr.zync.core.clock.MutableClock
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.apply
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.operator.FieldType
import dev.njr.zync.core.operator.OperatorOutcome
import dev.njr.zync.core.operator.OutputSchema
import dev.njr.zync.core.operator.evaluate
import dev.njr.zync.core.state.InMemoryStateStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exercises the whole `:core` public surface the way a downstream consumer
 * (`data`/`server`/`app`) will — proving the module is usable end-to-end through
 * its public API alone, not just its internals.
 */
class PublicApiSmokeTest {
    @Test
    fun captureOfflineThenServerCompletesConverges() {
        val clock = MutableClock(1_000)
        val rng = Random(1)
        val hlc = HlcGenerator("phone", clock)
        val store = InMemoryStateStore()

        val task = Ulid.generate(clock, rng)
        val inbox = Ulid.generate(clock, rng)

        // Phone, offline: create a task and file it under Inbox.
        apply(Op.SetField(Ulid.generate(clock, rng), task, EntityType.Node, hlc.now(), Actor.Human, "phone", clock.nowMillis(), "title", JsonPrimitive("Buy milk")), store)
        apply(Op.Move(Ulid.generate(clock, rng), task, EntityType.Node, hlc.now(), Actor.Human, "phone", clock.nowMillis(), inbox), store)

        // Later, the server completes it (higher HLC).
        clock.millis = 2_000
        apply(Op.SetField(Ulid.generate(clock, rng), task, EntityType.Node, hlc.now(), Actor.Human, "phone", clock.nowMillis(), "status", JsonPrimitive("DONE")), store)

        val snapshot = store.project().getValue(task)
        assertTrue(snapshot.alive)
        assertEquals(inbox, snapshot.parent)
        assertEquals(JsonPrimitive("Buy milk"), snapshot.fields["title"])
        assertEquals(JsonPrimitive("DONE"), snapshot.fields["status"])
    }

    @Test
    fun operatorOutputValidatesThroughPublicApi() {
        val schema = OutputSchema(fields = mapOf("summary" to FieldType.String))
        val outcome = schema.evaluate(
            attempts = listOf(buildJsonObject { put("summary", JsonPrimitive("a quick milk errand")) }),
            retries = 1,
        )
        val accepted = assertIs<OperatorOutcome.Accepted>(outcome)
        assertEquals(1, accepted.attemptsUsed)
    }
}

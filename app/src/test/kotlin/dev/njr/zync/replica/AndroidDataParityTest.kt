package dev.njr.zync.replica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.apply
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.data.AndroidZyncDatabase
import dev.njr.zync.data.SqlDelightStateStore
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * M5 Task 1: the SQLDelight `StateStore` over the real **AndroidSqliteDriver**
 * (Robolectric) merges identically to the in-memory reference — the phone and server
 * run the same durable store. Finishes the M4-deferred Android driver coverage.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidDataParityTest {
    private class FixedClock(private val ms: Long) : Clock {
        override fun nowMillis() = ms
    }

    private fun id(seed: Int) = Ulid.generate(FixedClock(seed.toLong()), Random(seed.toLong()))
    private var counter = 0
    private fun opId() = Ulid.generate(FixedClock((counter++).toLong()), Random((counter + 500).toLong()))
    private fun hlc(ms: Long, dev: String = "phone") = Hlc(ms, 0, dev)

    @Test
    fun androidDriverMatchesInMemoryReference() {
        val t = id(1); val p = id(2); val ctx = id(3)
        val batch = listOf<Op>(
            Op.SetField(opId(), t, EntityType.Node, hlc(10), Actor.Human, "phone", 10, "title", JsonPrimitive("Buy milk")),
            Op.SetField(opId(), t, EntityType.Node, hlc(12, "desk"), Actor.Human, "desk", 12, "title", JsonPrimitive("Buy oat milk")),
            Op.Move(opId(), t, EntityType.Node, hlc(11), Actor.Human, "phone", 11, p),
            Op.AddTag(opId(), t, EntityType.Tag, hlc(13), Actor.Human, "phone", 13, ctx),
            Op.SetField(opId(), p, EntityType.Node, hlc(9), Actor.Human, "phone", 9, "title", JsonPrimitive("Project")),
            Op.Tombstone(opId(), id(4), EntityType.Node, hlc(14), Actor.Human, "phone", 14),
        )

        val reference = InMemoryStateStore().apply { batch.forEach { apply(it, this) } }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val android = SqlDelightStateStore(AndroidZyncDatabase.create(context, name = null))
        batch.forEach { apply(it, android) }

        assertEquals(reference.project(), android.project())
        assertEquals(reference.allParents(), android.allParents())
    }
}

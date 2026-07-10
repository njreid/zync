package dev.njr.zync.server.sync

import dev.njr.zync.core.sync.BootstrapSnapshot
import dev.njr.zync.core.sync.PullResponse
import dev.njr.zync.core.sync.PushRequest
import dev.njr.zync.core.sync.PushResponse
import dev.njr.zync.core.sync.RegisterEntry
import dev.njr.zync.core.sync.TagEntry
import dev.njr.zync.core.sync.TombstoneEntry
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class DtoTest {
    private val json = Json
    private val opId = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val entity = Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVRZ")
    private val ctx = Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVQZ")
    private val hlc = Hlc(10, 0, "phone")

    private inline fun <reified T> roundTrip(serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
    }

    @Test
    fun pushRequestRoundTrips() {
        val op = Op.SetField(opId, entity, EntityType.Node, hlc, Actor.Human, "phone", 10, "title", JsonPrimitive("Buy milk"))
        roundTrip(PushRequest.serializer(), PushRequest(listOf(op)))
    }

    @Test
    fun pushResponseRoundTrips() {
        roundTrip(PushResponse.serializer(), PushResponse(listOf(opId, entity), serverHead = 42))
    }

    @Test
    fun pullResponseRoundTrips() {
        val op = Op.SetField(opId, entity, EntityType.Node, hlc, Actor.Human, "phone", 10, "title", JsonPrimitive("X"), seq = 7)
        roundTrip(PullResponse.serializer(), PullResponse(listOf(op), head = 7))
    }

    @Test
    fun bootstrapSnapshotRoundTrips() {
        val snapshot = BootstrapSnapshot(
            registers = listOf(RegisterEntry(entity, "title", JsonPrimitive("Buy milk"), hlc, Actor.Human)),
            tombstones = listOf(TombstoneEntry(entity, hlc)),
            tags = listOf(TagEntry(entity, ctx, present = true, hlc)),
            moves = listOf(Op.Move(opId, entity, EntityType.Node, hlc, Actor.Human, "phone", 10, newParentId = ctx)),
            headSeq = 99,
        )
        roundTrip(BootstrapSnapshot.serializer(), snapshot)
    }
}

package dev.njr.zync.core.op

import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id.Ulid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class OpTest {
    private val json = Json
    private val opId = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val entityId = Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVRZ")
    private val ctxId = Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVQZ")
    private val hlc = Hlc(10, 0, "phone")

    private fun roundTrip(op: Op) {
        val encoded = json.encodeToString(Op.serializer(), op)
        assertEquals(op, json.decodeFromString(Op.serializer(), encoded), "round-trip failed: $encoded")
    }

    @Test
    fun setFieldRoundTrips() = roundTrip(
        Op.SetField(opId, entityId, EntityType.Node, hlc, Actor.Human, "phone", 10, "title", JsonPrimitive("Buy milk")),
    )

    @Test
    fun moveRoundTrips() = roundTrip(
        Op.Move(opId, entityId, EntityType.Node, hlc, Actor.Human, "phone", 10, newParentId = ctxId),
    )

    @Test
    fun addTagRoundTrips() = roundTrip(
        Op.AddTag(opId, entityId, EntityType.Tag, hlc, Actor.Human, "phone", 10, contextId = ctxId),
    )

    @Test
    fun removeTagRoundTrips() = roundTrip(
        Op.RemoveTag(opId, entityId, EntityType.Tag, hlc, Actor.Human, "phone", 10, contextId = ctxId),
    )

    @Test
    fun addAttachmentRoundTrips() = roundTrip(
        Op.AddAttachment(
            opId, entityId, EntityType.Attachment, hlc, Actor.Human, "phone", 10,
            value = buildJsonObject {
                put("type", JsonPrimitive("image"))
                put("blobHash", JsonPrimitive("sha256:abc"))
                put("relativePath", JsonPrimitive("a/b.png"))
            },
        ),
    )

    @Test
    fun tombstoneRoundTrips() = roundTrip(
        Op.Tombstone(opId, entityId, EntityType.Node, hlc, Actor.Human, "phone", 10),
    )

    @Test
    fun operatorAndAgentActorsRoundTrip() {
        roundTrip(Op.SetField(opId, entityId, EntityType.Node, hlc, Actor.Operator("inbox-clarify"), "srv", 10, "summary", JsonPrimitive("Y")))
        roundTrip(Op.SetField(opId, entityId, EntityType.Node, hlc, Actor.Agent("research"), "srv", 10, "notes", JsonPrimitive("Z")))
    }

    @Test
    fun seqSurvivesRoundTripWhenAssigned() = roundTrip(
        Op.SetField(opId, entityId, EntityType.Node, hlc, Actor.Human, "phone", 10, "title", JsonPrimitive("X"), seq = 42),
    )

    /** Golden wire format — locks the on-disk/on-wire encoding against silent drift. */
    @Test
    fun goldenWireFormat() {
        val op = Op.SetField(opId, entityId, EntityType.Node, hlc, Actor.Human, "phone", 10, "title", JsonPrimitive("Buy milk"))
        val expected = """{"type":"set_field","opId":"01ARZ3NDEKTSV4RRFFQ69G5FAV",""" +
            """"entityId":"01BX5ZZKBKACTAV9WEVGEMMVRZ","entityType":"Node",""" +
            """"hlc":{"physical":10,"counter":0,"deviceId":"phone"},"actor":{"type":"human"},""" +
            """"deviceId":"phone","wallClock":10,"field":"title","value":"Buy milk"}"""
        assertEquals(expected, json.encodeToString(Op.serializer(), op))
    }
}

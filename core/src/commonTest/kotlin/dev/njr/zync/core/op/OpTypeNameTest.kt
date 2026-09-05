package dev.njr.zync.core.op

import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class OpTypeNameTest {
    private val hlc = Hlc(1, 0, "d")

    @Test
    fun typeNameMatchesTheSerialNameForEverySubtype() {
        assertEquals("set_field", Op.SetField(id(1), id(2), EntityType.Node, hlc, Actor.Human, "d", 0, "f", JsonPrimitive("test")).typeName)
        assertEquals("move", Op.Move(id(1), id(2), EntityType.Node, hlc, Actor.Human, "d", 0, id(3)).typeName)
        assertEquals("add_tag", Op.AddTag(id(1), id(2), EntityType.Tag, hlc, Actor.Human, "d", 0, id(3)).typeName)
        assertEquals("remove_tag", Op.RemoveTag(id(1), id(2), EntityType.Tag, hlc, Actor.Human, "d", 0, id(3)).typeName)
        assertEquals("add_attachment", Op.AddAttachment(id(1), id(2), EntityType.Attachment, hlc, Actor.Human, "d", 0, JsonPrimitive("test")).typeName)
        assertEquals("tombstone", Op.Tombstone(id(1), id(2), EntityType.Node, hlc, Actor.Human, "d", 0).typeName)
    }
}

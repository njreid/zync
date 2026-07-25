package dev.njr.zync.web

import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.ATTACHMENT_FIELD
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.RecordingEmitter
import dev.njr.zync.web.views.referenceItemView
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Reference UI: path resolution + tree helpers, and the item view's markdown resolution. */
class ReferenceViewTest {
    private val store = InMemoryStateStore()
    private val emitter = RecordingEmitter(store)
    private val commands = ContentCommands(emitter)
    private val read = ContentReadModel(store)

    @Test
    fun pathResolutionAndTreeHelpers() {
        val family = commands.createFolder("Family", WellKnownNodes.REFERENCE_ROOT)
        val letters = commands.createFolder("Letters", family)
        val note = commands.createTask("note").also { commands.move(it, letters) }

        assertEquals(letters, read.referenceNodeByPath("/Family/Letters"))
        assertEquals(note, read.referenceNodeByPath("/family/letters/note")) // case-insensitive
        assertNull(read.referenceNodeByPath("/Family/Nope"))
        assertEquals(listOf("Family", "Letters"), read.referenceAncestors(note).map { it.title })
        assertEquals("Family / Letters", read.referenceCrumb(note))
        assertEquals(listOf("Letters"), read.referenceChildren(family).map { it.title })
    }

    @Test
    fun itemViewResolvesLinksAndImages() {
        val family = commands.createFolder("Family", WellKnownNodes.REFERENCE_ROOT)
        val letters = commands.createFolder("Letters", family)
        val item = commands.createTask("note").also { commands.move(it, family) }
        commands.setNotes(item, "See [letters](/Family/Letters) and ![pic](photo.png).")
        // Attach a blob named photo.png to the item.
        val att: Ulid = emitter.newId()
        emitter.setField(
            att, ATTACHMENT_FIELD,
            buildJsonObject {
                put("nodeId", JsonPrimitive(item.toString()))
                put("relativePath", JsonPrimitive("photo.png"))
                put("blobHash", JsonPrimitive("blob-abc123"))
                put("type", JsonPrimitive("image/png"))
            },
        )

        val html = WebPlatform.renderFragment("ref") { referenceItemView(read, read.node(item)!!) }
        assertTrue(html.contains("href=\"/reference/$letters\""), "reference-path link resolved: $html")
        assertTrue(html.contains("src=\"/blob/blob-abc123\""), "attachment image resolved: $html")
        // An unresolvable "/"-path stays literal in the body (rendered as text, not a broken link).
        commands.setNotes(item, "dead [x](/Nope/Missing)")
        val html2 = WebPlatform.renderFragment("ref") { referenceItemView(read, read.node(item)!!) }
        assertTrue(html2.contains("[x](/Nope/Missing)"), html2)
    }

    /** The context-menu operations: subtree ids for recursive delete, add-child, and move. */
    @Test
    fun contextMenuOperations() {
        val a = commands.createFolder("A", WellKnownNodes.REFERENCE_ROOT)
        val sub = commands.createFolder("Sub", a)
        val leaf = commands.createTask("leaf").also { commands.move(it, sub) }
        val b = commands.createFolder("B", WellKnownNodes.REFERENCE_ROOT)

        // Recursive delete gathers the folder + all descendants.
        assertEquals(setOf(a, sub, leaf), read.subtreeIds(a).toSet())

        // Move the leaf under B; it re-parents.
        commands.move(leaf, b)
        assertEquals(b.toString(), read.node(leaf)!!.parent.toString())

        // referenceFolders lists every folder by path (for the move picker).
        val folders = read.referenceFolders().map { it.path }.toSet()
        assertTrue(folders.containsAll(setOf("A", "A / Sub", "B")), "folders: $folders")

        // Recursive purge removes the whole subtree.
        read.subtreeIds(a).forEach { commands.purge(it) }
        assertNull(read.node(a))
        assertNull(read.node(sub))
    }
}

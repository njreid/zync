package dev.njr.zync.data

import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.state.RegisterKey
import dev.njr.zync.core.state.RegisterValue
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SQLite keyword search: reindex-on-write, rename, tombstone, FILED, non-searchable kinds. */
class SqlDelightSearchTest {
    private val id = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private fun store() = SqlDelightStateStore(JvmZyncDatabase.inMemory())
    private var counter = 0L

    private fun SqlDelightStateStore.set(entity: Ulid, field: String, value: String) =
        putRegister(RegisterKey(entity, field), RegisterValue(JsonPrimitive(value), Hlc(++counter, 0, "d"), Actor.Human))

    @Test
    fun indexesAndFindsByTextTokens() {
        val s = store()
        s.set(id, "kind", "task")
        s.set(id, "title", "Quarterly Budget review")
        s.set(id, "notes", "spreadsheet in Drive")
        assertEquals(listOf(id), s.search("budget"))
        assertEquals(listOf(id), s.search("quart"))          // substring/prefix
        assertEquals(listOf(id), s.search("budget drive"))    // AND across fields
        assertTrue(s.search("nonexistent").isEmpty())
    }

    @Test
    fun renameDropsOldTermAndAddsNew() {
        val s = store()
        s.set(id, "kind", "task")
        s.set(id, "title", "old widget")
        assertEquals(listOf(id), s.search("widget"))
        s.set(id, "title", "new gadget")
        assertTrue(s.search("widget").isEmpty(), "old term should drop after rename")
        assertEquals(listOf(id), s.search("gadget"))
    }

    @Test
    fun tombstoneRemovesFromIndex() {
        val s = store()
        s.set(id, "kind", "task"); s.set(id, "title", "ephemeral")
        assertEquals(listOf(id), s.search("ephemeral"))
        s.putTombstone(id, Hlc(++counter, 0, "d"))
        assertTrue(s.search("ephemeral").isEmpty())
    }

    @Test
    fun filedItemsStaySearchable() {
        val s = store()
        s.set(id, "kind", "task"); s.set(id, "title", "archived receipt")
        s.set(id, "status", "FILED")
        assertEquals(listOf(id), s.search("receipt"))
    }

    @Test
    fun nonSearchableKindsAreNotIndexed() {
        val s = store()
        s.set(id, "kind", "context"); s.set(id, "name", "errands")
        assertTrue(s.search("errands").isEmpty())
    }
}

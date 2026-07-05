package dev.njr.zync.attach

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `writes content addressed files under root`() {
        val root = temp.newFolder("Zync")
        val store = AttachmentStore(root)

        val relativePath = store.writeContent("hello".toByteArray(), ".m4a")

        assertTrue(relativePath.matches(Regex("attachments/[0-9a-f]{2}/[0-9a-f]{64}\\.m4a")))
        assertArrayEquals("hello".toByteArray(), store.read(relativePath))
    }

    @Test
    fun `same content produces same relative path`() {
        val store = AttachmentStore(temp.newFolder("Zync"))

        val first = store.writeContent("same".toByteArray(), "pdf")
        val second = store.writeContent("same".toByteArray(), "pdf")

        assertEquals(first, second)
    }

    @Test
    fun `rejects paths outside the attachment root`() {
        val store = AttachmentStore(temp.newFolder("Zync"))

        assertIllegalArgument { store.write("../escape.txt", "bad".toByteArray()) }
        assertIllegalArgument { store.write("/tmp/escape.txt", "bad".toByteArray()) }
        assertIllegalArgument { store.writeContent("bad".toByteArray(), "../txt") }
    }

    @Test
    fun `delete removes resolved file`() {
        val store = AttachmentStore(temp.newFolder("Zync"))
        val path = store.writeContent("bye".toByteArray(), "txt")

        assertTrue(store.delete(path))
        assertFalse(store.resolve(path).exists())
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}

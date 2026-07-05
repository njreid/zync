package dev.njr.zync.attach

import dev.njr.zync.data.AttachmentType
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AttachmentStoreTest {
    private lateinit var root: File
    private lateinit var store: AttachmentStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("zync-attach").toFile()
        store = AttachmentStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `write stores bytes and returns a content-addressed path under the type dir`() {
        val bytes = "hello audio".toByteArray()
        val rel = store.write(bytes, AttachmentType.AUDIO, "m4a")

        assertTrue("path should start with the type dir", rel.startsWith("audio/"))
        assertTrue("path should keep the extension", rel.endsWith(".m4a"))
        val file = File(root, rel)
        assertTrue("file should exist on disk", file.isFile)
        assertArrayEquals(bytes, file.readBytes())
        assertArrayEquals(bytes, store.read(rel))
    }

    @Test
    fun `identical content dedups to the same path`() {
        val a = store.write("same".toByteArray(), AttachmentType.PDF, "pdf")
        val b = store.write("same".toByteArray(), AttachmentType.PDF, "pdf")
        assertEquals(a, b)
    }

    @Test
    fun `different content yields different paths`() {
        val a = store.write("one".toByteArray(), AttachmentType.PDF, "pdf")
        val b = store.write("two".toByteArray(), AttachmentType.PDF, "pdf")
        assertNotEquals(a, b)
    }

    @Test
    fun `leading dot on extension is normalized`() {
        val rel = store.write("x".toByteArray(), AttachmentType.OCR_TEXT, ".txt")
        assertTrue(rel.endsWith(".txt"))
        assertFalse(rel.contains("..txt"))
    }

    @Test
    fun `read returns null and delete is false for a missing path`() {
        assertNull(store.read("audio/aa/nope.m4a"))
        assertFalse(store.delete("audio/aa/nope.m4a"))
    }

    @Test
    fun `delete removes a stored blob`() {
        val rel = store.write("bye".toByteArray(), AttachmentType.AUDIO, "m4a")
        assertTrue(store.delete(rel))
        assertNull(store.read(rel))
    }
}

package dev.njr.zync.server.blob

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlobServiceTest {
    private fun service(max: Long = 1024) = BlobService(InMemoryBlobStore(), maxBytes = max)

    @Test
    fun keyIsContentAddressedAndDeterministic() {
        val bytes = "hello".encodeToByteArray()
        assertEquals(blobKey(bytes), blobKey("hello".encodeToByteArray()))
        assertTrue(isValidBlobKey(blobKey(bytes)))
        // the key is derived from content, never chosen by a caller
        assertTrue(blobKey(bytes).startsWith("blob-"))
    }

    @Test
    fun storeThenFetchReturnsBytes() {
        val svc = service()
        val bytes = "attachment".encodeToByteArray()
        val key = svc.store(bytes)
        assertTrue(svc.fetch(key)!!.contentEquals(bytes))
    }

    @Test
    fun putIfAbsentDedupesIdenticalContent() {
        val store = InMemoryBlobStore()
        val svc = BlobService(store)
        val bytes = "dup".encodeToByteArray()
        val k1 = svc.store(bytes)
        val k2 = svc.store(bytes)
        assertEquals(k1, k2)
        assertTrue(store.exists(k1))
        // second store is a no-op write
        assertTrue(!store.putIfAbsent(k1, bytes))
    }

    @Test
    fun oversizedUploadRejected() {
        val svc = service(max = 8)
        val big = ByteArray(9)
        val ex = assertFailsWith<BlobTooLargeException> { svc.store(big) }
        assertEquals(9, ex.size)
    }

    @Test
    fun fetchMissingOrMalformedKeyReturnsNull() {
        val svc = service()
        assertNull(svc.fetch("blob-" + "0".repeat(64))) // valid shape, absent
        assertNull(svc.fetch("../../etc/passwd"))         // malformed → rejected before store
        assertNull(svc.fetch("blob-xyz"))                 // wrong length/charset
    }
}

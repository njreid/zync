package dev.njr.zync.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AllowedDeviceDaoTest {
    private lateinit var db: ZyncDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = ZyncDatabase.inMemory(context)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `insert then byPubkey returns the device`() = runTest {
        val id = db.allowedDeviceDao().insert(
            AllowedDeviceEntity(name = "Laptop", pubkey = "abc123", addedAt = 1000L, lastSeen = null)
        )
        val found = db.allowedDeviceDao().byPubkey("abc123")
        assertNotNull(found)
        assertEquals(id, found!!.id)
        assertEquals("Laptop", found.name)
        assertEquals(false, found.revoked)
    }

    @Test
    fun `byPubkey returns null when not found`() = runTest {
        assertNull(db.allowedDeviceDao().byPubkey("nope"))
    }

    @Test
    fun `duplicate pubkey insert throws`() = runTest {
        db.allowedDeviceDao().insert(AllowedDeviceEntity(name = "A", pubkey = "dup", addedAt = 1L, lastSeen = null))
        try {
            db.allowedDeviceDao().insert(AllowedDeviceEntity(name = "B", pubkey = "dup", addedAt = 2L, lastSeen = null))
            fail("expected unique constraint violation")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun `setRevoked flips the flag`() = runTest {
        val id = db.allowedDeviceDao().insert(AllowedDeviceEntity(name = "A", pubkey = "k1", addedAt = 1L, lastSeen = null))
        db.allowedDeviceDao().setRevoked(id, true)
        val found = db.allowedDeviceDao().byPubkey("k1")!!
        assertTrue(found.revoked)
    }

    @Test
    fun `observeAll emits inserted devices`() = runTest {
        db.allowedDeviceDao().insert(AllowedDeviceEntity(name = "A", pubkey = "k2", addedAt = 1L, lastSeen = null))
        val all = db.allowedDeviceDao().observeAll().first()
        assertEquals(1, all.size)
        assertEquals("A", all[0].name)
    }

    @Test
    fun `touch updates lastSeen`() = runTest {
        val id = db.allowedDeviceDao().insert(AllowedDeviceEntity(name = "A", pubkey = "k3", addedAt = 1L, lastSeen = null))
        db.allowedDeviceDao().touch(id, 5000L)
        val found = db.allowedDeviceDao().byPubkey("k3")!!
        assertEquals(5000L, found.lastSeen)
    }
}

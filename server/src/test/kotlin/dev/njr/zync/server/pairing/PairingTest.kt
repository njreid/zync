package dev.njr.zync.server.pairing

import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.auth.Ed25519
import dev.njr.zync.server.auth.SqlDeviceRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PairingTest {
    private fun manager(): Pair<SqlDeviceRegistry, PairingManager> {
        val db = JvmZyncDatabase.inMemory()
        val registry = SqlDeviceRegistry(db)
        return registry to PairingManager(db, registry)
    }

    private val devicePub = Ed25519.publicKeyFor(ByteArray(32) { (it + 1).toByte() })

    @Test
    fun redeemValidCodeRegistersDevice() {
        val (registry, mgr) = manager()
        val now = 1_000L
        val code = mgr.open(now)
        val result = mgr.redeem(code, devicePub, now + 1_000)
        val paired = assertIs<PairingResult.Paired>(result)
        assertEquals(PairingManager.fingerprint(devicePub), paired.deviceId)
        assertTrue(registry.publicKey(paired.deviceId)!!.contentEquals(devicePub))
        assertFalse(registry.isRevoked(paired.deviceId))
    }

    @Test
    fun unknownCodeRejected() {
        val (_, mgr) = manager()
        assertIs<PairingResult.Rejected>(mgr.redeem("NOTACODE", devicePub, 1_000))
    }

    @Test
    fun expiredCodeRejected() {
        val (_, mgr) = manager()
        val code = mgr.open(now = 1_000)
        val result = mgr.redeem(code, devicePub, now = 1_000 + PairingManager.DEFAULT_TTL + 1)
        assertIs<PairingResult.Rejected>(result)
    }

    @Test
    fun codeIsSingleUse() {
        val (_, mgr) = manager()
        val code = mgr.open(1_000)
        assertIs<PairingResult.Paired>(mgr.redeem(code, devicePub, 1_500))
        assertIs<PairingResult.Rejected>(mgr.redeem(code, devicePub, 1_600))
    }

    @Test
    fun serverIdentityPersistsAndSigns() {
        val keyFile = File.createTempFile("zync-id", ".key").apply { delete() }
        try {
            val a = ServerIdentity.loadOrCreate(keyFile.absolutePath)
            val b = ServerIdentity.loadOrCreate(keyFile.absolutePath) // reload from disk
            assertTrue(a.publicKey.contentEquals(b.publicKey), "identity must be stable across reloads")

            val msg = "hello".encodeToByteArray()
            assertTrue(Ed25519.verify(a.publicKey, msg, a.sign(msg)))
        } finally {
            keyFile.delete()
        }
    }

    @Test
    fun qrRendersNonEmpty() {
        val ascii = Qr.ascii("zync://pair?h=x&k=y&c=z&e=1")
        assertTrue(ascii.isNotBlank())
        assertTrue(ascii.any { it in "█▀▄" }, "expected block glyphs")
    }
}

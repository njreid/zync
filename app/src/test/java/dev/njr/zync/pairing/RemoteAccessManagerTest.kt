package dev.njr.zync.pairing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.data.AllowedDeviceDao
import dev.njr.zync.data.AllowedDeviceEntity
import dev.njr.zync.server.LanConfig
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** In-memory fake DAO — these tests only need `PairingService` to construct, not to be exercised. */
private class NoopAllowedDeviceDao : AllowedDeviceDao {
    override suspend fun insert(device: AllowedDeviceEntity): Long = 1L
    override suspend fun byPubkey(pubkey: String): AllowedDeviceEntity? = null
    override fun observeAll(): Flow<List<AllowedDeviceEntity>> = MutableStateFlow(emptyList())
    override suspend fun setRevoked(id: Long, revoked: Boolean) = Unit
    override suspend fun touch(id: Long, lastSeen: Long) = Unit
}

private class FakeNsdAdvertiser : NsdAdvertiser {
    var registeredPort: Int? = null
    var registeredName: String? = null
    var registeredFingerprintHint: String? = null
    var unregisterCalls = 0

    override fun register(port: Int, name: String, fingerprintHint: String) {
        registeredPort = port
        registeredName = name
        registeredFingerprintHint = fingerprintHint
    }

    override fun unregister() {
        unregisterCalls++
        registeredPort = null
    }
}

private class FakeWifiIpAddressProvider(private val ip: String?) : WifiIpAddressProvider {
    override fun currentIpv4(): String? = ip
}

private class FakeServerController : ServerController {
    var restartCount = 0
    var lastLan: LanConfig? = null

    override fun restart(lan: LanConfig?): ServerBinding {
        restartCount++
        lastLan = lan
        return ServerBinding(httpPort = 8080, tlsPort = if (lan != null) 8443 else null)
    }
}

/**
 * Reversible but non-trivial fake: real protection (Android Keystore AES-GCM, see
 * [AndroidKeystorePasswordProtector]) is only meaningfully verified on a real device/emulator
 * (Task 8) — this fake just proves [RemoteAccessManager]/[ServerCertStore] correctly round-trip
 * whatever the injected protector hands back, without asserting anything about real crypto.
 */
private class FakePasswordProtector : PasswordProtector {
    var protectCalls = 0

    override fun protect(plain: CharArray): ByteArray {
        protectCalls++
        return String(plain).toByteArray(Charsets.UTF_8).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    override fun unprotect(protected: ByteArray): CharArray =
        String(protected.map { (it.toInt() xor 0x5A).toByte() }.toByteArray(), Charsets.UTF_8).toCharArray()
}

@RunWith(RobolectricTestRunner::class)
class RemoteAccessManagerTest {

    private lateinit var filesDir: File
    private lateinit var pairingService: PairingService
    private lateinit var nsd: FakeNsdAdvertiser
    private lateinit var wifiIp: FakeWifiIpAddressProvider
    private lateinit var serverController: FakeServerController
    private lateinit var protector: FakePasswordProtector

    private fun newManager(): RemoteAccessManager = RemoteAccessManager(
        certStore = ServerCertStore(filesDir, protector),
        server = serverController,
        pairingService = pairingService,
        nsd = nsd,
        wifiIp = wifiIp,
        deviceName = "Test Phone",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        filesDir = context.filesDir
        pairingService = PairingService(NoopAllowedDeviceDao(), randomNonce = { "nonce" })
        nsd = FakeNsdAdvertiser()
        wifiIp = FakeWifiIpAddressProvider("192.168.1.42")
        serverController = FakeServerController()
        protector = FakePasswordProtector()
    }

    @Test
    fun `state starts disabled`() {
        assertEquals(RemoteState.Disabled, newManager().state())
    }

    @Test
    fun `enable persists a keystore under the app-private files dir`() {
        newManager().enable()

        val keystoreFile = File(filesDir, ServerCertStore.KEYSTORE_FILENAME)
        val passwordFile = File(filesDir, ServerCertStore.PASSWORD_FILENAME)
        assertTrue("expected persisted keystore at ${keystoreFile.path}", keystoreFile.exists())
        assertTrue("expected protected password at ${passwordFile.path}", passwordFile.exists())
        assertTrue(keystoreFile.readBytes().isNotEmpty())
    }

    @Test
    fun `enable returns ip, tls port and fingerprint, and registers NSD`() {
        val info = newManager().enable()

        assertEquals("192.168.1.42", info.ip)
        assertEquals(8443, info.tlsPort)
        assertTrue(info.certFingerprint.isNotBlank())

        assertEquals(8443, nsd.registeredPort)
        assertEquals("Test Phone", nsd.registeredName)
        assertNotNull(nsd.registeredFingerprintHint)
    }

    @Test
    fun `enable is idempotent within one manager instance`() {
        val manager = newManager()

        val first = manager.enable()
        val second = manager.enable()

        assertEquals(first, second)
        assertEquals("cert must not be regenerated on a repeated enable()", 1, serverController.restartCount)
        assertEquals("NSD must not be re-registered on a repeated enable()", 8443, nsd.registeredPort)
    }

    @Test
    fun `fingerprint is stable across restarts because the cert is reloaded, not regenerated`() {
        val first = newManager().enable()
        val keystoreBytesAfterFirst = File(filesDir, ServerCertStore.KEYSTORE_FILENAME).readBytes()

        // Simulate an app restart: a brand-new RemoteAccessManager (and ServerCertStore) backed
        // by the same on-disk files dir.
        val second = newManager().enable()
        val keystoreBytesAfterSecond = File(filesDir, ServerCertStore.KEYSTORE_FILENAME).readBytes()

        assertEquals(
            "fingerprint must survive an app restart so a paired desktop's TLS pin stays valid",
            first.certFingerprint,
            second.certFingerprint,
        )
        assertTrue(
            "the persisted keystore bytes must not change on reload (cert not regenerated)",
            keystoreBytesAfterFirst.contentEquals(keystoreBytesAfterSecond),
        )
        // protect() is only called when *generating* a fresh identity, not when reloading one.
        assertEquals(1, protector.protectCalls)
    }

    @Test
    fun `the random password round-trips through the protector to reload the keystore`() {
        newManager().enable()

        val store = ServerCertStore(filesDir, protector)
        val reloaded = store.load()

        assertNotNull(reloaded)
        // Successfully loading the PKCS12 keystore below proves the password round-tripped
        // correctly through protect()/unprotect() — a wrong password would throw here.
        val keyStore = java.security.KeyStore.getInstance("PKCS12")
        keyStore.load(reloaded!!.keyStoreBytes.inputStream(), reloaded.keyStorePassword)
        assertTrue(keyStore.isKeyEntry(reloaded.keyAlias))
    }

    @Test
    fun `disable unregisters NSD and drops the LAN connector`() {
        val manager = newManager()
        manager.enable()

        manager.disable()

        assertEquals(1, nsd.unregisterCalls)
        assertNull("expected the server to be restarted loopback-only", serverController.lastLan)
        assertEquals(RemoteState.Disabled, manager.state())
    }

    @Test
    fun `disable is idempotent when already disabled`() {
        val manager = newManager()

        manager.disable()

        assertEquals(0, nsd.unregisterCalls)
        assertEquals(0, serverController.restartCount)
    }

    @Test
    fun `state reflects enabled info after enable`() {
        val manager = newManager()
        val info = manager.enable()

        val state = manager.state()
        assertTrue(state is RemoteState.Enabled)
        assertEquals(info, (state as RemoteState.Enabled).info)
    }
}

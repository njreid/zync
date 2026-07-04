package dev.njr.zync.pairing

import dev.njr.zync.server.LanConfig
import java.security.KeyStore

/**
 * Thin seam between [RemoteAccessManager] and the actual running LAN
 * [dev.njr.zync.server.ZyncServer] instance, owned by `ZyncApp`. The permanent loopback server
 * (used by the in-app WebView) is never touched by this interface — it is started once and lives
 * for the process lifetime. Enabling/disabling remote access only starts/stops a *separate* LAN
 * server instance. Kept as an interface so [RemoteAccessManager] is JVM-testable without booting a
 * real Ktor/Netty server.
 */
interface ServerController {
    /** Starts (or restarts, if already running) the LAN server. Returns the resolved TLS port. */
    fun enableLan(lan: LanConfig): Int

    /** Stops the LAN server, if one is running. Idempotent. */
    fun disableLan()
}

/** [RemoteAccessManager.state] result. */
sealed interface RemoteState {
    data object Disabled : RemoteState
    data class Enabled(val info: RemoteInfo) : RemoteState
}

/** What a caller needs to display/advertise once remote access is enabled. */
data class RemoteInfo(val ip: String, val tlsPort: Int, val certFingerprint: String)

/**
 * Owns the remote-access (LAN + NSD) lifecycle for the pairing/sync server:
 *  - [enable] loads (or, on first use, generates) the persisted server TLS identity, re-provisions
 *    the server with a [LanConfig] bound to the current Wi-Fi IP, and starts NSD advertising.
 *  - [disable] stops NSD advertising and drops back to a loopback-only server. Paired devices
 *    (`AllowedDeviceDao` rows) are untouched — disabling remote access doesn't un-pair anything.
 *
 * The server's TLS identity is persisted (see [ServerCertStore]) so its fingerprint — and
 * therefore a paired desktop's certificate pin — is stable across [enable] calls and app
 * restarts; devices don't need to re-pair just because remote access was toggled off and on.
 */
class RemoteAccessManager(
    private val certStore: ServerCertStore,
    private val server: ServerController,
    private val pairingService: PairingService,
    private val nsd: NsdAdvertiser,
    private val wifiIp: WifiIpAddressProvider,
    private val deviceName: String,
) {
    @Volatile private var state: RemoteState = RemoteState.Disabled

    /**
     * Idempotent: if remote access is already enabled, returns the existing [RemoteInfo] without
     * regenerating the cert, re-registering NSD, or restarting the server.
     */
    @Synchronized
    fun enable(): RemoteInfo {
        (state as? RemoteState.Enabled)?.let { return it.info }

        val identity = certStore.loadOrCreate()
        pairingService.setCertFingerprint(identity.certFingerprintSha256)

        val keyStore = KeyStore.getInstance(
            Crypto.KEYSTORE_TYPE,
            org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME,
        ).apply {
            load(identity.keyStoreBytes.inputStream(), identity.keyStorePassword)
        }
        val lan = LanConfig(
            keyStore = keyStore,
            keyStorePassword = identity.keyStorePassword,
            keyAlias = identity.keyAlias,
            host = "0.0.0.0",
        )
        val tlsPort = server.enableLan(lan)

        val ip = wifiIp.currentIpv4() ?: "0.0.0.0"
        nsd.register(tlsPort, deviceName, shortFingerprintHint(identity.certFingerprintSha256))

        val info = RemoteInfo(ip = ip, tlsPort = tlsPort, certFingerprint = identity.certFingerprintSha256)
        state = RemoteState.Enabled(info)
        return info
    }

    /** Idempotent: safe to call when already disabled. */
    @Synchronized
    fun disable() {
        if (state == RemoteState.Disabled) return
        nsd.unregister()
        server.disableLan()
        state = RemoteState.Disabled
    }

    fun state(): RemoteState = state

    /** Short, human-copyable prefix of the fingerprint for the NSD TXT record. */
    private fun shortFingerprintHint(fingerprint: String): String =
        fingerprint.replace(":", "").take(8)
}

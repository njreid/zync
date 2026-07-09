package dev.njr.zync.server.auth

/**
 * Allowed-device registry: maps a deviceId to its Ed25519 public key and tracks
 * revocation. Ops from an unknown or revoked device are rejected (spec §6). In
 * prod this is seeded from configuration/SSM; the in-memory impl backs tests and
 * small deployments.
 */
interface DeviceRegistry {
    fun publicKey(deviceId: String): ByteArray?
    fun isRevoked(deviceId: String): Boolean
}

class InMemoryDeviceRegistry : DeviceRegistry {
    private data class Entry(val publicKey: ByteArray, var revoked: Boolean)
    private val devices = mutableMapOf<String, Entry>()

    @Synchronized
    fun register(deviceId: String, publicKey: ByteArray) {
        devices[deviceId] = Entry(publicKey.copyOf(), revoked = false)
    }

    @Synchronized
    fun revoke(deviceId: String) {
        devices[deviceId]?.revoked = true
    }

    @Synchronized
    override fun publicKey(deviceId: String): ByteArray? = devices[deviceId]?.publicKey?.copyOf()

    @Synchronized
    override fun isRevoked(deviceId: String): Boolean = devices[deviceId]?.revoked ?: false
}

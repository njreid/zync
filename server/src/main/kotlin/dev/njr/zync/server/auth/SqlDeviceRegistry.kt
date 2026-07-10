package dev.njr.zync.server.auth

import dev.njr.zync.data.db.ZyncDatabase
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Persistent [DeviceRegistry] backed by the `device` table — paired devices survive
 * restarts. Public keys are stored base64.
 */
@OptIn(ExperimentalEncodingApi::class)
class SqlDeviceRegistry(private val db: ZyncDatabase) : DeviceRegistry {

    fun register(deviceId: String, publicKey: ByteArray, pairedAt: Long) {
        db.deviceQueries.upsertDevice(deviceId, Base64.encode(publicKey), pairedAt)
    }

    fun revoke(deviceId: String) {
        db.deviceQueries.revokeDevice(deviceId)
    }

    override fun publicKey(deviceId: String): ByteArray? =
        db.deviceQueries.getDevice(deviceId).executeAsOneOrNull()?.let { Base64.decode(it.public_key) }

    override fun isRevoked(deviceId: String): Boolean =
        db.deviceQueries.getDevice(deviceId).executeAsOneOrNull()?.revoked == 1L
}

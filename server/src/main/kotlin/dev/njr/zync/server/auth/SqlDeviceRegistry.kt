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

    fun register(deviceId: String, publicKey: ByteArray, pairedAt: Long, replicaId: String) {
        db.deviceQueries.upsertDevice(deviceId, Base64.encode(publicKey), pairedAt, replicaId)
    }

    /**
     * The op-authoring replica id bound to this pairing, or null for a device paired
     * before schema v2 (which must re-pair before `/sync/push` accepts its ops).
     */
    fun replicaId(deviceId: String): String? =
        db.deviceQueries.getDevice(deviceId).executeAsOneOrNull()?.replica_id

    fun revoke(deviceId: String) {
        db.deviceQueries.revokeDevice(deviceId)
    }

    override fun publicKey(deviceId: String): ByteArray? =
        db.deviceQueries.getDevice(deviceId).executeAsOneOrNull()?.let { Base64.decode(it.public_key) }

    override fun isRevoked(deviceId: String): Boolean =
        db.deviceQueries.getDevice(deviceId).executeAsOneOrNull()?.revoked == 1L
}

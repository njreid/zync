package dev.njr.zync.replica

import android.content.Context
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Persists the phone's paired-central-server credentials (from [PairingClient]): the
 * server address + pinned public key, the assigned deviceId, and the device private
 * seed. SharedPreferences-backed; the device seed should move to the Android Keystore
 * in a hardening pass.
 */
@OptIn(ExperimentalEncodingApi::class)
class AndroidPairingStore(context: Context) : PairingStore {
    private val prefs = context.getSharedPreferences("zync_pairing", Context.MODE_PRIVATE)

    override fun save(server: PairedServer) {
        prefs.edit()
            .putString("address", server.address)
            .putString("serverKey", Base64.encode(server.serverPublicKey))
            .putString("deviceId", server.deviceId)
            .putString("deviceSeed", Base64.encode(server.deviceSeed))
            .apply()
    }

    override fun load(): PairedServer? {
        val address = prefs.getString("address", null) ?: return null
        val serverKey = prefs.getString("serverKey", null) ?: return null
        val deviceId = prefs.getString("deviceId", null) ?: return null
        val deviceSeed = prefs.getString("deviceSeed", null) ?: return null
        return PairedServer(address, Base64.decode(serverKey), deviceId, Base64.decode(deviceSeed))
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

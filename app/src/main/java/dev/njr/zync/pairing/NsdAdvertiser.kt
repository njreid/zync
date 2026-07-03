package dev.njr.zync.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises the LAN pairing/sync server over mDNS/DNS-SD (`_zync._tcp`) so a desktop client on
 * the same network can discover it without the user typing in an IP address.
 *
 * Kept as an interface (rather than calling `NsdManager` directly from [RemoteAccessManager]) so
 * the lifecycle logic is JVM-testable with a fake: real NSD registration requires an Android
 * platform service and an actual socket/network stack, which only a real device or emulator can
 * exercise meaningfully (see Task 8 instrumented tests) — a Robolectric shadow would only be
 * testing the shadow, not real mDNS behavior.
 */
interface NsdAdvertiser {
    /**
     * Registers (or re-registers, superseding any prior registration) `_zync._tcp` on [port],
     * advertised under [name], with [fingerprintHint] published as short TXT-record metadata so a
     * scanning desktop can pre-filter candidates before starting the full pairing/TLS handshake.
     */
    fun register(port: Int, name: String, fingerprintHint: String)

    /** Idempotent: unregisters the current advertisement, if any. Safe to call when not registered. */
    fun unregister()
}

/** Production [NsdAdvertiser] backed by the platform `NsdManager`. */
class AndroidNsdAdvertiser(context: Context) : NsdAdvertiser {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    override fun register(port: Int, name: String, fingerprintHint: String) {
        unregister()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("fp", fingerprintHint)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed for ${info.serviceName}: error $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed for ${info.serviceName}: error $errorCode")
            }
        }
        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override fun unregister() {
        val listener = registrationListener ?: return
        registrationListener = null
        try {
            nsdManager.unregisterService(listener)
        } catch (_: IllegalArgumentException) {
            // Not currently registered (e.g. registration never completed) — nothing to undo.
        }
    }

    companion object {
        private const val TAG = "AndroidNsdAdvertiser"
        private const val SERVICE_TYPE = "_zync._tcp"
    }
}

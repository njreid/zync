package dev.njr.zync.pairing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * Resolves the device's current Wi-Fi IPv4 address, for display/advertising when remote access is
 * enabled. Kept as an interface so [RemoteAccessManager] is JVM-testable via a fake — the real
 * implementation depends on live `ConnectivityManager` state that Robolectric only partially
 * shadows and that only means anything on a real network (see Task 8 instrumented tests).
 */
fun interface WifiIpAddressProvider {
    /** The current Wi-Fi IPv4 address, or `null` if there is no active Wi-Fi network. */
    fun currentIpv4(): String?
}

/**
 * Production [WifiIpAddressProvider]: reads the active network's link addresses via
 * `ConnectivityManager`/`LinkProperties` rather than the deprecated `WifiManager.getIpAddress`,
 * which doesn't support IPv6 and is unreliable on newer Android versions.
 */
class ConnectivityWifiIpAddressProvider(private val context: Context) : WifiIpAddressProvider {
    override fun currentIpv4(): String? {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return null
        val capabilities = cm.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val linkProperties = cm.getLinkProperties(network) ?: return null
        return linkProperties.linkAddresses
            .mapNotNull { it.address as? Inet4Address }
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
}

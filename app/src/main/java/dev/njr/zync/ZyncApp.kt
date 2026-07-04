package dev.njr.zync

import android.app.Application
import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
import dev.njr.zync.pairing.AndroidKeystorePasswordProtector
import dev.njr.zync.pairing.AndroidNsdAdvertiser
import dev.njr.zync.pairing.ConnectivityWifiIpAddressProvider
import dev.njr.zync.pairing.PairingService
import dev.njr.zync.pairing.RemoteAccessManager
import dev.njr.zync.pairing.ServerBinding
import dev.njr.zync.pairing.ServerCertStore
import dev.njr.zync.pairing.ServerController
import dev.njr.zync.server.LanConfig
import dev.njr.zync.server.ZyncServer
import dev.njr.zync.server.androidAssets
import java.util.UUID

class ZyncApp : Application() {
    val database: ZyncDatabase by lazy { ZyncDatabase.build(this) }
    val repository: NodeRepository by lazy { NodeRepository(database) }
    val serverToken: String = UUID.randomUUID().toString()

    val pairingService: PairingService by lazy {
        PairingService(database.allowedDeviceDao(), randomNonce = { UUID.randomUUID().toString() })
    }

    val remoteAccess: RemoteAccessManager by lazy {
        RemoteAccessManager(
            certStore = ServerCertStore(filesDir, AndroidKeystorePasswordProtector()),
            server = serverController,
            pairingService = pairingService,
            nsd = AndroidNsdAdvertiser(this),
            wifiIp = ConnectivityWifiIpAddressProvider(this),
            deviceName = android.os.Build.MODEL ?: "zync",
        ).also { pairingService.remoteAccess = it }
    }

    private var server: ZyncServer? = null
    private var boundPort: Int = -1

    /**
     * Bridges [RemoteAccessManager] to the actual [ZyncServer] instance: `ZyncServer`'s
     * [LanConfig] is fixed at construction, so toggling remote access on/off means stopping the
     * current server and starting a new one with (or without) a `LanConfig` bound to it.
     */
    private val serverController = object : ServerController {
        @Synchronized
        override fun restart(lan: LanConfig?): ServerBinding {
            server?.stop()
            val newServer = ZyncServer(
                database,
                repository,
                serverToken,
                androidAssets(assets),
                lan = lan,
                pairing = pairingService,
            )
            val httpPort = newServer.start()
            server = newServer
            boundPort = httpPort
            return ServerBinding(httpPort = httpPort, tlsPort = newServer.tlsPort())
        }
    }

    /**
     * Blocks while binding — call from a background thread. Starts the server loopback-only;
     * call [remoteAccess]`.enable()` to additionally bind a LAN HTTPS connector.
     */
    @Synchronized
    fun ensureServerStarted(): Int {
        if (server == null) {
            serverController.restart(null)
        }
        return boundPort
    }
}

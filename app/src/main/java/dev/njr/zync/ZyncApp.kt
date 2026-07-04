package dev.njr.zync

import android.app.Application
import android.util.Log
import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
import dev.njr.zync.pairing.AndroidKeystorePasswordProtector
import dev.njr.zync.pairing.AndroidNsdAdvertiser
import dev.njr.zync.pairing.ConnectivityWifiIpAddressProvider
import dev.njr.zync.pairing.PairingService
import dev.njr.zync.pairing.RemoteAccessManager
import dev.njr.zync.pairing.ServerCertStore
import dev.njr.zync.pairing.ServerController
import dev.njr.zync.server.LanConfig
import dev.njr.zync.server.ZyncServer
import dev.njr.zync.server.androidAssets
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private const val TAG = "zync"

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

    /**
     * The PERMANENT loopback server: started once, on first [ensureServerStarted] call, bound to
     * `127.0.0.1` on a port that never changes for the life of the process. The in-app WebView
     * loads `http://127.0.0.1:<port>/...` once at launch and never again — so this server (and
     * this port) must never be restarted or replaced, or the WebView's connection dies underneath
     * it (see m1c final-review CRIT-A: the old single-server `restart()` design killed the very
     * server the WebView was talking to a few seconds after any remote-access toggle).
     */
    @Volatile private var loopbackServer: ZyncServer? = null
    @Volatile private var loopbackPort: Int = -1

    /** The separate LAN (HTTPS) server, created by [RemoteAccessManager.enable] and destroyed by
     * [RemoteAccessManager.disable]. `null` when remote access is disabled. Never affects
     * [loopbackServer] — the two are independent Netty engines sharing the same [repository]/
     * [pairingService]/[database]/assets. */
    @Volatile private var lanServer: ZyncServer? = null

    /**
     * Serializes all server start/stop work onto a single dedicated background thread — never the
     * caller's thread. This matters because [ServerController] methods can be invoked from inside
     * a Ktor/Netty request-handling coroutine (`POST /remote/enable`, `/remote/disable`), which by
     * default runs on the Netty engine's own event-loop worker threads. Unlike the old single-
     * server design, `/remote/enable` and `/remote/disable` are only ever served by the *loopback*
     * engine (see `requireLoopbackConnector` in `PairingRoutes.kt`) and only ever start/stop the
     * *LAN* engine — a different `EmbeddedServer` instance — so there is no self-shutdown/circular
     * -wait hazard here: a handler can safely await the LAN engine's stop()/start() synchronously
     * without ever blocking on itself. Being a single thread also gives us the mutual exclusion
     * previously spread across separate locks, without any lock-ordering hazard between them.
     */
    private val serverExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "zync-server") }

    private val serverController = object : ServerController {
        override fun enableLan(lan: LanConfig): Int =
            serverExecutor.submit(
                Callable {
                    try {
                        lanServer?.stop()
                        val newLan = ZyncServer(
                            database,
                            repository,
                            serverToken,
                            androidAssets(assets),
                            lan = lan,
                            pairing = pairingService,
                        )
                        newLan.start()
                        lanServer = newLan
                        newLan.tlsPort()
                            ?: error("LAN ZyncServer.start() returned no TLS port despite a LanConfig")
                    } catch (t: Throwable) {
                        Log.e(TAG, "enabling LAN server failed", t)
                        throw t
                    }
                },
            ).get()

        override fun disableLan() {
            serverExecutor.submit(
                Callable {
                    try {
                        lanServer?.stop()
                        lanServer = null
                    } catch (t: Throwable) {
                        Log.e(TAG, "disabling LAN server failed", t)
                        throw t
                    }
                },
            ).get()
        }
    }

    /**
     * Blocks while binding — call from a background thread. Starts (once) the permanent loopback
     * server; a no-op on subsequent calls. Call [remoteAccess]`.enable()` to additionally start a
     * separate LAN HTTPS server.
     */
    fun ensureServerStarted(): Int {
        if (loopbackServer == null) {
            serverExecutor.submit(
                Callable {
                    if (loopbackServer == null) {
                        val s = ZyncServer(
                            database,
                            repository,
                            serverToken,
                            androidAssets(assets),
                            lan = null,
                            pairing = pairingService,
                        )
                        loopbackPort = s.start()
                        loopbackServer = s
                    }
                },
            ).get()
        }
        return loopbackPort
    }

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "uncaught exception on thread ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}

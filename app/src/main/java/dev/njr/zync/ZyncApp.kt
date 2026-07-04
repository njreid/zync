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
import dev.njr.zync.pairing.ServerBinding
import dev.njr.zync.pairing.ServerCertStore
import dev.njr.zync.pairing.ServerController
import dev.njr.zync.server.LanConfig
import dev.njr.zync.server.ZyncServer
import dev.njr.zync.server.androidAssets
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private const val TAG = "zync"
private const val OLD_SERVER_STOP_DELAY_MS = 3000L

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

    @Volatile private var server: ZyncServer? = null
    @Volatile private var boundPort: Int = -1

    /**
     * All server start/swap work is serialized onto this single dedicated background thread —
     * never the caller's thread. This matters because `restart()` can be invoked from inside a
     * Ktor/Netty request-handling coroutine (`POST /remote/enable`, `/remote/disable`), which by
     * default runs *on the Netty engine's own event-loop worker threads*. Being a single thread
     * also gives us the mutual exclusion previously spread across three separate `@Synchronized`
     * locks (this class, the anonymous `ServerController`, and `RemoteAccessManager`) for free,
     * without any lock-ordering hazard between them.
     */
    private val restartExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "zync-server-restart") }

    /**
     * Stops old (superseded) [ZyncServer] instances, always off the [restartExecutor] and never
     * synchronously awaited by [restartOnExecutor] — see that function's doc for why.
     */
    private val stopExecutor =
        Executors.newCachedThreadPool { r -> Thread(r, "zync-server-stop").apply { isDaemon = true } }

    /**
     * On-device root cause (see m1c-task-8 report): the previous implementation called
     * `server?.stop()` *before* creating/starting the replacement server, and did so
     * synchronously from whatever thread called `restart()` — including, for `POST
     * /remote/enable`/`/remote/disable`, the very request-handling coroutine currently being
     * served *by the server being stopped*. `EmbeddedServer.stop(gracePeriodMillis,
     * timeoutMillis)` waits (up to `gracePeriodMillis`) for in-flight requests — including this
     * one — to finish before closing connections, but this request can't finish until its handler
     * returns, and its handler is blocked inside `stop()` waiting for exactly that: a circular
     * wait. After the grace/timeout period elapsed, Netty force-closed the connection with no
     * response ever sent — exactly the "Failed to fetch" symptom observed on-device, and (in the
     * single-threaded call path) could wedge the Netty event loop outright.
     *
     * The fix is ordering, not just threading: build and start the *new* server first (a fresh
     * engine, unrelated to any in-flight request), swap the app's live reference to it, and only
     * *afterwards* stop the *old* one — asynchronously, on [stopExecutor], without ever awaiting
     * that stop. By the time the old engine is asked to shut down, the handler that triggered
     * this restart has already returned its response (using the *new* server's resolved ports),
     * and Ktor/Netty is free to flush that response over the old engine's still-open connection
     * before this task ever touches it. No thread ever blocks waiting for a server to stop, so
     * there is no self-shutdown/deadlock hazard regardless of which thread/dispatcher called
     * `restart()`.
     */
    private fun restartOnExecutor(lan: LanConfig?): ServerBinding =
        restartExecutor.submit(
            Callable {
                try {
                    val oldServer = server
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
                    val binding = ServerBinding(httpPort = httpPort, tlsPort = newServer.tlsPort())
                    if (oldServer != null) {
                        stopExecutor.execute {
                            try {
                                // Give the response to *this* restart's own triggering request
                                // (e.g. POST /remote/enable, served by `oldServer`) time to
                                // actually flush before the old engine is asked to shut down.
                                // That response is composed by the caller *after* this whole
                                // restart() call returns, so without this delay the old engine's
                                // stop() (and its own grace/timeout window) can race the still
                                // in-flight response and win, closing the connection before a
                                // single byte of the reply goes out — observed on-device as
                                // "Failed to fetch" despite the restart itself having fully
                                // succeeded (both connectors up, NSD registered).
                                Thread.sleep(OLD_SERVER_STOP_DELAY_MS)
                                oldServer.stop()
                            } catch (t: Throwable) {
                                Log.e(TAG, "stopping superseded server failed", t)
                            }
                        }
                    }
                    binding
                } catch (t: Throwable) {
                    Log.e(TAG, "server restart failed (lan=${lan != null})", t)
                    throw t
                }
            },
        ).get()

    /**
     * Bridges [RemoteAccessManager] to the actual [ZyncServer] instance: `ZyncServer`'s
     * [LanConfig] is fixed at construction, so toggling remote access on/off means stopping the
     * current server and starting a new one with (or without) a `LanConfig` bound to it.
     */
    private val serverController = object : ServerController {
        override fun restart(lan: LanConfig?): ServerBinding = restartOnExecutor(lan)
    }

    /**
     * Blocks while binding — call from a background thread. Starts the server loopback-only;
     * call [remoteAccess]`.enable()` to additionally bind a LAN HTTPS connector.
     */
    fun ensureServerStarted(): Int {
        if (server == null) {
            restartOnExecutor(null)
        }
        return boundPort
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

package dev.njr.zync

import android.app.Application
import android.content.Context
import android.util.Log
import dev.njr.zync.core.clock.Clock
import dev.njr.zync.data.AndroidZyncDatabase
import dev.njr.zync.data.SqlDelightStateStore
import dev.njr.zync.replica.AndroidHlcStore
import dev.njr.zync.replica.LocalBlobStore
import dev.njr.zync.replica.LocalHlc
import dev.njr.zync.replica.OpWriter
import dev.njr.zync.replica.PhoneOpEmitter
import dev.njr.zync.replica.ReplicaCapture
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.sse.ChangeNotifier
import java.io.File
import kotlin.random.Random
import dev.njr.zync.server.ZyncServer
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private const val TAG = "zync"

class ZyncApp : Application() {
    val serverToken: String = UUID.randomUUID().toString()

    // --- Op-log stack (M7): the phone as a replica; the shared :web UI reads/writes this ---
    private val opClock = Clock { System.currentTimeMillis() }

    /** Stable per-device id for HLCs (independent of the server-assigned pairing deviceId). */
    val deviceId: String by lazy {
        getSharedPreferences("zync_device", Context.MODE_PRIVATE).let { prefs ->
            prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
        }
    }
    val opDatabase: dev.njr.zync.data.db.ZyncDatabase by lazy { AndroidZyncDatabase.create(this) }
    val opStore: SqlDelightStateStore by lazy { SqlDelightStateStore(opDatabase) }
    val localBlobs: LocalBlobStore by lazy { LocalBlobStore(File(filesDir, "blobs")) }
    /** Shared HLC: the op writer issues, the sync client observes — one clock. */
    val localHlc: LocalHlc by lazy { LocalHlc(AndroidHlcStore(this), deviceId, opClock) }
    val pairingStore: dev.njr.zync.replica.PairingStore by lazy { dev.njr.zync.replica.AndroidPairingStore(this) }
    val opWriter: OpWriter by lazy {
        OpWriter(opDatabase, opStore, localHlc, deviceId, opClock, Random.Default)
    }
    val replicaCapture: ReplicaCapture by lazy { ReplicaCapture(opWriter, localBlobs, inbox = { null }) }
    val contentChanges: ChangeNotifier = ChangeNotifier()
    val contentRead: ContentReadModel by lazy { ContentReadModel(opStore) }
    val contentCommands: ContentCommands by lazy {
        ContentCommands(PhoneOpEmitter(opWriter) { dev.njr.zync.sync.SyncScheduler.requestSync(this) })
    }

    /** The shared :web UI surface the loopback server serves. */
    val webContent: dev.njr.zync.server.WebContent by lazy {
        dev.njr.zync.server.WebContent(contentRead, contentCommands, contentChanges)
    }

    /** Capture an attachment into the inbox as op-log entries (blob + AddAttachment op). */
    fun captureToInbox(
        title: String,
        type: dev.njr.zync.data.AttachmentType,
        bytes: ByteArray,
        extension: String,
    ): dev.njr.zync.core.id.Ulid {
        val node = replicaCapture.captureAttachment(title, bytes, type.name.lowercase(java.util.Locale.US), "capture.$extension")
        contentChanges.notifyChanged()
        dev.njr.zync.sync.SyncScheduler.requestSync(this)
        return node
    }

    /**
     * Pair with a central server from a tapped/scanned `zync://pair` link (deep link
     * into [MainActivity]); persists the credentials and kicks a first sync.
     */
    suspend fun pairFromUri(uri: String): dev.njr.zync.replica.PairingOutcome {
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)
        return try {
            dev.njr.zync.replica.pairFromUri(http, uri, replicaId = deviceId, store = pairingStore).also {
                if (it is dev.njr.zync.replica.PairingOutcome.Paired) {
                    dev.njr.zync.sync.SyncScheduler.requestSync(this)
                }
            }
        } finally {
            http.close()
        }
    }

    /**
     * Sync once with the paired central server: upload pending attachment blobs, then
     * push local ops and pull remote ([dev.njr.zync.replica.ReplicaSynchronizer]).
     * No-op if the phone isn't paired yet. Called by [dev.njr.zync.sync.SyncWorker].
     */
    suspend fun syncOnce() {
        val paired = pairingStore.load() ?: return
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)
        try {
            val signer = dev.njr.zync.replica.Ed25519DeviceSigner(paired.deviceId, paired.deviceSeed)
            val now = { System.currentTimeMillis() }
            val nonce = { UUID.randomUUID().toString() }
            dev.njr.zync.replica.ReplicaSynchronizer(
                client = dev.njr.zync.replica.SyncClient(
                    http = http,
                    baseUrl = paired.address,
                    db = opDatabase,
                    store = opStore,
                    hlc = localHlc,
                    signer = signer,
                    now = now,
                    nonce = nonce,
                ),
                blobs = dev.njr.zync.replica.BlobUploader(http, paired.address, localBlobs, signer, now, nonce),
                db = opDatabase,
                warn = { Log.w(TAG, it) },
            ).syncOnce()
        } finally {
            http.close()
        }
        contentChanges.notifyChanged()
    }

    /**
     * The PERMANENT loopback server: started once, on first [ensureServerStarted] call, bound to
     * `127.0.0.1` on a port that never changes for the life of the process. The in-app WebView
     * loads `http://127.0.0.1:<port>/...` once at launch and never again — so this server (and
     * this port) must never be restarted or replaced, or the WebView's connection dies underneath
     * it.
     */
    @Volatile private var loopbackServer: ZyncServer? = null
    @Volatile private var loopbackPort: Int = -1

    /** Serializes loopback server start onto a single dedicated background thread, off the caller's. */
    private val serverExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "zync-server") }

    /**
     * Blocks while binding — call from a background thread. Starts (once) the permanent loopback
     * server; a no-op on subsequent calls.
     */
    fun ensureServerStarted(): Int {
        if (loopbackServer == null) {
            serverExecutor.submit(
                Callable {
                    if (loopbackServer == null) {
                        val s = ZyncServer(serverToken, webContent)
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
        // Activate background sync: a connectivity-gated periodic sweep (prompt pushes come
        // from captures + local :web mutations via SyncScheduler.requestSync). No-op unpaired.
        dev.njr.zync.sync.SyncScheduler.schedulePeriodic(this)
    }
}

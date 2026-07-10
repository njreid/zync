package dev.njr.zync.server

import dev.njr.zync.server.blob.BlobService
import dev.njr.zync.server.blob.S3BlobStore
import dev.njr.zync.server.durability.DbBackupGateway
import dev.njr.zync.server.durability.LitestreamCli
import dev.njr.zync.server.durability.StartupSequence
import dev.njr.zync.server.hardening.Hardening
import dev.njr.zync.server.hardening.TokenBucketRateLimiter
import dev.njr.zync.server.sync.SyncService
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import software.amazon.awssdk.services.s3.S3Client

/**
 * Entry point: restore-if-fresh + open the durable DB, wire auth/blobs/hardening
 * from env, and serve. In the container litestream is PID 1 and runs this via
 * `-exec`; `ZYNC_LITESTREAM_URL` (if set) also lets the app restore on boot.
 */
fun main() {
    val dbPath = System.getenv("ZYNC_DB_PATH") ?: "zync.db"
    val port = System.getenv("ZYNC_PORT")?.toInt() ?: 8080

    val gateway = System.getenv("ZYNC_LITESTREAM_URL")?.let(::LitestreamCli) ?: DbBackupGateway.None
    val service = SyncService(StartupSequence.open(dbPath, gateway))

    val blobs = System.getenv("ZYNC_BLOB_BUCKET")?.let { bucket ->
        BlobService(S3BlobStore(S3Client.create(), bucket))
    }
    val hardening = Hardening(TokenBucketRateLimiter(capacity = 240, refillPerSecond = 4.0))
    val auth = ServerConfig.buildAuth()

    embeddedServer(Netty, port = port) {
        zyncModule(service, auth = auth, blobs = blobs, hardening = hardening)
    }.start(wait = true)
}

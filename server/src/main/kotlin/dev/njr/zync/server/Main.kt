package dev.njr.zync.server

import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.auth.SqlDeviceRegistry
import dev.njr.zync.server.blob.BlobService
import dev.njr.zync.server.blob.S3BlobStore
import dev.njr.zync.server.content.ServerContent
import dev.njr.zync.web.sse.ChangeNotifier
import dev.njr.zync.server.durability.DbBackupGateway
import dev.njr.zync.server.durability.LitestreamCli
import dev.njr.zync.server.durability.StartupSequence
import dev.njr.zync.server.hardening.Hardening
import dev.njr.zync.server.hardening.StorageQuota
import dev.njr.zync.server.hardening.TokenBucketRateLimiter
import dev.njr.zync.server.hardening.UsageGauges
import dev.njr.zync.server.pairing.PairCommand
import dev.njr.zync.server.pairing.PairingEndpoint
import dev.njr.zync.server.pairing.PairingManager
import dev.njr.zync.server.pairing.ServerIdentity
import dev.njr.zync.server.sync.CompactionPolicy
import dev.njr.zync.server.sync.OplogCompactor
import dev.njr.zync.server.sync.SyncService
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import software.amazon.awssdk.services.s3.S3Client
import kotlin.time.Duration.Companion.minutes

/**
 * Entry point. `server pair` mints a pairing code + prints a QR (operator command);
 * with no args it restores-if-fresh + opens the durable DB, wires auth/blobs/
 * hardening/pairing from env, and serves. litestream is PID 1 and runs this via
 * `-exec` in the container.
 */
fun main(args: Array<String>) {
    val dbPath = System.getenv("ZYNC_DB_PATH") ?: "zync.db"
    val keyFile = System.getenv("ZYNC_SERVER_KEY_FILE") ?: "server-identity.key"
    val identity = ServerIdentity.loadOrCreate(keyFile)

    if (args.firstOrNull() == "pair") {
        val db = JvmZyncDatabase.file(dbPath)
        val address = System.getenv("ZYNC_PUBLIC_ADDR") ?: "https://localhost"
        PairCommand.run(db, identity, address, System.currentTimeMillis())
        return
    }

    val port = System.getenv("ZYNC_PORT")?.toInt() ?: 8080
    val gateway = System.getenv("ZYNC_LITESTREAM_URL")?.let(::LitestreamCli) ?: DbBackupGateway.None
    val db = StartupSequence.open(dbPath, gateway)

    val changes = ChangeNotifier()
    val service = SyncService(db, onIngest = { changes.notifyChanged() })
    val registry = SqlDeviceRegistry(db)
    val auth = ServerConfig.buildAuth(registry)
    val webauthn = auth.sessions?.let { ServerConfig.buildWebAuthn(db, it) }
    val pairing = PairingEndpoint(PairingManager(db, registry), identity)
    val blobs = System.getenv("ZYNC_BLOB_BUCKET")?.let { bucket ->
        BlobService(S3BlobStore(S3Client.create(), bucket))
    }
    val hardening = Hardening(TokenBucketRateLimiter(capacity = 240, refillPerSecond = 4.0))
    val content = ServerContent(service, changes)

    // Op-log compaction: daily by default; 0 disables. Retention via ZYNC_OPLOG_RETAIN_*.
    val compactor = OplogCompactor(db, CompactionPolicy.fromEnv(System::getenv), metrics = hardening.metrics)
    val compactEveryMinutes = System.getenv("ZYNC_COMPACT_INTERVAL_MINUTES")?.toLong() ?: (24L * 60)
    if (compactEveryMinutes > 0) {
        compactor.start(CoroutineScope(SupervisorJob() + Dispatchers.Default), compactEveryMinutes.minutes)
    }
    val usage = {
        UsageGauges(
            opLogOps = db.transportQueries.countOps().executeAsOne(),
            opLogBytes = db.transportQueries.oplogBytes().executeAsOne(),
            compactionFloor = compactor.floor(),
        )
    }
    // Op-log byte quota (ZYNC_QUOTA_OPLOG_MB, default 1024; 0 disables): pushes get
    // 507 once hit, until compaction frees space. Blob spend is capped S3-side
    // (bucket lifecycle + budget alarm), not here — see deploy/bootstrap.md.
    val quota = StorageQuota.fromEnv(db, System::getenv)

    embeddedServer(Netty, port = port) {
        zyncModule(
            service, auth = auth, blobs = blobs, hardening = hardening, pairing = pairing,
            content = content, webauthn = webauthn, usage = usage, compactionFloor = compactor::floor,
            quota = quota,
        )
    }.start(wait = true)
}

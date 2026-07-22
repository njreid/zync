package dev.njr.zync.server

import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.data.db.ZyncDatabase
import dev.njr.zync.server.auth.SqlDeviceRegistry
import dev.njr.zync.server.operator.AnthropicLlmClient
import dev.njr.zync.server.operator.OperatorManifests
import dev.njr.zync.server.operator.OperatorRuntime
import dev.njr.zync.server.operator.ReadScopeResolver
import dev.njr.zync.server.sync.SettableIngestHook
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
    val ingestHook = SettableIngestHook()
    val service = SyncService(db, onIngest = { changes.notifyChanged() }, hook = ingestHook)
    val registry = SqlDeviceRegistry(db)
    val auth = ServerConfig.buildAuth(registry)
    val webauthn = auth.sessions?.let { ServerConfig.buildWebAuthn(db, it) }
    val pairing = PairingEndpoint(PairingManager(db, registry), identity, publicAddress = System.getenv("ZYNC_PUBLIC_ADDR"))
    val agenda = dev.njr.zync.server.agenda.AgendaEndpoint(db, ingestToken = System.getenv("ZYNC_AGENDA_TOKEN"))
    // Newz handoff: a DEDICATED signing key (never the pairing identity). Prefer the
    // env seed (lets ops know the public key without box access); file fallback.
    val newz = System.getenv("ZYNC_PUBLIC_ADDR")?.let { zyncAddress ->
        val newzAddress = System.getenv("ZYNC_NEWZ_PUBLIC_ADDR") ?: zyncAddress
        val identity = System.getenv("ZYNC_NEWZ_SIGNING_SEED")
            ?.let(dev.njr.zync.server.pairing.ServerIdentity::fromBase64Seed)
            ?: dev.njr.zync.server.pairing.ServerIdentity.loadOrCreate(System.getenv("ZYNC_NEWZ_KEY_FILE") ?: "newz-signing.key")
        dev.njr.zync.server.integrations.NewzIntegration(
            db, identity, newzAddress, redeemToken = System.getenv("ZYNC_NEWZ_REDEEM_TOKEN"),
        ).also {
            org.slf4j.LoggerFactory.getLogger("zync.newz")
                .info("newz handoff signing key: kid={} publicKey={}", it.kid, it.publicKeyBase64)
        }
    }
    val blobs = System.getenv("ZYNC_BLOB_BUCKET")?.let { bucket ->
        BlobService(S3BlobStore(S3Client.create(), bucket))
    }
    // The summarize operator reads OCR text from the blob store; wired here now
    // that blobs exist. Degrades to disabled without ANTHROPIC_API_KEY.
    wireOperators(db, service, ingestHook, blobs)
    val hardening = Hardening(TokenBucketRateLimiter(capacity = 240, refillPerSecond = 4.0))
    val content = ServerContent(service, changes)
    // External op API (bots/scripts/integrations): env-token auth for now (ZYNC_BOT_TOKEN).
    val botAuth = dev.njr.zync.server.api.EnvBotAuth.fromEnv()
    val botApi = dev.njr.zync.server.api.ExternalOpApi(service)

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
            service,
            auth = auth,
            blobs = blobs,
            hardening = hardening,
            pairing = pairing,
            content = content,
            webauthn = webauthn,
            agenda = agenda,
            newz = newz,
            botApi = botApi,
            botAuth = botAuth,
            allowUnauthenticatedWeb = System.getenv("ZYNC_ALLOW_UNAUTHENTICATED_WEB") == "true",
            usage = usage,
            compactionFloor = compactor::floor,
            quota = quota,
        )
    }.start(wait = true)
}

/**
 * Wire the M8 operator runtime onto the ingest hook. LLM operators (auto-clarify,
 * summarize) need `ANTHROPIC_API_KEY`; the retrieval operators (suggest-file,
 * auto-file-done) are deterministic keyword scorers and run regardless.
 */
private fun wireOperators(db: ZyncDatabase, service: SyncService, hook: SettableIngestHook, blobs: BlobService?) {
    val log = org.slf4j.LoggerFactory.getLogger("zync.operators")
    val llm = AnthropicLlmClient.fromEnv()
    val blobText: (String) -> String? =
        blobs?.let { svc -> { key -> svc.fetch(key)?.toString(Charsets.UTF_8) } } ?: { null }

    // Retrieval operators (keyword file-location suggestions) work without an LLM.
    val index = dev.njr.zync.server.operator.ReferenceIndex(service.stateStore)
    val completers = mapOf(
        "suggest-file" to dev.njr.zync.server.operator.FileSuggesters.suggestFile(index, blobText),
        "auto-file-done" to dev.njr.zync.server.operator.FileSuggesters.autoFileDone(index, blobText),
    )
    val operators = if (llm != null) OperatorManifests.fromEnv() else OperatorManifests.retrievalOnly()

    hook.delegate = OperatorRuntime(
        db = db,
        store = service.stateStore,
        operators = operators,
        scopes = ReadScopeResolver.default(),
        llm = llm ?: DisabledLlmClient,
        emit = service::ingestLocal,
        blobText = blobText,
        completers = completers,
    )
    log.info(if (llm != null) "operators enabled (LLM + retrieval)" else "operators enabled (retrieval-only; no ANTHROPIC_API_KEY)")
}

/** A no-op [dev.njr.zync.server.operator.LlmClient] for LLM-free (retrieval-only) operation. */
private object DisabledLlmClient : dev.njr.zync.server.operator.LlmClient {
    override fun complete(request: dev.njr.zync.server.operator.LlmRequest) =
        dev.njr.zync.server.operator.LlmReply.Unavailable("LLM disabled")
}

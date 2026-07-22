package dev.njr.zync.server.api

import dev.njr.zync.core.api.EnvelopeResult
import dev.njr.zync.core.api.IntentResult
import dev.njr.zync.core.api.OpEnvelope
import dev.njr.zync.core.api.OpIntent
import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.HlcGenerator
import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import dev.njr.zync.server.sync.SyncService
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.OpEmitter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.random.Random

/**
 * Translates an external-op-api envelope (spec §3) into provenance-tagged ops and ingests
 * them atomically. Intents become ops via [ContentCommands] over a recording emitter that
 * stamps `Actor.Bot(id)`; the whole envelope is validated first, then ingested in one
 * transaction (spec §5, RESOLVED Q3 = atomic) — if any intent is invalid, nothing lands.
 * Commit-only for step 1; `propose` mode + suggestions arrive in step 2.
 */
class ExternalOpApi(
    private val service: SyncService,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    /** The well-known inbox root (null = tree root), for the "inbox" parent alias. */
    private val inbox: () -> Ulid? = { null },
) {
    fun submit(bot: BotIdentity, env: OpEnvelope): EnvelopeResult {
        val emitter = RecordingBotEmitter(bot.id, now, random)
        val commands = ContentCommands(emitter)
        val results = env.intents.map { translate(it, commands, emitter) }
        // Atomic: only ingest if every intent validated. Otherwise nothing lands.
        if (results.none { it.status == "error" }) service.ingestLocalBatch(emitter.ops)
        return EnvelopeResult(results)
    }

    private fun translate(i: OpIntent, c: ContentCommands, e: RecordingBotEmitter): IntentResult = try {
        when (i.op) {
            "create" -> {
                val id = if (i.kind == "project") c.createProject(i.title.orEmpty(), resolveParent(i.parent))
                else c.createTask(i.title.orEmpty(), resolveParent(i.parent))
                i.fields?.forEach { (f, v) -> e.setField(id, f, v) }
                i.tags?.forEach { c.addTag(id, Ulid.parse(it)) }
                ok(i, id)
            }
            "comment" -> ok(i, c.addComment(target(i), i.text.orEmpty()))
            "setField" -> {
                val t = target(i)
                e.setField(t, requireNotNull(i.field) { "field required" }, i.value ?: JsonNull)
                ok(i, t)
            }
            "addTag" -> { val t = target(i); c.addTag(t, Ulid.parse(requireNotNull(i.context) { "context required" })); ok(i, t) }
            "move" -> { val t = target(i); c.move(t, requireNotNull(resolveParent(i.parent)) { "parent required" }); ok(i, t) }
            "complete" -> { val t = target(i); c.complete(t); ok(i, t) }
            "trash" -> { val t = target(i); c.trash(t); ok(i, t) }
            else -> IntentResult(i.op, null, "error", "unsupported op '${i.op}'")
        }
    } catch (ex: Exception) {
        IntentResult(i.op, null, "error", ex.message ?: "invalid intent")
    }

    private fun ok(i: OpIntent, id: Ulid) = IntentResult(i.op, id.toString(), "committed")

    private fun target(i: OpIntent): Ulid = Ulid.parse(requireNotNull(i.target) { "target required" })

    private fun resolveParent(p: String?): Ulid? = when (p) {
        null, "inbox" -> inbox()
        "reference" -> WellKnownNodes.REFERENCE_ROOT
        else -> Ulid.parse(p)
    }
}

/**
 * An [OpEmitter] that builds `Actor.Bot`-authored server ops into [ops] WITHOUT ingesting —
 * so the caller can validate the whole envelope, then ingest the batch atomically.
 */
class RecordingBotEmitter(
    botId: String,
    private val now: () -> Long,
    private val random: Random,
) : OpEmitter {
    private val clock = Clock { now() }
    private val hlc = HlcGenerator("server", clock)
    private val actor = Actor.Bot(botId)
    val ops = mutableListOf<Op>()

    override fun newId(): Ulid = Ulid.generate(clock, random)
    override fun setField(entity: Ulid, field: String, value: JsonElement) {
        ops += Op.SetField(newId(), entity, EntityType.Node, hlc.now(), actor, "server", now(), field, value)
    }
    override fun move(node: Ulid, newParent: Ulid) {
        ops += Op.Move(newId(), node, EntityType.Node, hlc.now(), actor, "server", now(), newParent)
    }
    override fun addTag(node: Ulid, context: Ulid) {
        ops += Op.AddTag(newId(), node, EntityType.Tag, hlc.now(), actor, "server", now(), context)
    }
    override fun removeTag(node: Ulid, context: Ulid) {
        ops += Op.RemoveTag(newId(), node, EntityType.Tag, hlc.now(), actor, "server", now(), context)
    }
    override fun tombstone(entity: Ulid) {
        ops += Op.Tombstone(newId(), entity, EntityType.Node, hlc.now(), actor, "server", now())
    }
}

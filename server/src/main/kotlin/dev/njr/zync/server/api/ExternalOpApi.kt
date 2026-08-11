package dev.njr.zync.server.api

import dev.njr.zync.core.api.EnvelopeResult
import dev.njr.zync.core.api.IntentResult
import dev.njr.zync.core.api.OpEnvelope
import dev.njr.zync.core.api.OpIntent
import dev.njr.zync.core.agent.AgentFlow
import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.HlcGenerator
import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.content.KIND_SUGGESTION
import dev.njr.zync.core.content.SuggestionKind
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

/**
 * Translates an external-op-api envelope (spec §3) into provenance-tagged ops and ingests
 * them atomically. Intents become ops via [ContentCommands] over a recording emitter that
 * stamps `Actor.Bot(id)`; the whole envelope is validated first, then ingested in one
 * transaction (spec §5, RESOLVED Q3 = atomic) — if any intent is invalid, nothing lands.
 * In `propose` mode every state-changing verb (setField/complete/trash and the structural
 * move/addTag/attach) mints a `kind=suggestion` node for human review instead of writing live;
 * additive verbs (comment/free-tags) always commit.
 */
class ExternalOpApi(
    private val service: SyncService,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    /** The well-known inbox root (null = tree root), for the "inbox" parent alias. */
    private val inbox: () -> Ulid? = { null },
    /** Server blob store, for verifying `attach` blobRefs (uploaded via PUT /api/blobs). */
    private val blobs: dev.njr.zync.server.blob.BlobService? = null,
) {
    fun submit(bot: BotIdentity, env: OpEnvelope): EnvelopeResult {
        // Effective mode: a bot without commit capability, or an envelope asking to propose,
        // routes mutations through the proposal path (spec §4). Committing needs both.
        val propose = !bot.commits || env.mode == "propose"
        val emitter = RecordingBotEmitter(bot.id, now, random)
        val commands = ContentCommands(emitter)
        val results = env.intents.map { translate(it, commands, emitter, propose, bot.capabilities) }
        if (results.any { it.status == "error" }) {
            // Atomic (RESOLVED Q3): one bad intent rejects the whole envelope and nothing is
            // ingested — so the other intents must NOT report a committed/proposed nodeId for an
            // op that never landed, or a bot would record a phantom id (and cache it under the
            // idempotency key). Rewrite every non-error result to a rolled-back error.
            return EnvelopeResult(results.map {
                if (it.status == "error") it
                else IntentResult(it.op, null, "error", "rolled back — another intent in the envelope failed")
            })
        }
        service.ingestLocalBatch(emitter.ops)
        return EnvelopeResult(results)
    }

    private fun translate(
        i: OpIntent,
        c: ContentCommands,
        e: RecordingBotEmitter,
        propose: Boolean,
        caps: dev.njr.zync.core.api.BotCapabilities,
    ): IntentResult = try {
        if (i.op !in caps.verbs) return err(i, "verb '${i.op}' not permitted")
        val allowedFields = caps.fields
        val field = i.field
        if (i.op == "setField" && allowedFields != null && (field == null || field !in allowedFields)) {
            return err(i, "field '$field' not permitted")
        }
        when (i.op) {
            "create" -> {
                // The field whitelist and the addTag grant also bind the create path — otherwise a
                // bot could set non-whitelisted fields or tag via `create.fields`/`create.tags`,
                // bypassing the caps that are enforced on standalone setField/addTag intents.
                val badField = i.fields?.keys?.firstOrNull { allowedFields != null && it !in allowedFields }
                if (badField != null) return err(i, "field '$badField' not permitted")
                if (!i.tags.isNullOrEmpty() && "addTag" !in caps.verbs) return err(i, "addTag not permitted")
                // Type is derived from location + children, so create is uniform (i.kind ignored).
                val id = c.createTask(i.title.orEmpty(), resolveParent(i.parent))
                i.fields?.forEach { (f, v) -> e.setField(id, f, v) }
                i.tags?.forEach { c.addTag(id, Ulid.parse(it)) }
                if (propose) { e.setField(id, AgentFlow.FIELD_PROPOSED, JsonPrimitive(true)); proposed(i, id) } else ok(i, id)
            }
            // Comments are additive and can't clobber human state → always commit.
            "comment" -> ok(i, c.addComment(target(i), i.text.orEmpty()))
            "setField" -> edit(i, e, target(i), requireNotNull(i.field) { "field required" }, i.value ?: JsonNull, propose)
            "complete" -> edit(i, e, target(i), Fields.STATUS, JsonPrimitive("DONE"), propose)
            "trash" -> edit(i, e, target(i), Fields.STATUS, JsonPrimitive("DROPPED"), propose)
            "attach" -> {
                val t = target(i)
                val blobHash = requireNotNull(i.blobRef) { "blobRef required" }
                // The blob must already be stored (PUT /api/blobs) whether we attach now or on
                // accept, so the referenced content exists and is hash-verified either way.
                if (blobs?.exists(blobHash) != true) {
                    return err(i, "blob not found — upload via PUT /api/blobs first")
                }
                val type = i.type ?: "pdf"
                val name = i.name ?: "attachment"
                if (!propose) return ok(i, e.attach(t, type, blobHash, name))
                proposeStructural(i, e, t, SuggestionKind.ATTACH) {
                    e.setField(it, Fields.PROPOSED_ATTACHMENT, buildJsonObject {
                        put("blobHash", blobHash); put("type", type); put("name", name)
                    })
                }
            }
            "addTag" -> {
                val t = target(i)
                val context = Ulid.parse(requireNotNull(i.context) { "context required" })
                if (!propose) { c.addTag(t, context); return ok(i, t) }
                proposeStructural(i, e, t, SuggestionKind.ADD_TAG) {
                    e.setField(it, Fields.PROPOSED_CONTEXT, JsonPrimitive(context.toString()))
                }
            }
            // Free-form tags are additive per-label metadata (mergeable) — always commit, so a
            // propose-only bot can still flag items relevant to it.
            "addFreeTag" -> { val t = target(i); c.addFreeTag(t, requireNotNull(i.tag) { "tag required" }); ok(i, t) }
            "removeFreeTag" -> { val t = target(i); c.removeFreeTag(t, requireNotNull(i.tag) { "tag required" }); ok(i, t) }
            "move" -> {
                val t = target(i)
                val parent = requireNotNull(resolveParent(i.parent)) { "parent required" }
                if (!propose) { c.move(t, parent); return ok(i, t) }
                proposeStructural(i, e, t, SuggestionKind.MOVE) {
                    e.setField(it, Fields.PROPOSED_PARENT, JsonPrimitive(parent.toString()))
                }
            }
            else -> err(i, "unsupported op '${i.op}'")
        }
    } catch (ex: Exception) {
        err(i, ex.message ?: "invalid intent")
    }

    /** A field edit: commit the live `SetField`, or (propose) mint a suggestion node (§4). */
    private fun edit(i: OpIntent, e: RecordingBotEmitter, target: Ulid, field: String, value: JsonElement, propose: Boolean): IntentResult {
        if (!propose) { e.setField(target, field, value); return ok(i, target) }
        return proposeStructural(i, e, target, SuggestionKind.SET_FIELD) {
            e.setField(it, Fields.TARGET_FIELD, JsonPrimitive(field))
            e.setField(it, Fields.PROPOSED_VALUE, value)
        }
    }

    /**
     * Mint a suggestion node of [kind] targeting [target] (external-op-api §4, generalized to
     * structural ops): the common `kind=suggestion` / `targetId` / `suggestionKind` / `proposed`
     * scaffolding, parented under the target like a comment; [payload] adds the kind-specific
     * fields. Accepting emits the real op as `Actor.Human` (see `ContentCommands.acceptSuggestion`).
     */
    private fun proposeStructural(
        i: OpIntent,
        e: RecordingBotEmitter,
        target: Ulid,
        kind: String,
        payload: (Ulid) -> Unit,
    ): IntentResult {
        val sug = e.newId()
        e.setField(sug, Fields.KIND, JsonPrimitive(KIND_SUGGESTION))
        e.setField(sug, Fields.SUGGESTION_KIND, JsonPrimitive(kind))
        e.setField(sug, Fields.TARGET_ID, JsonPrimitive(target.toString()))
        payload(sug)
        e.setField(sug, AgentFlow.FIELD_PROPOSED, JsonPrimitive(true))
        e.move(sug, target) // associate under the target
        return proposed(i, sug)
    }

    private fun ok(i: OpIntent, id: Ulid) = IntentResult(i.op, id.toString(), "committed")
    private fun proposed(i: OpIntent, id: Ulid) = IntentResult(i.op, id.toString(), "proposed")
    private fun err(i: OpIntent, message: String) = IntentResult(i.op, null, "error", message)

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
    override fun addAttachment(attachment: Ulid, value: JsonElement) {
        ops += Op.AddAttachment(newId(), attachment, EntityType.Attachment, hlc.now(), actor, "server", now(), value)
    }

    /** Link a (server-stored) blob to [node] as an attachment entity; returns its id. */
    fun attach(node: Ulid, type: String, blobHash: String, name: String): Ulid {
        val id = newId()
        val payload = buildJsonObject {
            put("nodeId", node.toString())
            put("type", type)
            put("blobHash", blobHash)
            put("relativePath", name)
        }
        ops += Op.AddAttachment(newId(), id, EntityType.Attachment, hlc.now(), actor, "server", now(), payload)
        return id
    }
}

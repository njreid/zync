package dev.njr.zync.server.operator

import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.HlcGenerator
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.operator.CascadeGraph
import dev.njr.zync.core.operator.InputVersion
import dev.njr.zync.core.operator.OperatorIo
import dev.njr.zync.core.operator.OperatorManifest
import dev.njr.zync.core.operator.OperatorOutcome
import dev.njr.zync.core.operator.TriggerKind
import dev.njr.zync.core.operator.evaluate
import dev.njr.zync.core.state.EntitySnapshot
import dev.njr.zync.core.state.StateStore
import dev.njr.zync.data.db.ZyncDatabase
import dev.njr.zync.server.sync.OpIngestHook
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * The M8 operator runtime (spec §7): listens to the ingest path via
 * [OpIngestHook], evaluates triggers over the freshly-ingested ops, and runs
 * the operator lifecycle — readScope match (checked *before* idempotency, so a
 * late op that pushed an entity out of scope cancels the fire) → read the
 * entity at input version V → LLM call with typed-output validation and
 * bounded retries → emit provenance-tagged ops (`actor = Operator(id)`) within
 * the write scope → record `operator_run(operator, entity, V)`.
 *
 * Enforced rules:
 * - **Idempotency / re-entrancy**: a firing is keyed by `(operator, entity, V)`
 *   where V covers only the operator's *read* fields — late offline history
 *   re-fires it, redelivery and immaterial edits don't.
 * - **No self-trigger**: an operator's own output ops never trigger it (actor
 *   check), and its writes never change its own V (writes ∉ reads, verified
 *   at construction).
 * - **Field ownership by construction**: emitted ops must pass
 *   `WriteScope.permits`; anything else is dropped and flagged. Only
 *   `SetField` is ever built here — operators have no destructive power
 *   (no tombstone/move, threat model T4).
 * - **Fuel + cycles**: statically, a declared-scope cascade cycle fails
 *   construction ([CascadeGraph]); dynamically, each firing is capped by
 *   `fuel.maxOpsPerFiring` and each operator's total emissions per cascade by
 *   `fuel.maxOpsPerCascade` — exceeding either halts that operator's cascade
 *   and records a flagged run.
 *
 * Cascades run on [executor] (a single background thread in production; run
 * inline in tests): each wave's emissions — ingested synchronously through
 * [emit] with hook echoes suppressed — become the next wave's trigger ops
 * until the cascade quiesces or fuel runs out.
 */
class OperatorRuntime(
    private val db: ZyncDatabase,
    private val store: StateStore,
    operators: List<OperatorManifest>,
    scopes: ReadScopeResolver,
    private val llm: LlmClient,
    private val emit: (Op) -> Op,
    private val blobText: (String) -> String? = { null },
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val random: Random = Random.Default,
    private val executor: Executor = defaultExecutor(),
    private val json: Json = Json,
) : OpIngestHook {
    private val log = LoggerFactory.getLogger("zync.operators")
    private val hlc = HlcGenerator(DEVICE_ID, clock)
    private val emitting = ThreadLocal.withInitial { false }

    private class Registered(val manifest: OperatorManifest, val scope: ReadScope)

    private val registered: List<Registered> = operators.map { manifest ->
        val scope = scopes.resolve(manifest.readScope)
            ?: throw IllegalArgumentException("operator '${manifest.id}': unknown readScope '${manifest.readScope.ref}'")
        Registered(manifest, scope)
    }

    init {
        val duplicates = registered.groupingBy { it.manifest.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate operator ids: $duplicates" }
        for (reg in registered) {
            val overlap = reg.manifest.writeScope.fields intersect reg.scope.reads
            require(overlap.isEmpty()) {
                "operator '${reg.manifest.id}' writes fields it reads ($overlap): its own output would change its input version"
            }
        }
        val io = registered.map {
            OperatorIo(
                id = it.manifest.id,
                reads = it.scope.reads,
                writes = it.manifest.writeScope.fields,
                refireable = it.manifest.trigger == TriggerKind.EntityChangesInScope,
            )
        }
        CascadeGraph.findCycle(io)?.let { cycle ->
            throw IllegalArgumentException("operator cascade cycle detected: ${cycle.joinToString(" -> ")}")
        }
    }

    override fun onIngested(ops: List<Op>) {
        if (emitting.get()) return // our own emissions echoing back through the ingest path
        if (registered.isEmpty() || ops.isEmpty()) return
        executor.execute { runCascade(ops) }
    }

    private fun runCascade(seed: List<Op>) {
        try {
            seed.forEach { hlc.observe(it.hlc) } // operator writes must sort after their triggers
            val spent = HashMap<String, Int>()
            val halted = HashSet<String>()
            var wave = seed
            var depth = 0
            while (wave.isNotEmpty() && depth < MAX_CASCADE_DEPTH) {
                depth++
                val snapshots = store.project()
                val next = mutableListOf<Op>()
                for ((entityId, entityOps) in wave.groupBy { it.entityId }) {
                    for (reg in registered) {
                        if (reg.manifest.id in halted) continue
                        // No self-trigger: only ops from other actors count.
                        val triggering = entityOps.filterNot { (it.actor as? Actor.Operator)?.id == reg.manifest.id }
                        if (triggering.none { affects(reg, it) }) continue
                        next += fire(reg, entityId, snapshots[entityId], spent, halted)
                    }
                }
                wave = next
            }
            if (wave.isNotEmpty()) log.warn("cascade halted at depth cap {} with {} pending ops", MAX_CASCADE_DEPTH, wave.size)
        } catch (t: Throwable) {
            log.error("operator cascade failed", t)
        }
    }

    /** Could this op change the operator's view of its entity (scope or version)? */
    private fun affects(reg: Registered, op: Op): Boolean = when (op) {
        is Op.SetField -> op.field in reg.scope.reads
        is Op.Move, is Op.AddTag, is Op.RemoveTag, is Op.Tombstone -> true
        is Op.AddAttachment -> false
    }

    private fun fire(
        reg: Registered,
        entityId: Ulid,
        snapshot: EntitySnapshot?,
        spent: MutableMap<String, Int>,
        halted: MutableSet<String>,
    ): List<Op> {
        val m = reg.manifest
        if (snapshot == null || !snapshot.alive) return emptyList()
        // Scope match comes BEFORE idempotency (spec §9a): a late op that pushed
        // the entity out of scope cancels the fire even though V changed.
        if (!reg.scope.matches(snapshot)) return emptyList()

        val version = inputVersion(reg, entityId)
        val entity = entityId.toString()
        val q = db.transportQueries
        if (q.operatorRunExists(m.id, entity, version).executeAsOne() > 0L) return emptyList()
        if (m.trigger == TriggerKind.EntityEntersScope &&
            q.operatorHandledCount(m.id, entity).executeAsOne() > 0L
        ) return emptyList()

        // Typed-output loop: schema-validation failure is the ONLY retried condition.
        val request = LlmRequest(m.id, OperatorPrompt.system(m), OperatorPrompt.user(snapshot, reg.scope.reads, blobText), m.output)
        val attempts = mutableListOf<JsonElement>()
        var outcome: OperatorOutcome = OperatorOutcome.Rejected(0, emptyList())
        while (attempts.size < m.retries + 1) {
            when (val reply = llm.complete(request)) {
                is LlmReply.Unavailable -> {
                    // Transient: abort without recording, so the next trigger retries.
                    log.warn("operator {} entity {}: llm unavailable: {}", m.id, entity, reply.message)
                    return emptyList()
                }
                is LlmReply.Refusal -> {
                    log.warn("operator {} entity {}: llm refused: {}", m.id, entity, reply.reason)
                    attempts += JsonNull
                }
                is LlmReply.Text -> attempts += parseAttempt(reply.text)
            }
            outcome = m.output.evaluate(attempts, m.retries)
            if (outcome is OperatorOutcome.Accepted) break
        }

        val accepted = when (outcome) {
            is OperatorOutcome.Rejected -> {
                log.warn(
                    "operator {} entity {}: output rejected after {} attempts: {}",
                    m.id, entity, outcome.attemptsUsed, outcome.lastErrors,
                )
                q.recordOperatorRun(m.id, entity, version, STATUS_REJECTED)
                return emptyList()
            }
            is OperatorOutcome.Accepted -> outcome.value as JsonObject // Valid ⇒ object
        }

        // Field ownership by construction: build only declared output fields,
        // then drop anything the write scope doesn't permit.
        val candidates = m.output.fields.keys.sorted().mapNotNull { field ->
            accepted[field]?.let { value ->
                Op.SetField(newId(), entityId, EntityType.Node, hlc.now(), Actor.Operator(m.id), DEVICE_ID, clock.nowMillis(), field, value)
            }
        }
        val (allowed, denied) = candidates.partition { m.writeScope.permits(it) }
        if (denied.isNotEmpty()) {
            log.warn(
                "operator {} entity {}: dropped {} op(s) outside writeScope: {}",
                m.id, entity, denied.size, denied.filterIsInstance<Op.SetField>().map { it.field },
            )
        }

        // Fuel: per-firing cap, then per-cascade budget. Exceeding either halts
        // this operator's cascade and records a flagged run.
        val already = spent.getOrElse(m.id) { 0 }
        if (allowed.size > m.fuel.maxOpsPerFiring || already + allowed.size > m.fuel.maxOpsPerCascade) {
            log.warn(
                "operator {} entity {}: fuel exhausted (firing={}, cascade={}+{}, limits={}/{})",
                m.id, entity, allowed.size, already, allowed.size, m.fuel.maxOpsPerFiring, m.fuel.maxOpsPerCascade,
            )
            halted += m.id
            q.recordOperatorRun(m.id, entity, version, STATUS_FUEL_EXHAUSTED)
            return emptyList()
        }

        val emitted = withEmissionGuard { allowed.map(emit) }
        spent[m.id] = already + emitted.size
        q.recordOperatorRun(m.id, entity, version, STATUS_DONE)
        log.info("operator {} entity {}: emitted {} op(s) at version {}", m.id, entity, emitted.size, version)
        return emitted
    }

    /** V = the HLCs of exactly what the operator reads (fields, tags, parent). */
    private fun inputVersion(reg: Registered, entityId: Ulid): String {
        val fields = store.allRegisters()
            .filterKeys { it.entityId == entityId && it.field in reg.scope.reads }
            .entries.associate { (key, value) -> key.field to value.hlc }
        val tags = store.allTags()
            .filterKeys { it.nodeId == entityId }
            .entries.associate { (key, value) -> key.contextId.toString() to value.hlc }
        return InputVersion.of(fields, tags, store.getParent(entityId)?.toString())
    }

    private fun parseAttempt(text: String): JsonElement = try {
        json.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) {
        JsonNull // validate() rejects it as "expected a JSON object"
    }

    private fun newId(): Ulid = Ulid.generate(clock, random)

    private inline fun <T> withEmissionGuard(block: () -> T): T {
        emitting.set(true)
        return try {
            block()
        } finally {
            emitting.set(false)
        }
    }

    companion object {
        /** Provenance device id for operator-authored ops (like the content UI's `server`). */
        const val DEVICE_ID = "server"
        const val STATUS_DONE = "done"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_FUEL_EXHAUSTED = "fuel_exhausted"

        /** Backstop for cascades fuel accounting can't see (misconfigured scopes). */
        const val MAX_CASCADE_DEPTH = 16

        private fun defaultExecutor(): Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "zync-operators").apply { isDaemon = true }
        }
    }
}

package dev.njr.zync.server.operator

import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.operator.FieldType
import dev.njr.zync.core.operator.Fuel
import dev.njr.zync.core.operator.OperatorManifest
import dev.njr.zync.core.operator.OutputSchema
import dev.njr.zync.core.operator.ReadScopeHandle
import dev.njr.zync.core.operator.TriggerKind
import dev.njr.zync.core.operator.WriteScope
import dev.njr.zync.core.sync.PushRequest
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.Ops
import dev.njr.zync.server.hlc
import dev.njr.zync.server.str
import dev.njr.zync.server.sync.SettableIngestHook
import dev.njr.zync.server.sync.SyncService
import java.util.concurrent.Executor

/**
 * In-memory SyncService + OperatorRuntime wired the way production wires them,
 * but with a fake LLM, a fixed clock, and an inline executor so cascades run
 * synchronously inside `push`.
 */
class OperatorHarness(
    operators: List<OperatorManifest>,
    val llm: FakeLlmClient = FakeLlmClient(),
    scopes: ReadScopeResolver = ReadScopeResolver.default(),
) {
    val db = JvmZyncDatabase.inMemory()
    private val hook = SettableIngestHook()
    val service = SyncService(db, hook = hook)
    val runtime = OperatorRuntime(
        db = db,
        store = service.stateStore,
        operators = operators,
        scopes = scopes,
        llm = llm,
        emit = service::ingestLocal,
        clock = Clock { 1_000_000L },
        executor = Executor { it.run() },
    )
    val ops = Ops()

    init {
        hook.delegate = runtime
    }

    fun push(ops: List<Op>) = service.push(PushRequest(ops))

    /** Redeliver the ingest hook for [ops] — simulates duplicate notification. */
    fun redeliver(ops: List<Op>) = runtime.onIngested(ops)

    /** Every operator-authored op currently in the log. */
    fun operatorOps(): List<Op> = service.recentOps(500).filter { it.actor is Actor.Operator }

    /** Recorded run statuses for (operator, entity). */
    fun runStatuses(operatorId: String, entity: Ulid): List<String> =
        db.transportQueries.operatorRuns(operatorId, entity.toString()).executeAsList().map { it.status }

    /** The human ops that put a fresh task in the inbox (root, ACTIVE, kind=task). */
    fun captureTask(entity: Ulid, at: Long, title: String = "Buy milk"): List<Op> = listOf(
        ops.setField(entity, "kind", str("task"), hlc(at)),
        ops.setField(entity, "title", str(title), hlc(at, 1)),
        ops.setField(entity, "status", str("ACTIVE"), hlc(at, 2)),
    )
}

/** Manifest builder with sane defaults for tests. */
fun manifest(
    id: String,
    scopeRef: String = ReadScopes.INBOX_TASK_REF,
    trigger: TriggerKind = TriggerKind.EntityChangesInScope,
    writes: Set<String> = setOf("summary"),
    output: OutputSchema = OutputSchema(mapOf("summary" to FieldType.String)),
    retries: Int = 1,
    fuel: Fuel = Fuel(maxOpsPerFiring = 4, maxOpsPerCascade = 16),
): OperatorManifest = OperatorManifest(
    id = id,
    name = id,
    readScope = ReadScopeHandle(scopeRef),
    writeScope = WriteScope(fields = writes),
    trigger = trigger,
    output = output,
    retries = retries,
    fuel = fuel,
)

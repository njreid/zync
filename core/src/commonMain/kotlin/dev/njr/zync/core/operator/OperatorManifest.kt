package dev.njr.zync.core.operator

import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import kotlinx.serialization.Serializable

/**
 * The shared, pure pieces of an operator declaration (spec §7). The operator
 * *runtime* — LLM calls, readScope predicate evaluation, trigger dispatch, fuel
 * accounting — is **M8**; M3 defines only the data types and the pure validation
 * they need, so `core` (phone + server) agrees on the shape. The shape may still
 * evolve when M8 lands.
 */
@Serializable
data class OperatorManifest(
    val id: String,
    val name: String,
    /** Opaque handle to a tree predicate; the M8 runtime resolves it. */
    val readScope: ReadScopeHandle,
    /** Fields/child-types the operator may emit — must exclude human-owned fields. */
    val writeScope: WriteScope,
    val trigger: TriggerKind,
    /** Typed schema the LLM result must satisfy. */
    val output: OutputSchema,
    /** Max LLM re-attempts on schema-validation failure (the only control flow). */
    val retries: Int,
    val fuel: Fuel,
)

/** Opaque reference to a read-scope predicate resolved by the M8 runtime. */
@Serializable
data class ReadScopeHandle(val ref: String)

/**
 * What an operator is permitted to emit. Field ownership is enforced **by
 * construction** here (spec §7): an operator may only write fields in [fields] and
 * create child objects of [childTypes]; it may never target a human-owned field.
 * This — not HLC/LWW order — is what protects human writes.
 */
@Serializable
data class WriteScope(
    val fields: Set<String>,
    val childTypes: Set<EntityType> = emptySet(),
) {
    fun permitsField(field: String): Boolean = field in fields

    fun permitsChildType(type: EntityType): Boolean = type in childTypes

    /** Whether an operator would be allowed to emit [op] (the emission guard, V4). */
    fun permits(op: Op): Boolean = when (op) {
        is Op.SetField -> permitsField(op.field)
        is Op.AddAttachment -> permitsChildType(EntityType.Attachment)
        is Op.AddTag, is Op.RemoveTag -> permitsChildType(EntityType.Tag)
        is Op.Move, is Op.Tombstone -> false // structural/terminal ops are not operator-emittable
    }
}

/** When an operator fires relative to a readScope match. */
@Serializable
enum class TriggerKind { EntityEntersScope, EntityChangesInScope }

/** Cost bounds: ops per single firing and ops across a whole trigger cascade. */
@Serializable
data class Fuel(val maxOpsPerFiring: Int, val maxOpsPerCascade: Int)

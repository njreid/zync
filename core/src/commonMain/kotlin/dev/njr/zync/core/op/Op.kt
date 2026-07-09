package dev.njr.zync.core.op

import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id.Ulid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A single, immutable entry in the op log — the source of truth (spec §3). State
 * is a deterministic fold of these. Modeled as a sealed hierarchy (one subtype
 * per mutation) rather than the spec's nullable-field bag, so each payload is
 * total and serialization is polymorphic (discriminator `type`).
 *
 * Common fields:
 * - [opId]      ULID; the idempotency key for delivery (re-delivery is a no-op).
 * - [seq]       server-assigned transport cursor; null until ingested. NOT merge order.
 * - [entityId]  the entity this op targets.
 * - [entityType] kind of that entity.
 * - [hlc]       the merge order ("who wins"); distinct from [seq].
 * - [actor]     provenance.
 * - [deviceId]  the origin device.
 * - [wallClock] informational only (real time of authoring).
 */
@Serializable
sealed class Op {
    abstract val opId: Ulid
    abstract val seq: Long?
    abstract val entityId: Ulid
    abstract val entityType: EntityType
    abstract val hlc: Hlc
    abstract val actor: Actor
    abstract val deviceId: String
    abstract val wallClock: Long

    /** Set an LWW register `(entityId, field)` to [value]. `create` is a bundle of these. */
    @Serializable
    @SerialName("set_field")
    data class SetField(
        override val opId: Ulid,
        override val entityId: Ulid,
        override val entityType: EntityType,
        override val hlc: Hlc,
        override val actor: Actor,
        override val deviceId: String,
        override val wallClock: Long,
        val field: String,
        val value: JsonElement,
        override val seq: Long? = null,
    ) : Op()

    /** Reparent a node; integrated via the HLC-ordered move algorithm (spec §4). */
    @Serializable
    @SerialName("move")
    data class Move(
        override val opId: Ulid,
        override val entityId: Ulid,
        override val entityType: EntityType,
        override val hlc: Hlc,
        override val actor: Actor,
        override val deviceId: String,
        override val wallClock: Long,
        val newParentId: Ulid,
        override val seq: Long? = null,
    ) : Op()

    /** Add tag membership `(entityId=node, contextId)` — LWW boolean `present=true`. */
    @Serializable
    @SerialName("add_tag")
    data class AddTag(
        override val opId: Ulid,
        override val entityId: Ulid,
        override val entityType: EntityType,
        override val hlc: Hlc,
        override val actor: Actor,
        override val deviceId: String,
        override val wallClock: Long,
        val contextId: Ulid,
        override val seq: Long? = null,
    ) : Op()

    /** Remove tag membership `(entityId=node, contextId)` — LWW boolean `present=false`. */
    @Serializable
    @SerialName("remove_tag")
    data class RemoveTag(
        override val opId: Ulid,
        override val entityId: Ulid,
        override val entityType: EntityType,
        override val hlc: Hlc,
        override val actor: Actor,
        override val deviceId: String,
        override val wallClock: Long,
        val contextId: Ulid,
        override val seq: Long? = null,
    ) : Op()

    /** Attach an immutable blob reference (`type`, `blobHash`, `relativePath`) to a node. */
    @Serializable
    @SerialName("add_attachment")
    data class AddAttachment(
        override val opId: Ulid,
        override val entityId: Ulid,
        override val entityType: EntityType,
        override val hlc: Hlc,
        override val actor: Actor,
        override val deviceId: String,
        override val wallClock: Long,
        val value: JsonElement,
        override val seq: Long? = null,
    ) : Op()

    /** Terminal purge of an entity — tombstone wins over concurrent edits (spec §5). */
    @Serializable
    @SerialName("tombstone")
    data class Tombstone(
        override val opId: Ulid,
        override val entityId: Ulid,
        override val entityType: EntityType,
        override val hlc: Hlc,
        override val actor: Actor,
        override val deviceId: String,
        override val wallClock: Long,
        override val seq: Long? = null,
    ) : Op()
}

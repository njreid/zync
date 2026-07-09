package dev.njr.zync.core.state

import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
import kotlinx.serialization.json.JsonElement

/** A LWW register value: the winning payload plus the clock/actor that set it. */
data class RegisterValue(val value: JsonElement, val hlc: Hlc, val actor: Actor)

/** A LWW-boolean tag-membership value. */
data class TagValue(val present: Boolean, val hlc: Hlc)

/** Key for the `(entityId, field)` register map. */
data class RegisterKey(val entityId: Ulid, val field: String)

/** Key for the `(nodeId, contextId)` tag-membership map. */
data class TagKey(val nodeId: Ulid, val contextId: Ulid)

/**
 * A queryable projection of one entity, folded from the register/tombstone/tag/
 * move state. `alive` follows spec existence: has ≥1 register **and** is not
 * tombstoned (tombstone is terminal). `parent` is null for root-level nodes.
 */
data class EntitySnapshot(
    val entityId: Ulid,
    val alive: Boolean,
    val fields: Map<String, JsonElement>,
    val tags: Set<Ulid>,
    val parent: Ulid?,
)

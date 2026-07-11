package dev.njr.zync.web.content

import dev.njr.zync.core.id.Ulid
import kotlinx.serialization.json.JsonElement

/**
 * Op-writing primitives the shared UI mutates through. Each surface supplies an impl:
 * the phone wires it to `OpWriter` (local ops queued for sync), the server to its ingest
 * (apply + persist, replicas converge). [ContentCommands] maps UI intents onto these.
 */
interface OpEmitter {
    /** Mint a new entity id. */
    fun newId(): Ulid

    fun setField(entity: Ulid, field: String, value: JsonElement)
    fun move(node: Ulid, newParent: Ulid)
    fun addTag(node: Ulid, context: Ulid)
    fun removeTag(node: Ulid, context: Ulid)
    fun tombstone(entity: Ulid)
}

package dev.njr.zync.replica

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.web.content.OpEmitter
import kotlinx.serialization.json.JsonElement

/**
 * The phone's [OpEmitter] for the shared web UI (M6): every content mutation goes
 * through [OpWriter], so it's applied to the local store AND queued unsynced for the
 * next push. The same `:web` `ContentCommands`/`ContentReadModel` the server uses run
 * on the phone loopback over this emitter.
 *
 * [onLocalMutation] fires after each *local* mutating op (never on a sync pull, which
 * applies ops straight to the store, not through this emitter) — the phone uses it to
 * request a prompt push without risking a pull→notify→push loop.
 */
class PhoneOpEmitter(
    private val writer: OpWriter,
    private val onLocalMutation: () -> Unit = {},
) : OpEmitter {
    override fun newId(): Ulid = writer.newId()
    override fun setField(entity: Ulid, field: String, value: JsonElement) { writer.setField(entity, field, value); onLocalMutation() }
    override fun move(node: Ulid, newParent: Ulid) { writer.move(node, newParent); onLocalMutation() }
    override fun addTag(node: Ulid, context: Ulid) { writer.addTag(node, context); onLocalMutation() }
    override fun removeTag(node: Ulid, context: Ulid) { writer.removeTag(node, context); onLocalMutation() }
    override fun tombstone(entity: Ulid) { writer.tombstone(entity); onLocalMutation() }
}

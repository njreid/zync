package dev.njr.zync.server.content

import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.HlcGenerator
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import dev.njr.zync.server.sync.SyncService
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.OpEmitter
import dev.njr.zync.web.sse.ChangeNotifier
import kotlinx.serialization.json.JsonElement
import kotlin.random.Random

/**
 * Op-emitter for the central server's content UI: browser mutations become
 * server-authored ops (deviceId `server`, real wall-clock HLC) ingested via
 * [SyncService.ingestLocal] — so they merge, persist, and sync to replicas.
 */
class ServerOpEmitter(
    private val service: SyncService,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) : OpEmitter {
    private val clock = Clock { now() }
    private val hlc = HlcGenerator("server", clock)

    override fun newId(): Ulid = Ulid.generate(clock, random)
    override fun setField(entity: Ulid, field: String, value: JsonElement) =
        ingest(Op.SetField(newId(), entity, EntityType.Node, hlc.now(), Actor.Human, "server", now(), field, value))
    override fun move(node: Ulid, newParent: Ulid) =
        ingest(Op.Move(newId(), node, EntityType.Node, hlc.now(), Actor.Human, "server", now(), newParent))
    override fun addTag(node: Ulid, context: Ulid) =
        ingest(Op.AddTag(newId(), node, EntityType.Tag, hlc.now(), Actor.Human, "server", now(), context))
    override fun removeTag(node: Ulid, context: Ulid) =
        ingest(Op.RemoveTag(newId(), node, EntityType.Tag, hlc.now(), Actor.Human, "server", now(), context))
    override fun tombstone(entity: Ulid) =
        ingest(Op.Tombstone(newId(), entity, EntityType.Node, hlc.now(), Actor.Human, "server", now()))

    private fun ingest(op: Op) {
        service.ingestLocal(op)
    }
}

/**
 * The server's content-UI wiring: a read model over the merged store, commands that
 * ingest server-authored ops, and a change feed the browser SSE follows (fired on any
 * ingest — browser mutations AND phone pushes).
 */
class ServerContent(service: SyncService, val changes: ChangeNotifier = ChangeNotifier()) {
    val read = ContentReadModel(service.stateStore)
    val commands = ContentCommands(ServerOpEmitter(service))
}

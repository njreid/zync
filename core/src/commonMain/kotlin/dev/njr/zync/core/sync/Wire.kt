package dev.njr.zync.core.sync

import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.Op
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The phone↔server sync wire contract (op/merge spec §6). `seq` is the server's
 * transport cursor (assigned on ingest); HLC on each op remains the merge order.
 */

/** `POST /sync/push` body — the phone's pending ops. */
@Serializable
data class PushRequest(val ops: List<Op>)

/** `POST /sync/push` reply — which ops were accepted, and the server's head seq. */
@Serializable
data class PushResponse(val ackedOpIds: List<Ulid>, val serverHead: Long)

/** `GET /sync/pull?since=<cursor>` reply — ops with `seq > cursor`, paged, plus head. */
@Serializable
data class PullResponse(val ops: List<Op>, val head: Long)

/** `POST /blob` reply — the server-computed content-addressed key. */
@Serializable
data class BlobKeyResponse(val key: String)

/** A LWW register in the bootstrap snapshot. */
@Serializable
data class RegisterEntry(val entityId: Ulid, val field: String, val value: JsonElement, val hlc: Hlc, val actor: Actor)

@Serializable
data class TombstoneEntry(val entityId: Ulid, val hlc: Hlc)

@Serializable
data class TagEntry(val nodeId: Ulid, val contextId: Ulid, val present: Boolean, val hlc: Hlc)

/**
 * `GET /sync/bootstrap` reply — a compacted snapshot for a fresh install / new
 * device: the register map + tombstones + tags + the move-log tail + head seq.
 * The client seeds state from this, then tails ops by `seq` (never replays history).
 */
@Serializable
data class BootstrapSnapshot(
    val registers: List<RegisterEntry>,
    val tombstones: List<TombstoneEntry>,
    val tags: List<TagEntry>,
    val moves: List<Op.Move>,
    val headSeq: Long,
)

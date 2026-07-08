# zync — Op Log, Merge & Operator Model (spec)

> **Status: 🟡 DRAFT for review (2026-07-08).** The implementable core behind the
> two architecture specs (`…-backup-sync-architecture.md`,
> `…-kotlin-kmp-target-architecture.md`). Lives in the shared KMP `core` module
> (op types, HLC, merge, apply, move algorithm, serialization); `data` holds the
> SQLDelight schema; `server` holds the operator/agent runtime. Language-agnostic on
> purpose so the Kotlin-vs-anything pick stays reversible.

## 0. Invariants (what must always hold)

1. **The op log is the source of truth.** All state is a deterministic fold of the
   log. Materialized tables are a cache that can be rebuilt.
2. **Convergence:** any two replicas that have seen the same set of ops compute
   byte-identical state, regardless of delivery order.
3. **Capture never blocks:** the phone appends ops locally while offline; sync is
   best-effort on reconnect.
4. **Provenance is on every op** (human / operator / agent) and drives
   field-ownership, loop-breaking, and audit.
5. **Two replicas only** that ever diverge: **phone** (offline-capable) and
   **server** (always-on, the integration point). Desktop/browser write straight to
   the server (no local log), so they never diverge.

## 1. Identity & clocks

- **Entity IDs**: **ULID** (or UUIDv7) generated at the edge — globally unique, no
  coordination, so offline `create` never collides.
- **Op IDs**: ULID. The **idempotency key** for delivery — re-delivering an op is a
  no-op.
- **HLC (Hybrid Logical Clock)** — the *conflict-resolution* order. Packed as
  `(physicalMillis, counter, deviceId)`:
  ```
  fun now(): Hlc {
    val p = max(wallClockMillis(), last.physical)
    val c = if (p == last.physical) last.counter + 1 else 0
    last = Hlc(p, c, deviceId); return last
  }
  fun observe(remote: Hlc) {           // call on receiving any remote op
    val p = max(wallClockMillis(), last.physical, remote.physical)
    val c = when {
      p == last.physical && p == remote.physical -> max(last.counter, remote.counter) + 1
      p == last.physical -> last.counter + 1
      p == remote.physical -> remote.counter + 1
      else -> 0
    }
    last = Hlc(p, c, deviceId)
  }
  // total order: compare physical, then counter, then deviceId (tiebreak)
  ```
- **Transport cursor ≠ HLC.** The **server assigns a monotonic `seq`** to each op on
  ingest; peers pull "ops with `seq > cursor`". `seq` is for *transport* ("what's
  new"); **HLC is for *merge*** ("who wins"). Keep these distinct — conflating them
  is the classic bug.

## 2. Entities as CRDTs

State is modeled as **LWW-Registers keyed by `(entityId, field)`** plus a couple of
special cases. A register holds `{value, hlc, actor}`; **merge = higher HLC wins**
(tiebreak by `deviceId`). Everything below reduces to that except tree-moves.

| Entity | Fields (LWW registers) | Special |
|---|---|---|
| `node` | `title, notes, kind, status, deferUntil, sortOrder` | `parent` via **move** (§4); existence via **tombstone** |
| `context` | `name` | tombstone |
| tag membership | per `(nodeId, contextId)`: LWW boolean `present` | — |
| `attachment` | *immutable* (`nodeId, type, blobHash, relativePath`) | add + tombstone only |

- **Trash/complete/reopen are ordinary LWW `status` writes** (reversible), *not*
  tombstones. Hard **tombstones** are reserved for permanent purge + attachment
  removal, and **tombstone wins** over concurrent edits (terminal).
- `convert task→project` = a bundle: `SetField(kind)` + `Move(→folder)`.

## 3. Op types

```
Op {
  opId: Ulid            // idempotency key
  seq: Long?            // server-assigned on ingest (null until then)
  entityId: Ulid
  entityType: Node|Context|Tag|Attachment
  type: SetField | Move | AddTag | RemoveTag | AddAttachment | Tombstone
  field: String?        // for SetField
  value: Json?          // for SetField / AddAttachment payload
  newParentId: Ulid?    // for Move
  hlc: Hlc              // merge order
  actor: Human | Operator(id) | Agent(id)
  deviceId: String
  wallClock: Long       // informational only
}
```
`create` is just the first `SetField`s for an entity (kind/title/parent…) bundled in
one transaction. There is no distinct "create" op type — existence = "has ≥1
non-tombstoned register."

## 4. The one hard part: tree moves

`Move(node, newParent)` can create **cycles** (A→under B while B→under A) or orphan
subtrees. Use **Kleppmann's move algorithm**:

- Keep every move in a **move log** ordered by HLC.
- To integrate a move: process moves **in HLC order**; when applying a move, if
  `newParent` is `node` itself or a descendant of `node`, **skip it** (node stays
  put). Same input + same order + same cycle-check ⇒ all replicas converge.
- **Late-arriving move** (HLC earlier than moves already applied): **undo** the moves
  after it, apply it, **redo** the rest. O(#moves-after) — fine at personal scale.

**Two-party simplification (adopted):** the **server is the move arbiter**. The
phone applies its offline moves *optimistically* against local state; on sync it
adopts the server's integrated result (the server ran the algorithm over the merged
move log). The phone still runs the algorithm locally for its optimistic view, but
final convergence is "server integrates, phone pulls the resolved order." This keeps
correctness while avoiding multi-party coordination.

## 5. Merge = apply, deterministically

Applying any op is **idempotent** (dedupe by `opId`) and **commutative** under these
rules, so delivery order doesn't change final state:

```
applySetField(op):   reg = registers[op.entityId, op.field]
                     if reg == null || op.hlc > reg.hlc: registers[...] = {op.value, op.hlc, op.actor}
applyTombstone(op):  tombstones[op.entityId] = max(existing, op.hlc); tombstone wins
applyTag(op):        reg = tagRegisters[nodeId, contextId]
                     if op.hlc > reg.hlc: reg = {present = (op.type==AddTag), op.hlc}
applyMove(op):       moveLog.insert(op); reintegrateMovesFrom(op.hlc)   // §4
```
Materialized `node`/`context`/etc. tables are projections of the register map (kept
for query speed; rebuildable).

## 6. Sync protocol (phone ↔ server)

```
PUSH:  phone POSTs its pending ops (synced=false).
       server: for each op (dedupe by opId) → assign seq → apply (§5) → persist.
               returns {ackedOpIds, serverHead}
       phone: mark acked ops synced=true.
PULL:  phone GETs ops where seq > cursor  (paged).
       phone: observe(hlc) then apply (§5) for each; advance cursor = max seq.
BOOTSTRAP (fresh install / new device): pull a compacted **snapshot** of current
       state (register map + move log tail) then tail ops by seq — never replay all
       history.
```
- **Auth**: device Ed25519 identity (native) / server session (browser). Ops from an
  unknown device are rejected.
- **Blobs**: attachment bytes go to S3 (content-addressed, `blob-<sha256>`) via the
  server; the op only carries the hash + `relativePath`. Phone uploads pending blobs
  on sync; `putIfAbsent` dedupes.
- **Consistency window**: desktop sees server truth immediately; the phone's
  unsynced offline edits are local-only until it reconnects. Accepted.

## 7. Operators (central, server-only)

An **operator manifest**:
```
Operator {
  id; name
  readScope:  predicate over the tree (e.g. "kind=task AND parent=INBOX AND tags=∅")
  writeScope: fields/child-types it may emit (must exclude human-owned fields)
  trigger:    on op whose entity enters/changes within readScope
  output:     JSON schema for the LLM result (typed)
  retries:    N attempts on schema-validation failure   // the ONLY control flow
  fuel:       max ops per firing + max ops per cascade
}
```
**Lifecycle:** trigger → read entity at **version V** (its current register HLCs) →
call LLM → validate against `output` schema (retry ≤ N) → emit ops
(`actor=Operator(id)`, within `writeScope`) → record `handled(operatorId, entityId,
V)`.

**Rules (enforced, not aspirational):**
- **Idempotency:** skip if `handled(operatorId, entityId, V)` exists. Re-fire only
  when the input version changes (**re-entrancy** for late-arriving offline history).
- **No self-trigger:** an operator's own output ops never re-trigger it (checked via
  `actor`).
- **Field ownership:** `writeScope` excludes human-owned fields; an operator op also
  **loses any LWW race to a human write** (operators stamp HLC from the input
  version's region, so a later human edit wins). Operators prefer **adding child /
  annotation nodes** over overwriting.
- **Termination:** cycles between operators are detected from declared scopes; each
  cascade has a **fuel budget**; exceeding it halts the cascade and flags it.
- Operators are **eventual** and **never run on the phone / offline**.

## 8. Agents (human-gated)

- An operator may emit a **recommendation** node (`actor=Operator`) — e.g. "run a
  research agent on this project."
- **The human approves** → an **agent task** is created (`actor=Human`) → the agent
  runs server-side (may be long-running, may call tools) → emits **proposed objects**
  (`actor=Agent`, flagged `proposed`) into the tree.
- Agent output is **proposals the human accepts/edits/trashes**, never silent
  mutation of human-authored state. Acceptance is a human op.
- Agents are a higher trust/cost tier than operators and are out of the merge core
  (they're just another provenance-tagged writer).

## 9. Worked conflicts (pressure tests)

1. **Field edit race.** Phone offline `title="Buy milk"` (h1); desktop online
   `title="Buy oat milk"` (h2>h1). Sync → LWW h2 wins everywhere. (If h1>h2, phone
   wins.) Deterministic.
2. **Move + edit (independent).** Phone moves T under P (offline); server sets
   `status=done`. Different registers → both apply: T is under P **and** done.
3. **Move cycle.** Phone `A→under B`; server `B→under A`. Move algorithm in HLC
   order skips the later one that would form a cycle → one winner, no cycle, both
   converge.
4. **Operator vs human.** Human renames T (h2, offline) while operator writes
   `summary` (operator-owned, h1) on the server. No conflict (different fields); and
   had the operator targeted `title`, scope forbids it / it loses to h2.
5. **Late history re-triggers operator.** Phone offline a day, adds 5 inbox tasks;
   on sync they land with past HLCs; the inbox-clarify operator's readScope matches →
   fires once per task (idempotent by version), producing clarifications.
6. **Trash vs edit.** Phone edits `notes` (offline); server `status=DROPPED`. Both
   LWW registers → T keeps the new notes and is trashed (reversible). No data lost.

## 10. SQLite schema (sketch)

```
op_log(op_id PK, seq, entity_id, entity_type, op_type, field, value,
       hlc_physical, hlc_counter, hlc_device, actor, device_id, wall_clock, synced)
register(entity_id, field, value, hlc_physical, hlc_counter, hlc_device, actor,
         PRIMARY KEY(entity_id, field))          -- the LWW state
tombstone(entity_id PK, hlc_physical, hlc_counter, hlc_device)
move_log(op_id PK, node_id, new_parent_id, hlc_physical, hlc_counter, hlc_device)
tag(node_id, context_id, present, hlc_physical, hlc_counter, hlc_device,
    PRIMARY KEY(node_id, context_id))
sync_state(peer PK, cursor)
operator_run(operator_id, entity_id, input_version, status,
             PRIMARY KEY(operator_id, entity_id, input_version))
-- materialized projections: node, context, node_context, attachment (rebuildable)
```
Same schema (SQLDelight) on phone and server; the phone omits `seq` assignment
(server-only) and adds the `synced` flag usage.

## 11. Open questions
- **Tags:** LWW-boolean per membership (chosen) vs an OR-Set (handles rapid
  add/remove/add better). LWW-boolean is simpler; revisit if tag churn is high.
- **Tombstone policy:** "tombstone always wins" (chosen) vs LWW delete-vs-edit
  (resurrect). GTD uses reversible `status` for most removal, so hard tombstones are
  rare — the simple rule is fine.
- **Snapshot/compaction cadence** for bootstrap + `op_log` truncation (keep last N?).
- **Operator readScope language:** a small predicate DSL vs. reusing SQLDelight
  queries. Reusing queries avoids a second engine.
- **Clock skew / malicious HLC:** the server can clamp incoming `hlc.physical` to a
  sane window on ingest (it's the trusted arbiter).
- **Op payload size / privacy:** ops carry plaintext values (trusted-server model);
  encrypt at rest server-side; consider size padding only if metadata leakage matters.

## 12. What this feeds
- `core` (KMP): `Op`, `Hlc`, registers, `apply`, the move algorithm, serialization,
  operator manifest types + validation. **The must-be-identical code.**
- `data`: the SQLDelight schema above.
- `server`: seq assignment, the sync endpoints, the operator runtime + LLM client,
  the agent runtime, S3/blob handling.
- Next: the **migration/milestones plan** sequences building this without a big-bang
  rewrite.

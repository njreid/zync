# External op API — bots, scripts & integrations writing to Zync — spec (2026-07-22)

> Status: SPEC (planning only). Defines how external actors (scripts, bots, integrations
> like Newz) submit, edit, comment on, and *propose* changes to Zync items. The thesis:
> Zync already has the substrate — an op-log CRDT with per-op provenance (`Actor`), a
> server-side op-emission path (`SyncService.ingestLocal`), an operator scope/capability
> model, and an agent-proposal flow (`proposed` flag + accept/reject). This is **not a new
> system**; it is a friendly, capability-scoped **front door** onto the actor model that
> already runs everything. Builds on the target architecture
> (`2026-07-08-kotlin-kmp-target-architecture.md`) and the operator/merge model
> (`2026-07-08-oplog-merge-operator-model.md`). Supersedes the ad-hoc single-token
> integration endpoints (`/agenda`, `/integrations/newz/*`) as the general pattern.

## 0. First principles

Everything in Zync is a fold over a log of ops (`SetField`/`Move`/`AddTag`/`AddAttachment`/
`Tombstone`), each stamped with an `Actor` (`Human`/`Operator(id)`/`Agent(id)`), an HLC, an
opId, and a device id. Devices push **signed** ops; operators emit ops server-side via
`SyncService.ingestLocal`; agents surface output as `proposed` nodes a human accepts.

Therefore an external bot is **a new class of actor**, not a new capability. The design
reduces to three decisions:

1. **Intents, not raw ops.** Bots must not mint HLCs/ULIDs, serialize `Op`, or sign like a
   replica. They express high-level intents (*comment X on Y*, *propose dueDate*); the
   **server** translates to ops and owns the HLC, opId, and — critically — the `Actor`
   provenance. This is `ContentCommands` exposed over the wire, parameterized by an
   authenticated identity.
2. **Capabilities, not trust-by-connection.** A request originating from "an integration"
   grants nothing by itself. Each bot identity carries explicit capabilities (verbs it may
   use, fields/subtree it may touch, commit-vs-propose) — the same shape as an operator's
   read/write scope.
3. **Provenance is server-assigned and unspoofable.** Every external write is `Agent(botId)`;
   a bot can never author as `Human`. The UI attributes it.

## 1. Actor model — reuse `Agent`, don't fork it

`core/.../op/Actor.kt` is `sealed interface Actor { Human; Operator(id); Agent(id) }`,
serialized into every op. External bots map cleanly onto **`Actor.Agent(id)`** where `id` is
the bot's registered id: the semantics (an automated, reviewable writer whose output may be
proposed) already match, and the proposals panel already keys on *agent-authored + proposed*.

- **Decision: reuse `Actor.Agent(id)`.** No new `Actor` variant, so no op-serialization
  change and no risk to the golden-locked `Op` vectors.
- Human-readable names live in the **bot registry** (§2), resolved for display; ops stay
  compact (`Agent("newz")`). If we later want a hard human/agent/bot trichotomy in the merge
  layer (e.g. bots can *never* win LWW over a human on the same field), that becomes an
  `Actor` variant + a merge rule — noted as a follow-up, not needed for v1.

## 2. Identity & capabilities — the bot registry

A persisted registry (mirrors the `device`/`allowed_device` pattern), one row per bot:

```
bot(
  id            TEXT PRIMARY KEY,     -- stable, appears in Actor.Agent(id) + attribution
  name          TEXT NOT NULL,        -- display name ("Newz", "Reading-list scraper")
  secret_hash   TEXT NOT NULL,        -- sha256 of the bearer token (never store the token)
  capabilities  TEXT NOT NULL,        -- JSON: the capability grant (below)
  created_wall  INTEGER NOT NULL,
  revoked       INTEGER NOT NULL DEFAULT 0
)
```

**Capability grant** (JSON in `capabilities`):

```json
{
  "verbs": ["create", "comment", "addTag", "setField", "move", "complete", "trash"],
  "mode": "commit" | "propose",              // may it write live, or only propose?
  "fields": ["title","notes","dueDate","..."] | "*",   // setField whitelist
  "subtree": "<ulid>|inbox|reference|*",     // where it may create/move (root of allowed area)
  "rateLimitPerMin": 120
}
```

- `mode:"propose"` forces every mutation through the proposal path (§4) regardless of verb —
  the default for low-trust bots (public scrapers).
- `mode:"commit"` lets a trusted internal service (e.g. Newz, first-party) write live.
- Capability checks are enforced **server-side per intent**, before any op is emitted — the
  operator `WriteScope.permits` machinery generalizes here.

**Auth transport:** `Authorization: Bearer <token>`; the server hashes and looks up
`secret_hash`. Tokens are minted out-of-band (`server bot add <name> --caps …` CLI, mirroring
`server pair`). **v1 fallback:** a single env-configured token with a fixed capability grant
(exactly like `ZYNC_AGENDA_TOKEN`), so Newz can integrate before the registry table lands.
The registry (a v6→v7 migration) is the durable multi-bot answer.

## 3. The op-intent envelope (transport-agnostic)

One schema, so the same messages ride REST now and the websocket / a bus later. An envelope
is a batch of intents applied atomically (all-or-nothing) with an idempotency key:

```json
{
  "idempotencyKey": "9f2c…",           // client-generated UUID; makes retries safe (§5)
  "mode": "commit" | "propose",        // optional override, clamped by the bot's capability
  "intents": [
    { "op": "create",  "kind": "task", "title": "Read: Foo", "parent": "inbox",
      "fields": { "notes": "https://…", "person": "Sam" }, "tags": ["<ctxUlid>"] },
    { "op": "comment", "target": "<ulid>", "text": "Auto-summary: …" },
    { "op": "setField","target": "<ulid>", "field": "dueDate", "value": 1893456000000 },
    { "op": "addTag",  "target": "<ulid>", "context": "<ulid>" },
    { "op": "move",    "target": "<ulid>", "parent": "<ulid>" },
    { "op": "complete","target": "<ulid>" },
    { "op": "trash",   "target": "<ulid>" },
    { "op": "attach",  "target": "<ulid>", "blobRef": "<sha256>", "type": "pdf", "name": "a.pdf" }
  ]
}
```

Notes:
- **Intents are high-level, not `Op`.** The server translates each to one or more `Op`s via a
  server-side `ExternalContentCommands(actor = Agent(botId))` layered on the existing
  `ContentCommands`/`OpEmitter` → `ingestLocal`. Server owns HLC/opId/actor/wallClock.
- `"parent": "inbox"` / `"reference"` are well-known aliases resolving to the inbox root /
  `WellKnownNodes.REFERENCE_ROOT`; bare ULIDs are literal.
- **Blobs (`attach`)** are two-step: `PUT /api/blobs` (content-addressed, returns the sha256
  key, reusing `BlobService`) then reference it by `blobRef`. Media/markdown from the Newz
  extraction flow lands this way, verified by hash/length.
- Response returns, per intent, the resulting `nodeId`(s) and whether it committed or was
  filed as a proposal, so a bot can correlate.

## 4. Commit vs propose — and the field-edit proposal (the one hard part)

**Committing** is straightforward: translate intents → ops with `Actor.Agent(botId)` →
`ingestLocal`. Provenance flows through; the UI shows attribution.

**Proposing a new node** already works: set `AgentFlow.FIELD_PROPOSED` on the created node;
it surfaces in the proposals panel; accept/reject are the existing human ops.

**Proposing an *edit to an existing field* is genuinely new** and needs a model — a live
`SetField` would just overwrite (LWW), and the node-level `proposed` flag can't represent
"pending change to field F of node Y". The clean answer, which **reuses the node model**:

- A proposed edit is a **suggestion node**: `kind="suggestion"`, `proposed=true`,
  `actor=Agent(botId)`, fields `{ targetId, targetField, proposedValue, rationale? }`,
  parented under its target (like a comment). No new op type, no schema change — it is
  ordinary content, so projection, sync, and the proposals panel handle it for free.
- **Accepting** a suggestion is a human op that (a) emits the real `SetField(targetId,
  targetField, proposedValue)` as `Actor.Human`, then (b) tombstones/clears the suggestion.
  **Rejecting** just tombstones it. This mirrors `acceptProposal`/`acceptFileSuggestion`.
- The proposals/detail UI renders suggestions as "*@newz suggests dueDate → Aug 3* [Accept]
  [Dismiss]", diffing against the current value.
- Concurrency: two bots suggesting the same field → two suggestion nodes (both survive; human
  picks). A suggestion whose target field changed underneath it before acceptance shows a
  "value moved" note (compare `proposedValue`'s basis) — advisory, not blocking.

This makes "propose" uniform across create / comment / edit and keeps the human as the only
actor that mutates live human-owned state — consistent with the operator field-ownership rule
(threat model T4).

## 5. Idempotency & delivery

- The op log is idempotent by `opId`, but intents don't carry opIds. So the server persists
  `(bot_id, idempotency_key) → [emitted opIds / node ids]` (a small `bot_request` table, or a
  bounded in-memory LRU for v1). A retry with the same key returns the original result and
  emits nothing new — the same contract the Newz extraction spec's `Idempotency-Key` assumes.
- Envelopes are **atomic**: all intents in one envelope ingest in a single transaction (like
  `SyncService.push`), or none do. Partial failure → 4xx, no ops emitted.
- At-least-once from the bot's side is fine; idempotency keys make it effectively
  exactly-once at the item level.

## 6. Transport — REST now; websocket/bus later, same envelope

**Recommendation: REST for writes + the existing SSE change feed for reads.** REST is
universal (curl, any language, webhooks, the Newz WebView via `fetch`), stateless, trivially
authed, and matches the existing Ktor endpoints. The transport-agnostic envelope (§3) means
adopting another transport is a swap, not a rewrite.

Write endpoints (under a new `/api`, session-exempt, bearer-authed):
- `POST /api/ops` — submit an envelope; returns per-intent results.
- `PUT  /api/blobs` — content-addressed blob upload (returns sha256 key).
- `GET  /api/items/{id}` — read a projected node (for bots that read-then-write).
- `GET  /api/search?q=` — reuse `store.search` (§ Reference/FTS).

Read / react side (for bots that subscribe rather than poll):
- **SSE** `GET /api/changes` (filtered) reusing `ChangeNotifier` — a bot streams a compact
  change feed and reacts. This is the same primitive Datastar already rides.
- **Webhooks** (a registered callback URL per bot, fired on matching changes) are the
  push-to-bot dual of SSE — a later addition; SSE covers v1.

**Deliberately deferred, with the line where they'd earn their keep:**
- **The app↔zyncd websocket is the *replica* channel** (signed real-time push/pull for a
  device holding a local CRDT). Do **not** route bots onto it — they aren't replicas and
  don't want HLC/signing. Keep it for replicas; bots get REST+SSE.
- **gRPC** — a nice typed contract, but generated stubs are friction for random scripts and
  it's awkward for webhooks/browsers. Skip.
- **NATS / a bus** — the right answer *if/when* Zync's **internal** nervous system needs
  durable fan-out with backpressure across many producers/consumers (operators, agents,
  external ingress). It's an ops dependency; the current single-writer server works. Adopt it
  as an internal transport once producer/consumer count justifies it — the envelope schema is
  designed to be the message payload if so.

## 7. Provenance, attribution & safety

- Every external op carries `Actor.Agent(botId)`; the read model resolves the bot's display
  name. Comments, suggestions, and edits show *by whom* (the UI already renders `Actor` for
  proposals — extend to comments/edits).
- **Field ownership:** a `commit`-mode bot can write live, but human-owned fields should still
  prefer human LWW on conflict — enforce via capability `fields` whitelists now, and (later)
  an `Actor` merge rule if we want it structural.
- **Hardening:** reuse `installHardening` — per-token rate limits (from the capability grant),
  size caps on envelopes/blobs, and the existing remote-IP limiter. `/api/*` joins
  `SESSION_EXEMPT` (bearer-authed, not session-authed). Revocation is a `bot.revoked` flag
  checked per request (like device revocation).
- **Audit:** log `(botId, idempotencyKey, verbs, targetIds, outcome)` without logging values —
  same posture as the newz/extraction audit requirement.

## 8. How Newz plugs in

- **WebView "push into Zync":** the Newz WebView (already device-authenticated via the handoff
  SSO) calls `POST /api/ops` with a create intent (article → inbox task, url in `notes`,
  markdown as a comment/attachment). If Newz should stage rather than commit, its bot grant is
  `mode:"propose"`.
- **Newz URL-extraction ingestion (the deferred half of the newz spec):** Zync's
  extraction-result handler downloads the returned media via `PUT /api/blobs` (hash-verified)
  and creates the item via `POST /api/ops` — i.e. the *same door*, no bespoke path. The
  `Idempotency-Key` from the extraction job maps to the envelope key.
- Newz's existing single-purpose tokens (`ZYNC_AGENDA_TOKEN`, `ZYNC_NEWZ_REDEEM_TOKEN`) are
  the precedent for the v1 env-token fallback; the registry generalizes them.

## 9. Data-model / code touch-points

- `core`: no `Actor` change (reuse `Agent`). Optional: a `kind="suggestion"` vocabulary
  constant + `Fields.TARGET_ID`/`TARGET_FIELD`/`PROPOSED_VALUE` for suggestion nodes.
- `data`: `bot` (+ optional `bot_request`) tables → **schema v6→v7** migration (`6.sqm`);
  bump the three version-assertion sites (data `SqlDelightStateStoreTest`, server
  `DurabilityTest`, add a `MigrationTest` v6→v7 case). Env-token fallback needs no migration.
- `server`: `BotRegistry` + `BotAuth`; `ExternalContentCommands(actor)` over `ingestLocal`;
  `apiRoutes(...)` (`/api/ops`, `/api/blobs`, `/api/items`, `/api/search`, `/api/changes`);
  wire into `zyncModule` + `SESSION_EXEMPT` + `installHardening`; `server bot add` CLI.
- `web`: render suggestion nodes + attribution in the proposals panel / detail; accept/reject
  commands (`acceptSuggestion` emits the real `SetField` + tombstones the suggestion).
- `app`: none required for the server API; the phone loopback can expose `/api` too (it serves
  `:web` over the same op stack) if on-device bots ever want it.

## 10. Test plan

- **Envelope translation** (server): each intent → the expected `Op`(s) with `Actor.Agent`;
  `commit` vs `propose` routing; capability rejection (verb/field/subtree/rate) → 403 with no
  ops emitted; atomic envelope (one bad intent → whole batch rejected).
- **Idempotency**: same key twice → one set of ops, identical response.
- **Suggestion lifecycle** (mirror `SummarizeEndToEndTest`/`FileSuggestionTest`): propose a
  field edit → suggestion node appears in proposals; accept → real `SetField(Human)` + suggestion
  tombstoned; reject → tombstoned, no field change.
- **Blob path**: `PUT /api/blobs` dedupes by hash; `attach` intent references it; blob-before-op
  ordering holds.
- **Migration**: v6→v7 creates `bot`; version assertions bump together.
- **Playwright**: a seeded suggestion renders "[Accept]/[Dismiss]" and accepting updates the value.
- **End-to-end**: a fake "bot" posts an envelope over `testApplication`, item appears in the
  projection/inbox with correct attribution.

## 11. Sequencing (proposed)

1. **Env-token fallback + `POST /api/ops` (commit only)** — the minimal door: one trusted bot,
   create/comment/setField/move via `ExternalContentCommands`, idempotency LRU. Unblocks Newz.
2. **`propose` mode + suggestion nodes** + accept/reject UI (the field-edit model, §4).
3. **`/api/blobs` + `attach`** — media ingestion; wire the Newz extraction result handler.
4. **Bot registry (v7 migration) + capabilities + `server bot add`** — multi-bot, scoped.
5. **`/api/changes` SSE** (react side) + webhooks; **NATS** only if fan-out demands it.

Each step is independently shippable and testable (JVM route tests + Playwright).

## 12. Open questions

1. **Actor trichotomy?** Keep bots as `Agent`, or add `Actor.Bot`/`External` with a structural
   merge rule (human LWW always beats a bot on the same field)? Proposed: reuse `Agent` for
   v1; revisit if capability whitelists prove insufficient.
2. **Suggestion staleness UX** — when a proposed field's live value moved before acceptance,
   accept-anyway / show-diff / auto-dismiss? Proposed: show the diff, human decides.
3. **Envelope size / batch limits** and whether cross-envelope ordering matters for a bot doing
   many small writes (vs one big batch).
4. **Webhook delivery guarantees** — at-least-once with retries + a dead-letter, or best-effort?
   (Only relevant once webhooks land.)
5. **Rate-limit granularity** — per-token only, or per-token-per-verb (e.g. cheap reads vs
   expensive creates)?
6. **Do we want a typed client SDK** (Kotlin/TS) generated from the envelope schema, or is the
   JSON contract + examples enough for third-party bots?

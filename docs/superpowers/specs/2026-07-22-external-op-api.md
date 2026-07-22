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

## 1. Actor model — a distinct `Actor.Bot` with a structural merge rule (RESOLVED Q1)

`core/.../op/Actor.kt` is `sealed interface Actor { Human; Operator(id); Agent(id) }`,
serialized into every op. **Decision (Q1): add `Actor.Bot(id)`** rather than reuse `Agent`,
because we want a *structural* guarantee — not just a capability whitelist — that automated
external writers can never silently overwrite a human's decision.

- **New variant** `Actor.Bot(val id: String)` (`@SerialName("bot")`). Adding a sealed subtype
  is backward-compatible for the wire (old ops never carry it); but it **touches the
  golden-locked serialization + merge conformance**, so it needs new vectors (see
  `2026-07-08-merge-conformance-vectors.md`) and a version bump of the conformance suite.
- **Merge rule (the point of the variant):** a register value authored by `Human` is **never
  overwritten by a `Bot`**, regardless of HLC. Concretely, `Apply.lww` gains an actor-priority
  override layered on the HLC comparison — the register already carries `RegisterValue.actor`,
  so the merge can consult writer class:
  - `Human` beats `Bot` on the same register, independent of HLC (a late bot op does not clobber a human edit; a bot op that *predates* a human edit also stays shadowed).
  - Among same-class writers (Human↔Human, Bot↔Bot, Operator↔Operator), plain LWW-by-HLC.
  - `Operator` and `Agent` keep today's behavior (LWW); only the Human-vs-Bot pair is special.
  This makes "a bot proposes, a human disposes" true even for *committed* bot writes on
  human-touched fields — the capability whitelist (§2) then just narrows *which* fields a bot
  may attempt at all.
- Human-readable names live in the **bot registry** (§2), resolved for display; ops stay
  compact (`Bot("newz")`). The proposals panel keys on *proposed + non-Human actor*, so it
  already surfaces bot output.

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
  "rateLimit": { "default": 120, "create": 30, "attach": 10 }  // per-minute, per verb (Q5)
}
```

Rate limits are **per-token, per-verb** (RESOLVED Q5): each verb draws from its own bucket
(`rateLimit.<verb>`, falling back to `default`), so cheap reads and expensive
creates/attaches are throttled independently.

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
- **Hardening:** reuse `installHardening` — **per-token, per-verb** rate limits (Q5; keyed
  `(botId, verb)`, evicting/bounded buckets like the existing limiter), size caps on
  envelopes/blobs, and the existing remote-IP limiter as a backstop. `/api/*` joins
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

- `core`: **add `Actor.Bot(id)`** (`@SerialName("bot")`) + the Human-beats-Bot override in
  `merge/Apply.kt` `lww` (§1); **new merge-conformance + serialization vectors** and a bump of
  the conformance suite version. Plus a `kind="suggestion"` vocabulary constant +
  `Fields.TARGET_ID`/`TARGET_FIELD`/`PROPOSED_VALUE` for suggestion nodes.
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

## 9a. Client SDKs — Kotlin + Go, stdlib-first (RESOLVED Q6)

Ship **typed SDKs in Kotlin and Go** up front (not just a JSON contract), leaning on each
language's standard library as far as possible so third-party bots have near-zero deps:

- **Envelope types are the contract.** The intent envelope (§3) + capability grant (§2) are
  defined once as the source of truth; the SDKs mirror them as typed structs. (v1: hand-write
  the small type set in each language; if it grows, generate from a single JSON-Schema. Avoid
  a heavy codegen toolchain — the point is stdlib-first.)
- **Kotlin SDK:** a thin `ZyncClient(baseUrl, token)` over `kotlinx.serialization` for the
  envelope + the existing Ktor client (already a project dep) — or `java.net.http.HttpClient`
  for a zero-extra-dep option. Verbs mirror the intents: `client.create(...)`,
  `client.comment(...)`, `client.propose(field, value)`, `client.submit(envelope)`; blob
  upload; an SSE `changes()` flow. Publishable as a small artifact.
- **Go SDK:** `net/http` + `encoding/json` only (no third-party deps). `zync.New(baseURL,
  token)`, an `Envelope`/`Intent` struct set, `client.Submit(ctx, env)`, `client.Attach(...)`,
  and a `Changes(ctx)` reader over the SSE stream. Idempotency-Key handled by the client
  (auto-generated per submit, overridable).
- Both SDKs: automatic `Idempotency-Key`, typed error surface for capability/rate/validation
  rejections (map the 4xx codes), and retry-with-backoff that's safe because of idempotency.
- Live in-repo (`sdk/kotlin`, `sdk/go`) with a shared `sdk/README.md` + curl examples, so the
  JSON contract is *also* documented for languages without an SDK.

## 10. Test plan

- **Merge rule** (core, new conformance vectors): a `Bot` `SetField` never overwrites a
  `Human` register value regardless of HLC ordering (bot-after-human AND bot-before-human);
  Bot↔Bot and Human↔Human still LWW-by-HLC; `Operator`/`Agent` unchanged; shuffle-convergence
  still holds. Serialization round-trip for `Actor.Bot`.
- **Envelope translation** (server): each intent → the expected `Op`(s) with `Actor.Bot`;
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
- **SDKs**: Kotlin + Go SDK smoke tests against `testApplication` / a local server — build an
  envelope, submit, assert the item lands + idempotent retry returns the same result + a
  capability rejection maps to the typed error. Keep them dependency-light (stdlib clients).

## 11. Sequencing (proposed)

0. **`Actor.Bot` + Human-beats-Bot merge rule** (core, §1) — foundational, since even
   commit-mode writes must be safe. New conformance/serialization vectors; conformance suite
   bump. Land first so everything downstream stamps the right actor with the right guarantees.
1. **Env-token fallback + `POST /api/ops` (commit only)** — the minimal door: one trusted bot,
   create/comment/setField/move via `ExternalContentCommands(actor = Bot(id))`, atomic
   size-capped envelopes, idempotency LRU. Unblocks Newz.
2. **`propose` mode + suggestion nodes** + accept/reject UI (the field-edit model, §4;
   show-diff on stale, Q2).
3. **`/api/blobs` + `attach`** — media ingestion; wire the Newz extraction result handler.
4. **Bot registry (v6→v7 migration) + per-verb capabilities + `server bot add`** — multi-bot,
   scoped, per-token-per-verb limits (Q5).
5. **`/api/changes` SSE** (react side, Q4 — webhooks deferred); **NATS** only if fan-out demands it.
6. **Kotlin + Go SDKs** (§9a, Q6) — track the endpoints as they land; publish once the
   envelope + capabilities stabilize (after step 4).

Each step is independently shippable and testable (JVM route tests + Playwright + SDK smoke).

## 12. Open questions — RESOLVED 2026-07-22

1. **Actor model.** → **Add `Actor.Bot(id)` with a structural merge rule** (§1): a human
   register value is never overwritten by a bot, regardless of HLC. Costs new
   merge-conformance + serialization vectors, but makes "bot proposes, human disposes" a
   guarantee, not a policy.
2. **Suggestion staleness.** → **Show the diff, human decides** (§4): render proposed vs
   current; accept still applies the proposed value.
3. **Envelope batching.** → **Atomic + size-capped** (§3/§5): all intents in one envelope
   commit in a single transaction or none; cap intent count + bytes; no cross-envelope
   ordering guarantee.
4. **React side.** → **SSE `/api/changes` for v1; webhooks deferred** (§6). Delivery-guarantee
   question re-opens if/when webhooks land.
5. **Rate-limit granularity.** → **Per-token, per-verb** (§2/§7): each verb has its own bucket,
   so cheap reads and expensive creates/attaches throttle independently.
6. **Client tooling.** → **Ship typed Kotlin + Go SDKs up front, stdlib-first** (§9a): Go on
   `net/http`+`encoding/json` only; Kotlin on `kotlinx.serialization` + `java.net.http` (or the
   existing Ktor client). Hand-written types v1; generate from a single JSON-Schema only if the
   type set grows. The JSON contract is documented alongside for other languages.

## 13. Still-open (deferred)

- Webhook delivery guarantees (at-least-once + DLQ vs best-effort) — when webhooks are built.
- Whether the envelope types should be **generated** from one JSON-Schema source vs hand-written
  per SDK — decide once the type set stabilizes (§9a).
- Adopting **NATS** as the internal bus — revisit when producer/consumer fan-out justifies the
  ops dependency (§6).
- A structural policy for `Operator`/`Agent` vs `Human` on the same field (today only the
  Human-vs-`Bot` pair is special) — only if operators/agents ever need the same guarantee.

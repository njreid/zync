# Stateless MCP interface — an always-propose front door for LLM clients — spec (2026-08-11)

> Status: SPEC (planning only). Defines a **Model Context Protocol** endpoint on the central
> server so MCP-speaking clients (Claude Desktop/Code, agent runtimes, IDEs) can *read* Zync's
> content and *propose* changes to it. The thesis mirrors the external-op-api spec
> (`2026-07-22-external-op-api.md`): this is **not a new write system**. It is a thin,
> **stateless** MCP adapter that exposes `ContentReadModel` as read tools/resources and funnels
> every mutation through the existing `ExternalOpApi.submit()` — with one hard rule layered on
> top: **the MCP door always proposes, never commits**, regardless of the bot's capability, so
> a human confirms everything an LLM does. Builds on the external-op-api (actor model,
> capabilities, suggestion nodes, idempotency, hardening) and the operator/merge model
> (`2026-07-08-oplog-merge-operator-model.md`).

## 0. First principles

The external-op-api already established the shape: external actors express **intents**, the
server translates them to provenance-tagged ops (`Actor.Bot(id)`), a capability grant scopes
what each bot may do, and `mode:"propose"` routes mutations through the suggestion-node flow a
human accepts/rejects. MCP does not change any of that. It adds a *second transport* onto the
same door — one shaped for the way LLM clients discover and invoke capabilities — and it hard-
codes the safest policy the existing machinery already supports.

Three decisions define it:

1. **MCP is a transport over the existing envelope, not a new write path.** An MCP `tools/call`
   is translated to one `OpIntent`, wrapped in a one-intent `OpEnvelope`, and handed to
   `ExternalOpApi.submit()`. Read tools/resources project over `ContentReadModel`. No new op
   emission code, no new proposal model — the adapter is glue.
2. **Always propose — structurally, at the door.** The handler submits every envelope with
   `mode = "propose"`, *overriding* the bot's `capabilities.mode`. Even a `commit`-capable bot,
   arriving via `/mcp`, is downgraded to propose. Combined with the `Actor.Bot` "human beats
   bot" merge rule (external-op-api §1), an LLM can never silently overwrite a human decision —
   it can only surface a suggestion the human disposes of.
3. **Stateless.** No session id, no server-held connection state, no server→client stream. Each
   HTTP request is a self-contained JSON-RPC message authenticated by its own bearer token. This
   matches Ktor's request model, survives restarts and horizontal scaling for free, and keeps
   the MCP surface as auditable as `/api/ops`.

## 1. Transport — stateless Streamable HTTP (JSON-RPC 2.0)

MCP's current transport is **Streamable HTTP**. We implement its **stateless** profile:

- **One endpoint: `POST /mcp`.** Accepts a single JSON-RPC 2.0 request (or notification),
  returns a single JSON-RPC response as `application/json`. No batching required for v1.
- **No session.** The server never issues an `Mcp-Session-Id` header and never requires one on
  input. Any inbound `Mcp-Session-Id` is ignored. There is no per-client state to resume.
- **No server-initiated stream.** `GET /mcp` (which in the spec opens a server→client SSE
  channel for unsolicited messages) returns **405 Method Not Allowed** — a stateless server has
  no long-lived channel. Zync already has a bearer-authed change feed (`GET /api/changes`, SSE)
  for bots that want to react; MCP clients that poll can call read tools on a timer, and the
  design note in §6 covers wiring `/api/changes` as an optional MCP-adjacent signal.
- **`initialize` is answered per request, statelessly.** We return `protocolVersion`,
  `serverInfo`, and `capabilities: { tools: {}, resources: {} }` (no `listChanged` — the tool
  list is static). `notifications/initialized` is accepted and ignored. No handshake state is
  persisted; a client may call `initialize` and `tools/call` in separate connections.
- **Methods handled:** `initialize`, `ping`, `tools/list`, `tools/call`, `resources/list`,
  `resources/read`, `resources/templates/list`. Everything else → JSON-RPC error `-32601`.

Rationale for stateless over the session-ful profile: Zync's write door is already stateless
and bearer-authed (external-op-api §6); a session-ful MCP server would add resumability and
server-push state this design deliberately doesn't want. The transport-agnostic envelope means
we lose nothing — the same intents ride `/api/ops` and `/mcp`.

## 2. Auth — reuse the bot bearer token (no new identity)

`/mcp` authenticates **exactly like `/api/ops`**: `Authorization: Bearer <token>` resolved by
the existing `BotAuth` (`EnvBotAuth` + `SqlBotRegistry`) to a `BotIdentity` with its
`BotCapabilities`. No token ⇒ **401** (JSON-RPC transport error via HTTP status, before any
JSON-RPC parsing). `/mcp` joins `SESSION_EXEMPT`.

The bot's capabilities still scope the MCP surface, with one clamp:

- **`verbs`** filter which *write tools* are advertised in `tools/list` and callable — a bot
  without `create` never sees a `create_task` tool.
- **`fields`** filter `set_field` (rejected per-intent by `ExternalOpApi`, surfaced as a tool
  error).
- **`rateLimit`** applies unchanged — the same per-token, per-verb `VerbRateLimiter`.
- **`mode` is ignored for direction — always propose.** The grant's `mode` no longer decides
  commit-vs-propose on this door; it's pinned to propose. (A `commit`-capable bot keeps
  committing on `/api/ops`; only `/mcp` downgrades it.)

MCP's spec prefers OAuth 2.1 for interactive clients. Per the current decision we reuse bearer
tokens (consistent with the rest of the external-op-api, zero new machinery). OAuth is a clean
later addition (§10) that would sit in front of the same `BotIdentity` resolution.

## 3. Tool surface — read + propose

Tools are the MCP-native shape of the intents (external-op-api §3) plus read projections over
`ContentReadModel`. Names are snake_case (MCP convention); each carries a JSON-Schema
`inputSchema` derived from `OpIntent`/`NodeView`.

### 3.1 Read tools (gated by auth, not by write verbs)

| Tool | Args | Returns | Backing |
|------|------|---------|---------|
| `list_inbox` | — | inbox `NodeView`s (id, title, status…) | `ContentReadModel.inbox` |
| `list_children` | `parent` (ULID\|alias) | child `NodeView`s | `.children` |
| `get_node` | `id` | one node's projected fields + comments/attachments | `.project()` snapshot |
| `list_comments` | `id` | comment `NodeView`s | `.comments` |
| `search` | `q` | matching nodes (FTS/reference search) | reference/FTS search |
| `list_proposals` | — | pending proposals + suggestion nodes | `.proposals` / `.suggestions` |
| `list_reference` | `folder?` | reference tree | `.reference` / `.referenceChildren` |

These exist so an agent can **find the target ULID** before proposing an edit — the read-then-
write loop the external-op-api spec §6 anticipated with `/api/items` and `/api/search` (which
this design finally motivates). Read tools return compact JSON, capped in count, values only
(no op-log internals).

### 3.2 Write tools (auth + verb-scoped; always propose)

Each maps to one `OpIntent` and is submitted `mode:"propose"`. The **proposable verbs today**
(external-op-api §4 + current `ExternalOpApi` behavior):

| Tool | Intent | Propose behavior |
|------|--------|------------------|
| `create_task` / `create_project` | `create` | node minted with `proposed=true`; surfaces in proposals panel |
| `set_field` | `setField` | mints a **suggestion node** (`targetId`/`targetField`/`proposedValue`) |
| `complete` | `complete` | suggestion: `status → DONE` |
| `trash` | `trash` | suggestion: `status → DROPPED` |
| `comment` | `comment` | **commits** (additive, can't clobber human state) |
| `add_free_tag` / `remove_free_tag` | `addFreeTag`/`removeFreeTag` | **commits** (additive metadata) |

> **Not yet exposed as MCP tools:** `attach`, `addTag`, `move`. `ExternalOpApi` currently
> *rejects* these in propose mode ("not proposable yet"). Because `/mcp` is propose-only, they'd
> always error, so we don't advertise them until the propose path learns to stage them
> (external-op-api §13 / this spec §10). This is the one place the always-propose rule *shrinks*
> the surface versus `/api/ops`, and it's the honest boundary.

**The additive-op question (open, §10):** `comment`/`add_free_tag` commit even under propose
because they're mergeable and can't overwrite a human. That's the existing `ExternalOpApi`
contract. Whether the MCP door should *also* hold those back as proposals ("truly nothing lands
without confirmation") is a policy call for the user — §10 Q1.

### 3.3 `tools/call` result shape

The adapter builds a one-intent envelope, calls `submit()`, and maps the `IntentResult` to an
MCP tool result:

- `status:"proposed"` → `content:[{type:"text", text:"Proposed <verb>; awaiting confirmation. suggestion=<nodeId>"}]`, `isError:false`, plus a `structuredContent` block echoing `{status, nodeId}`.
- `status:"committed"` (additive) → text notes it committed, `isError:false`.
- `status:"error"` → `isError:true`, the `error` string surfaced so the LLM can correct (bad
  ULID, field not permitted, rate-limited). Capability/validation rejections are **tool errors**,
  not transport errors — the connection stays usable.

## 4. Resources — addressable reads (secondary)

MCP resources give clients a browsable, stable-URI view. v1 exposes a small set, all read-only:

- **Resource templates:** `zync://node/{id}` (one node projection), `zync://node/{id}/comments`.
- **Static resources (`resources/list`):** `zync://inbox`, `zync://proposals`,
  `zync://reference`.
- `resources/read` returns `application/json` text contents from the same `ContentReadModel`
  projections as the read tools.

Resources are optional for a client to use (the read *tools* cover the same ground for tool-only
clients) but make Zync feel native in resource-aware UIs. No subscriptions in v1 (stateless: no
`resources/subscribe`, no `notifications/resources/updated`).

## 5. Idempotency & atomicity

- MCP tool calls are single-intent, so envelope atomicity is trivial. The adapter still routes
  through `ExternalOpApi.submit()`, inheriting its all-or-nothing guarantee.
- **Idempotency key:** derived deterministically as `mcp:<botId>:<jsonrpc-request-id>` when the
  client supplies a JSON-RPC `id`, or accepted explicitly via an optional `idempotencyKey` tool
  argument. This reuses the `(botId, key)` `IdempotencyCache` in `ApiRoutes` — a retried
  `tools/call` with the same id returns the original result and emits nothing new. (Refactor: lift
  the idempotency lock + cache out of `apiRoutes` into a small shared `OpIngress` both `/api/ops`
  and `/mcp` call — §7.)

## 6. React side (deferred, noted)

Stateless MCP has no server push. Clients that must react to Zync changes use the existing
bearer-authed **`GET /api/changes`** SSE feed (unchanged) and re-query via read tools. Wiring a
proper MCP `notifications/resources/updated` stream would require the session-ful transport and
is explicitly out of scope; if it's ever wanted it's a separate spec, not a change to this one.

## 7. Code touch-points

- `core`: no changes — reuses `OpEnvelope`/`OpIntent`/`BotCapabilities`. (Suggestion-node
  vocabulary already exists.)
- `server`:
  - New `mcp/` package: `McpRoutes.kt` (`POST /mcp` + `GET /mcp` → 405), `McpJsonRpc.kt`
    (request/response/error DTOs), `McpDispatcher.kt` (method routing), `McpTools.kt`
    (tool registry + JSON-Schemas + intent translation), `McpResources.kt`.
  - `OpIngress` — extract the idempotency-lock + rate-limit + submit critical section from
    `apiRoutes` so `/mcp` and `/api/ops` share one path (and one idempotency cache). The MCP
    dispatcher forces `mode="propose"` when building the envelope.
  - Read tools call a `ContentReadModel` bound to the server's `StateStore` (already available
    via `ServerContent`).
  - Wire into `App.zyncModule` next to `apiRoutes(...)`; add `/mcp` to `SESSION_EXEMPT`; reuse
    `installHardening` (size caps, remote-IP backstop) and the per-verb `VerbRateLimiter`.
- `web`: none — proposals/suggestions already render in the proposals panel; MCP writes surface
  there identically to `/api/ops` proposals.
- `app`: none — the phone loopback can serve `/mcp` too (same op stack) if on-device MCP clients
  ever want it, but that's not required.
- `sdk`/docs: a short `docs/mcp.md` (or `sdk/README.md` section) with the endpoint URL, the
  tool list + schemas, and a curl `initialize`/`tools/list`/`tools/call` example, so any MCP
  client can connect without an SDK.

## 8. Hardening & audit

- `/mcp` is `SESSION_EXEMPT`, bearer-authed; unknown/revoked token → 401.
- Reuse `installHardening` request caps and the remote-IP limiter as a backstop; reuse the
  per-token, per-verb `VerbRateLimiter` for write tools (read tools get a `default`-bucket
  limit).
- **Audit** `(botId, method, tool, targetId, outcome)` without values — same posture as
  `/api/ops` (external-op-api §7). Every write is `Actor.Bot(botId)`, propose-flagged,
  attributable in the UI.
- The always-propose clamp is defense-in-depth atop the `Actor.Bot` merge rule: even if a future
  bug let a commit slip through, a bot write still can't overwrite a human register value.

## 9. Test plan

- **Transport:** `initialize` returns the advertised capabilities with no `Mcp-Session-Id`
  header; `GET /mcp` → 405; unknown method → `-32601`; malformed JSON-RPC → `-32700`/`-32600`;
  missing bearer → 401.
- **Tool discovery:** `tools/list` reflects the bot's `verbs` (a propose-only tool set); a bot
  lacking `create` doesn't see `create_task`.
- **Always-propose (the core guarantee):** a **commit-capable** bot calling `set_field` via
  `/mcp` produces a *suggestion node* (`proposed=true`), **not** a live `SetField` — asserted
  against the projection; the same bot on `/api/ops` still commits. `complete`/`trash` likewise
  propose.
- **Additive commit:** `comment` via `/mcp` lands a committed comment (documents the §10 Q1
  boundary).
- **Read tools:** `list_inbox`/`search`/`get_node` return the seeded projection; counts capped.
- **Idempotency:** same JSON-RPC id twice → one suggestion, identical tool result.
- **Errors as tool errors:** bad ULID / non-whitelisted field / rate-limit → `isError:true`,
  connection still usable (not a transport error).
- **Rate limit:** exceed a verb bucket → tool error, shared bucket with `/api/ops`.
- **E2E:** an MCP client (raw JSON-RPC over `testApplication`) discovers tools, proposes an edit,
  and the suggestion appears in `ContentReadModel.suggestions()` with `Actor.Bot` attribution;
  accepting it (existing human op) applies the real `SetField(Human)`.

## 10. Open questions

1. **Do additive ops (`comment`, `add_free_tag`) also hold for confirmation?** Today they commit
   even in propose mode (mergeable, can't clobber). Strict reading of "any change is a proposal"
   would stage them too. **Recommendation:** keep the existing `ExternalOpApi` semantics
   (additive → commit) for consistency across doors, and document it; revisit if users want a
   literally-nothing-lands mode. *Needs your call.*
2. **Expose `attach`/`addTag`/`move`?** They require teaching `ExternalOpApi`'s propose path to
   stage them (external-op-api §13). Do we block this spec on that, or ship read + the four
   proposable write verbs (+ additive comment/tag) first and add them when the propose path
   grows? **Recommendation:** ship without them; add later.
3. **OAuth 2.1?** Bearer for v1 (decided). Add MCP OAuth when an interactive desktop client
   without a pre-provisioned token needs to connect.
4. **Resources vs tools-only?** v1 ships both a small resource set and read tools. If resource
   maintenance isn't worth it, tools alone cover reads — droppable without touching writes.

## 11. Sequencing (proposed)

0. **`OpIngress` refactor** — lift the idempotency/rate-limit/submit critical section out of
   `apiRoutes` so `/mcp` and `/api/ops` share it (pure refactor, existing tests guard it).
1. **`POST /mcp` skeleton** — `initialize`/`ping`/`tools/list`, bearer auth, stateless, `GET`→405.
2. **Read tools** — `list_inbox`/`list_children`/`get_node`/`search`/`list_comments` over
   `ContentReadModel`.
3. **Write tools (always propose)** — `create_*`/`set_field`/`complete`/`trash` + additive
   `comment`/`*_free_tag`; the propose clamp; tool-error mapping. The core guarantee lands here.
4. **Resources** — `zync://node/{id}`, `zync://inbox`, `zync://proposals`, `zync://reference`.
5. **Docs** — `docs/mcp.md`: endpoint, tool schemas, curl walkthrough.

Each step is independently shippable and JVM-route-testable; nothing here touches `core`, the
merge rules, or the schema.

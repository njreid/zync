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
| `search` | `query`, `limit?` | matching nodes (hybrid keyword/semantic, see §12) | reference/FTS search |
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
| `move` | `move` | suggestion: `proposedParent` (organize) |
| `add_tag` | `addTag` | suggestion: `proposedContext` |
| `attach` | `attach` | suggestion: `proposedAttachment` (blob must be uploaded first via `PUT /api/blobs`) |

All eleven external-op-api write verbs are shipped as MCP tools (§10 Q2, resolved 2026-08-12) —
`ExternalOpApi`'s propose path was generalized to stage `move`/`addTag`/`attach` as suggestion
nodes the same way `set_field` does, so nothing about propose-only shrinks the MCP surface versus
`/api/ops` anymore.

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
- **Idempotency key:** `McpServer` dedupes on `(botId, jsonrpc-request-id)` via its own
  `McpIdempotencyCache` — same shape as `ApiRoutes`' `IdempotencyCache` (bounded LRU, best-effort,
  a restart forgets it) but a separate instance, since the key space differs (`/api/ops` keys on
  an explicit `idempotencyKey` field; `/mcp` keys on the JSON-RPC request id, which every
  well-behaved client already sends). A retried `tools/call` with the same id returns the original
  result and emits nothing new.
- **Rate limit:** unlike idempotency, the per-token/per-verb `VerbRateLimiter` (§8) IS one shared
  instance across both doors — `Main` constructs it once and passes it into both `apiRoutes(...)`
  and `McpServer(...)`, so a bot can't reset its budget by switching transports. The originally
  sketched single shared `OpIngress` (below) turned out to be more machinery than the actual
  sharing need: only the rate budget has to be cross-door-consistent; idempotency dedup is
  naturally per-door because the two keys mean different things.

## 6. React side (deferred, noted)

Stateless MCP has no server push. Clients that must react to Zync changes use the existing
bearer-authed **`GET /api/changes`** SSE feed (unchanged) and re-query via read tools. Wiring a
proper MCP `notifications/resources/updated` stream would require the session-ful transport and
is explicitly out of scope; if it's ever wanted it's a separate spec, not a change to this one.

## 7. Code touch-points

- `core`: no changes — reuses `OpEnvelope`/`OpIntent`/`BotCapabilities`. (Suggestion-node
  vocabulary already exists.)
- `server`: new `mcp/` package, as shipped:
  - `McpRoutes.kt` — `POST /mcp` + `GET /mcp` → 405, bearer auth, hands the parsed JSON-RPC
    object to `McpServer.handle`.
  - `McpJsonRpc.kt` — request/response/error DTOs and error codes.
  - `McpServer.kt` — method routing, tool dispatch, resource reads, and the always-propose clamp
    (folds in what this section originally sketched as a separate `McpDispatcher.kt`/
    `McpResources.kt` — one class turned out simpler than three for this surface area).
  - `McpTools.kt` — tool registry + JSON-Schemas + intent translation (`McpCatalog`).
  - `McpIdempotency.kt` — `McpIdempotencyCache`, the `/mcp`-side idempotency dedup (§5).
  - `VerbRateLimiter` (in `api/ApiRoutes.kt`, made non-private) is constructed once in `Main` and
    passed into both `apiRoutes(...)` and `McpServer(...)` — the actual cross-door sharing
    mechanism; see §5. There is no separate `OpIngress` type.
  - Read tools call a `ContentReadModel` bound to the server's `StateStore` (already available
    via `ServerContent`).
  - Wired into `App.zyncModule` next to `apiRoutes(...)`; `/mcp` joins `SESSION_EXEMPT`; reuses
    `installHardening` (size caps, remote-IP backstop) and the shared per-verb `VerbRateLimiter`.
  - `embed/` package (added alongside, §12): `OllamaEmbeddingClient`, `SemanticSearch` — the
    hybrid backend the MCP `search` tool and `/api/search` both call through.
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

## 10. Open questions — RESOLVED 2026-08-12

1. **Additive ops (`comment`, `add_free_tag`) hold for confirmation?** → **No — keep committing**
   (mergeable, can't clobber a human). Consistent across `/api/ops` and `/mcp`; documented in the
   tool descriptions. Revisit only if a literally-nothing-lands mode is wanted.
2. **Expose `attach`/`addTag`/`move`?** → **Yes, shipped.** `ExternalOpApi`'s propose path was
   generalized (suggestion nodes now carry `suggestionKind` + `proposedParent`/`proposedContext`/
   `proposedAttachment`); accepting emits the real `Move`/`AddTag`/`AddAttachment` as `Actor.Human`.
   All eleven write verbs are exposed as MCP tools; agents can organize (tag/move) reference items.
3. **OAuth 2.1?** → **Deferred.** Bearer for v1; OAuth sits in front of the same `BotIdentity`
   resolution when an interactive client without a pre-provisioned token needs to connect.
4. **Resources vs tools-only?** → **Both shipped** (`zync://inbox|proposals|reference` +
   `zync://node/{id}[/comments]`); read tools cover the same ground for tool-only clients.

## 11. Semantic search (embeddings)

Added alongside the read surface so `search` (the `/api/search` endpoint and the MCP `search`
tool) is **hybrid**: the existing keyword LIKE index first, then embedding-based semantic hits it
missed. Design decisions:

- **Getting embeddings** — an `EmbeddingClient` port mirrors the `LlmClient` pattern; the default
  adapter is **Ollama** (`ZYNC_EMBED_URL`, `nomic-embed-text`) — local-first, no third party sees
  your data. Unset ⇒ semantic search disabled and behavior is exactly keyword-only. Voyage/OpenAI/
  ONNX adapters drop in behind the same port (Anthropic has no embeddings API of its own).
- **Indexing** — SQLDelight's SQLite-3.18 dialect can't compile `sqlite-vec`/FTS5 virtual-table
  MATCH, and Android system SQLite won't reliably load native extensions (the same reason
  `search_doc` is a LIKE table). So vectors live in an **in-memory index**, brute-force cosine —
  a few ms at personal scale, no ANN structure, identical on server and phone. Each entry keeps a
  content hash so only changed nodes re-embed. The corpus is embedded **lazily** on search (no
  background worker, write path untouched); embedding is network I/O so search runs on
  `Dispatchers.IO`.
- **Deferred:** persisting vectors as a BLOB in the durable store (the spec's original upgrade
  path) and, server-side only, swapping brute-force for `sqlite-vec` over raw JDBC — both behind
  the unchanged `EmbeddingIndex`/`SemanticSearch` query API. Background (non-lazy) indexing if the
  first-search-after-edits latency ever bites.

## 12. Sequencing (as built)

0. ~~`OpIngress` refactor~~ — superseded (§5): only the rate limiter needed cross-door sharing,
   done by passing one `VerbRateLimiter` instance into both routers, not a shared submit path.
1. **`POST /mcp` skeleton** — `initialize`/`ping`/`tools/list`, bearer auth, stateless, `GET`→405.
2. **Read tools** — `list_inbox`/`list_children`/`get_node`/`search`/`list_comments` over
   `ContentReadModel`.
3. **Write tools (always propose)** — `create_*`/`set_field`/`complete`/`trash` + additive
   `comment`/`*_free_tag`; the propose clamp; tool-error mapping. The core guarantee lands here.
4. **Resources** — `zync://node/{id}`, `zync://inbox`, `zync://proposals`, `zync://reference`.
5. **Docs** — `docs/mcp.md`: endpoint, tool schemas, curl walkthrough.

Each step is independently shippable and JVM-route-testable; nothing here touches `core`, the
merge rules, or the schema.

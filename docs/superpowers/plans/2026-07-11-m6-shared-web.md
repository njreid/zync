# zync M6 — Shared Web Module (kotlinx.html + Datastar)

> **For agentic workers:** implement task-by-task; `- [ ]` steps. **Depends on M5**
> (`:core` op log, `:data` store, phone replica). Roadmap: `2026-07-08-rebuild-roadmap.md`.
> Semantics: `../specs/2026-07-08-oplog-merge-operator-model.md`. Status: 🟡 DRAFT.

**Goal:** one content UI, **shared by the server and the phone**, rendered server-side
from the **op-log projection**. A new **`:web`** module (Ktor routes + kotlinx.html
views + Datastar/SSE) is served by the central **`:server`** (desktop/browser) and by the
**phone's loopback Ktor** (offline, local SQLite). **Retires the vanilla-JS UI**
(`app/src/main/assets/web/`) and its Playwright suite (`webtest/`).

**Current state (survey 2026-07-11):** the vanilla-JS UI has views inbox/tree/detail/
contexts/pickers/settings hitting Room-backed JSON routes on the phone loopback
(`server/ApiRoutes.kt`: `/roots /inbox /nodes /children /contexts /complete /reopen
/defer /move /convert /trash /attachments …`). M6 reimplements these as server-rendered
hypermedia over the op log; Room + `ApiRoutes` retire here or in M7.

## Global constraints
- **`:web` is KMP jvm + android** (Ktor server runs on the JVM server AND on the phone
  loopback). kotlinx.html + Ktor SSE are multiplatform; the Datastar JS runtime ships as
  a static asset (no node build).
- **Reads** come from the op-log projection via a shared read model (over `:data`); the
  vanilla-JS UI's read model (`BridgeReadModel`, M5) generalizes here.
- **Writes** go through the op log via a `ContentCommands` port: phone → `OpWriter`;
  server → a server-side op writer (apply + persist, then it syncs to replicas).
- No node/npm needed. `./gradlew :web:allTests` green each commit; views assert rendered
  HTML; SSE asserts the event stream; both surfaces exercised (Ktor test host + Robolectric).

---

### Task 1: `:web` KMP module scaffold + Datastar runtime
**Files:** `web/build.gradle.kts`, `settings.gradle.kts`, `libs.versions.toml`
(kotlinx-html, ktor-server-sse), `web/src/commonMain`, Datastar JS asset.
- KMP jvm+android; deps: ktor-server-core, kotlinx-html, ktor SSE; vendor the Datastar
  runtime as a served static file.
- [x] **Step 1:** kotlinx.html shell + `/health` render via `respondHtml` (ktor
  html-builder), served by a Ktor test host (jvm); android target compiles. `:web:jvmTest`
  green. (Datastar JS runtime vendoring deferred to Task 4 — it needs the file content;
  Robolectric phone-serving is Task 8.)
- [x] **Step 2: Commit** `feat(web): KMP web module scaffold + kotlinx.html`.

### Task 2: Read model + command ports (domain views)
**Files:** `web/…/ContentReadModel.kt`, `ContentCommands.kt`, view-model types.
- `ContentReadModel` over the op-log projection: inbox, tree/children, node detail,
  contexts/tags, project decomposition. `ContentCommands` port for mutations (create,
  setField, complete/reopen, defer, move, convert task↔project, trash, tag) → ops.
- [ ] **Step 1 (TDD):** read model folds projection into view models; command port turns
  each intent into the right op(s); an in-memory impl (over `core` + InMemory store).
- [ ] **Step 2: Commit** `feat(web): content read model + command ports`.

### Task 3: Core views — layout, inbox, tree, detail (kotlinx.html)
**Files:** `web/…/views/*.kt`, routes.
- Server-rendered layout (Geist/Inter tokens), inbox list, project/tree view, node detail
  (title/notes/status/tags/attachments). Read from the read model.
- [ ] **Step 1 (TDD):** each view renders expected HTML from a seeded projection (assert
  structure/content); routes return them.
- [ ] **Step 2: Commit** `feat(web): kotlinx.html layout + inbox/tree/detail views`.

### Task 4: Datastar reactivity — live updates via SSE
**Files:** `web/…/sse/`, Datastar-bound views.
- An SSE stream that pushes updated fragments when the op-log state changes
  (tree/task/project/context/tag). Datastar `data-*` bindings drive the reactive DOM.
- [ ] **Step 1 (TDD):** a state change emits the expected SSE fragment event; the view
  carries the right Datastar attributes.
- [ ] **Step 2: Commit** `feat(web): Datastar SSE live updates`.

### Task 5: Mutations wired to commands
**Files:** action routes + Datastar actions in views.
- complete/reopen/defer/move/convert/trash/tag from the UI → `ContentCommands` → ops →
  live SSE refresh. Optimistic where sensible.
- [ ] **Step 1 (TDD):** each action posts → emits the right op(s) → projection + SSE
  reflect it (against the in-memory command impl).
- [ ] **Step 2: Commit** `feat(web): content mutations via op-log commands`.

### Task 6: Reading / commenting / planning / decomposing
**Files:** views + routes for read view, comments, project planning, decompose.
- Long-form reading view; comments (as child nodes/annotations); project planning +
  decompose-into-subtasks flows.
- [ ] **Step 1 (TDD):** each flow renders + mutates through the op log.
- [ ] **Step 2: Commit** `feat(web): reading, comments, planning, decompose`.

### Task 7: Serve from the central server
**Files:** `server/…` wires `:web` routes; server-side `ContentCommands` (apply + persist).
- The central server serves the shared UI to desktop/browser (behind M4 auth/session);
  its command impl writes ops via the same ingest path so replicas converge.
- [ ] **Step 1 (TDD):** Ktor test host — the server renders the UI + a mutation converges
  to the server store.
- [ ] **Step 2: Commit** `feat(server): serve the shared web UI`.

### Task 8: Serve from the phone loopback + retire vanilla-JS UI
**Files:** phone `ZyncServer`/loopback wires `:web`; delete `assets/web/` + `ApiRoutes`
(or thin it); retire `webtest/` Playwright.
- The phone loopback serves the same `:web` UI, backed by `OpWriter` + local store; remove
  the vanilla-JS UI and its Playwright suite (key flows rewritten as `:web` tests).
- [ ] **Step 1 (Robolectric):** the phone loopback renders the shared UI + a capture/
  mutation shows up via the op log.
- [ ] **Step 2: Commit** `feat(app): phone loopback serves shared web UI; retire vanilla-JS`.

### Task 9: Acceptance
- [ ] **Step 1 (acceptance):** the shared UI renders + mutates on **both** surfaces from
  the op log (server via Ktor test host, phone via Robolectric); parity of rendered views
  for the same projection.
- [ ] **Step 2: Commit** `test(web): shared UI parity on server + phone`.

## Interfaces / decisions
- **`:web` is the single UI**; server and phone differ only in the `ContentCommands`
  impl (server-ingest vs phone `OpWriter`) and auth wrapper.
- **Hypermedia over the op log:** views render from the projection; Datastar/SSE pushes
  fragments on change — no client-side state model.
- **Datastar JS** vendored as a static asset (no node build step).

## Open questions
- Datastar SSE event contract specifics (verify against data-star.dev when building Task 4).
- Comments/annotations as child nodes vs a dedicated op type (start with child nodes).
- How much of `ApiRoutes`/Room to delete now vs. M7 (thin clients) — likely thin here,
  finish in M7.

## Definition of done
One `:web` module renders the content UI from the op-log projection, reactive via
Datastar/SSE, with all mutations flowing through the op log; served by both the central
server and the phone loopback; the vanilla-JS UI + Playwright suite retired. Ready for M7
(native hybrid shell + thin clients).

# GTD mobile build — ORDERED implementation roadmap (2026-07-21)

> Status: ROADMAP (sequencing only; no feature design). Synthesizes the six design
> plans below into a single ordered build order for the remaining mobile-GTD work.
> Build-order #1 (`rank`/`FractionalIndex`) and #2 (FIFO inbox + fractional reorder)
> are DONE. Each increment here is **independently committable + pushable** with its
> own green tests (AGENTS.md §Version Control: "commit and push each increment… never
> commit a red tree"). Ordering minimizes conflicts on the hot shared `:web` files and
> the `data/` migration/version chokepoint.
>
> Source plans:
> - `2026-07-21-gtd-sequencing.md` (cross-cutting order — this roadmap follows it)
> - `2026-07-21-gtd-next-action.md` (#3)
> - `2026-07-21-gtd-gestures.md` (swipe/keyboard)
> - `2026-07-21-gtd-size-triage.md` (#4)
> - `2026-07-21-gtd-reference-fts5.md` (#5)
> - `2026-07-21-gtd-suggest-embeddings.md` (#6)

## Contention surface (the shared files that force ordering)

- **`web/.../content/ContentReadModel.kt`** — `NodeView` struct + `toView()` are
  rebase-forcing; per-surface query methods are function-disjoint.
- **`web/.../views/NodeViews.kt`** — `nodeRow()` + `inboxSection()` are rewritten by
  swipe AND #4 (must serialize); `nextSection`/`referenceSection` are disjoint.
- **`web/.../views/Layout.kt`** — the `Tab` enum + `tabBar` (4th tab = structural).
- **`data/src/commonMain/sqldelight/**`** — migration numbering + `Schema.version`;
  version-assertion sites (`SqlDelightStateStoreTest`, `MigrationTest`,
  `server DurabilityTest`) must bump together on any schema change.
- `WebRoutes.kt`, `ContentCommands.kt`, `custom.css`, `Fields.kt` — additive, lower risk.

## Legend
- **Lane** = `serial-web` (one ordered web worktree; contends on `NodeView`/`nodeRow`/
  `inboxSection`/`Layout`/`WebRoutes`), or `parallel` (own worktree, touches only
  `data/` and/or `server/operator/`, safe to run concurrently).

---

## The ordered increments

### Phase 0 — front-load every structural change (land FIRST, unblocks all)

**A. `feat(core+web): GTD field vocab + NodeView struct fields`**
- Files: `core/.../content/Fields.kt` (`SIZE`+`Size{S,M,L}`; `Status{ACTIVE,WAITING,DONE,DROPPED,FILED}`; `WellKnownNodes.REFERENCE_ROOT` ULID; `FILE_SUGGESTIONS`+`PROPOSED_FILE_PARENT`), `web/.../content/ContentReadModel.kt` (add `size`, `fileSuggestions`, `proposedFileParent` to `NodeView`+`toView`; `data class FileSuggestion(targetId, title, tree, score)`), `web/.../content/ContentCommands.kt` (`setSize`, `file()`).
- Lane: **serial-web** (it is the base every later web branch forks from — not parallel).
- Depends on: nothing (builds on #1/#2, already merged).
- Risk: **Highest-value de-risk in the plan.** Concentrates ALL three `NodeView`
  constructor changes into one early commit so downstream branches rebase once, not
  late+repeatedly. Pick a real 26-char Crockford-base32 `REFERENCE_ROOT` literal and
  add a `Ulid.parse` round-trip test. Adopt #6's final `FileSuggestion` shape here so
  #4's stub (increment E) and #6's real impl (increment H) don't collide on the type.
  Pure additive — trivially green.

### Track P — parallel worktrees (fork after A; touch data/ + server only)

**P1. `feat(data): FTS5 keyword search index (#5-data)`**
- Files: `data/.../sqldelight/.../SearchIndex.sq` (NEW), `.../migrations/5.sqm` (NEW → `Schema.version` **6**), `data/.../SqlDelightStateStore.kt` (reindex-on-write + `search()`), `core/.../state/StateStore.kt` (`+search` port), `core/.../state/InMemoryStateStore.kt` (naive scan), `core/.../content/FtsQuery.kt` (NEW), version-assertion bumps in `SqlDelightStateStoreTest`/`DurabilityTest` + new `MigrationTest` v5→v6 case.
- Lane: **parallel** (data/+core only; no hot web files).
- Depends on: A (so `Status.FILED`/`REFERENCE_ROOT` in `Fields.kt` don't collide).
- Risk: Android system-SQLite may lack FTS5 on old API levels — verify at min-SDK, fall
  back to a bundled SQLite driver if absent. Confirm SQLDelight 2.1 accepts the `fts5`
  vtable + `tokenize=` option (fallback: bare `fts5(...)`). Owns migration **5.sqm** —
  merge before P2.

**P2. `feat(server): suggest-file + auto-file-done operators + embeddings (#6-operator)`**
- Files: `data/.../NodeEmbedding.sq` + `migrations/6.sqm` (NEW → `Schema.version` **7**), `server/.../operator/{OperatorRuntime,ReadScope,OperatorManifests}.kt` (additive), NEW `CompletionSource.kt`/`FileLocationSuggester.kt`/`CosineIndex.kt`/`EmbeddingClient.kt`/`EmbeddingIndexer.kt`/`CompositeIngestHook.kt`, `server/.../Main.kt` (`wireOperators`), version-assertion bumps 6L→**7L** + `MigrationTest` v6→v7 case.
- Lane: **parallel** (server/operator + data/ only; emits operator-owned fields, no web UI).
- Depends on: A (operator target fields in `Fields.kt`); **P1 merged first** for
  migration numbering (takes `6.sqm`→v7) and to consume its `ftsSearch` (degrades to
  embedding-only if absent, so still shippable independently).
- Risk: **Stacked-migration conflict with P1** — reserve numbers up front (5=FTS→v6,
  6=embeddings→v7); merge P1 first, then P2 rebases the three assertion literals to 7L.
  No Anthropic embeddings API exists — pick Voyage (`ZYNC_VOYAGE_API_KEY`) or local ONNX
  MiniLM behind the `EmbeddingClient` port; store model+dims per vector for staleness.
  **Evaluate keeping embeddings in a server-only DB to avoid the shared migration
  entirely** (removes the P1/P2 chokepoint).

### Track S — serialized web worktree (after A; strictly ordered)

**S1. `feat(web): context-scoped Next Action list (#3)`**
- Files: `web/.../content/ContentReadModel.kt` (`NextRow`, `nextActions`, `nextOrder`, `completableNow`, `sizeOrder`; keep `activeTasks`), `web/.../views/NodeViews.kt` (rewrite `nextSection`), `web/.../WebRoutes.kt` (`/next` + `/updates/next` pass inbox+context), `web/.../resources/custom.css` (optional `.project` label).
- Lane: **serial-web**.
- Depends on: A (`NodeView.size` for the size-bump comparator; degrades to rank+dueDate).
- Risk: Low. Function-disjoint from swipe within `NodeViews.kt` (`nextSection` vs
  `nodeRow`) — ordered first anyway to keep every merge a fast-forward.

**S2. `feat(web): swipe + keyboard gesture layer`**
- Files: NEW `web/src/commonMain/resources/zync-gestures.js`, `web/.../views/NodeViews.kt` (`inboxSection` `swipe-row` wrapper + hidden `swipe-fire` triggers in `nodeRow`), `web/.../views/Layout.kt` (module `<script>` + tab `data-key`), `web/.../WebRoutes.kt` (`/assets/zync-gestures.js` static route — reuses `/complete`,`/trash`), `web/.../resources/custom.css` (swipe/cursor affordance), `server/.../DevServer.kt` (Playwright seed tasks).
- Lane: **serial-web**.
- Depends on: #2 (done — inbox rows are `reorderable`, visible buttons removed). Delivers
  the complete/trash promise #2 made.
- Risk: `nodeRow()` is edited by both S2 and S3 — **must precede S3, never parallel.**
  Tap-vs-swipe click-suppression is the tricky bit; CSP-safe (external ES module, no
  eval; CSS var set via CSSOM). No schema/read-model/`Fields` touch → zero conflict with
  Track P.

**S3. `feat(web): size field + inline inbox triage expand (#4)`**
- Files: `web/.../content/ContentCommands.kt` (`split`; `setSize` already in A), `web/.../content/ContentReadModel.kt` (`attachments()`, `fileSuggestions()` stub, `depthOf`/`subtreeHeight`/`moveWouldExceedDepth`), `web/.../views/NodeViews.kt` (`nodeRow` expand toggle + `triagePanel` + size badge), `web/.../WebRoutes.kt` (`/size`,`/rename`,`/split`,`/notes` + depth guard on `/move`,`/move-detail`), `web/.../resources/custom.css` (`.triage`,`.size-chips`,`.size-badge`), `server/.../DevServer.kt` (attachment seed).
- Lane: **serial-web**.
- Depends on: A (size vocab), S2 (shares `nodeRow`). Lands the 4-level nesting cap here
  (first build that can create a 5th level via `split`/move chips).
- Risk: `nodeRow`/`inboxSection` rewrite collides with S2 → serialize (factor
  `nodeRowCore`/`nodeRowInbox` if overlap is ever unavoidable). Make the `fileSuggestions()`
  stub return the **A-defined `FileSuggestion` shape** so S5 fills it with no type churn.
  Leave an explicit empty suggestion slot in `triagePanel` for S5. Depth cap is a
  route-entry validation (409), not a merge invariant (CRDT can transiently exceed 4).

**S4. `feat(web): Reference tab + search surface (#5-web)`**
- Files: `web/.../views/Layout.kt` (`Tab.REFERENCE` — 4th tab, **structural**), `web/.../content/ContentReadModel.kt` (FILED filters in `inbox()`/`activeTasks()`/`dueTasks()`/`projects()`; `reference()`, `search()`), `web/.../content/ContentCommands.kt` (`file()`/`unfile()` — `file()` may already be in A), `web/.../views/NodeViews.kt` (`referenceSection`+`referenceResults`; "File" button in `nodeDetail`), `web/.../WebRoutes.kt` (`reference` param; `/reference`,`/reference/search`,`/updates/reference`,`/node/{id}/file`), `web/.../resources/custom.css` (4-tab bar + search box), `server/.../{ServerContent,App}.kt` + `app/.../ZyncServer.kt` (ensure Reference root, thread `reference=`), `server/.../DevServer.kt` (seed FILED items).
- Lane: **serial-web**.
- Depends on: **P1 merged** (consumes `store.search()`), A (`REFERENCE_ROOT`, FILED, `file()`).
- Risk: `Tab.REFERENCE` is the one rebase-forcing web edit (consumed by every route's
  `page(...)`); keep it inside S4 and rebase S5 on it. `referenceSection` is disjoint
  from `nodeRow`/`inboxSection`, so no S2/S3 contention. Phone must also seed the
  Reference-root title so `/reference` isn't dead.

**S5. `feat(web): file-location suggestion chips + DONE→file banner (#6-web)`**
- Files: `web/.../content/ContentCommands.kt` (`acceptFileSuggestion`, `dismissFileSuggestions`, `acceptProposedFile`, `rejectProposedFile`), `web/.../views/NodeViews.kt` (suggestion chips in S3's expand slot + DONE `proposedFileParent` banner), `web/.../WebRoutes.kt` (`/node/{id}/file`,`/file-dismiss`,`/file-done`,`/file-done-reject`), `web/.../resources/custom.css` (`.suggest-chip`,`.file-banner`). (`NodeView` parse of the operator fields already landed in A.)
- Lane: **serial-web**.
- Depends on: **S3** (fills its expand slot) + **P2 merged** (operator writes the fields).
- Risk: Lowest structural risk (append-only into S3's slot; NodeView fields pre-landed in
  A). Accepting a chip is the human `Move` and inherits S3's depth guard via `/move`.
  Degrades cleanly when P2/embeddings unconfigured (fields simply absent → nothing renders).

---

## Merge cadence

```
A ─┬─────────────────────────────────────────────────────────  (land first)
   ├─ P1 #5-data (mig5→v6) ──► P2 #6-operator (mig6→v7)         (parallel, P1 before P2)
   └─ S1 #3 ─► S2 swipe ─► S3 #4 ─► S4 #5-web(needs P1) ─► S5 #6-web(needs S3+P2)
```

Land A → merge P1, then P2 whenever green → land S1…S5 in order, rebasing the S branch
on P merges only where S4/S5 read the new data/operator surfaces. Every arrow is a
green, pushed commit.

## Top merge-risk points

1. **Stacked data/ migrations + triple version bump (P1 vs P2).** Reserve 5=FTS→v6,
   6=embeddings→v7 up front; merge P1 first. Prefer a server-only embeddings DB to drop
   the second shared migration.
2. **`nodeRow()` rewritten twice (S2 vs S3).** Serialize; factor `nodeRowCore`/
   `nodeRowInbox` only if forced to overlap.
3. **`NodeView` struct (A).** All three field additions front-loaded into A so no branch
   takes a late constructor conflict.
4. **`Tab` enum 4th tab (S4).** Structural to nav; keep in S4, rebase S5 on it.
5. **`inboxSection` expand slot (S3 vs S5).** S3 ships the empty slot; S5 fills it —
   append-only, never interleaved.
6. **`FileSuggestion` type shape (#4 stub vs #6 real).** Resolve in A by adopting #6's
   `{targetId,title,tree,score}` shape everywhere.

# GTD triage — cross-cutting SEQUENCING plan (2026-07-21)

> Status: PLAN (sequencing only — no feature design). Scope: order the remaining
> build-order items (§9 of `2026-07-21-mobile-gtd-triage-ux.md`) so they land as
> independently-committable increments with minimal merge contention on the shared
> `:web` files. Build-order #1 (`rank`/`FractionalIndex`) and #2 (FIFO inbox +
> fractional-rank reorder) are DONE. This plan does **not** design the features; it
> maps each remaining item to the SHARED files it must edit and recommends the merge
> order. Companion design plans (per item) fill in signatures/algorithms.

## 0. The shared files (contention surface)

Anything two branches both edit is a conflict risk. The load-bearing shared files
and *why* they are hot:

| File | Role | Contention hotspot |
|------|------|--------------------|
| `web/.../content/ContentReadModel.kt` | single read surface | the **`NodeView` data class** + `toView()` (structural: every reader rebases) and the per-surface query methods |
| `web/.../views/NodeViews.kt` | all kotlinx.html fragments | **`nodeRow()`** and **`inboxSection()`** (rewritten by swipe + triage-expand); other sections are additive |
| `web/.../content/ContentCommands.kt` | mutation vocab | additive methods only (low collision) |
| `web/.../WebRoutes.kt` | routes + SSE + `applied`/`appliedDetail` helpers | additive routes (low) + the shared helper closures + `Tab` wiring |
| `web/.../views/Layout.kt` | page shell + **`Tab` enum** | Reference adds a 4th tab (structural to the tab bar) |
| `web/.../resources/custom.css` | served as a FILE | additive rules, but same file = textual conflicts |
| `core/.../content/Fields.kt` | field-name vocab | additive constants (trivial collisions) |
| `data/src/commonMain/sqldelight/**` | SQLite schema + migrations | **migration numbering + `Schema.version`** — a global chokepoint |

Version-assertion sites that MUST bump on ANY schema change (all assert `5L` today):
- `data/src/jvmTest/.../SqlDelightStateStoreTest.kt` (`schemaVersionBaselineForMigrationHarness`)
- `data/src/jvmTest/.../MigrationTest.kt` (`PRAGMA user_version` == `Schema.version`)
- `server/src/test/.../durability/DurabilityTest.kt` (`freshBootCreatesAtCurrentSchemaVersion`)

## 1. Item → shared-file matrix

Legend: ● heavy edit (rewrite/structural) · ◐ moderate (new method/section) · ○ additive/minor · — none.

| Shared file | #3 Next algo | gestures/swipe | #4 size+triage-expand | #5 Reference+FTS5 | #6 suggest+embeddings |
|-------------|:---:|:---:|:---:|:---:|:---:|
| `ContentReadModel.kt` (NodeView struct) | — | — | ● add `size` to NodeView+toView | — | — |
| `ContentReadModel.kt` (queries) | ● rework `activeTasks`→context Next | — | ◐ inbox expand reads | ◐ `reference()`, `search()` | ◐ `suggestions(node)` |
| `NodeViews.kt` `nodeRow()` | — | ● swipe `data-on:*` | ● expand toggle | — | — |
| `NodeViews.kt` `inboxSection()` | — | ○ | ● inline-expand panel | — | ○ (chips slot) |
| `NodeViews.kt` `nextSection()` | ● per-project grouping + context bar | — | — | — | — |
| `NodeViews.kt` (new section) | — | — | — | ◐ `referenceSection`+search box | ◐ suggestion chips in expand |
| `ContentCommands.kt` | — | — | ◐ `setSize`, `split` | ◐ `file()` (status=FILED+move) | ○ accept-chip = `move` |
| `WebRoutes.kt` | ◐ `/next` ctx + `/updates/next` | ○ (reuse `/complete`,`/trash`) or gesture asset route | ◐ `/node/{id}/size`, expand | ◐ `/reference`,`/updates/reference`,`/search` | ○ chip-accept route |
| `Layout.kt` `Tab` enum | — | — | — | ● add `Tab.REFERENCE` (4th tab) | — |
| `custom.css` | ○ | ◐ swipe affordance | ◐ expand panel + size chips | ◐ search box + 4-tab bar | ○ chip styling |
| `Fields.kt` | — | — | ◐ `SIZE`+`Size` values, `status` WAITING/FILED | ○ `REFERENCE_ROOT` id, FILED | ○ operator target field |
| `data/**` schema | — | — | — | ● FTS5 vtable + store write path + migration | ● embeddings table + migration |
| `server/operator/**` | — | — | — | — | ● manifest+scope+prompt+embeddings (isolated) |

Key reads:
- The **only structural (rebase-forcing) edits** are: `NodeView` gains `size` (#4);
  the `Tab` enum gains Reference (#5); and two stacked `data/` migrations (#5, #6).
- `nodeRow()` is rewritten by BOTH swipe and #4 → they MUST serialize (or be one
  workstream). Everything else in `NodeViews.kt` is function-disjoint.
- `#6` splits cleanly into a **disjoint server/data half** and a **web-UI half** that
  reuses #4's expand panel + the existing `proposed`/accept-reject machinery.

## 2. Disjoint vs serialized

**Disjoint modules — safe in parallel worktrees (touch `data/` and/or
`server/operator/`, not the hot web files):**

- **#5-data** — the FTS5 virtual table + the `SqlDelightStateStore` write path that
  keeps it in sync on register writes + migration + the 3 version-assertion bumps.
  Touches `data/` only. No `NodeViews.kt`/`ContentReadModel.kt` query wiring yet
  (expose a raw `search(query): List<Ulid>` at the store boundary; the web read
  method lands in #5-web).
- **#6-operator** — the `suggest-file` operator manifest, read scope, prompt, and the
  embeddings table + brute-force cosine (spec Q7). Touches `server/operator/` and
  `data/` (embeddings table). No web-UI edits (it only emits `proposed` nodes, which
  the existing proposals panel already renders).

**Serialized on the hot web files (one worktree, ordered) — contend on
`NodeView`/`nodeRow`/`inboxSection`:**

- #3 Next (rewrites `nextSection` + read-model Next query)
- gestures/swipe (rewrites `nodeRow`)
- #4 size+triage-expand (rewrites `nodeRow`+`inboxSection`, mutates `NodeView`)
- #5-web (new `referenceSection` + `Tab.REFERENCE` + `/reference` routes)
- #6-web (suggestion chips inside #4's expand panel)

Note: **#3 and swipe are function-disjoint** within the same two files
(`nextSection` vs `nodeRow`; different read-model methods) — they *could* run in
parallel with trivial import-level conflicts, but serializing them keeps every
`NodeViews.kt` merge a clean fast-forward. Recommended: serialize.

## 3. Recommended ordering (independently-committable increments)

### Phase 0 — vocab + structural pre-commit (tiny, land FIRST, unblocks everything)
- **Increment A `feat(core+web): size vocab + NodeView.size`.** Additive, no behavior
  change: `Fields.SIZE` + a `Size {S,M,L}` object; extend `status` constants
  (WAITING/FILED) and add a `REFERENCE_ROOT` well-known id constant; add `size: String?`
  to `NodeView` + `toView()`; add `ContentCommands.setSize` + `file()`. This front-loads
  the one **NodeView structural change** and all `Fields.kt` additions into a single
  commit so downstream branches rebase once, early, instead of colliding late.
  Tests: JVM read-model unit (`toView` maps `size`), core commonTest (Size values).

### Track P — parallel worktrees (start immediately after Phase 0)
- **P1 = #5-data** (branch `gtd/reference-fts`): migration **5.sqm** → `Schema.version`
  6; FTS5 vtable indexing title/notes/OCR-text/summary; store write-path sync;
  version bumps in the 3 assertion sites; new `MigrationTest` v5→v6 case.
- **P2 = #6-operator** (branch `gtd/suggest-operator`): migration **6.sqm** →
  `Schema.version` 7 (embeddings table); operator manifest/scope/prompt; cosine scan.
  **Reserve migration numbers up front** (5 = FTS, 6 = embeddings) so P1 and P2 never
  both claim `5.sqm` or the same `Schema.version` literal.

### Track S — serialized web worktree (after Phase 0)
1. **S1 = #3 Next Action** — rework `ContentReadModel` Next (top loose action +
   first completable per project, context-scoped, rank+dueDate/size key per Q3) and
   `nextSection` (per-project rows) + `/next?context=` + `/updates/next` filter.
2. **S2 = gestures/swipe** — `nodeRow` swipe `data-on:*` mapped onto existing
   `/complete` + `/trash`; css affordance; vendored gesture-helper asset FILE +
   `/assets/...` route only if `data-on:swipe` is not built into Datastar.
3. **S3 = #4 triage expand** — builds on S2's `nodeRow` + Phase-0 `size`: inline
   expand panel (rename/size/split/attach), `/node/{id}/size`, css. Leaves a labelled
   slot for #6 chips.
4. **S4 = #5-web** (after P1 merged) — `referenceSection` + search box, `/reference`,
   `/updates/reference`, `/search`, `Tab.REFERENCE`, `file()` wiring, css.
5. **S5 = #6-web** (after S3 + P2 merged) — render up-to-3 score-ranked file
   suggestions as one-tap chips in S3's expand slot; accept = the human `Move`.

Merge cadence: land Phase 0 → merge P1, P2 whenever green (they don't touch web) →
land S1…S5 in order, rebasing the S branch on P merges only where S4/S5 read the new
`data/` + operator surfaces.

## 4. Highest-risk merge points (flagged)

1. **Stacked `data/` migrations + triple version bump (P1 vs P2).** Both add a
   migration and both edit the SAME three assertion lines (`5L`→…). In parallel
   worktrees this is a guaranteed conflict on migration numbering and on
   `Schema.version`. MITIGATION: reserve numbers before starting (5=FTS→v6,
   6=embeddings→v7); merge P1 before P2; P2 rebases the assertions to `7L`. If P2
   can keep embeddings in a **server-only** DB rather than the shared `data/` schema,
   it avoids the second migration entirely — evaluate that first.
2. **`nodeRow()` rewritten twice (S2 vs S3).** Swipe handlers and the expand toggle
   both live in the same function. Serialize S2→S3 (already in the plan); do NOT
   parallelize. If they must overlap, factor `nodeRow` into
   `nodeRowCore` + `nodeRowInbox` in S2 so S3 edits only the inbox variant.
3. **`NodeView` structural change (Phase 0).** Adding `size` shifts the data-class
   constructor every branch calls via `toView()`. Landing it in Phase 0 (before any
   Track-S/P branch forks) is the mitigation; if it slips, every open branch takes a
   constructor conflict.
4. **`Tab` enum → 4th tab (S4).** `Layout.kt`'s `Tab` enum and the `tabBar` list are
   consumed by `page(...)` on every route. Adding `Tab.REFERENCE` is structural to the
   nav; keep it inside S4 and rebase later S-increments on it. (Also: the bottom tab
   bar CSS assumes `flex: 1 1 0` — a 4th tab is layout-only, no code risk.)
5. **`inboxSection()` (S3 vs S5).** #4's expand panel and #6's suggestion chips both
   render inside the expanded inbox item. Land the expand container (S3) first with an
   explicit empty slot; S5 fills the slot — keeps them append-only, not interleaved.
6. **Shared `applied`/`appliedDetail` closures in `WebRoutes.kt`.** Every new POST
   route calls these helpers; concurrent branches adding routes conflict only on
   import lines and the route block's braces — low risk, but land route additions in
   the serialized S track rather than in the parallel P track.

## 5. Summary of the sequence

```
Phase 0:  A (size vocab + NodeView.size + Fields)              ── land first
Track P:  P1 #5-data (mig5→v6)  ‖  P2 #6-operator (mig6→v7)    ── parallel worktrees
Track S:  S1 #3 Next → S2 swipe → S3 #4 expand
          → S4 #5-web (needs P1) → S5 #6-web (needs S3+P2)     ── serialized
```

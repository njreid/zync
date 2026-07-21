# GTD triage UX — Inbox / Next / Projects / Reference — spec (2026-07-21)

> Status: SPEC (planning only). Defines the redesigned task/project UX for the shared
> `:web` UI (server + phone loopback + desktop), aligned to the op-log/KMP target in
> `2026-07-08-kotlin-kmp-target-architecture.md`. Supersedes the ad-hoc inbox/tree
> surfaces; extends the launcher context model (`2026-07-15-launcher.md`) and the
> capture/OCR pipeline (`2026-07-16-scan-ocr-summary.md`). Everything is Datastar over
> SSE — no native triage screen — so the same UI triages on phone and desktop.

## Shape

```
CAPTURE ──► INBOX (FIFO queue) ──triage──► TASK (actionable, sized)
                                    ├─────► WAITING (person now; external event later)
                                    ├─────► PROJECTS (filed into the 4-level tree)
                                    └─────► REFERENCE (filed / archived, LLM-maintained)

NEXT ACTION (per context) = top loose action + first completable action per project
REFERENCE search = SQLite FTS5 keyword (everywhere) + embeddings (server-only)
```

Four fixed surfaces, each a tab: **Inbox**, **Next**, **Projects**, **Reference**.

## 1. Data model (core vocabulary — additive to `Fields.kt`)

All new fields are LWW registers written with `Op.SetField`; none need a new op type.

- **`rank`** — fractional-index string giving each node a total order *among its
  siblings* (§3). Drives FIFO inbox, project reorder, and loose-task reorder.
- **`size`** — `S | M | L` (S <2m, M <30m, L <~2h). No `XL`: bigger ⇒ must become a
  project (a node with children). Breakpoints are advisory and tunable.
- **`status`** — extend the existing set to
  `ACTIVE | WAITING | DONE | DROPPED | FILED`. `WAITING` means blocked on someone
  (today: the `person` field) or something (future: an external-event trigger, §7).
  `FILED` = moved into Reference (archived; still searchable, out of active lists).
- **Level / role — derived, not stored** (§2). A node's *role* (Task vs Project) is
  "has live children?"; its *tier label* (Pillar/Initiative/Project/Task) is derived
  from tree depth. Nothing to keep in sync.
- **Reference root** — a well-known node id (like the inbox root), the parent of the
  Reference tree. Filing sets `status=FILED` and `Move`s under this subtree.

Reused as-is: `parent` (tree), `tags` (contexts), `deferUntil`, `dueDate`, `person`,
attachments (`AddAttachment` + `ocrStatus`/`ocrBlobHash`/`summary` from the scan spec).

## 2. Levels: derive from depth, don't store a tier field

The question was enforcement. Two options:

**A. Derive from structure (RECOMMENDED).** Role = "has children ⇒ Project, else Task".
Tier label = depth within its hierarchy. The only hard rule is a **max nesting depth
of 4**, enforced at `Move` time (reject a move that would create a 5th level).

- *Pros:* one source of truth (the tree itself); **auto-promotion is free** — add a
  child to a Task and it *is* a Project with no extra op; moving a subtree relabels
  automatically; impossible to reach an inconsistent tier/depth state.
- *Cons:* tier is positional, not intrinsic — you can't have a standalone empty
  "Initiative" at the root; the 4-level cap is a move-time validation, not a type.

**B. Explicit `level` field per node.** Store `PILLAR|INITIATIVE|PROJECT|TASK`.

- *Pros:* intrinsic, stable across moves; can validate `child.level == parent.level-1`;
  empty containers allowed.
- *Cons:* two sources of truth (depth vs level) that drift; every move must
  recompute/validate; auto-promotion becomes an explicit op the user or an operator
  must fire. More machinery, more failure modes.

**Recommendation: A.** Keep `kind` only for the coarse node type
(`task | context | comment | attachment`) — *not* for the pillar→task ladder. See the
open question on how loose root tasks are labeled.

## 3. Ordering primitive (the linchpin)

FIFO, manual reorder, and "the next action" all need a stable sibling order that
survives concurrent edits on the op-log.

- **Representation:** `rank` is a fractional-index string. To insert between neighbors
  `a` and `b`, mint a key that sorts lexicographically in `(a, b)`; prepend/append at
  the ends. Written as an ordinary LWW `SetField` — merges with zero new merge code.
- **FIFO inbox = free:** unranked inbox items sort by **ULID ascending** (ULIDs are
  time-ordered), so capture order *is* FIFO with no writes. `rank` only appears once a
  human reorders.
- **Concurrent reorder:** two devices re-ranking the *same* node → LWW picks one rank
  (fine). Two devices inserting *different* nodes at the same slot → keys may tie;
  break ties by node ULID for a deterministic, convergent order.
- **Rebalance:** keys can grow on repeated same-slot inserts; renormalize a sibling
  list (rewrite ranks evenly) when any key exceeds a length threshold. Rare; a local,
  emit-N-SetField operation.
- **Why not a positioned `Move` op:** `Op.Move` carries only `newParentId`. Adding an
  index would need a new op + bespoke merge for concurrent index shifts. A `rank`
  register reuses everything we have.

## 4. Inbox (triage)

- FIFO list (§3). The triage surface — no create field (creation is capture).
- **Per-item gestures / keys** (Datastar, same handlers on touch + desktop):
  - swipe-left / `del` → **delete** (`DROPPED`); swipe-right / `space` → **complete**
    (`DONE`); `j`/`k` move the cursor; `Enter`/tap → **expand** the item inline.
  - Expanded: rename, set size, split (add subtask ⇒ becomes a project), add
    link/description, add/preview attachments, and **three suggested file locations**
    (§6) as one-tap chips (2 project targets + 1 reference area, say).
- All are existing commands (`complete`/`trash`/`move`/`addSubtask`/`setPerson`/…)
  plus `setField(rank|size)`; the inbox fragment re-renders over SSE as today.
- CSP note: keyboard/swipe handling is Datastar `data-on:*` expressions (the loopback
  CSP already carves out `script-src 'unsafe-eval'` for exactly this). No inline
  `<style>`/`<script>`; a small vendored gesture helper if `data-on:swipe` isn't
  built in — served as an asset FILE like `datastar.js`.

## 5. Next Action (per context)

For the selected context C, `Next` lists, in order:

1. The **top loose action** — highest-`rank` (or oldest) `ACTIVE` task at the root
   that is completable in C (tagged C, or C = "any").
2. For **each project** with at least one `ACTIVE` action completable in C: that
   project's **first** such action by `rank` (its Next Action) — one row per project.

Excludes `WAITING`/`DONE`/`DROPPED`/`FILED` and defer-hidden items. "Completable in
context" = the action carries context C's tag (§ launcher L4). This replaces the
current flat "all active tasks" list, which is wrong per the model.

## 6. Suggestions, OCR/ASR, summaries — reuse the operator/agent pipeline

- **File-location suggestions** (3 per inbox item) come from an **operator** over the
  Reference/Projects trees: keyword (FTS5) + server-side embedding similarity, emitted
  as **agent proposals** (the existing `proposed` flag + accept/reject UX). Accepting a
  chip is the human `Move`.
- **Attachments:** media land instantly as blobs; **OCR (phone/Drive) and ASR** feed
  text; the **summarize operator (server)** writes `summary` — all unchanged from
  `2026-07-16-scan-ocr-summary.md`. Extracted text also feeds FTS5/embeddings so
  scanned/spoken content is searchable.

## 7. Reference + search

- Separate tree under the Reference root; holds filed items and completed tasks;
  **mostly LLM-maintained** (an operator proposes filing/reorganization; humans
  confirm). Organized by area/domain.
- **Keyword search: SQLite FTS5** in `data/` — available on both server and phone
  (the phone replica has the same SQLite). Indexes title, notes, OCR/ASR text, summary.
- **Semantic search: server-only** (embeddings table alongside FTS5). The phone falls
  back to FTS5 keyword when offline; semantic results arrive when the server is
  reachable. Keeps model weights/compute off the phone.
- `WAITING`-on-external (event triggers) is deferred; `person` covers waiting for now.

## 8. Surfaces & routing (`:web`)

- Tabs: `/` (Inbox), `/next` (Next Action, context-scoped), `/projects` (4-level tree,
  reorderable), `/reference` (tree + search box). Bottom tab bar on mobile; the same
  markup works as a sidebar/top nav on desktop widths via CSS.
- Each surface keeps its own SSE stream (`/updates`, `/updates/next`, …) as today.
- Desktop parity: global keys (`g i/n/p/r` to switch tabs; `/` focuses search) layered
  on the same Datastar handlers as the touch gestures.

## 9. Build order (proposed)

1. **`rank` primitive** + fractional-index helper in `core` (+ merge/rebalance tests).
2. FIFO inbox (ULID order) and project/loose **reorder** UI (drag / `j`+`k`+move).
3. **Next Action** algorithm (§5) — replaces the current flat list.
4. `size` field + triage expand (rename/split/size/attach) + swipe/key handlers.
5. **Reference** tree + FTS5 keyword search (both surfaces).
6. Suggestion operator (§6) + server-side embeddings.

Each step is shippable and independently testable (JVM read-model tests + Playwright).

## Open questions — RESOLVED 2026-07-21

1. **Tier labels for loose root tasks.** → **Plain Tasks.** Loose root items are plain
   Tasks; the Pillar→Initiative→Project→Task ladder only labels nodes *inside* an
   explicit hierarchy. The root is not a tacit Pillar.
2. **Reorder gesture on touch.** → **Both, phased.** Ship explicit "move up/down / send
   to top" buttons for v1 (trivial under CSP, identical touch + desktop); add
   drag-to-reorder later on top of the same `rank` primitive.
3. **Next Action ordering key.** → **Rank + dueDate/size.** Manual `rank` is the base
   order, but `dueDate` and `size` may bump an action ahead when picking a project's
   Next Action (not pure `rank`).
4. **Suggestion count & mix.** → **Up to 3, any mix.** Score-ranked across Projects +
   Reference (no fixed 2+1 split), with a confidence floor below which fewer/none show;
   count varies 0–3.
5. **Completed tasks in Reference.** → **Auto-file (proposed).** On `DONE`, an operator
   *proposes* filing the task into Reference (accept/reject UX); projects empty out fast.
6. **Context definition for Next.** → **Manual pick (v1).** Always a manual launcher-L4
   context pick for v1; the model still supports inferred context (time/location/device)
   later.
7. **Embedding model & storage.** → **SQLite blob + brute-force cosine.** Store vectors
   in SQLite and cosine-scan at query time (fine at personal scale); no dedicated vector
   index for v1. (Embedding model TBD.)
8. **4-level cap enforcement UX.** → **Reject (v1).** A `Move` that would exceed depth 4
   is rejected at move-time; no auto-promote/merge offer for v1.

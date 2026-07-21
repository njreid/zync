# Build order #3 — Next Action algorithm (GTD triage spec §5) — PLAN

> Status: PLAN (design only; no feature code, no gradle). Implements spec §5 of
> `docs/superpowers/specs/2026-07-21-mobile-gtd-triage-ux.md` (open questions RESOLVED
> 2026-07-21 — honored below, esp. Q3 rank+dueDate/size, Q6 manual context pick).
> Builds on #1 (`FractionalIndex` + `Fields.RANK`) and #2 (FIFO inbox + reorder).

## Goal

Replace the current flat `nextSection` (backed by `ContentReadModel.activeTasks`) with a
context-scoped Next Action list:

1. The **top loose action** — the single highest-priority `ACTIVE` root task (parent ==
   null) completable in the selected context C.
2. Then **one row per project** — each project's *first* completable-in-C `ACTIVE` action.

Excludes `WAITING`/`DONE`/`DROPPED`/`FILED`, defer-hidden items, and still-in-inbox items.
Within a project, ordering is manual `rank` as the base, but `dueDate` and `size` may bump
an action ahead (Q3). `size` (build #4) may be absent — the design **degrades to rank+dueDate**
when no sizes are set.

---

## 1. Core vocabulary — `core/.../content/Fields.kt` (SHARED)

Add the `size` field constant and value vocab now (forward-compatible; build #4 adds the
`setSize` command + triage UI). Reading an absent `size` yields `null`, which the Next
comparator treats as neutral — so adding the constant is inert until sizes are written.

```kotlin
/** Advisory effort size (GTD triage §1): S <2m · M <30m · L <~2h. Absent = unsized. */
const val SIZE = "size"
```

```kotlin
/** Effort-size values for [Fields.SIZE] (spec §1; no XL — bigger ⇒ becomes a project). */
object Size { const val S = "S"; const val M = "M"; const val L = "L" }
```

No new op type, no merge code — `size` is an ordinary LWW `Op.SetField`.

**Conflict note:** build #4 also edits `Fields.kt` (and adds `ContentCommands.setSize`). This
plan only *adds* `SIZE`/`Size`; sequence #3 before #4 so #4 reuses the constant. `ContentCommands.kt`
is **not** touched by #3 (avoids overlap with #4).

## 2. Read model — `web/.../content/ContentReadModel.kt` (SHARED)

### 2a. `NodeView.size` (new nullable field)

Add to `NodeView`:

```kotlin
/** Advisory effort size S|M|L (GTD §1); null = unsized (build #4 writes it). */
val size: String? = null,
```

and read it in `toView()`:

```kotlin
size = fields[Fields.SIZE].asString(),
```

(Trailing default keeps all existing `NodeView(...)` call sites compiling.)

### 2b. Result row type

```kotlin
/**
 * One Next-surface row (spec §5): an [action] plus the [project] it belongs to.
 * [project] == null ⇒ the top loose root action (§5.1); non-null ⇒ that project's
 * first completable action (§5.2, one row per project).
 */
data class NextRow(val action: NodeView, val project: NodeView?)
```

### 2c. The algorithm

```kotlin
/**
 * Next Action list for context [context] (null = "any"), spec §5. Returns the top
 * loose root action (project == null) followed by each project's first completable
 * action (one [NextRow] per project). [inbox] is the well-known inbox id, excluded so
 * untriaged items don't leak in. Excludes WAITING/DONE/DROPPED/FILED, defer-hidden,
 * and person-delegated (today's WAITING bridge). Ordering: [nextOrder] — rank base,
 * dueDate/size bump (Q3); degrades to rank+dueDate when sizes are absent.
 */
fun nextActions(context: Ulid?, inbox: Ulid? = null, now: Long = Long.MAX_VALUE): List<NextRow> {
    val order = nextOrder()
    val byId = snapshots().associate { it.entityId.toString() to it.toView() } // project lookup

    val candidates = snapshots()
        .filter { it.kind() == "task" && !it.proposed() }
        .map { it.toView() }
        .filter { completableNow(it, context, now) }
        .filter { it.parent?.toString() != inbox?.toString() } // drop still-in-inbox items

    val loose = candidates
        .filter { it.parent == null }
        .minWithOrNull(order)
        ?.let { NextRow(it, null) }

    val perProject = candidates
        .filter { it.parent != null }
        .groupBy { it.parent!!.toString() }
        .mapNotNull { (pid, actions) ->
            val first = actions.minWithOrNull(order) ?: return@mapNotNull null
            NextRow(first, byId[pid])
        }
        // Stable project-row order: the project's own manual rank, ULID tiebreak.
        .sortedWith(compareBy({ it.project?.effectiveRank() ?: "" }, { it.project?.id?.toString() ?: "" }))

    return listOfNotNull(loose) + perProject
}

/** Completable in [context] now: ACTIVE (not WAITING/DONE/DROPPED/FILED), not deferred, not delegated, tag-matched. */
private fun completableNow(n: NodeView, context: Ulid?, now: Long): Boolean =
    n.status != "WAITING" && n.status != "DONE" && n.status != "DROPPED" && n.status != "FILED" &&
        n.person == null &&                                   // person set == waiting-on-someone (§1 WAITING bridge)
        (n.deferUntil == null || n.deferUntil <= now) &&
        (context == null || n.tags.any { it.toString() == context.toString() })

/**
 * Priority comparator for picking a Next action (Q3 = rank + dueDate/size). Ascending =
 * higher priority. dueDate bumps ahead (soonest first, undated last); then smaller size
 * (S<M<L, absent = neutral M); then the manual `rank` base order; then ULID for a
 * deterministic, convergent tiebreak. All-absent sizes ⇒ the size term is constant ⇒
 * pure rank+dueDate (graceful degradation before build #4).
 */
private fun nextOrder(): Comparator<NodeView> = compareBy(
    { it.dueDate ?: Long.MAX_VALUE },
    { sizeOrder(it.size) },
    { it.effectiveRank() },
    { it.id.toString() },
)

private fun sizeOrder(size: String?): Int = when (size) {
    Size.S -> 0; Size.L -> 2; else -> 1 // M and absent are the neutral middle
}
```

Notes:
- `effectiveRank()` (private, already exists: `rank ?: id.lowercase()`) is reused as the base
  order — unranked actions fall back to ULID, matching #2's FIFO convention.
- **"project" = the action's immediate parent node** (per §2 "has a live child ⇒ Project"),
  resolved via `byId`. A deeper action rolls up to its direct container. If the parent snapshot
  is missing/dead, `byId[pid]` is null and the row still renders (label degrades to none) — but
  in practice the parent is alive since it has a live child.
- **dueDate policy (v1):** any due date bumps ahead, soonest first. A `now`-relative "due-soon
  horizon" (bump only if `dueDate <= now + window`) is a later tunable refinement; not needed
  for #3. `now` is still a param for defer filtering.
- `activeTasks` / `waitingTasks` / `dueTasks` are **kept** (Today/Waiting tiles still use them);
  only `nextSection` stops calling `activeTasks`.

## 3. View — `web/.../views/NodeViews.kt` (SHARED)

Rewrite `nextSection` to take `inbox` + `context` and render `NextRow`s:

```kotlin
/**
 * The Next Actions surface (spec §5, context-scoped): the top loose root action, then
 * each project's first completable action (one row/project). Context is a manual pick
 * (Q6) via the same ?context= cookie the inbox uses; null = "any".
 */
fun FlowContent.nextSection(read: ContentReadModel, inbox: Ulid?, now: Long, context: Ulid? = null) {
    h2 { +"Next" }
    context?.let { contextBar(read, it) }        // manual context pill (only when one is picked)
    val rows = read.nextActions(context, inbox, now)
    if (rows.isEmpty()) {
        p("muted") { +"No next actions. Clarify the inbox to add some." }
    } else {
        ul {
            rows.forEach { row ->
                li {
                    row.project?.let { proj ->
                        a(href = "/node/${proj.id}") { span("project") { +(proj.title ?: "(project)") } }
                        +" · "
                    }
                    nodeRow(row.action)          // link + @person + ·STATUS + complete/trash buttons
                }
            }
        }
    }
}
```

- Loose action rows have no project prefix; project rows show a linked project label + `·` +
  the action (`nodeRow`, non-reorderable, so it keeps the ✓/🗑 buttons).
- Optional cosmetic: a `.project` selector in `custom.css` (SHARED, served as a file) to dim/space
  the project label. Purely visual; skip if not wanted. No inline styles (CSP).

## 4. Routes — `web/.../WebRoutes.kt` (SHARED)

`selectedContext()` (already private in this file) is reused. Update both `/next` sites to pass
`inbox()` + the selected context (mirrors the inbox surface):

- `get("/next")`: also honor `?context=` cookie-persist like `/` does, then
  `nextSection(read, inbox(), now(), call.selectedContext())`.
- `sse("/updates/next")`: read `val context = call.selectedContext()` at stream open (cookie pins
  the filter; a context switch is a navigation that reopens the stream — same pattern as
  `/updates`), then `nextSection(read, inbox(), now(), context)`.

## 5. Migration / version bumps

**None.** `size` and all other reads are LWW `Op.SetField` values over the existing schema. No
SQLDelight migration, no `data` or `server` durability version-assertion bump. (`data/.../sqldelight`
untouched.)

## 6. Test plan

### JVM/common read-model tests — new `web/src/commonTest/.../content/NextActionTest.kt`
(pattern: `ContextFilterTest` — `InMemoryStateStore` + `RecordingEmitter` + `ContentCommands` + `ContentReadModel`)

1. `looseTopThenOnePerProject` — 2 loose root tasks + 2 projects (2 active actions each). Expect
   rows = [top loose, projA first, projB first]; exactly one row per project.
2. `excludesWaitingDoneDroppedDeferredAndDelegated` — a `DONE`, a `DROPPED`, a `setPerson` (waiting),
   a future-`defer` task all absent; the deferred one appears once `now` passes its `deferUntil`.
3. `excludesInboxItems` — tasks created under the inbox id (passed as `inbox=`) never surface.
4. `contextScoping` — with context C, only C-tagged actions; `context = null` returns all
   (any-context). Untagged project action hidden under C.
5. `dueDateBumpsAheadOfRank` — a project with action `hi` (better rank, no due) and `lo` (worse
   rank, due date) → picked action is `lo` (due bumps ahead).
6. `sizeBreaksTieByRankWhenAbsent_thenBumpsWhenSet` — two same-due actions, no sizes → lower `rank`
   wins (pure-rank degradation). Set the worse-rank one to `Size.S` → it now wins (size bump).
   Asserts graceful degradation before build #4.
7. `projectRowsOrderedByProjectRank` — two projects with explicit `setRank` → project rows follow
   project rank order.

### JVM route/render test — new `web/src/jvmTest/.../NextRenderTest.kt`
(pattern: `ReorderTest` — `testApplication`, `install(SSE)`, `webRoutes(read, inbox={...}, changes=ChangeNotifier(), commands=...)`)

8. `nextTabRendersLooseThenProjectRows` — `GET /next` body contains the loose action title, the
   project label, and the project's first action; does not contain an inbox item title.
9. `nextSseFragmentIsPatched` — `GET /updates/next` (or the post-driven path) yields
   `event: datastar-patch-elements` with the `next` fragment.
10. `nextRespectsContextCookie` — `GET /next?context=<C>` shows only C-tagged actions (cookie set,
    stream reopens filtered).

### Playwright (`webtest/`)
Not required for #3 — the JVM render tests cover markup + SSE. Optional smoke: the `/next` tab shows
project-grouped rows after triaging an item into a project (defer to build #4 when triage-expand UI
lands).

---

## SHARED files touched (sequence to avoid conflicts)

| File | Change | Conflict risk |
|------|--------|---------------|
| `core/.../content/Fields.kt` | add `SIZE` const + `Size` object | build #4 also edits — do #3 first, #4 reuses |
| `web/.../content/ContentReadModel.kt` | `NodeView.size`, `NextRow`, `nextActions`, `nextOrder`, `completableNow`, `sizeOrder`; `toView` reads SIZE | central; keep `activeTasks` |
| `web/.../views/NodeViews.kt` | rewrite `nextSection` signature + body | low |
| `web/.../WebRoutes.kt` | 2 call sites (`/next`, `/updates/next`) pass inbox+context | low |
| `web/.../resources/custom.css` | optional `.project` label style | cosmetic only |

**Not touched:** `ContentCommands.kt` (setSize is build #4), `data/.../sqldelight` schema, server
operator runtime, version assertions.

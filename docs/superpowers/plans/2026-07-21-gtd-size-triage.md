# Build order #4 — `size` field + inbox triage EXPAND — plan (2026-07-21)

> Scope: the GTD triage spec (`docs/superpowers/specs/2026-07-21-mobile-gtd-triage-ux.md`)
> build-order **#4**. Adds the `size` field (S | M | L; **no XL** — RESOLVED) and the
> inline **triage EXPAND** panel in the inbox fragment (spec §4): rename, set size, split
> (add subtask ⇒ becomes a project), add link/description, preview attachments, and three
> one-tap **file-location chips** (build #6's suggestion operator — the slot is wired/stubbed
> now). Also lands the **4-level nesting cap** enforced at Move time (RESOLVED Q8). DESIGN
> ONLY; no gradle run.

## 0. Summary of decisions

- `size` is an LWW register (`Op.SetField`) — **no op type, no schema/migration, no version
  bump** (same as `rank`). Values `S | M | L`; `null` clears. No `XL` (bigger ⇒ make it a
  project by splitting).
- The EXPAND panel is rendered **inside the inbox fragment**, collapsed, one panel per row.
  Open/closed state is a **Datastar client signal** (`$exp`) holding the expanded node id —
  it survives idiomorph SSE patches, so the panel a user is triaging does **not** snap shut
  on every mutation. Pure `data-*` expressions ⇒ CSP-safe (uses the existing
  `script-src 'unsafe-eval'` carve-out; no inline `<style>`/`<script>`).
- The **4-level cap** lands **here (#4)**, not #5 — rationale in §7. The enforcement point is
  a **read-model guard consulted by every move route**; the helper is shared so #5's
  Reference filing inherits it for free.

---

## 1. Core vocab — `core/.../content/Fields.kt` (SHARED)

Add:

```kotlin
/**
 * Effort/size estimate (GTD triage §1): S (<2m) | M (<30m) | L (<~2h). Advisory,
 * tunable. No XL — anything bigger must become a project (a node with children).
 * LWW register via Op.SetField; absent = unsized.
 */
const val SIZE = "size"
```

And a value object alongside `OcrStatus`:

```kotlin
/** Allowed values for [Fields.SIZE] (GTD triage §1 — no XL). */
object Size {
    const val S = "S"; const val M = "M"; const val L = "L"
    val ALL = setOf(S, M, L)
}
```

No new op type. No `EntityType` change. **No `data/` schema change, no migration, no
version-assertion bump** (`SqlDelightStateStoreTest` / server `DurabilityTest` untouched) —
call this out explicitly so reviewers don't expect a migration.

---

## 2. Commands — `web/.../content/ContentCommands.kt` (SHARED)

```kotlin
/** Set the effort size (GTD triage §1); null/invalid clears. */
fun setSize(node: Ulid, size: String?) =
    ops.setField(node, Fields.SIZE,
        size?.takeIf { it in Size.ALL }?.let(::JsonPrimitive) ?: JsonNull)

/**
 * Split during triage: add a subtask AND make the parent a project (spec §4 —
 * "add subtask ⇒ it becomes a project"). Role is still stored in `kind` today, so
 * we set it explicitly; when §2's derive-from-children lands this convert is a no-op.
 */
fun split(parent: Ulid, childTitle: String): Ulid {
    val child = addSubtask(parent, childTitle)   // existing: createTask(title, parent)
    convertToProject(parent)                      // existing
    return child
}
```

`rename` (title), `setNotes` (description/link), `move`, `convertToProject`, `addSubtask`
already exist and are reused. For "add link" v1 folds the URL into `notes`/description (one
textarea labelled "Link or description"); a dedicated `Fields.LINK` is a later, additive
option — noted, not built, to keep scope tight.

---

## 3. Read model — `web/.../content/ContentReadModel.kt` (SHARED)

**3a. Surface `size` on `NodeView`:**

```kotlin
data class NodeView( … existing …, val size: String? = null )
// in toView():  size = fields[Fields.SIZE].asString(),
```

**3b. Attachment preview.** Attachments are **separate `EntityType.Attachment` entities**
(not child nodes): payload register (`ATTACHMENT_FIELD`) is a JSON object
`{nodeId,type,blobHash,relativePath}` (see `OpWriter.createAttachment`). Add:

```kotlin
data class AttachmentView(val id: Ulid, val type: String?, val filename: String?, val blobHash: String?)

/** Attachments linked to [node] (payload.nodeId == node), for triage preview (§4). */
fun attachments(node: Ulid): List<AttachmentView>
```

Implementation: `store.project().values.filter { it.alive }`, pick entities carrying the
attachment register, parse the JSON payload, keep those whose `nodeId == node`. Preview is
**filename + type + a link** (+ the node's own OCR chip via existing `node.ocrStatus`); no
image thumbnail — `:web` has no blob-serving route yet, so a real thumbnail is out of scope
and explicitly deferred.

**3c. File-location suggestion slot (build #6 stub — wire now).**

```kotlin
data class FileSuggestion(val target: Ulid, val label: String, val area: String) // area: "project"|"reference"

/**
 * Up to 3 score-ranked file-location suggestions for [node] (spec §6, RESOLVED Q4:
 * 0–3, any Projects/Reference mix, confidence-floored). STUB until build #6's
 * suggestion operator lands: returns emptyList now. The operator will emit `proposed`
 * suggestion nodes (kind "fileSuggestion", payload target id + score) that this reads
 * and ranks — accepting a chip is the human Move (§6). The UI slot is wired today.
 */
fun fileSuggestions(node: Ulid): List<FileSuggestion> = emptyList()
```

**3d. Depth helpers (4-level cap, §7).**

```kotlin
/** 1-based depth: a root child = 1, its child = 2, … (cycle-guarded, capped walk). */
fun depthOf(id: Ulid): Int
/** Tallest descendant chain below [id]; a leaf = 0. */
fun subtreeHeight(id: Ulid): Int
/** True iff moving [node] under [newParent] would push any node past [max] levels. */
fun moveWouldExceedDepth(node: Ulid, newParent: Ulid, max: Int = 4): Boolean =
    depthOf(newParent) + 1 + subtreeHeight(node) > max
```

`depthOf` walks `parent` to root; bound the loop (e.g. > `max+2`) so a corrupt cycle can't
hang. `subtreeHeight` recurses over `children(id)` (already defined), also bounded.

---

## 4. Views — `web/.../views/NodeViews.kt` (SHARED)

The inbox list keeps rendering `nodeRow(it, reorderable = true)` per `li`; **append the
triage panel inside the same `li`**. Signal `$exp` (string) holds the expanded node id.

**4a. Row expand toggle** (added at the front of `nodeRow` when `reorderable`):

```kotlin
button(classes = "action expand") {
    attributes["data-on:click"] = "\$exp = (\$exp === '${node.id}' ? '' : '${node.id}')"
    attributes["title"] = "Expand"
    +"▸"
}
```

ULIDs are Crockford base32 (safe inside a JS string literal). One panel open at a time —
simplest correct behaviour; survives morphs because Datastar signals live in its store, not
the DOM.

**4b. `triagePanel(read, node)`** — a `div(classes="triage")` with
`attributes["data-show"] = "\$exp === '${node.id}'"`, containing:

1. **Rename** — `input data-bind:t_<id>` + button
   `@post('/node/<id>/rename?title=' + encodeURIComponent($t_<id>))`.
2. **Size** — three chips S/M/L; the current one gets `chip-on`:
   `@post('/node/<id>/size?size=M')`; plus a "clear" chip `?size=`.
3. **Split** — `input data-bind:s_<id>` + button
   `@post('/node/<id>/split?title=' + encodeURIComponent($s_<id>))` ("Add subtask").
4. **Link / description** — `input data-bind:n_<id>` + button
   `@post('/node/<id>/notes?notes=' + encodeURIComponent($n_<id>))`; shows current `notes`.
5. **Attachments** — `read.attachments(node)`: filename + type rows, each a link to
   `/node/<id>/read`; plus the node's OCR chip if `node.ocrStatus != null`. Empty ⇒ omitted.
6. **Suggested file locations** — `read.fileSuggestions(node)`: up to 3 chips
   `@post('/node/<id>/move?parent=<target>')` (accepting a chip **is** the Move — reuses the
   existing `/move` route, so it inherits the depth guard §7). Empty now (stub) ⇒ a muted
   "No suggestions yet" line so the slot is visibly wired.

All handlers are Datastar `data-on:click` (colon syntax) — no inline JS. Each posts to an
**inbox-scoped route** that re-renders `#inbox`, so the panel morphs in place with `$exp`
preserved (size chip highlight flips, etc.).

Also render the size on the collapsed row (e.g. `node.size?.let { span("size-badge"){+it} }`)
so triaged items read at a glance on the inbox and other surfaces.

---

## 5. Routes — `web/.../WebRoutes.kt` (SHARED)

All new mutations are **inbox-scoped** (reuse the existing `applied {}` helper that patches
`#inbox`). Inside the `commands != null` block:

```kotlin
post("/node/{id}/size") {
    val size = call.request.queryParameters["size"]  // "S"|"M"|"L"|"" (clear)
    val id = call.nodeId()
    if (id == null) badRequest()
    else if (!size.isNullOrEmpty() && size !in Size.ALL) badRequest("size must be S|M|L")
    else call.applied { setSize(id, size?.ifEmpty { null }) }
}
post("/node/{id}/rename") {
    val title = call.request.queryParameters["title"]?.trim().orEmpty()
    val id = call.nodeId()
    if (id != null && title.isNotEmpty()) call.applied { rename(id, title) } else badRequest()
}
post("/node/{id}/split") {
    val title = call.request.queryParameters["title"]?.trim().orEmpty()
    val id = call.nodeId()
    if (id != null && title.isNotEmpty()) call.applied { split(id, title) } else badRequest()
}
post("/node/{id}/notes") {
    val notes = call.request.queryParameters["notes"].orEmpty().trim()
    val id = call.nodeId()
    if (id != null) call.applied { setNotes(id, notes) } else badRequest()
}
```

**Depth guard on every Move route** (`/node/{id}/move`, `/node/{id}/move-detail`, and any
future chip/reference route). Insert before emitting:

```kotlin
post("/node/{id}/move") {
    val parent = call.request.queryParameters["parent"]?.let { runCatching { Ulid.parse(it) }.getOrNull() }
    val id = call.nodeId()
    if (id == null || parent == null) return@post call.respondText("bad request", BadRequest)
    if (read.moveWouldExceedDepth(id, parent)) {
        call.respondText("move would exceed 4 levels", status = HttpStatusCode.Conflict)  // 409, no op emitted
    } else call.applied { move(id, parent) }
}
```

Same guard wraps `/move-detail`. (The suggestion chips post to `/move`, so they inherit it.)

`badRequest()` is shorthand for the existing `call.respondText("bad request", BadRequest)`.

---

## 6. CSS — `web/.../resources/custom.css` (SHARED, served as a FILE)

Add (no inline styles — CSP):

```css
.triage { flex-basis: 100%; margin: .3rem 0 .1rem; padding: .5rem .2rem;
          border-top: 1px dashed var(--pico-muted-border-color); }
.triage .org-row { /* reuse existing .org-row flex layout */ }
.size-chips { display: flex; gap: .4rem; }
.size-badge { font-family: "Iosevka Charon Mono", ui-monospace, monospace;
              font-weight: 700; font-size: .7em; padding: 0 .35rem;
              border: 1px solid var(--pico-muted-border-color); border-radius: .4rem; }
button.action.expand { border: none; }   /* subtle chevron */
```

Reuse existing `.org-row`, `button.action`, `.chip-on`.

---

## 7. 4-level nesting cap — belongs HERE (#4), recommendation + enforcement

**Recommendation: land it in #4, not #5.** #4 introduces the first *bulk* user Move
affordance (one-tap file-location chips) **and** `split`, which deepens the tree — so this
is the first build that can actually create a 5th level. #5 (Reference filing) then reuses
the same guard with zero extra work. Putting it here keeps the invariant in front of the
code that first exercises it.

**Enforcement point: the move *routes*, backed by `ContentReadModel.moveWouldExceedDepth`.**
Rationale (matches spec §2/§8 + Q8 "rejected at move-time"): the op-log is a CRDT — you
cannot cleanly "reject" an already-merged `Op.Move` in the apply/merge layer, and two
offline devices could still momentarily converge to depth-5. So the cap is a **mutation-entry
validation**, not a merge invariant. Every human Move originates at a route with read access,
so guarding there (409, no op emitted) is the single correct choke point. The helper lives in
the shared read model so server + phone + #5 all consult the same logic. Document the residual:
concurrent moves from two devices can transiently exceed depth 4; acceptable for v1 (a future
operator could flag/repair). `ContentCommands.move` stays pure (no read access) — do **not**
guard there.

---

## 8. Dev seeding — `server/.../DevServer.kt` (server-only; low conflict)

To make the panel drivable in Playwright, extend `main()` seeding:
- give one inbox task an attachment (`content.commands` has no attach helper — add a tiny
  `ServerContent`-level attach or emit an `Op.AddAttachment` inline like `seedStubProposal`)
  so "Preview attachments" renders;
- keep the existing "Launch website" project as a real Move target;
- (optional) seed a stub `fileSuggestion` proposal so a chip is drivable before build #6.

Minimal, additive; flagged as a shared-ish touch only to sequence against other dev-seed edits.

---

## 9. Test plan

**JVM — commonTest (`web/src/commonTest/.../content/`):**
- `ContentTest.setSizeSetsAndClears` — `setSize(n,"M")` ⇒ `read.node(n).size=="M"`;
  `setSize(n,null)` and `setSize(n,"XL")` clear/reject (view `size==null`).
- `ContentTest.splitMakesParentAProject` — `split(p,"child")` ⇒ parent `kind=="project"`,
  child present under it.
- New `DepthCapTest` (commonTest) — build a 4-deep chain via `move`; assert
  `depthOf`, `subtreeHeight`, and `moveWouldExceedDepth(leaf, deepParent)==true`;
  a legal move `==false`; cycle-guard (a self/loop parent doesn't hang).

**JVM — jvmTest route tests (`web/src/jvmTest/.../` — new `SizeTriageTest`, `io.ktor
testApplication` + `RecordingEmitter` + `ChangeNotifier`, mirror `MutationsTest`):**
- `POST /node/{id}/size?size=M` ⇒ 200, body is `datastar-patch-elements`, `node.size=="M"`;
  `?size=XL` ⇒ 400; `?size=` clears.
- `POST /node/{id}/rename?title=…`, `/split?title=…`, `/notes?notes=…` each patch `#inbox`
  and mutate state (split ⇒ parent becomes project).
- **Depth guard:** build a depth-4 chain; `POST /node/{leaf}/move?parent={deep}` ⇒ 409 and
  parent unchanged; a legal `/move` ⇒ 200.
- **Fragment render:** `GET /` (or the `/updates` push body) contains the size chips, the
  `data-show`/`$exp` expand wiring, and the "Suggested file locations" slot placeholder —
  render-only assertion like `OcrSummaryViewTest`.

**Playwright (`webtest/tests/` — extend `web-ux.spec.js` or new `size-triage.spec.js`,
against `./gradlew :server:webDevServer`):**
- Click the ▸ expand on a seeded inbox item ⇒ panel visible; click size **M** ⇒ chip gets
  `chip-on` and a size badge appears on the row; **panel stays open** after the SSE morph
  (proves `$exp` survives idiomorph — the key UX guarantee).
- Rename in-panel ⇒ row title updates.
- "Add subtask" (split) ⇒ item now shows as a project (appears on the Projects tab).
- Attachment preview row renders (seeded attachment); suggestion slot shows its wired
  placeholder. Assert no CSP/`unsafe-eval`/JS console errors (existing pattern).

---

## 10. SHARED files touched (sequence conflicts against these)

| File | Change |
|------|--------|
| `core/.../content/Fields.kt` | `SIZE` const + `Size` object |
| `web/.../content/ContentCommands.kt` | `setSize`, `split` |
| `web/.../content/ContentReadModel.kt` | `NodeView.size`; `attachments`; `fileSuggestions` (stub); `depthOf`/`subtreeHeight`/`moveWouldExceedDepth` |
| `web/.../views/NodeViews.kt` | expand toggle + `triagePanel` + row size badge |
| `web/.../WebRoutes.kt` | `/size` `/rename` `/split` `/notes` routes + depth guard on `/move`,`/move-detail` |
| `web/.../resources/custom.css` | `.triage`, `.size-chips`, `.size-badge`, `.expand` |
| `server/.../DevServer.kt` | Playwright seed (attachment + optional stub suggestion) |

**Not touched:** `data/` schema + migrations (no `size` migration — LWW register); `OpEmitter.kt`;
core op/merge; version-assertion tests. `Fields.SIZE` is the only `core` edit ⇒ coordinate
with any concurrent build that also edits `Fields.kt`/`ContentReadModel.kt` (#5 Reference,
#6 suggestions both will).

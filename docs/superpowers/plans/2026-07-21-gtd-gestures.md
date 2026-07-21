# Plan — GTD gesture + keyboard layer (spec §4, build order #4 partial)

> Status: PLAN (design only — no feature code, no gradle). Implements the swipe +
> keyboard gesture layer from `2026-07-21-mobile-gtd-triage-ux.md` §4/§8. Depends on
> build #1 (`Fields.RANK` + `FractionalIndex`) and #2 (FIFO inbox + fractional reorder;
> visible complete/trash buttons already removed from inbox rows). **This work UNBLOCKS
> that removal**: build #2 stripped the inbox row's complete/trash buttons on the promise
> that swipe-right completes and swipe-left deletes — that promise is delivered here.

## 0. Scope

IN: swipe-right → complete (`DONE`), swipe-left → delete (`DROPPED`); `j`/`k` cursor;
`space`→complete, `del`/`backspace`→trash on the cursor row; `Enter`/tap → open the item
(navigate to its detail = today's "expanded" view); desktop global chords `g` then
`i`/`n`/`p`/`r` to switch tabs; `/` focuses search when a search box exists.

OUT (later builds): the inline triage expand panel (rename/size/split/chips) is build #4
proper — here `Enter`/tap just opens `/node/{id}`; when the inline panel lands, only the
`Enter` target swaps from "navigate" to "toggle panel", no gesture rewiring. `/reference`
tab + search box is build #5 — the `r` chord and `/` focus are wired now and become live
when that surface ships (they no-op / soft-navigate until then).

## 1. Approach decision — vendored helper, NOT per-row Datastar signals

**Chosen: a single vendored ES-module asset** `web/src/commonMain/resources/zync-gestures.js`,
served at `/assets/zync-gestures.js` exactly like `datastar.js`, loaded once from
`Layout.page`'s `<head>`. Rejected the pure-Datastar `data-on:pointer*` + per-gesture-signal
approach because:

- Swipe detection is multi-event stateful math (record start, track delta, decide
  tap-vs-swipe, **suppress the trailing navigation click**) that would be duplicated as
  unreadable inline expressions on every one of N inbox rows.
- The global `g`-chord state machine and `/`-focus have no natural per-element home.
- A single **delegated** listener on `document` survives the SSE fragment swap (the whole
  `#inbox` subtree is replaced on every mutation) with zero re-binding, and keeps the
  server-rendered markup tiny.

**Datastar still owns every server mutation.** The helper never invents a network call or a
new endpoint: on a committed swipe/keypress it synthesizes a `.click()` on a *hidden*
Datastar-bound trigger button inside the row (`data-on:click="@post('/node/{id}/complete')"`
/ `/trash`). Datastar's own handler then does the SSE POST and patches `#inbox` — the exact
existing op/SSE path, reused verbatim. The only Datastar expressions we add are on those
hidden triggers.

### CSP safety

- The helper is a plain external ES module → satisfied by `script-src 'self'`; it uses **no
  `eval`/`new Function`**, so it does not even lean on the `'unsafe-eval'` carve-out (that
  carve-out remains only for Datastar). No inline `<script>`.
- Drag feedback is a script-set CSS custom property: `row.style.setProperty('--swipe-dx', …)`.
  CSP `style-src` governs `<style>` elements and markup `style=` attributes **only**; CSSOM
  mutations from script are always allowed — so this is CSP-safe. The static rule that
  consumes it (`transform: translateX(var(--swipe-dx,0))`) lives in `custom.css`, a served
  FILE. No inline `<style>`.
- Actual CSP under test (`app/.../ZyncServer.kt`): `default-src 'self'; script-src 'self'
  'unsafe-eval'; connect-src 'self' ws:; img-src 'self' data:`. The new asset needs nothing
  beyond `'self'`.

## 2. Tap-vs-swipe disambiguation (the critical bit)

The row is `<a href="/node/{id}">` — a real link, so a tap navigates for free and works with
no JS (progressive enhancement / CSP-proof). The danger is a horizontal swipe that ends over
the same anchor firing a `click` and navigating anyway. Algorithm in the helper, using
Pointer Events (fire for touch **and** mouse, so Playwright's `page.mouse` exercises them):

1. `pointerdown` on an element inside `.swipe-row`: record `startX/startY`, `pointerId`,
   `row`; set `moved=false`; `setPointerCapture`.
2. `pointermove`: `dx=x-startX`, `dy=y-startY`. Once `|dx| > HYST (8px)` **and**
   `|dx| > |dy|` → this is a horizontal swipe: set `moved=true`, add class `.swiping`
   (disables the row's CSS transition), `preventDefault()` (stops the browser starting a
   scroll/text-select), and `row.style.setProperty('--swipe-dx', dx+'px')` for live drag.
   If vertical dominates first, bail out and let the page scroll (row keeps `touch-action:
   pan-y`).
3. `pointerup`:
   - If `moved` and `|dx| >= COMMIT (0.35 * row.clientWidth, min 64px)`: fire the matching
     hidden trigger — `dx>0 → .swipe-fire.complete`, `dx<0 → .swipe-fire.trash` — via
     `.click()`. Then **swallow the trailing click**: add a one-shot capture-phase
     `click` listener on `document` that calls `preventDefault()+stopPropagation()` and
     removes itself, so the anchor never navigates.
   - Else (tap, or under-threshold swipe): clear `--swipe-dx` (CSS transition snaps it
     back); do **nothing else** → the natural anchor `click`/navigation proceeds.
   - Always reset `moved`, remove `.swiping`.

`touch-action: pan-y` on `.swipe-row` (in `custom.css`) lets the browser own vertical
scrolling while we own the horizontal axis, so triage never fights the scroll gesture.

## 3. Keyboard model (in the same helper)

Single `keydown` listener on `document`. Guard: if `document.activeElement` is an
`input/textarea/select` or `isContentEditable`, ignore all keys except `Escape` (so typing in
the detail-page fields is untouched). All handled keys `preventDefault()`.

Per-row cursor (only when `.swipe-row` rows exist — i.e. Inbox):
- `j` → move cursor to next `.swipe-row` (wrap/clamp at ends), `k` → previous. Cursor =
  `.cursor` class + `scrollIntoView({block:'nearest'})`. Cursor is tracked by **index**, not
  element ref, and re-resolved from the live `.swipe-row` NodeList each keypress, so it
  survives SSE re-renders (clamps if the list shrank).
- `space` → `.click()` the cursor row's `.swipe-fire.complete`.
- `Delete`/`Backspace` → `.click()` the cursor row's `.swipe-fire.trash`.
- `Enter` → activate the cursor row's `<a>` (`.click()` → navigate to detail; the future
  inline-expand build swaps this target only).

Global chords (every page):
- `g` arms a 1.2s chord window (a `pendingG` flag). The next key looks up
  `nav.tabbar a[data-key=<letter>]` (and a `[data-key=r]` link when Reference ships) and, if
  found, sets `location.href` to its `href`. `i`→`/`, `n`→`/next`, `p`→`/projects`,
  `r`→`/reference`. Reading the target from the DOM keeps the mapping declarative and avoids
  hard-coding routes in JS.
- `/` → focus `#search` / `input[type=search]` if present (`preventDefault` so the slash is
  not typed); no-op until the Reference search box exists.
- `Escape` → clear cursor / cancel a pending `g`.

## 4. Exact files

### CREATE

- `web/src/commonMain/resources/zync-gestures.js` — the vendored helper (§2–§3). Plain ES
  module, ~120 lines, no deps, no eval. Tunables as top consts: `HYST=8`, `COMMIT_FRAC=0.35`,
  `COMMIT_MIN=64`, `CHORD_MS=1200`.

### EDIT — SHARED FILES (flag for conflict sequencing)

- **`web/src/commonMain/kotlin/dev/njr/zync/web/views/NodeViews.kt`** (SHARED):
  - `inboxSection`, inbox branch: wrap each item as
    `li("swipe-row") { attributes["data-node"] = it.id.toString(); nodeRow(it, reorderable = true) }`
    (currently `li { nodeRow(it, reorderable = true) }`).
  - `nodeRow(node, reorderable = true)`: after the visible anchor/spans and the existing
    reorder buttons, append two **hidden** trigger buttons (these are the complete/trash
    handlers build #2 removed from view, re-added as invisible gesture targets):
    ```
    button(classes = "swipe-fire complete") {
        attributes["data-on:click"] = "@post('/node/${node.id}/complete')"
        attributes["tabindex"] = "-1"; attributes["aria-hidden"] = "true"
    }
    button(classes = "swipe-fire trash") {
        attributes["data-on:click"] = "@post('/node/${node.id}/trash')"
        attributes["tabindex"] = "-1"; attributes["aria-hidden"] = "true"
    }
    ```
    Signature unchanged: `fun FlowContent.nodeRow(node: NodeView, reorderable: Boolean = false)`.
    Non-`reorderable` surfaces are untouched (they keep their visible ✓/🗑 buttons).

- **`web/src/commonMain/kotlin/dev/njr/zync/web/views/Layout.kt`** (SHARED):
  - In `page(...)`'s `<head>`, after the datastar `<script>`, add
    `script(type = "module", src = "/assets/zync-gestures.js") {}`.
  - In `tabBar`, add `attributes["data-key"] = when(tab){INBOX->"i";NEXT->"n";PROJECTS->"p";…}`
    on each tab `<a>` so the `g`-chord map is read from the DOM. (Add the `r`/Reference key
    when that tab is introduced.)

- **`web/src/commonMain/kotlin/dev/njr/zync/web/WebRoutes.kt`** (SHARED):
  - Add a static route beside the other `/assets/*`:
    ```
    get("/assets/zync-gestures.js") {
        call.respondText(WebPlatform.asset("zync-gestures.js"), ContentType("application", "javascript"))
    }
    ```
  - **No new mutation routes.** Reuse `/node/{id}/complete` and `/node/{id}/trash` verbatim.

- **`web/src/commonMain/resources/custom.css`** (SHARED):
  ```
  .swipe-row { position: relative; touch-action: pan-y; transform: translateX(var(--swipe-dx, 0));
               transition: transform .18s ease; }
  .swipe-row.swiping { transition: none; }
  .swipe-row.cursor { outline: 2px solid var(--pico-primary); outline-offset: -2px; }
  .swipe-fire { display: none; }               /* hidden gesture triggers */
  /* optional reveal affordance: green (right/complete) / red (left/trash) under the row */
  ```

### EDIT — TEST INFRA (not shared product code)

- **`server/.../operator/../DevServer.kt`** — add two dedicated seed tasks so the gesture spec
  never collides with other specs' seeds: `createTask("Swipe me done")`, `createTask("Swipe me
  gone")`. (Mirrors the existing "CSP probe task" pattern.)

### FILES EXPLICITLY NOT TOUCHED

`ContentCommands.kt`, `ContentReadModel.kt`, `Fields.kt`, `data/` schema + migrations,
`server/operator/*`. **No op types, no fields, no schema/version bump** — the schema-version
assertions in `SqlDelightStateStoreTest` / server `DurabilityTest` do **not** move.

## 5. Test plan

### JVM (io.ktor `testApplication`, fast, no browser)

Extend `web/src/jvmTest/.../DatastarServingTest.kt` (or a new `GesturesServingTest.kt`):
1. `GET /assets/zync-gestures.js` → 200, `application/javascript`, body non-empty and
   contains a known marker string (e.g. `swipe-fire`).
2. Home page HTML wires the module: `home.contains("""src="/assets/zync-gestures.js"""")`.
3. Inbox fragment contract: render with a seeded inbox item and assert the fragment contains
   `class="swipe-row"`, `data-node="`, and the two hidden triggers with the correct paths:
   `data-on:click="@post('/node/…/complete')"` (class `swipe-fire complete`) and `…/trash`
   (class `swipe-fire trash`). Guards the server-render contract the JS depends on, without a
   browser.

### Playwright (`webtest/tests/gestures.spec.js`, new)

Runs against `./gradlew :server:webDevServer` (the config's `webServer`); pointer events fire
for `page.mouse`, so no touch emulation needed. Reuse the CSP posture by running the suite
under `ZYNC_DEV_CSP` (as csp.spec documents) to prove the helper needs only `'self'`.
1. **Swipe-right completes**: `page.mouse` drag from the "Swipe me done" row center to
   `+140px` (steps) then up → assert `#inbox` no longer contains "Swipe me done" (timeout 5s),
   like csp.spec's completion assertion.
2. **Swipe-left trashes**: same on "Swipe me gone" with `-140px` → drops out.
3. **Tap opens (no accidental nav-suppression)**: a near-zero-move down/up (a real click) on a
   row's link → `page` URL becomes `/node/…` and detail shows the title. Proves swipe
   suppression does not break legitimate taps.
4. **Under-threshold swipe does NOT act**: small `+20px` drag then up → row stays in `#inbox`
   and URL unchanged (no navigation, no complete).
5. **Keyboard cursor**: focus `body`; `j` → first `.swipe-row` gains `.cursor`; second `j` →
   next; `k` → back; `space` → cursor row completes (drops out); `Enter` on a cursor row →
   navigates to its detail.
6. **Global chords**: `g` then `n` → URL `/next`; `g` then `p` → `/projects`; `g` then `i` →
   `/`. (`r` and `/` added to the spec when Reference ships.)
7. **No CSP/JS errors**: collect `pageerror`/`console.error`; assert none match
   `Content Security Policy|unsafe-eval|is not defined|SyntaxError`, and
   `script[src="/assets/zync-gestures.js"]` is present — same guard style as `web-ux.spec.js`.

## 6. Sequencing / conflict notes

Shared files this build edits — coordinate so nothing lands concurrently on them:
`NodeViews.kt`, `Layout.kt`, `WebRoutes.kt`, `custom.css`. It does **not** touch
`ContentReadModel.kt`, `ContentCommands.kt`, `Fields.kt`, or the data schema, so it is
conflict-free with any core/read-model work. It is additive over build #2 (which already set
`reorderable=true` inbox rows and removed the visible buttons) and should merge after it.

# GTD build-order #5 — Reference tree + FTS5 keyword search (DESIGN)

> Status: PLAN (design only — no feature code, no gradle). Implements build-order #5 of
> `docs/superpowers/specs/2026-07-21-mobile-gtd-triage-ux.md` §7/§8: a `FILED` status, a
> well-known Reference-root node, a "file into Reference" command (`setStatus(FILED)` +
> `Move` under the root), and SQLite **FTS5** keyword search living in `data/` so it runs
> identically on the server (JVM driver) and the phone (Android driver). Semantic
> search/embeddings are explicitly OUT (that is build-order #6, `gtd-suggest-embeddings.md`).
> Honors the sequencing plan (`gtd-sequencing.md`): the FTS engine is a **`data/`-only**
> concern maintained by the `SqlDelightStateStore` write path and exposed as a raw
> `search(query): List<Ulid>` at the store boundary; the web read method + surface land on
> top of it.

## 0. Design decisions (the two that matter)

**D1 — Index sync = rebuild-one-entity-on-write, inside `SqlDelightStateStore` (NOT a
trigger, NOT a core `apply()` change).** Every op-apply path already funnels through the
`StateStore` port's `putRegister` / `putTombstone`. So the FTS index is kept current by
re-deriving a single entity's search document from the `register` table whenever a
*searchable* field of that entity changes, then `DELETE`+`INSERT`-ing its FTS row. This:
- keeps `core/merge/Apply.kt` (hot, shared) untouched — the port boundary is the seam;
- works on **all four** apply sites for free (server `SyncService` local+remote, phone
  `SyncClient` pull + `OpWriter` local) because they all call `putRegister`/`putTombstone`;
- reuses the projection's visibility rules in one place instead of duplicating merge logic
  in SQL triggers (a `register`-row trigger can't see the tombstone/kind/status of the
  entity without awkward cross-table joins, and FTS5 triggers can't easily upsert per-field).

Rejected: **SQL triggers on `register`** (can't compute alive/kind/status cheaply; FTS5
per-column upsert is painful) and **whole-index rebuild on every write** (O(N) per op).

**D2 — `search()` lives on the `StateStore` port** so the shared `ContentReadModel`
(which only knows `StateStore`, not `data/`) can call it platform-agnostically.
`InMemoryStateStore` gets a naive scan implementation so the JVM read-model/route tests
exercise search without a real SQLite/FTS5 engine; `SqlDelightStateStore` gets the FTS5
implementation. This is the "raw `search(query): List<Ulid>` at the store boundary" the
sequencing plan calls for.

## 1. What gets indexed

Per spec §7: **title, notes, summary, OCR text**. Of these, `title`/`notes`/`summary` are
LWW register values already in the projection. Full **OCR text is a large blob** keyed by
`ocrBlobHash`, not a register value — it is not reachable from `SqlDelightStateStore`
without a blob read. Scope for #5:

- Index `title`, `notes`, `summary` now (the operator-written `summary` is derived from the
  OCR text, so scanned/spoken docs are already discoverable by their gist).
- Add an **`ocr` column to the FTS schema now** (so no second migration later) but leave it
  empty for #5. A follow-up wires the blob-holding side (server `summarize` operator, which
  already S3-fetches the OCR text; phone `OcrWorker`, which already has the bytes) to write
  the `ocr` column via a new `StateStore.indexOcrText(entityId, text)` port method. Noted as
  a TODO in the schema comment; not implemented in #5.

Indexed iff the entity is **alive**, **not tombstoned**, `kind ∈ {task, project, attachment}`
(skip `context`, `comment`, agent-flow internal kinds, and unreviewed proposals), and has at
least one non-blank text field. `FILED` items stay indexed (spec §7: "archived; still
searchable"). Visibility filtering of `DROPPED` happens at read time (§4), not in the index.

## 2. `data/` — schema, migration, version bumps (the chokepoint)

Current `ZyncDatabase.Schema.version` = **5** (1 + four `.sqm`). This adds migration
**`5.sqm`** → version **6**.

### 2a. New file `data/src/commonMain/sqldelight/dev/njr/zync/data/db/SearchIndex.sq`
Fresh-schema definition (used when a new DB is created at the current version) + queries:

```sql
-- Full-text keyword index over content nodes (GTD triage spec §7). Standalone FTS5
-- (not external-content) so DELETE/INSERT by entity_id is trivial; entity_id is
-- UNINDEXED (a stored key, not a search column). Kept in sync by SqlDelightStateStore's
-- reindex() on register/tombstone writes. `ocr` is reserved for full OCR text (wired
-- later from the blob-holding side); empty for now.
CREATE VIRTUAL TABLE search_index USING fts5(
  entity_id UNINDEXED,
  title,
  notes,
  summary,
  ocr,
  tokenize = 'unicode61 remove_diacritics 2'
);

-- Text fields of one entity, for rebuild-on-write (values are JSON text; decoded in Kotlin).
docFields:
SELECT field, value FROM register
WHERE entity_id = ? AND field IN ('kind', 'title', 'notes', 'summary', 'status');

deleteDoc:
DELETE FROM search_index WHERE entity_id = ?;

insertDoc:
INSERT INTO search_index(entity_id, title, notes, summary, ocr)
VALUES (?, ?, ?, ?, ?);

searchDoc:
SELECT entity_id FROM search_index
WHERE search_index MATCH ? ORDER BY rank LIMIT ?;
```

### 2b. New migration `data/src/commonMain/sqldelight/dev/njr/zync/data/db/migrations/5.sqm`
```sql
-- v5 → v6: FTS5 keyword search index over content nodes (GTD triage spec §7).
CREATE VIRTUAL TABLE search_index USING fts5(
  entity_id UNINDEXED, title, notes, summary, ocr,
  tokenize = 'unicode61 remove_diacritics 2'
);
```
(Existing DBs migrate with an empty index; see §2d for a one-time backfill.)

### 2c. Version-assertion bumps (all `5L` → `6L`)
- `data/src/jvmTest/kotlin/dev/njr/zync/data/SqlDelightStateStoreTest.kt` line ~59
  (`schemaVersionBaselineForMigrationHarness`).
- `server/src/test/kotlin/dev/njr/zync/server/durability/DurabilityTest.kt` line ~65
  (`freshBootCreatesAtCurrentSchemaVersion`).
- `data/src/jvmTest/kotlin/dev/njr/zync/data/MigrationTest.kt` already asserts against
  `ZyncDatabase.Schema.version` dynamically — **no literal to bump**, but ADD a v5→v6 case
  (§6).

### 2d. `SqlDelightStateStore` (edit `data/.../SqlDelightStateStore.kt`)
Add a private searchable-field set, a `reindex`, and the port method. Called from the two
mutating overrides:

```kotlin
private val SEARCHABLE_KINDS = setOf("task", "project", "attachment")
private val REINDEX_TRIGGERS = setOf("kind", "title", "notes", "summary", "status")

override fun putRegister(key: RegisterKey, value: RegisterValue) {
    db.registerQueries.putRegister(/* …unchanged… */)
    if (key.field in REINDEX_TRIGGERS) reindex(key.entityId)
}

override fun putTombstone(entityId: Ulid, hlc: Hlc) {
    db.tombstoneQueries.putTombstone(/* …unchanged… */)
    reindex(entityId) // now dead → deleteDoc
}

/** Rebuild one entity's FTS row from its registers (D1). Idempotent; DELETE then INSERT. */
private fun reindex(entityId: Ulid) {
    val id = entityId.toString()
    db.searchIndexQueries.deleteDoc(id)
    if (db.tombstoneQueries.getTombstone(id).executeAsOneOrNull() != null) return
    val fields = db.searchIndexQueries.docFields(id).executeAsList()
        .associate { it.field_ to jsonString(it.value_) } // decode JSON text → raw string
    val kind = fields["kind"]
    if (kind !in SEARCHABLE_KINDS) return
    val title = fields["title"]; val notes = fields["notes"]; val summary = fields["summary"]
    if (listOf(title, notes, summary).all { it.isNullOrBlank() }) return
    db.searchIndexQueries.insertDoc(id, title, notes, summary, "")
}

// JsonNull / cleared fields read as absent (mirrors ContentReadModel.asString()).
private fun jsonString(raw: String): String? =
    (json.decodeFromString(JsonElement.serializer(), raw) as? JsonPrimitive)
        ?.takeIf { it !is JsonNull }?.content

override fun search(query: String, limit: Int): List<Ulid> {
    val match = FtsQuery.toMatch(query) ?: return emptyList() // null = no usable terms
    return db.searchIndexQueries.searchDoc(match, limit.toLong()).executeAsList().map(Ulid::parse)
}
```

`reindex` writes inside the caller's existing ingest transaction (server wraps batches; the
FTS row commits atomically with the register write). Reindex only fires for the five trigger
fields, so `rank`/`dueDate`/tag writes don't churn the index.

**One-time backfill for pre-v6 DBs:** the `5.sqm` migration creates an empty table; existing
content isn't reindexed by the migration. Add `SqlDelightStateStore.rebuildSearchIndex()`
that iterates `project()` and calls `reindex` for every entity, invoked once when the FTS
table is empty but the store is non-empty (a cheap `SELECT COUNT(*)` guard at store
construction, or explicitly from `StartupSequence.open` after migration). Specify: guard in
the store's `init` — if `search_index` is empty and `register` is not, run the rebuild.

## 3. `core/` — port method, well-known root, FILED, FTS query builder

- **`core/.../state/StateStore.kt`** (shared port — additive): add
  `fun search(query: String, limit: Int = 50): List<Ulid>`.
- **`core/.../state/InMemoryStateStore.kt`** (D2): naive impl — scan `project()`, keep
  entities that are alive, `kind ∈ SEARCHABLE_KINDS`, and whose lowercased
  `title+" "+notes+" "+summary` contains every whitespace-token of the query as a prefix
  (AND-of-prefix, mirroring FTS default). Return ids, capped at `limit`. Pure Kotlin, no
  index. (Ordering need not match bm25; tests assert membership, not rank.)
- **`core/.../content/Fields.kt`** (additive; coordinate with #4 which also adds FILED):
  ```kotlin
  object Status { const val ACTIVE = "ACTIVE"; const val WAITING = "WAITING"
      const val DONE = "DONE"; const val DROPPED = "DROPPED"; const val FILED = "FILED" }
  /** Well-known Reference-tree root (spec §7). Fixed ULID: same node id on every
   *  device/install, so `Move`s under it merge cleanly. Ensured (title set) at bootstrap. */
  object WellKnownNodes { val REFERENCE_ROOT: Ulid = Ulid.parse("00000000000000000000000REF") }
  ```
  (Pick a real 26-char Crockford-base32 ULID literal; `…REF` shown for intent.) If #4 lands
  `Status.FILED` first, this file only adds `WellKnownNodes` — sequence via the matrix.
- **`core/.../content/FtsQuery.kt`** (NEW): `object FtsQuery { fun toMatch(raw: String): String? }`
  — tokenize on non-alphanumeric, drop empties, quote each token and append `*` for prefix
  (`foo bar` → `"foo"* "bar"*`), AND-joined; returns `null` if no usable token. Prevents raw
  user input from throwing FTS5 syntax errors and gives prefix-as-you-type behavior. Unit
  tested in core.

## 4. `web/` — read model, command, surface, route

### 4a. `ContentReadModel.kt` (shared — moderate)
- **FILED exclusion** in the active-list queries (add `&& it.status != Status.FILED`):
  `inbox()`, `activeTasks()`, `dueTasks()`, `contextTasks()`; and in `projects()` add
  `it.status != "FILED"`. (FILED items must drop out of Inbox/Next/Projects but stay in
  Reference + search.) `children()` is deliberately NOT filtered (the reference tree reuses it).
- **`fun reference(root: Ulid?): List<NodeView>`** = `children(root)` filtered to
  `status == FILED` (or all children of the root; use `children(root)` and show FILED first).
  Root defaults handled by caller.
- **`fun search(query: String, limit: Int = 50): List<NodeView>`**:
  `store.search(query, limit).mapNotNull { node(it) }.filter { it.status != "DROPPED" }`.
  Empty query → empty list.

### 4b. `ContentCommands.kt` (shared — additive)
```kotlin
/** File a node into Reference (spec §7): archived + moved under the Reference root. */
fun file(node: Ulid, referenceRoot: Ulid) { setStatus(node, Fields.Status.FILED); move(node, referenceRoot) }
fun unfile(node: Ulid) = setStatus(node, "ACTIVE") // reopen; move back is a separate drag
```
(`setStatus` is already the private helper.)

### 4c. `Layout.kt` `Tab` enum (shared — STRUCTURAL, 4th tab)
Add `REFERENCE("/reference", "Reference", "📁")` and include it in the `tabBar` list
(`listOf(Tab.INBOX, Tab.NEXT, Tab.PROJECTS, Tab.REFERENCE)`). This is the only rebase-forcing
edit in #5 — land it in the same web worktree slot the sequencing plan reserves.

### 4d. `NodeViews.kt` (shared — new, disjoint section)
```kotlin
fun FlowContent.referenceSection(read: ContentReadModel, referenceRoot: Ulid?, query: String?) {
    h2 { +"Reference" }
    // Search box: Datastar v1 COLON syntax; debounced GET patches #reference-results.
    input(type = InputType.search) {
        attributes["data-bind:q"] = ""
        attributes["placeholder"] = "Search everything…"
        attributes["data-on:input__debounce.300ms"] = "@get('/reference/search?q='+encodeURIComponent(\$q))"
    }
    div { id = "reference-results"; referenceResults(read, referenceRoot, query) }
}

private fun FlowContent.referenceResults(read: ContentReadModel, root: Ulid?, query: String?) {
    if (!query.isNullOrBlank()) {
        val hits = read.search(query)
        if (hits.isEmpty()) p("muted") { +"No matches." } else ul { hits.forEach { li { nodeRow(it) } } }
    } else {
        val filed = read.reference(root)
        if (filed.isEmpty()) p("muted") { +"Nothing filed yet." } else ul { filed.forEach { li { nodeRow(it) } } }
    }
}
```
No new `nodeRow` variant — reuse the existing button row (search hits link to `/node/{id}`).
The inbox/detail "File" affordance (a button posting `/node/{id}/file`) is added to
`nodeDetail`'s organize section as an additive control (does not rewrite `nodeRow`, avoiding
the swipe/#4 contention on that function).

### 4e. `WebRoutes.kt` (shared — additive routes + one new param)
- Add param `reference: () -> Ulid? = { null }` to `fun Route.webRoutes(...)`.
- `get("/reference")`: `page("Reference", settingsHref, Tab.REFERENCE) { div { id="reference"; attributes["data-on:load"]="@get('/updates/reference')"; referenceSection(read, reference(), null) } }`.
- `get("/reference/search")`: read `q`, `respondDatastar(patchElementsEvent(renderFragment("reference-results") { referenceResults(read, reference(), q) }))`. (Search is a read; lives outside the `commands != null` block so it works read-only.)
- `sse("/updates/reference")` (inside `changes != null`): push `renderFragment("reference") { referenceSection(read, reference(), null) }` on change (mirrors `/updates/projects`).
- `post("/node/{id}/file")` (inside `commands != null`): `call.appliedDetail(id) { file(id, reference() ?: return@appliedDetail) }` — files then re-renders the detail fragment. Guard null reference root with a 400.

### 4f. `custom.css` (shared — additive)
- `nav.tabbar` currently lays out 3 tabs; make it robust to 4 (it uses flex `.tab { flex: 1 }`
  per the file — verify; if a fixed width/grid, widen to 4). Add a `#reference input[type=search]`
  rule (full-width, `min-height: 2.6rem`, margin) matching `.quick-add input`.

## 5. Server + phone wiring (non-shared)

- **`server/.../content/ServerContent.kt`**: after constructing `commands`, ensure the
  Reference root exists once — if `read.node(WellKnownNodes.REFERENCE_ROOT) == null`, emit
  `commands.rename(REFERENCE_ROOT, "Reference")` (a title write makes it alive/projectable;
  it stays at the tree root with no parent). Expose it for the route wiring.
- **`server/.../App.kt`** line ~101: pass `reference = { WellKnownNodes.REFERENCE_ROOT }` to
  `webRoutes(...)`.
- **`app/.../server/ZyncServer.kt`** `webRoutes(...)` call (~line 120): same `reference =`
  arg; ensure the phone content bootstrap (WebContent) also seeds the Reference-root title
  once so `/reference` isn't empty/dead. Phone `SqlDelightStateStore` gets FTS for free
  (same schema).
- **No changes to `SyncService` / `SyncClient` / `OpWriter`** — index maintenance is inside
  the store's `putRegister`/`putTombstone` (D1).
- **`server/.../DevServer.kt`**: optionally seed a couple of FILED reference items so the
  Playwright `/reference` spec has content.

## 6. Test plan

**core (commonTest):**
- `FtsQueryTest` — tokenization/quoting/prefix; empty/punctuation-only → null; injection-y
  input (`foo OR bar"`) yields a safe match string.
- `InMemoryStateStoreTest` (or extend) — `search()` finds by title/notes/summary token
  prefix; excludes non-searchable kinds, tombstoned, and blank-text nodes.

**data (jvmTest — real SQLite/FTS5):**
- `SqlDelightSearchTest` — apply ops (title/notes/summary), assert `search("term")` returns
  the id; rename → old term drops, new term hits (rebuild-on-write); tombstone → gone;
  `FILED` status still searchable; non-searchable kind (`context`) never indexed.
- `MigrationTest` — ADD `v5MigratesToV6WithSearchIndex`: hand-build a v5 DB with a `register`
  row, `PRAGMA user_version=5`, open → `Schema.version` (6), `search_index` exists and
  (after backfill) returns the row. Assert existing rows preserved.
- `SqlDelightStateStoreTest.schemaVersionBaselineForMigrationHarness` → `6L`.

**server (jvmTest):**
- `DurabilityTest.freshBootCreatesAtCurrentSchemaVersion` → `6L`; add a round-trip that
  writes a titled node and asserts `store.search(...)` finds it after a fresh boot / restore.

**web (jvmTest — ktor `testApplication`, `RecordingEmitter`/InMemory store):**
- `ReferenceRouteTest` — `GET /reference` renders the tab + search box; `GET /reference/search?q=`
  returns a `datastar-patch-elements` fragment targeting `#reference-results` with matching
  hits; empty `q` shows the filed tree.
- `FileCommandTest` — `POST /node/{id}/file` sets `status=FILED` + reparents under the
  reference root; the item leaves `inbox()`/`activeTasks()`/`projects()` but appears in
  `reference()` and `search()`.
- `NavTabsTest` — assert the 4th (Reference) tab renders and is active on `/reference`.

**Playwright (`webtest/` against `./gradlew :server:webDevServer`):**
- `reference.spec` — navigate to Reference tab; type a query in the search box; assert
  debounced results patch in over SSE/`@get`; assert a seeded FILED item is listed; file an
  inbox item via its detail "File" button and assert it appears under Reference and vanishes
  from Inbox. CSP: search uses only Datastar `data-on:*`/`data-bind:*` (colon syntax) and the
  vendored `datastar.js` asset — no inline script/style, so the loopback CSP is satisfied.

## 7. Shared files touched (for conflict sequencing)

| Shared file | Edit | Nature |
|---|---|---|
| `core/.../state/StateStore.kt` | `+ fun search(...)` | additive port method |
| `core/.../state/InMemoryStateStore.kt` | naive `search` impl | additive |
| `core/.../content/Fields.kt` | `+ object Status` (FILED), `+ WellKnownNodes.REFERENCE_ROOT` | additive — **coordinate FILED with #4** |
| `core/.../content/FtsQuery.kt` | NEW | isolated |
| `web/.../content/ContentReadModel.kt` | FILED filters in queries; `+ reference()`, `+ search()` | additive methods + small query edits (no `NodeView` struct change) |
| `web/.../content/ContentCommands.kt` | `+ file()/unfile()` | additive |
| `web/.../views/Layout.kt` | `+ Tab.REFERENCE` (4th tab) | **STRUCTURAL** — the one rebase-forcing edit |
| `web/.../views/NodeViews.kt` | `+ referenceSection`/`referenceResults`; "File" button in `nodeDetail` organize | disjoint (does NOT touch `nodeRow`/`inboxSection`) |
| `web/.../WebRoutes.kt` | `+ reference` param; `/reference`, `/reference/search`, `/updates/reference`, `/node/{id}/file` | additive routes + one new default param |
| `web/.../resources/custom.css` | 4-tab bar + search input | additive rules |
| `data/src/commonMain/sqldelight/**` | `SearchIndex.sq` (NEW) + `5.sqm` (NEW) → **Schema.version 6** | **global chokepoint** — serialize with #6's embeddings migration (which becomes `6.sqm`/v7) |
| version assertions (`SqlDelightStateStoreTest`, `DurabilityTest`; `MigrationTest` +case) | `5L → 6L` | must bump with the migration |

**Sequencing note:** the only rebase-forcing edits are `Tab.REFERENCE` (Layout.kt) and the
`data/` migration numbering — both isolated from the `nodeRow`/`inboxSection` contention that
#3/gestures/#4 fight over. Per `gtd-sequencing.md`, #5 can land as a mostly-disjoint
`#5-data` (data/ + store + migration + version bumps) followed by `#5-web` (read method +
surface + Tab), stacked after #6's embeddings migration is numbered to avoid a `.sqm`
collision (whichever schema change merges second takes the next version number and updates
the same three assertion sites).

## 8. Risks

- **Android FTS5 availability.** FTS5 is compiled into Android's system SQLite on modern API
  levels but is not guaranteed on very old ones. Mitigation: the phone already ships a
  SQLDelight driver; if the system SQLite lacks FTS5, switch the Android driver to a bundled
  SQLite (requery/androidx-sqlite-bundled) so FTS5 is always present. Verify on the min-SDK
  target; add a boot self-check that the `search_index` table is queryable.
- **SQLDelight 2.1.0 FTS5 parsing.** The SQLite dialect must accept `CREATE VIRTUAL TABLE …
  USING fts5(…)` and the `MATCH` query. Confirmed supported in 2.x; if the compiler rejects
  the `tokenize=` option, fall back to bare `fts5(entity_id UNINDEXED, title, notes, summary, ocr)`.
- **Well-known ULID literal.** Must be a valid 26-char Crockford-base32 that `Ulid.parse`
  accepts; pick a memorable-but-valid constant and add a `Ulid.parse` round-trip test.
</content>
</invoke>

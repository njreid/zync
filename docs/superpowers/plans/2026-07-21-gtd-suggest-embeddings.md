# Build order #6 — file-location suggestion operator + server embeddings + DONE→auto-file (plan)

> Status: PLAN (design only). Implements GTD triage spec §6 (file-location suggestions,
> RESOLVED Q4 = up-to-3 score-ranked, any mix, confidence floor) and §7 / RESOLVED Q5
> (DONE→auto-file-to-Reference, operator PROPOSES, human accepts). Embeddings are
> server-only, SQLite blob + brute-force cosine (RESOLVED Q7). Mirrors the summarize
> operator. Depends on build order #5 (Reference tree + FTS5) for the keyword half;
> degrades gracefully to embedding-only if that index is absent (see §7).

## 0. Shape

```
INBOX item text ──► suggest-file operator (server) ──► fileSuggestions field (≤3 ranked)
                         │  FTS5 keyword ⊕ embedding cosine over Reference/Projects trees
                         └─► chips in expanded inbox row; accept a chip = human Move

task → DONE ──► auto-file-done operator (server) ──► proposedFileParent field (Reference-only, top-1)
                         └─► "File to <area>?" accept/reject; accept = Move under Reference + status=FILED
```

Both operators are LLM-shaped only in plumbing: the *scoring* is deterministic server
retrieval (FTS5 + cosine), not an LLM decision. We reuse the whole OperatorRuntime
lifecycle (readScope match → idempotency → typed output → write-scope → fuel → provenance)
by making the "completion" pluggable per operator (§4). Operators can only emit
`Op.SetField` on their firing entity (the runtime builds nothing else), so suggestions are
operator-owned **fields** on the inbox item — surfaced as the agent-proposal accept/reject
UX, where accepting is the human `Move` (spec §6). This mirrors how `auto-clarify` surfaces
`suggestedContext` and `summarize` surfaces `summary`.

---

## 1. Embedding model — TBD, abstracted behind a port

Anthropic offers **no embeddings API** (the platform is `POST /v1/messages` only; confirmed
against the claude-api reference — there is no `/v1/embeddings`). So the embedding backend is
a separate provider, kept behind an `EmbeddingClient` port and left configurable:

- **API option:** Voyage AI (Anthropic's documented embeddings recommendation), e.g.
  `voyage-3.5` / `voyage-3.5-lite`, 1024-dim, via HTTPS. Enabled by `ZYNC_VOYAGE_API_KEY`.
- **Local option (zero external egress, fits "server-only" posture best):** an ONNX/DJL
  sentence-transformer such as `all-MiniLM-L6-v2` (384-dim) run on the JVM.

The port makes the choice swappable and the operator degrades to FTS5-only when unset,
exactly like operators disable without `ANTHROPIC_API_KEY`. **Model id + dims are stored
alongside every vector (§3) so a model swap invalidates stale vectors** (treated as absent →
re-embed). Recommend starting with the local MiniLM impl to keep document text off third
parties (consistent with the trusted-server model); leave the concrete impl a follow-up.

---

## 2. Core vocabulary — `core/.../content/Fields.kt` (SHARED, additive)

Add three constants (LWW `SetField`, no new op types):

```kotlin
/** Operator-owned JSON array of ranked file-location proposals for an inbox item
 *  (GTD triage §6). Each element: {"targetId","title","tree","score"}; empty/absent
 *  = no confident suggestion. Accepting a chip is the human Move; owner: Operator("suggest-file"). */
const val FILE_SUGGESTIONS = "fileSuggestions"

/** Operator-owned single Reference-tree node id proposed as the filing parent for a
 *  DONE task (RESOLVED Q5). Accepting = Move under it + status FILED; owner: Operator("auto-file-done"). */
const val PROPOSED_FILE_PARENT = "proposedFileParent"
```

Also add a `FILED` value where status constants live (spec §1 extends status to
`ACTIVE|WAITING|DONE|DROPPED|FILED`). If no status-value object exists yet, add:

```kotlin
object StatusValues { const val ACTIVE="ACTIVE"; const val WAITING="WAITING"
    const val DONE="DONE"; const val DROPPED="DROPPED"; const val FILED="FILED" }
```

The **Reference root** is a well-known node id (like the inbox root), threaded as config, not
a field — see §5/§8.

---

## 3. Embeddings storage + similarity — `data/` (SHARED SCHEMA — version bump required)

New SQLDelight file `data/src/commonMain/sqldelight/dev/njr/zync/data/db/NodeEmbedding.sq`:

```sql
-- Server-only semantic index (GTD triage §7, RESOLVED Q7: SQLite blob + brute-force cosine).
-- One row per indexed Reference/Projects node; `vector` is little-endian float32 packed to
-- BLOB. `model`+`dims` gate staleness on a model swap. Not synced to the phone.
CREATE TABLE node_embedding (
  node_id     TEXT NOT NULL PRIMARY KEY,
  model       TEXT NOT NULL,
  dims        INTEGER NOT NULL,
  vector      BLOB NOT NULL,
  text_hash   TEXT NOT NULL,           -- sha256 of the embedded text; skip re-embed if unchanged
  updated_wall INTEGER NOT NULL
);

upsertEmbedding:
INSERT OR REPLACE INTO node_embedding(node_id, model, dims, vector, text_hash, updated_wall)
VALUES (?, ?, ?, ?, ?, ?);

deleteEmbedding:
DELETE FROM node_embedding WHERE node_id = ?;

embeddingTextHash:
SELECT text_hash FROM node_embedding WHERE node_id = ? AND model = ?;

-- Candidate scan for cosine (bounded by model match): only same-model vectors are comparable.
embeddingsForModel:
SELECT node_id, vector, dims FROM node_embedding WHERE model = ?;
```

Migration `data/src/commonMain/sqldelight/dev/njr/zync/data/db/migrations/5.sqm` (v5→v6):

```sql
-- v5 → v6: server-only semantic index for file-location suggestions (GTD triage §7).
CREATE TABLE node_embedding (
  node_id TEXT NOT NULL PRIMARY KEY, model TEXT NOT NULL, dims INTEGER NOT NULL,
  vector BLOB NOT NULL, text_hash TEXT NOT NULL, updated_wall INTEGER NOT NULL
);
```

**Version-assertion bumps (MANDATORY on any schema change):**
- `data/src/jvmTest/kotlin/dev/njr/zync/data/SqlDelightStateStoreTest.kt:59` — `assertEquals(6L, ZyncDatabase.Schema.version)`
- `server/src/test/kotlin/dev/njr/zync/server/durability/DurabilityTest.kt:65` — `assertEquals(6L, ZyncDatabase.Schema.version)`
- **Do NOT touch** `SyncServiceTest.kt:53` / `OplogCompactorTest.kt:96` — those `5L`s are seq/cursor
  counts, not the schema version.

Similarity helper (server, pure) `server/.../operator/CosineIndex.kt`:

```kotlin
object VectorCodec {                       // little-endian float32 pack/unpack
    fun encode(v: FloatArray): ByteArray
    fun decode(bytes: ByteArray, dims: Int): FloatArray
}
fun cosine(a: FloatArray, b: FloatArray): Double   // dot / (||a||·||b||); 0 if either is zero
```

Vectors are L2-normalized at store time so cosine is a dot product; brute-force scan over
`embeddingsForModel(model)` is fine at personal scale (no ANN index for v1).

---

## 4. Operator runtime — generalize the completion step (SHARED, backward-compatible)

`server/.../operator/OperatorRuntime.kt` currently hardcodes `llm.complete(request)`. Add a
per-operator completion source so a *retrieval* operator flows through the exact same
typed-output / write-scope / fuel / idempotency / provenance machinery.

New port `server/.../operator/CompletionSource.kt`:

```kotlin
/** Produces one typed-output attempt for an operator firing. The LLM port is one impl;
 *  retrieval operators (suggest-file, auto-file-done) are another. Same LlmReply contract:
 *  Text(json) → validated as the operator's OutputSchema; Unavailable → abort without
 *  recording (retry next trigger). */
fun interface CompletionSource {
    fun complete(request: LlmRequest, snapshot: EntitySnapshot): LlmReply
}

/** Adapts the existing LlmClient (ignores the snapshot). Default for every operator. */
class LlmCompletionSource(private val llm: LlmClient) : CompletionSource {
    override fun complete(request: LlmRequest, snapshot: EntitySnapshot) = llm.complete(request)
}
```

`OperatorRuntime` constructor gains one optional param (no churn for existing callers):

```kotlin
class OperatorRuntime(
    ...,
    private val llm: LlmClient,
    private val completers: Map<String, CompletionSource> = emptyMap(), // by operator id
    ...
)
```

In `fire()`, replace the single `llm.complete(request)` call:

```kotlin
val source = completers[m.id] ?: LlmCompletionSource(llm)
when (val reply = source.complete(request, snapshot)) { ... }   // rest of the loop unchanged
```

Everything else — retries, `OutputSchema.evaluate`, `WriteScope.permits`, fuel caps, the
`operator_run(operator, entity, V)` idempotency ledger, `Actor.Operator(id)` provenance — is
reused verbatim. This is the whole "mirror the summarize operator" ask.

Alternative considered & rejected: a second bespoke runtime for retrieval. It would
re-implement idempotency/fuel/provenance and drift; the ~10-line completer injection above is
strictly less machinery.

---

## 5. Read scopes — `server/.../operator/ReadScope.kt` (SHARED, additive)

Add two named scopes + register in `ReadScopeResolver.default()`:

```kotlin
const val INBOX_TRIAGE_REF = "inbox-triage"
const val DONE_TASK_REF    = "done-task"

/** Inbox items awaiting a filing suggestion: root, still-ACTIVE task. Reads the query text
 *  fields (title/notes/summary). writeScope (fileSuggestions) ∉ reads → own output can't
 *  change V (verified at construction). Re-fires on text edits (EntityChangesInScope). */
val inboxTriage = ReadScope(INBOX_TRIAGE_REF,
    reads = setOf(Fields.KIND, Fields.STATUS, Fields.TITLE, Fields.NOTES, Fields.SUMMARY)
) { s -> s.alive && s.parent == null &&
        s.fields[Fields.KIND].asString() == "task" &&
        (s.fields[Fields.STATUS].asString() ?: "ACTIVE") == "ACTIVE" }

/** A task just marked DONE: propose a Reference filing home. Reads status + text. */
val doneTask = ReadScope(DONE_TASK_REF,
    reads = setOf(Fields.KIND, Fields.STATUS, Fields.TITLE, Fields.NOTES, Fields.SUMMARY)
) { s -> s.alive && s.fields[Fields.KIND].asString() == "task" &&
        s.fields[Fields.STATUS].asString() == "DONE" }
```

Scope disjointness: `inboxTriage` requires ACTIVE, `doneTask` requires DONE — no double-fire.
Neither writes a field it reads, and their writes never trigger each other → `CascadeGraph`
passes.

**Known v1 limitation** (documented, acceptable): V covers only the *inbox item's* read
fields, not the Reference/Projects tree contents. If the tree changes but the item text
doesn't, no re-suggestion — suggestions are computed at triage time, which is soon after
capture. Fine for v1.

---

## 6. Manifests — `server/.../operator/OperatorManifests.kt` (SHARED, additive)

```kotlin
fun suggestFileLocations(): OperatorManifest = OperatorManifest(
    id = "suggest-file", name = "Suggest file locations",
    readScope = ReadScopeHandle(ReadScopes.INBOX_TRIAGE_REF),
    writeScope = WriteScope(fields = setOf(Fields.FILE_SUGGESTIONS)),
    trigger = TriggerKind.EntityChangesInScope,
    output = OutputSchema(
        fields = mapOf(Fields.FILE_SUGGESTIONS to FieldType.Array),
        required = setOf(Fields.FILE_SUGGESTIONS)),   // empty array is valid (floor filtered all)
    retries = 0,                                       // deterministic retrieval; no reroll
    fuel = Fuel(maxOpsPerFiring = 1, maxOpsPerCascade = 8))

fun autoFileDone(): OperatorManifest = OperatorManifest(
    id = "auto-file-done", name = "File completed task to Reference",
    readScope = ReadScopeHandle(ReadScopes.DONE_TASK_REF),
    writeScope = WriteScope(fields = setOf(Fields.PROPOSED_FILE_PARENT)),
    trigger = TriggerKind.EntityChangesInScope,
    output = OutputSchema(
        fields = mapOf(Fields.PROPOSED_FILE_PARENT to FieldType.String),
        required = emptySet()),                        // absent = below floor, no proposal
    retries = 0, fuel = Fuel(maxOpsPerFiring = 1, maxOpsPerCascade = 8))
```

`fromEnv()` returns `listOf(autoClarifyInbox(), summarize(), suggestFileLocations(), autoFileDone()) + extra`.

Note: `OutputSchema.validate` only checks top-level field *types* (`FieldType.Array` = is
JsonArray). Element shape is guaranteed by the retrieval source that builds it, so validation
is trivially satisfied — the typed-output loop still guards against a malformed source.

---

## 7. Retrieval engine — `server/.../operator/FileLocationSuggester.kt` (new)

The scoring core, and the two `CompletionSource` impls the runtime plugs in.

```kotlin
data class Candidate(val nodeId: Ulid, val title: String, val tree: String) // "projects"|"reference"

class ReferenceIndex(
    private val store: StateStore,
    private val db: ZyncDatabase,
    private val embed: EmbeddingClient?,           // null → embedding-only disabled
    private val ftsSearch: ((String) -> Map<String, Double>)? = null, // from build #5; null → FTS off
    private val referenceRoot: Ulid?,
    private val model: String,
    private val floor: Double = 0.35,              // ZYNC_SUGGEST_FLOOR
    private val kwWeight: Double = 0.5,
) {
    /** Up to 3 blended-score candidates ≥ floor, over Reference+Projects subtrees, excluding the
     *  item, its ancestors, and its current parent. FTS5 bm25 (normalized) ⊕ query-vs-candidate
     *  cosine; whichever signal is available is used (both, either, or none → empty). */
    fun rank(query: String, exclude: Ulid, treeFilter: Set<String>, limit: Int = 3): List<Pair<Candidate, Double>>
}
```

Scoring:
1. Query text = item `title + "\n" + notes + "\n" + summary` (+ OCR text if present via blob,
   reusing `OperatorPrompt`'s blob expansion — extracted OCR/ASR feeds the index per §6).
2. **Keyword**: `ftsSearch(query)` → per-node bm25; min-max normalize to [0,1].
3. **Embedding**: `embed.embed([query])` → one vector; `cosine` vs each candidate's stored
   vector (`embeddingsForModel(model)`); map [-1,1] → [0,1].
4. `blended = kwWeight*kw + (1-kwWeight)*emb` (single-signal → that signal alone).
5. Filter to alive Reference/Projects nodes, drop the item/ancestors/current-parent, take
   top-`limit` with `blended ≥ floor`.

Completion sources:

```kotlin
class SuggestFileCompletionSource(private val index: ReferenceIndex, private val blobText: (String)->String?)
  : CompletionSource {
    override fun complete(req: LlmRequest, snap: EntitySnapshot): LlmReply {
        val ranked = index.rank(queryText(snap, blobText), exclude = snap.entityId,
                                treeFilter = setOf("projects","reference"))
        return LlmReply.Text(buildJsonArray {  // [] when none clear the floor
            ranked.forEach { (c, s) -> add(buildJsonObject {
                put("targetId", c.nodeId.toString()); put("title", c.title)
                put("tree", c.tree); put("score", s) }) }
        }.let { """{"fileSuggestions":$it}""" })
    }
}
class AutoFileCompletionSource(private val index: ReferenceIndex, ...) : CompletionSource {
    override fun complete(...): LlmReply {
        val top = index.rank(query, exclude, treeFilter = setOf("reference"), limit = 1).firstOrNull()
        return LlmReply.Text(
            if (top != null) """{"proposedFileParent":"${top.first.nodeId}"}""" else "{}")
    }
}
```

If the embedding backend is momentarily unreachable (not merely unconfigured), return
`LlmReply.Unavailable(...)` so the firing aborts without recording and retries on the next
trigger — same semantics the runtime already gives the LLM path.

**Embedding indexer** `server/.../operator/EmbeddingIndexer.kt` (`OpIngestHook`): on ingested
`SetField` for title/notes/summary of a Reference/Projects node, recompute the node's text,
compare `sha256` to `embeddingTextHash(nodeId, model)`, and on change enqueue a background
re-embed → `upsertEmbedding`; on `Tombstone`/move-out-of-tree → `deleteEmbedding`. Runs on the
same single background executor as the operator cascade so the ingest path stays fast.

FTS5 dependency: the `ftsSearch` closure is provided by build order #5's `node_fts`. If #6
lands first, ship a minimal `node_fts` FTS5 virtual table + a `NodeFts.sq` here (flagged for
merge with #5) — but the design degrades to embedding-only when `ftsSearch` is null, so #6 is
independently shippable.

---

## 8. Prompt & wiring — `OperatorPrompt.kt` (unchanged) + `Main.kt` (additive)

`OperatorPrompt` is untouched: retrieval sources don't use `request.system/user`. (They read
the snapshot directly.) The runtime still builds a `LlmRequest` for them — harmless.

`server/src/main/kotlin/dev/njr/zync/server/Main.kt` `wireOperators()`: after building `blobs`
and the runtime, construct the embedding backend from env and register the two completers:

```kotlin
val embed = EmbeddingClient.fromEnv()               // null unless configured
val refRoot = System.getenv("ZYNC_REFERENCE_ROOT")?.let(Ulid::parse)
val index = ReferenceIndex(service.stateStore, db, embed, ftsSearch = /*from #5*/ null, refRoot, model = embed?.model ?: "none")
val completers = mapOf(
    "suggest-file"  to SuggestFileCompletionSource(index, blobText),
    "auto-file-done" to AutoFileCompletionSource(index, blobText))
val runtime = OperatorRuntime(db, service.stateStore, OperatorManifests.fromEnv(),
    ReadScopeResolver.default(), llm, service::ingestLocal, blobText, completers = completers)
hook.delegate = CompositeIngestHook(listOf(EmbeddingIndexer(db, service.stateStore, embed, refRoot), runtime))
```

`CompositeIngestHook` (trivial fan-out `OpIngestHook`) runs the indexer then the runtime.
Suggestion operators still work with `ANTHROPIC_API_KEY` unset — they don't call the LLM — so
`wireOperators` must build the runtime for the suggest/auto-file operators even when `llm ==
null` (register them with a no-LLM runtime; the `auto-clarify`/`summarize` LLM operators stay
disabled). Adjust the early `return` accordingly.

---

## 9. Web read model + commands (SHARED, additive)

`web/.../content/ContentReadModel.kt`:
- `NodeView` gains `fileSuggestions: List<FileSuggestion>` and `proposedFileParent: Ulid?`.
- `data class FileSuggestion(val targetId: Ulid, val title: String, val tree: String, val score: Double)`.
- `toView()` parses `Fields.FILE_SUGGESTIONS` (JSON array; tolerate malformed → empty) and
  `Fields.PROPOSED_FILE_PARENT`.

`web/.../content/ContentCommands.kt`:
```kotlin
/** Accept a filing chip: the human Move (spec §6), then clear the operator field. */
fun acceptFileSuggestion(node: Ulid, target: Ulid) { ops.move(node, target)
    ops.setField(node, Fields.FILE_SUGGESTIONS, JsonNull) }
fun dismissFileSuggestions(node: Ulid) = ops.setField(node, Fields.FILE_SUGGESTIONS, JsonNull)
/** Accept the Reference filing proposal (RESOLVED Q5): Move + status FILED + clear. */
fun acceptProposedFile(node: Ulid, target: Ulid) { ops.move(node, target)
    ops.setField(node, Fields.STATUS, JsonPrimitive("FILED"))
    ops.setField(node, Fields.PROPOSED_FILE_PARENT, JsonNull) }
fun rejectProposedFile(node: Ulid) = ops.setField(node, Fields.PROPOSED_FILE_PARENT, JsonNull)
```
(Accept/dismiss are human ops clearing an operator-owned field — same shape as
`acceptProposal`. Move is the human action the spec calls for.)

## 10. Views + routes + CSS (SHARED, additive)

`web/.../views/NodeViews.kt`:
- In the expanded inbox row / `nodeDetail organizeSection`, render `node.fileSuggestions` as
  up-to-3 one-tap chips (reuse `.chip` styling): label `"→ {title}"`, `data-on:click =
  "@post('/node/${node.id}/file?target=${s.targetId}')"`, plus a "dismiss" chip
  (`/node/${id}/file-dismiss`). Nothing renders when the list is empty (below floor).
- For a DONE task with `proposedFileParent`, render a subtle "File to <area>? [Accept] [No]"
  banner (`/node/${id}/file-done?target=...` and `/node/${id}/file-done-reject`).
- Datastar v1 COLON syntax (`data-on:click`), no inline styles/scripts (loopback CSP).

`web/.../WebRoutes.kt` (mutation block, alongside `/proposal/{id}/accept`):
```kotlin
post("/node/{id}/file")         { /* target from query → acceptFileSuggestion; re-render #inbox */ }
post("/node/{id}/file-dismiss") { /* dismissFileSuggestions */ }
post("/node/{id}/file-done")    { /* acceptProposedFile(target) */ }
post("/node/{id}/file-done-reject") { /* rejectProposedFile */ }
```
`web/src/commonMain/resources/custom.css`: reuse existing `.chip`/`.action`; add a `.suggest-chip`
modifier and a `.file-banner` rule if needed (served as a FILE — no inline style).

---

## 11. Test plan

JVM (fast, no gradle daemon contention beyond normal):
- **`server/.../operator/SuggestFileEndToEndTest`** — mirror `SummarizeEndToEndTest` via
  `OperatorHarness` with a fake `EmbeddingClient` + seeded `node_embedding` rows and a stub
  `ftsSearch`. Assert: (a) an inbox item yields ≤3 `fileSuggestions`, score-ranked, provenance
  `Operator("suggest-file")`, seq-assigned; (b) below-floor → empty array, no chips; (c)
  editing title re-fires (new V), redelivery does not; (d) DONE item is out of scope (no fire);
  (e) fuel cap (1 op/firing) respected.
- **`server/.../operator/AutoFileDoneEndToEndTest`** — DONE task → single `proposedFileParent`
  (Reference-only); below floor → `{}` (field absent); re-open (status→ACTIVE) leaves scope.
- **`OperatorHarness`** helpers: `referenceNode(...)`, `putEmbedding(node, vec)`,
  `captureTask` (exists) reused; extend to seed `node_embedding`.
- **`data/.../MigrationTest`** — add `v5..v6` case: hand-build v5, assert `node_embedding`
  exists post-migration and prior rows survive (mirror the v1→v2 case).
- **`data/.../SqlDelightStateStoreTest`** + **`server/.../durability/DurabilityTest`** — bump
  schema-version assertions 5L → 6L.
- **`server/.../operator/CosineIndexTest`** (core-style pure test) — `VectorCodec` round-trip;
  `cosine` correctness (orthogonal=0, identical=1, zero-vector guarded).
- **`web/.../content/SuggestionsReadModelTest`** — `RecordingEmitter`: write a
  `fileSuggestions` field, assert `NodeView.fileSuggestions` parses (and malformed→empty);
  `acceptFileSuggestion` emits a Move + clears the field; `acceptProposedFile` sets FILED.
- **Route test** (io.ktor `testApplication`, `RecordingEmitter`) — `/node/{id}/file` moves the
  node and re-renders `#inbox`.

Playwright (`webtest/` against `./gradlew :server:webDevServer`):
- Seed a Reference tree + an inbox item with a `fileSuggestions` field; expand the item; assert
  ≤3 chips render; click a chip → item leaves the inbox and appears under the target project.

---

## 12. SHARED files touched — conflict sequencing

| File | Change | Sequencing note |
|---|---|---|
| `core/.../content/Fields.kt` | +`FILE_SUGGESTIONS`, +`PROPOSED_FILE_PARENT`, +`StatusValues.FILED` | additive; land first |
| `data/.../db/NodeEmbedding.sq` + `migrations/5.sqm` | new table + migration | **schema change → bump v6** |
| `data/.../SqlDelightStateStoreTest.kt` (L59) | `5L`→`6L` | with the migration |
| `server/.../durability/DurabilityTest.kt` (L65) | `5L`→`6L` | with the migration |
| `server/.../operator/OperatorRuntime.kt` | +`completers` param, completer dispatch in `fire()` | backward-compatible; existing callers unaffected |
| `server/.../operator/ReadScope.kt` | +`inboxTriage`, +`doneTask` scopes | additive |
| `server/.../operator/OperatorManifests.kt` | +`suggestFileLocations()`, +`autoFileDone()`, `fromEnv` | additive |
| `server/.../operator/OperatorPrompt.kt` | unchanged (retrieval bypasses prompt) | — |
| `server/.../Main.kt` | `wireOperators`: embed client, completers, CompositeIngestHook | additive |
| `web/.../content/ContentReadModel.kt` | `NodeView` + parse | additive |
| `web/.../content/ContentCommands.kt` | +4 accept/dismiss commands | additive |
| `web/.../views/NodeViews.kt` | chips + DONE banner | additive |
| `web/.../WebRoutes.kt` | +4 POST routes | additive |
| `web/src/commonMain/resources/custom.css` | `.suggest-chip`/`.file-banner` | additive; served as FILE |

New (non-conflicting) files: `CompletionSource.kt`, `FileLocationSuggester.kt` (+ReferenceIndex),
`EmbeddingClient.kt`, `EmbeddingIndexer.kt`, `CosineIndex.kt`, `CompositeIngestHook.kt`,
`NodeEmbedding.sq`, tests.

Dependency on build order #5 (Reference tree + FTS5): only the *keyword* half; embedding-only
degradation keeps #6 shippable independently.

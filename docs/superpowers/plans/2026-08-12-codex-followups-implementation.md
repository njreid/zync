# Codex Review Followups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 9 items confirmed still-real in `docs/superpowers/plans/2026-08-12-codex-review-followups.md`, each scoped to the *minimal correct fix* for the actual diagnosed problem — not speculative rewrites.

**Architecture:** Nine independent tasks across `core`/`server`/`web`/`app`. Ordered smallest/safest → largest/riskiest so an early abort still leaves value landed. Tasks 1-2 are one-line-scale. Tasks 3-4 add small new persistence/index surfaces. Tasks 5-6 are Android bug fixes. Tasks 7-8 are moderate refactors. Task 9 is the largest and most open-ended — deliberately scoped down from "extract everything" to "extract the most tangled cluster" to keep it reviewable and low-risk.

**Tech Stack:** Kotlin/Ktor server, SQLDelight, kotlinx.html/Datastar web UI, Android/Kotlin app.

## Global Constraints

- Every task's fix must be the **minimal** change that corrects the diagnosed problem — no drive-by rewrites, no "while I'm here" scope creep, matching each file's existing style/conventions.
- `core`'s `Op` sealed type has **golden-locked serialization** (README) — any task touching `core/.../op/Op.kt` must run the core test suite (`./gradlew :core:jvmTest`) and confirm no serialization golden-file test broke.
- Android tasks (5, 6, and any Android-side pieces of 9) **cannot be compiled or run in this environment** — no Android SDK configured (confirmed earlier). Verification for those is careful manual symbol/logic cross-check against the actual codebase, documented in the report — same bar as the FCM plan's Task 7.
- One documented behavior must NOT be "fixed": `ShareImport.kt`'s image-labeled-as-attachment-type-PDF is an intentional, documented pragma ("the same pragma as the camera path until AttachmentType grows an IMAGE" — `ShareImport.kt:12-14`). Task 6 must preserve it exactly, not change it.
- Server tasks that touch concurrency (3) must account for the server handling concurrent requests — `HlcGenerator` itself is explicitly **not thread-safe** (`core/.../clock/Hlc.kt:47`); any shared instance needs external synchronization.
- Run the full relevant test suite (not just the new/changed test) before reporting a task DONE, given several of these touch shared/widely-used code paths.

---

### Task 1: Shared `Op` type-name accessor (item 8)

**Files:**
- Create: `core/src/commonMain/kotlin/dev/njr/zync/core/op/OpTypeName.kt`
- Modify: `app/src/main/kotlin/dev/njr/zync/replica/OpWriter.kt:85`
- Modify: `server/src/main/kotlin/dev/njr/zync/server/sync/SyncService.kt:155`
- Test: `core/src/commonTest/kotlin/dev/njr/zync/core/op/OpTypeNameTest.kt`

**Interfaces:**
- Produces: `val Op.typeName: String` (extension property on `core`'s `Op` sealed class) — consumed by `OpWriter.kt` and `SyncService.kt`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.njr.zync.core.op

import dev.njr.zync.core.clock.Hlc
import kotlin.test.Test
import kotlin.test.assertEquals

class OpTypeNameTest {
    private val hlc = Hlc(1, 0, "d")

    @Test
    fun typeNameMatchesTheSerialNameForEverySubtype() {
        assertEquals("set_field", Op.SetField(id(1), id(2), EntityType.Node, hlc, Actor.Human, "d", 0, "f", dev.njr.zync.core.op.testValue()).typeName)
        assertEquals("move", Op.Move(id(1), id(2), EntityType.Node, hlc, Actor.Human, "d", 0, id(3)).typeName)
        assertEquals("add_tag", Op.AddTag(id(1), id(2), EntityType.Tag, hlc, Actor.Human, "d", 0, id(3)).typeName)
        assertEquals("remove_tag", Op.RemoveTag(id(1), id(2), EntityType.Tag, hlc, Actor.Human, "d", 0, id(3)).typeName)
        assertEquals("add_attachment", Op.AddAttachment(id(1), id(2), EntityType.Attachment, hlc, Actor.Human, "d", 0, dev.njr.zync.core.op.testValue()).typeName)
        assertEquals("tombstone", Op.Tombstone(id(1), id(2), EntityType.Node, hlc, Actor.Human, "d", 0).typeName)
    }
}
```

Look at how existing `core` tests construct a `Ulid` (`id(n)` or similar helper — check `core/src/commonTest/kotlin/dev/njr/zync/core/` for the exact helper name/import already used by sibling tests, e.g. `ApplyTest.kt` or `ConformanceTest.kt` in `core/src/commonTest/kotlin/dev/njr/zync/core/merge/`) and adjust the test's id/value construction to match those exact existing helpers rather than inventing new ones — do not add a new `testValue()`/`id()` helper if equivalents already exist in that test source set; reuse them.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "dev.njr.zync.core.op.OpTypeNameTest"`
Expected: FAIL — compile error, `Op.typeName` doesn't exist yet.

- [ ] **Step 3: Add the extension property**

Create `core/src/commonMain/kotlin/dev/njr/zync/core/op/OpTypeName.kt`:

```kotlin
package dev.njr.zync.core.op

/**
 * The op's wire type name — identical to its `@SerialName` discriminator in [Op]. A single
 * source of truth for callers (audit logging, the transport-log `op_type` column) that
 * previously each independently derived this via `op::class.simpleName`, which drifts from
 * the actual serialized discriminator and isn't guaranteed stable across Kotlin versions.
 */
val Op.typeName: String
    get() = when (this) {
        is Op.SetField -> "set_field"
        is Op.Move -> "move"
        is Op.AddTag -> "add_tag"
        is Op.RemoveTag -> "remove_tag"
        is Op.AddAttachment -> "add_attachment"
        is Op.Tombstone -> "tombstone"
    }
```

The `when` is exhaustive over the sealed class with no `else` branch — if a new `Op` subtype is ever added, this fails to compile until `typeName` is updated too, closing exactly the "future op subtype missed in one path" risk the original review named.

Then update the two call sites. In `app/src/main/kotlin/dev/njr/zync/replica/OpWriter.kt`, find the line using `op::class.simpleName` (around line 85) and replace it with `op.typeName` (add `import dev.njr.zync.core.op.typeName` if the file doesn't already have a wildcard/relevant import). Do the same in `server/src/main/kotlin/dev/njr/zync/server/sync/SyncService.kt` around line 155.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "dev.njr.zync.core.op.OpTypeNameTest"`
Expected: PASS

- [ ] **Step 5: Run the full core, server, and app-relevant test suites**

Run: `./gradlew :core:jvmTest :server:test`
Expected: BUILD SUCCESSFUL, no regressions, no golden-serialization test broken (this change doesn't touch `Op`'s `@Serializable`/`@SerialName` annotations at all, only adds an unrelated extension property, but confirm anyway per the global constraint).

`OpWriter.kt` is Android app code — no compile check possible here; do a careful manual diff review confirming the import and call-site edit are syntactically correct Kotlin.

- [ ] **Step 6: Commit**

```bash
git add core/src/commonMain/kotlin/dev/njr/zync/core/op/OpTypeName.kt \
        core/src/commonTest/kotlin/dev/njr/zync/core/op/OpTypeNameTest.kt \
        app/src/main/kotlin/dev/njr/zync/replica/OpWriter.kt \
        server/src/main/kotlin/dev/njr/zync/server/sync/SyncService.kt
git commit -m "fix: shared Op.typeName replaces op::class.simpleName duplication"
```

---

### Task 2: Remove the double `notifyChanged()` (item 9)

**Files:**
- Modify: `web/src/commonMain/kotlin/dev/njr/zync/web/WebRoutes.kt`
- Test: existing web/server tests that assert on change-notification count (see Step 1)

**Interfaces:**
- None new — this is a deletion.

- [ ] **Step 1: Find or write a test proving single-notification**

First check whether a test already asserts `ChangeNotifier` fires exactly once per mutation (search `grep -rn "notifyChanged\|ChangeNotifier" web/src/commonTest server/src/test` for an existing test using a `ChangeNotifier` + counting emissions via its `changes: SharedFlow<Unit>`, e.g. collecting into a list with a short timeout, or checking `tryEmit` call count via a spy). If one exists and currently passes despite the double-call bug (because it doesn't count precisely), tighten its assertion to fail on double-emission. If none exists, add one: build a minimal `ContentCommands`-backed mutation through `WebRoutes`' `applied {}` path (find how existing `WebRoutes` tests set up a `testApplication` with a real `ChangeNotifier` — check `server/src/test/kotlin/dev/njr/zync/server/` for a test hitting an `applied`-wrapped route, e.g. a mutation POST route test) with a `ChangeNotifier` wired in, perform ONE mutation over HTTP, then assert `changes.changes` emitted exactly once (e.g. collect with `Channel`/`toList()` over a bounded timeout, or wrap `ChangeNotifier` in a counting spy for the test only).

- [ ] **Step 2: Run test to verify it fails (or confirm it doesn't yet exist/catch this)**

Run the new/tightened test. Expected: FAIL (or the test didn't exist) — two emissions observed where one is expected.

- [ ] **Step 3: Remove the redundant call**

In `web/src/commonMain/kotlin/dev/njr/zync/web/WebRoutes.kt`, find the `applied {}` helper (around lines 259-264). It currently reads approximately:

```kotlin
private suspend fun ApplicationCall.applied(changes: ChangeNotifier?, block: () -> Unit) {
    block()  // commands.mutate() inside here already routes through onIngest -> notifyChanged()
    changes?.notifyChanged()   // <-- DELETE THIS LINE
    ...
}
```

Delete the explicit `changes?.notifyChanged()` call. Confirm by reading the surrounding function in full first (don't blind-delete based on this plan's paraphrase — read the actual current lines and delete only the redundant notify call, leaving everything else in `applied {}` — response handling, error mapping, etc. — untouched).

- [ ] **Step 4: Run test to verify it passes**

Expected: PASS — exactly one emission per mutation.

- [ ] **Step 5: Run the full web and server test suites**

Run: `./gradlew :web:jvmTest :server:test` (adjust module test task names if `:web` uses a different one — check `web/build.gradle.kts` / run `./gradlew :web:allTests` if that's the KMP convention used elsewhere in this repo, matching how `:core:allTests` is described in the README).
Expected: BUILD SUCCESSFUL, no regressions. Specifically check no existing test relied on the double-notification (e.g. a test that happened to pass because of a race between two notifications — if any such test breaks, that's a sign of an actual dependency and must be investigated, not worked around by reverting this fix).

- [ ] **Step 6: Commit**

```bash
git add web/src/commonMain/kotlin/dev/njr/zync/web/WebRoutes.kt <test file(s) touched>
git commit -m "fix: remove redundant second notifyChanged() call on browser mutations"
```

---

### Task 3: Session store bounded LRU (item 2)

**Files:**
- Modify: `server/src/main/kotlin/dev/njr/zync/server/auth/SessionStore.kt`
- Test: `server/src/test/kotlin/dev/njr/zync/server/auth/SessionStoreTest.kt` (new, or extend existing if one exists — check first)

**Interfaces:**
- `SessionStore`'s public API (`mint`/`validate`/whatever its current method names are) must NOT change shape — this is an internal-storage-only change. Read the current file in full before editing (do not assume method names from the followups doc's paraphrase).

- [ ] **Step 1: Read the current file and write the failing test**

Read `server/src/main/kotlin/dev/njr/zync/server/auth/SessionStore.kt` in full first to get its exact current API. Then write a test proving boundedness — e.g., mint more than the new cap (1024) sessions and confirm the store's internal size never exceeds the cap (if there's no public size accessor, add a package-private/internal one for testing, matching how `IdempotencyCache`/`McpIdempotencyCache` expose testability elsewhere if they do, or test indirectly by confirming the oldest session eventually stops validating once evicted). Follow this repo's existing test style for whichever test file you're adding to/creating.

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL — the current unbounded `mutableMapOf` never evicts.

- [ ] **Step 3: Bound the map**

Replace the unbounded map with the same bounded-LRU pattern already used elsewhere in this codebase — `IdempotencyCache` in `server/src/main/kotlin/dev/njr/zync/server/api/ApiRoutes.kt` (a `LinkedHashMap` with `removeEldestEntry` override, `@Synchronized` accessors) is the exact template to mirror. Cap at 1024 entries (matching the existing caches' cap). Do NOT add SQL persistence — a server restart already logging everyone out is existing, accepted behavior (per the global constraint's research: no comment anywhere indicates persistence was ever intended; only the unboundedness is the actual gap). Keep the existing per-token expiry-on-validate behavior; the LRU cap is an *additional* backstop against unbounded growth, not a replacement for expiry.

- [ ] **Step 4: Run test to verify it passes**

Expected: PASS.

- [ ] **Step 5: Run the full server test suite**

Run: `./gradlew :server:test`
Expected: BUILD SUCCESSFUL, no regressions — in particular, existing `AuthTest`/`AuthRoutesTest`/`WebAuthnTest` (whichever exercise session validation) must still pass unchanged.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/dev/njr/zync/server/auth/SessionStore.kt \
        server/src/test/kotlin/dev/njr/zync/server/auth/SessionStoreTest.kt
git commit -m "fix: bound the session store to prevent unbounded growth"
```

---

### Task 4: Persist and unify the server's HLC (item 3)

**Files:**
- Create: `data/src/commonMain/sqldelight/dev/njr/zync/data/db/ServerHlc.sq`
- Create: `data/src/commonMain/sqldelight/dev/njr/zync/data/db/migrations/9.sqm`
- Create: `server/src/main/kotlin/dev/njr/zync/server/clock/ServerHlc.kt`
- Create: `server/src/main/kotlin/dev/njr/zync/server/clock/SqlHlcStore.kt`
- Modify: `server/src/main/kotlin/dev/njr/zync/server/content/ServerContent.kt`
- Modify: `server/src/main/kotlin/dev/njr/zync/server/api/ExternalOpApi.kt`
- Modify: `server/src/main/kotlin/dev/njr/zync/server/Main.kt`
- Test: `server/src/test/kotlin/dev/njr/zync/server/clock/ServerHlcTest.kt`
- Test: `data/src/jvmTest/kotlin/dev/njr/zync/data/MigrationTest.kt` (add a v9→v10 migration test, matching the existing per-migration test pattern in that file)

**Interfaces:**
- Produces: `class ServerHlc(store: HlcStore, deviceId: String = "server", clock: Clock = Clock { System.currentTimeMillis() }) { @Synchronized fun now(): Hlc; @Synchronized fun observe(remote: Hlc): Hlc; fun current(): Hlc }` — consumed by `ServerOpEmitter` and `RecordingBotEmitter`.
- Produces: `interface HlcStore { fun load(): Hlc?; fun save(hlc: Hlc) }` (server-side, mirroring the app's `dev.njr.zync.replica.HlcStore` shape but NOT reusing that exact type — it's a `data`-layer concept, define it fresh in `server`) and `class SqlHlcStore(db: ZyncDatabase): HlcStore`.

This is the largest server-side task in this batch — it fixes TWO bugs at once: the missing persistence AND the fact that `ServerOpEmitter` and `RecordingBotEmitter` currently each construct their own independent, unsynchronized `HlcGenerator("server", clock)` (confirmed: `ServerContent.kt:28` and `ExternalOpApi.kt`'s `RecordingBotEmitter`), so two browser and bot ops issued "concurrently" can get identical or non-monotonic HLCs relative to each other.

- [ ] **Step 1: Add the SQL table + migration**

Create `data/src/commonMain/sqldelight/dev/njr/zync/data/db/ServerHlc.sq`:

```sql
-- The server's own last-issued HLC (mirrors the phone's persisted HLC via
-- AndroidHlcStore/LocalHlc). A single row, id fixed at 0. Persisted so a server
-- restart doesn't reset the clock to zero, which could otherwise make new
-- server-authored ops lose to older ones after a wall-clock rollback.
CREATE TABLE server_hlc (
  id INTEGER NOT NULL PRIMARY KEY,
  physical INTEGER NOT NULL,
  counter INTEGER NOT NULL,
  device_id TEXT NOT NULL
);

loadServerHlc:
SELECT physical, counter, device_id FROM server_hlc WHERE id = 0;

saveServerHlc:
INSERT OR REPLACE INTO server_hlc(id, physical, counter, device_id) VALUES (0, ?, ?, ?);
```

Create `data/src/commonMain/sqldelight/dev/njr/zync/data/db/migrations/9.sqm`:

```sql
-- v9 → v10: persist the server's own HLC (see docs/superpowers/plans/2026-08-12-codex-review-followups.md item 3).
CREATE TABLE server_hlc (
  id INTEGER NOT NULL PRIMARY KEY,
  physical INTEGER NOT NULL,
  counter INTEGER NOT NULL,
  device_id TEXT NOT NULL
);
```

Add the v9→v10 test to `data/src/jvmTest/kotlin/dev/njr/zync/data/MigrationTest.kt`, matching the file's existing per-migration test pattern exactly (see `v7MigratesToV8WithAgendaEventLink` and `v8MigratesToV9WithDeviceFcmToken` — the latter added this session for the FCM plan, if that branch's changes are visible here; if not, mirror `v7MigratesToV8...`'s shape): build the pre-migration schema fixture (no `server_hlc` table), set `PRAGMA user_version = 9`, open via `JvmZyncDatabase.open(driver)`, assert the schema version advanced and that `db.serverHlcQueries.saveServerHlc(...)`/`loadServerHlc()` round-trips correctly.

- [ ] **Step 2: Run the migration test**

Run: `./gradlew :data:jvmTest --tests "dev.njr.zync.data.MigrationTest"`
Expected: PASS (including the new v9→v10 case).

- [ ] **Step 3: Write `HlcStore`/`SqlHlcStore` and the failing `ServerHlc` test**

Create `server/src/main/kotlin/dev/njr/zync/server/clock/ServerHlc.kt`:

```kotlin
package dev.njr.zync.server.clock

import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.clock.HlcGenerator

/** Persists the server's last-issued HLC (mirrors the phone's [dev.njr.zync.replica.HlcStore]). */
interface HlcStore {
    fun load(): Hlc?
    fun save(hlc: Hlc)
}

/**
 * The server's ONE shared hybrid logical clock — both browser-authored ops
 * ([dev.njr.zync.server.content.ServerOpEmitter]) and bot-authored ops
 * ([dev.njr.zync.server.api.RecordingBotEmitter]) advance it, so they stay mutually ordered.
 * [HlcGenerator] is documented not-thread-safe ("callers serialize op issuance"); the server
 * handles concurrent requests, so every access here is `@Synchronized`, matching the phone's
 * [dev.njr.zync.replica.LocalHlc] pattern. Every advance is persisted via [store].
 */
class ServerHlc(
    private val store: HlcStore,
    deviceId: String = "server",
    clock: Clock = Clock { System.currentTimeMillis() },
) {
    private val generator = HlcGenerator(deviceId, clock, store.load() ?: Hlc.zero(deviceId))

    @Synchronized
    fun now(): Hlc = generator.now().also(store::save)

    @Synchronized
    fun observe(remote: Hlc): Hlc = generator.observe(remote).also(store::save)

    fun current(): Hlc = generator.current()
}
```

Create `server/src/main/kotlin/dev/njr/zync/server/clock/SqlHlcStore.kt`:

```kotlin
package dev.njr.zync.server.clock

import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.data.db.ZyncDatabase

/** SQLDelight-backed [HlcStore] for the server. */
class SqlHlcStore(private val db: ZyncDatabase) : HlcStore {
    override fun load(): Hlc? =
        db.serverHlcQueries.loadServerHlc().executeAsOneOrNull()?.let { Hlc(it.physical, it.counter.toInt(), it.device_id) }

    override fun save(hlc: Hlc) {
        db.serverHlcQueries.saveServerHlc(hlc.physical, hlc.counter.toLong(), hlc.deviceId)
    }
}
```

Check `Hlc`'s actual constructor/field types first (`core/src/commonMain/kotlin/dev/njr/zync/core/clock/Hlc.kt:1-40`, above the `HlcGenerator` class already read for this plan) — confirm `physical`/`counter`/`deviceId` field names and types (`counter` may be `Int` not `Long`; adjust the `SqlHlcStore` casts above to match exactly, don't guess).

Write `server/src/test/kotlin/dev/njr/zync/server/clock/ServerHlcTest.kt`:

```kotlin
package dev.njr.zync.server.clock

import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerHlcTest {
    private class FakeStore : HlcStore {
        var saved: Hlc? = null
        override fun load(): Hlc? = saved
        override fun save(hlc: Hlc) { saved = hlc }
    }

    @Test
    fun nowPersistsEveryAdvance() {
        val store = FakeStore()
        val hlc = ServerHlc(store, clock = Clock { 1000L })
        val first = hlc.now()
        assertEquals(first, store.saved)
        val second = hlc.now()
        assertTrue(second.counter > first.counter || second.physical > first.physical)
        assertEquals(second, store.saved)
    }

    @Test
    fun loadsPriorStateOnConstruction() {
        val store = FakeStore()
        store.saved = Hlc(5000L, 3, "server")
        val hlc = ServerHlc(store, clock = Clock { 1000L }) // wall clock behind the persisted state
        val next = hlc.now()
        assertTrue(next.physical >= 5000L, "must not regress behind the persisted HLC even if wall clock is behind")
    }

    @Test
    fun observeAdvancesPastARemoteClockAndPersists() {
        val store = FakeStore()
        val hlc = ServerHlc(store, clock = Clock { 1000L })
        val remote = Hlc(9000L, 0, "phone-1")
        val observed = hlc.observe(remote)
        assertTrue(observed.physical >= 9000L)
        assertEquals(observed, store.saved)
    }
}
```

- [ ] **Step 4: Run test to verify it fails, then passes**

Run: `./gradlew :server:test --tests "dev.njr.zync.server.clock.ServerHlcTest"`
Expected: first FAIL (classes don't exist), then PASS after Step 3's code is in place.

- [ ] **Step 5: Wire ONE shared `ServerHlc` into both emitters**

Edit `server/src/main/kotlin/dev/njr/zync/server/content/ServerContent.kt`: change `ServerOpEmitter` to accept an injected `ServerHlc` instead of constructing its own `HlcGenerator` internally:

```kotlin
class ServerOpEmitter(
    private val service: SyncService,
    private val hlc: dev.njr.zync.server.clock.ServerHlc,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) : OpEmitter {
    private val clock = Clock { now() }
    // hlc.now() replaces the old private `hlc.now()` calls below — same call sites, now using the injected instance
    ...
```

Remove the old `private val clock = Clock { now() }; private val hlc = HlcGenerator("server", clock)` lines and the `clock` val usage for `newId()` should stay (it's a separate concern — id generation, not HLC — read the current file to confirm `newId()`'s exact dependency before changing anything there). Update `ServerContent`'s constructor to accept and pass through a `ServerHlc`:

```kotlin
class ServerContent(service: SyncService, hlc: dev.njr.zync.server.clock.ServerHlc, val changes: ChangeNotifier = ChangeNotifier()) {
    val read = ContentReadModel(service.stateStore)
    val commands = ContentCommands(ServerOpEmitter(service, hlc))
}
```

Edit `server/src/main/kotlin/dev/njr/zync/server/api/ExternalOpApi.kt`'s `RecordingBotEmitter` similarly — read its current constructor and internal `HlcGenerator` construction (already partially seen this session: `RecordingBotEmitter(botId, now, random)` with a private `clock`/`hlc` pair) and change it to accept the same injected `ServerHlc` instead of building its own. `ExternalOpApi`'s constructor will need the `ServerHlc` threaded through to pass to each `RecordingBotEmitter` it creates per `submit()` call.

Edit `server/src/main/kotlin/dev/njr/zync/server/Main.kt`: construct ONE `ServerHlc` early (right after `db` is opened), backed by `SqlHlcStore(db)`, and pass it into both `ServerContent(...)` and `ExternalOpApi(...)`:

```kotlin
val serverHlc = dev.njr.zync.server.clock.ServerHlc(dev.njr.zync.server.clock.SqlHlcStore(db))
```

Find the exact current construction lines for `content = ServerContent(service, changes)` and `botApi = dev.njr.zync.server.api.ExternalOpApi(service, blobs = blobs)` in `Main.kt` and update both calls to pass `serverHlc`.

- [ ] **Step 6: Run the full server test suite**

Run: `./gradlew :server:test`
Expected: BUILD SUCCESSFUL. This step WILL surface compile errors in every test that directly constructs `ServerOpEmitter`, `ServerContent`, `RecordingBotEmitter`, or `ExternalOpApi` with the old constructor shape — fix each call site to pass a `ServerHlc` (tests can construct `ServerHlc(object : HlcStore { override fun load() = null; override fun save(hlc: Hlc) {} })` inline, or a shared test fixture if that reads cleaner — check how many call sites there are first with `grep -rn "ServerOpEmitter(\|ServerContent(\|RecordingBotEmitter(\|ExternalOpApi(" server/src/test` before deciding whether a shared test helper is worth adding).

- [ ] **Step 7: Commit**

```bash
git add data/src/commonMain/sqldelight/dev/njr/zync/data/db/ServerHlc.sq \
        data/src/commonMain/sqldelight/dev/njr/zync/data/db/migrations/9.sqm \
        data/src/jvmTest/kotlin/dev/njr/zync/data/MigrationTest.kt \
        server/src/main/kotlin/dev/njr/zync/server/clock/ServerHlc.kt \
        server/src/main/kotlin/dev/njr/zync/server/clock/SqlHlcStore.kt \
        server/src/test/kotlin/dev/njr/zync/server/clock/ServerHlcTest.kt \
        server/src/main/kotlin/dev/njr/zync/server/content/ServerContent.kt \
        server/src/main/kotlin/dev/njr/zync/server/api/ExternalOpApi.kt \
        server/src/main/kotlin/dev/njr/zync/server/Main.kt \
        <any test files updated in Step 6>
git commit -m "fix: persist the server HLC and share one instance across browser+bot emitters"
```

---

### Task 5: Voice-recorder error handling in `VoiceCaptureActivity` (item 5)

**Files:**
- Modify: `app/src/main/kotlin/dev/njr/zync/capture/VoiceCaptureActivity.kt`

**Interfaces:** None new — matches the existing pattern already proven correct in the sibling file `CaptureSettingsBridge.kt`.

This is a **narrow, precise fix**, not the "extract a shared VoiceRecorder class" refactor the original review floated — that's explicitly out of scope here (too large a behavioral-risk surface with zero device-testing available). Only the one confirmed missing error handler.

- [ ] **Step 1: Confirm the current gap**

Read `app/src/main/kotlin/dev/njr/zync/capture/VoiceCaptureActivity.kt`'s `stopAndSave()` method (around lines 95-115) in full, and `CaptureSettingsBridge.kt`'s `stopAndSave()` (around lines 128-166) in full, side by side. Confirm precisely: does `VoiceCaptureActivity.stopAndSave()` still lack a `runCatching`/try-catch around `file.readBytes()` / `app.captureToInbox(...)`, while `CaptureSettingsBridge`'s equivalent block wraps it in `runCatching{}.onSuccess/onFailure` with user feedback? If the code has changed since the research pass and the gap no longer exists, stop and report DONE_WITH_CONCERNS noting the finding is stale rather than inventing a fix for a problem that's already gone.

- [ ] **Step 2: Add the missing error handling**

Wrap the read-bytes-and-persist block in `VoiceCaptureActivity.stopAndSave()` in the same `runCatching { }.onSuccess { }.onFailure { }` shape `CaptureSettingsBridge.stopAndSave()` already uses — mirror its exact structure (what it does on success: toast/finish; what it does on failure: toast/log) rather than inventing new UX, so the two paths behave identically on error. Read `CaptureSettingsBridge.kt`'s exact failure-branch code (toast text, `Log.w`/`Log.e` call, any cleanup of the partially-recorded file) and replicate it precisely in `VoiceCaptureActivity`, adjusted only for whatever contextual differences the Activity has vs. the Bridge (e.g. `finish()` vs. a bridge callback — check both files for how they each conclude the flow and keep each file's own conclusion mechanism, just add the missing catch).

- [ ] **Step 3: Manual verification**

No compilation possible (no Android SDK here). Re-read the edited method in full and confirm: the `runCatching` block's success/failure paths are syntactically complete, no unreachable code, no double-`finish()`/double-callback risk, and the file compiles by inspection (matching Kotlin syntax already used elsewhere in the same file). Document this review in the report.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/njr/zync/capture/VoiceCaptureActivity.kt
git commit -m "fix: VoiceCaptureActivity.stopAndSave() handles save failures (matches CaptureSettingsBridge)"
```

---

### Task 6: `CaptureRepository` delegates to `ShareImport`'s type/extension logic (item 6)

**Files:**
- Modify: `app/src/main/java/dev/njr/zync/attach/CaptureRepository.kt`
- Read only (do NOT modify): `app/src/main/java/dev/njr/zync/attach/ShareImport.kt`

**Interfaces:**
- `CaptureRepository.attachmentTypeFor(...)` and `extensionFor(...)` — Step 1 reads both files to pin down their exact current signatures before any edit — should delegate to `ShareImport`'s equivalent functions instead of reimplementing the mapping.

**Do NOT touch `ShareImport.kt`'s image→PDF pragma** — it's intentional and documented (see Global Constraints). This task only removes `CaptureRepository`'s independent, drifted reimplementation.

- [ ] **Step 1: Read both files in full**

Read `app/src/main/java/dev/njr/zync/attach/ShareImport.kt`'s `typeFor()` (around lines 29-44 for `extensionFor`, and wherever `typeFor` itself lives — read the whole file) and `app/src/main/java/dev/njr/zync/attach/CaptureRepository.kt`'s `attachmentTypeFor()` (around lines 43-50) and `extensionFor()` (around lines 52-67) in full, to get their exact current signatures — do they take the same inputs (MIME string, filename, URI)? Do they return the same types (`ShareImport.typeFor` reportedly returns a nullable/rejecting type where `CaptureRepository.attachmentTypeFor` has a broader catch-all defaulting to PDF)? This mismatch in return-type semantics (nullable-and-reject vs. always-succeed-with-fallback) is the main design question this task must resolve — read both call sites (wherever `CaptureRepository.attachmentTypeFor`/`extensionFor` are actually called from) to determine whether callers can tolerate a nullable/rejecting result, or whether `CaptureRepository` genuinely needs a non-nullable fallback that `ShareImport`'s function doesn't provide.

- [ ] **Step 2: Delegate, preserving `CaptureRepository`'s fallback behavior where it's actually needed**

If `CaptureRepository`'s callers need a non-null/always-succeeds result (this needs confirming from Step 1's call-site read — don't assume), keep a thin wrapper in `CaptureRepository` that calls `ShareImport.typeFor(...)`/`ShareImport.extensionFor(...)` first and falls back to a bounded default only if that returns null, rather than reimplementing the whole mapping table. The specific confirmed drift to close: `CaptureRepository.extensionFor()` currently coarsens ALL `audio/*` MIME types to `.m4a`, while `ShareImport.extensionFor()` preserves `mp3`/`ogg`/`wav`/`3gp` distinctly — after this fix, `CaptureRepository` must preserve those distinctly too, by virtue of delegating to `ShareImport`'s function rather than its own separate audio-extension `when` block.

- [ ] **Step 3: Manual verification**

No compilation possible. Re-read the edited `CaptureRepository.kt` in full: confirm every call site that used the old `attachmentTypeFor`/`extensionFor` still compiles against the new delegating signatures (same parameter types, same nullability contract as before at the CALL sites, even if the internal implementation now delegates), and confirm the audio-extension behavior change (m4a-for-everything → preserved distinct extensions) doesn't break anything else in `CaptureRepository` that assumed the old coarsened behavior (e.g. a filename-construction step elsewhere that hardcoded `.m4a`). Search for `.m4a` elsewhere in the file to check.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/njr/zync/attach/CaptureRepository.kt
git commit -m "fix: CaptureRepository delegates MIME/extension mapping to ShareImport instead of reimplementing it"
```

---

### Task 7: Index-based tree rendering (item 4)

**Files:**
- Modify: `web/src/commonMain/kotlin/dev/njr/zync/web/content/ContentReadModel.kt`
- Modify: `web/src/commonMain/kotlin/dev/njr/zync/web/views/NodeViews.kt`
- Test: `web/src/commonTest/kotlin/dev/njr/zync/web/content/ContentReadModelTest.kt` (extend if it exists; check first)

**Interfaces:**
- Produces: `ContentReadModel.childrenIndex(): Map<Ulid?, List<NodeView>>` — a full parent→children index built from ONE snapshot pass, using the exact same filter `children(parent)` already applies (kind exclusions, `!proposed()`, title-sort within each group). Consumed by `NodeViews.kt`'s `subtaskTree`/`treeSection`.

`ContentReadModel` is a server-process-lifetime **singleton** (`ServerContent.kt:55`: `val read = ContentReadModel(...)`, constructed once) — so this task must NOT cache anything on the `ContentReadModel` instance itself (it would go stale across mutations forever). The fix is call-site-scoped: build one index per top-level render, not per-request caching inside `ContentReadModel`.

- [ ] **Step 1: Add `childrenIndex()` to `ContentReadModel`, reusing the existing private helper's shape**

`ContentReadModel.kt` already has a private `childrenIndex(snaps: Map<Ulid, EntitySnapshot>): Map<String, List<Ulid>>` (around line 689, currently only used by `subtreeHeight`/`heightIn`) that applies the SAME filter as `children(parent)`. Add a new PUBLIC method that reuses this existing private helper but returns fully-materialized `NodeView`s instead of raw `Ulid`s, keyed by `Ulid?` (nullable, matching `children(parent: Ulid?)`'s parameter) instead of `String`:

```kotlin
/**
 * The full parent → children index, built from ONE snapshot pass — same filter, ordering,
 * and exclusions as [children], but computes every parent's children at once instead of
 * one [store.project] scan per call. For recursive tree rendering (see NodeViews.subtaskTree/
 * treeSection), which previously called [children] once per node, making a full-tree render
 * roughly quadratic in the number of live nodes.
 */
fun childrenIndex(): Map<Ulid?, List<NodeView>> {
    val snaps = snapshots()
    val byIdView = snaps.associate { it.entityId to it.toView() }
    return snaps
        .filter {
            it.kind() != "context" && it.kind() != "comment" &&
                it.kind() !in AgentFlow.INTERNAL_KINDS && !it.proposed()
        }
        .groupBy { it.parent }
        .mapValues { (_, group) -> group.mapNotNull { byIdView[it.entityId] }.sortedBy { it.title ?: "" } }
}
```

Check the exact field/method names used above (`snapshots()`, `.kind()`, `.proposed()`, `.parent`, `.entityId`, `.toView()`) against `children(parent: Ulid?)`'s actual current implementation (already read for this plan, lines 141-149) — this new method's filter must match `children()`'s filter EXACTLY (same three kind exclusions, same `!proposed()`, same title-sort) so results are identical to calling `children(p)` for every `p`, just computed once. If `snapshots()` returns a type where grouping by `.parent` (which is itself `Ulid?`) works directly as shown, keep it; adjust only if the actual types differ from what's assumed here.

Write a test in `web/src/commonTest/kotlin/dev/njr/zync/web/content/ContentReadModelTest.kt` (check first whether this file exists and what pattern it uses — if none exists, check how other `web` module tests are structured, e.g. by searching `web/src/commonTest` or `web/src/jvmTest`) that seeds a small tree (a root with 2 children, one of which has its own child) and asserts `childrenIndex()` returns the same grouped results as calling `children(parent)` for each `parent` individually — this is the load-bearing correctness property.

- [ ] **Step 2: Run the test**

Run: `./gradlew :web:jvmTest --tests "*ContentReadModelTest*"` (adjust the exact Gradle task per the module's actual KMP test task name — check `web/build.gradle.kts` or use `:web:allTests` if that's this repo's convention).
Expected: PASS.

- [ ] **Step 3: Thread the index through `NodeViews.kt`'s recursive renderers**

Edit `web/src/commonMain/kotlin/dev/njr/zync/web/views/NodeViews.kt`. Change `subtaskTree` and `treeSection` to take a pre-built index instead of `read` + calling `read.children()` at every recursion level:

```kotlin
private fun FlowContent.subtaskTree(index: Map<Ulid?, List<NodeView>>, parent: Ulid, levelsLeft: Int) {
    if (levelsLeft <= 0) return
    val children = index[parent].orEmpty()
    if (children.isEmpty()) return
    ul(classes = "subtasks-list") {
        children.forEach { child ->
            li {
                a(href = "/node/${child.id}") { +(child.title ?: "(untitled)") }
                child.size?.let { span("size-badge") { +it } }
                child.status?.let { span("status") { +" · $it" } }
                subtaskTree(index, child.id, levelsLeft - 1)
            }
        }
    }
}

fun FlowContent.treeSection(index: Map<Ulid?, List<NodeView>>, parent: Ulid?) {
    val children = index[parent].orEmpty()
    if (children.isEmpty()) return
    ul {
        children.forEach { child ->
            li {
                nodeRow(child)
                treeSection(index, child.id)
            }
        }
    }
}
```

Find every current call site of `subtaskTree(read, ...)` and `treeSection(read, ...)` (`grep -n "subtaskTree(\|treeSection(" web/src/commonMain/kotlin/dev/njr/zync/web/`) — each top-level entry point (e.g. `expandedPanel` at line 354, `nodeDetail` at line 736, and wherever `treeSection` is called from outside `NodeViews.kt`, e.g. a page-level template) must build the index ONCE via `read.childrenIndex()` and pass that index down, instead of passing `read` into the recursive functions. Update every call site found, preserving each function's existing signature otherwise (e.g. `expandedPanel(read: ContentReadModel, node: NodeView, canReorder: Boolean)` can still take `read` as a parameter — it just computes `val index = read.childrenIndex()` once at its own top and passes `index` to `subtaskTree`, rather than passing `read` through every recursive level).

- [ ] **Step 4: Run the full web/server test suites**

Run: `./gradlew :web:jvmTest :server:test`
Expected: BUILD SUCCESSFUL. This is a rendering-path change — any existing test that renders a tree (search for tests hitting `/node/{id}` or the inbox/reference tree HTML output) must still produce byte-identical HTML output; if any such test does string/structure assertions on rendered tree HTML, it should pass unchanged since the fix is a compute-once optimization, not a rendering change. If Playwright coverage exists for tree rendering (`webtest/`), note in the report whether it was feasible to run here (likely not — needs `npm`/browser setup) and flag it for manual confirmation.

- [ ] **Step 5: Commit**

```bash
git add web/src/commonMain/kotlin/dev/njr/zync/web/content/ContentReadModel.kt \
        web/src/commonMain/kotlin/dev/njr/zync/web/views/NodeViews.kt \
        <ContentReadModelTest.kt path>
git commit -m "perf: build the parent-children index once per tree render instead of per-node scans"
```

---

### Task 8: Consume the bootstrap snapshot on a fresh install (item 1)

**Files:**
- Modify: `app/src/main/kotlin/dev/njr/zync/replica/SyncClient.kt`
- Modify: `app/src/main/kotlin/dev/njr/zync/replica/ReplicaSynchronizer.kt` (or wherever it actually lives — confirm path)
- Test: no automated test possible without a real device (Android, no SDK here) — this task is code-review-verified only, same bar as Task 5/6 and the FCM plan's Task 7.

This is the **highest-risk item in this batch** — the original review itself flagged it: *"Snapshot application deserves crash/retry tests because partial seeding is more dangerous than slow replay."* Scope this conservatively: correctness (never double-seed, never partially seed) matters far more than completeness here.

- [ ] **Step 1: Read the current pull/sync flow in full**

Read `app/src/main/kotlin/dev/njr/zync/replica/SyncClient.kt` in full (already read once for this plan's research — re-confirm current state) and `ReplicaSynchronizer.kt` in full. Confirm exactly how `db.transportQueries.getCursor(peer)` behaves for a truly fresh install (does it return `null`, or `0L`? — this determines the fresh-install detection signal) and confirm there's no existing row in any content table (`register`, `tombstone`, `tag`) that would make bootstrap-seeding unsafe to run unconditionally just because the cursor is absent (e.g. could a device be re-paired with a cursor reset but existing local content? If so, seeding from bootstrap would need to be additive-safe or explicitly guarded to only run when the local store is ALSO empty, not just when the cursor is absent).

- [ ] **Step 2: Add `SyncClient.bootstrap()`**

Mirror `push()`/`pull()`'s existing pattern exactly (their auth-header construction via `authHeaders(...)`, their `GET`/`POST` call shape) for a new method:

```kotlin
suspend fun bootstrap(): BootstrapSnapshot {
    val response = http.get("$baseUrl/sync/bootstrap") {
        authHeaders("GET", "/sync/bootstrap").forEach { (k, v) -> header(k, v) }
    }
    response.requireOk("bootstrap")
    return json.decodeFromString(BootstrapSnapshot.serializer(), response.bodyAsText())
}
```

- [ ] **Step 3: Consume it in `ReplicaSynchronizer`, guarded and atomic**

In `ReplicaSynchronizer.syncOnce()` (currently `uploadPendingBlobs(); client.sync(); refreshAgenda()` per this plan's research), add a fresh-install check BEFORE the normal `client.sync()` call:

```kotlin
suspend fun syncOnce() {
    uploadPendingBlobs()
    if (isFreshInstall()) consumeBootstrap() else client.sync()
    refreshAgenda()
}
```

Where `isFreshInstall()` checks BOTH the absent cursor AND an empty local content store (per Step 1's finding — do not seed from bootstrap if there's any existing local content, to avoid double-seeding or overwriting local-only data; if in doubt, fail closed to the existing "just do a normal pull" path rather than risk data loss). `consumeBootstrap()` should:

1. Call `client.bootstrap()` to fetch the snapshot.
2. Inside ONE `db.transaction { }`, apply every `RegisterEntry`/`TombstoneEntry`/`TagEntry`/`Op.Move` from the snapshot to the local `StateStore` (using whatever apply mechanism `pull()` already uses per-op — check `pull()`'s exact `apply(op, store)`/`hlc.observe(op.hlc)` calls and replicate the same primitives for the bootstrap's flattened register/tombstone/tag/move shapes, which aren't raw `Op`s — check `core`'s merge functions for whether there's already a direct "apply a register/tombstone/tag entry" primitive distinct from `apply(op: Op, store: StateStore)`, since `BootstrapSnapshot` isn't a list of `Op`s).
3. Observe every entry's HLC via `hlc.observe(...)`.
4. Set the cursor to `snapshot.headSeq`.
5. Only commit the transaction if every step succeeds — if `consumeBootstrap()` throws partway through, the transaction must roll back entirely (leaving the device in its pre-bootstrap "fresh install" state, safe to retry from scratch on the next sync attempt) rather than leaving a half-seeded store.

If applying `BootstrapSnapshot`'s flattened entries requires new merge-layer plumbing beyond what a straightforward loop over `db.transaction { snapshot.registers.forEach { ... }; snapshot.tombstones.forEach { ... }; ... }` can accomplish cleanly against the existing `StateStore` API, STOP and report BLOCKED with exactly what's missing rather than inventing new `core` merge-layer surface under this task — that would be a larger, separate piece of work needing its own review.

- [ ] **Step 4: Manual verification**

No device/compile check possible. Re-read the full edited flow and specifically verify: (a) the fresh-install check can't false-positive on a normal re-sync (would cause data loss by re-seeding over live local edits), (b) the transaction genuinely wraps all writes including the cursor update, (c) a thrown exception anywhere in `consumeBootstrap()` leaves NO partial state (SQLDelight's `db.transaction{}` should roll back automatically on an uncaught exception inside the block — confirm this is how `db.transaction` is used elsewhere in this codebase, e.g. in `pull()`'s own transaction usage, to confirm the same rollback guarantee applies here).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/njr/zync/replica/SyncClient.kt \
        app/src/main/kotlin/dev/njr/zync/replica/ReplicaSynchronizer.kt
git commit -m "fix: consume the server's bootstrap snapshot on a fresh install instead of full replay"
```

---

### Task 9: Extract the capture construction cluster out of `ZyncApp` (item 7, scoped down)

**Files:**
- Create: `app/src/main/java/dev/njr/zync/CaptureGraph.kt`
- Modify: `app/src/main/java/dev/njr/zync/ZyncApp.kt`

**Interfaces:**
- Produces: `class CaptureGraph(app: ZyncApp) { val localBlobs: LocalBlobStore; val driveOcr: DriveOcr; val ocrProcessor: OcrProcessor }` — Step 1 confirms the exact member set and their dependency graph before any code moves — a pure mechanical extraction, no behavior change.

**This is deliberately the smallest safe slice of item 7, not a full `AppGraph` rewrite.** `ZyncApp` already delegates most sync logic to `ReplicaSynchronizer`/`SyncClient` (confirmed by this plan's research) — the remaining bloat this task addresses is the capture/OCR construction cluster (`localBlobs`, `driveOcr`, `ocrProcessor`), which is self-contained enough to extract with near-zero behavioral risk. Do NOT attempt to extract the database/state/HLC/pairing/server members in this pass — those are more tangled with the rest of the class and a bad extraction there risks real production breakage with no device to catch it.

- [ ] **Step 1: Read `ZyncApp.kt` in full and confirm the capture/OCR member boundary**

Read the current file in full (already read this session, but confirm no drift). Identify precisely: `localBlobs` (`by lazy { LocalBlobStore(File(filesDir, "blobs")) }`), `driveOcr` (`by lazy { GoogleDriveOcr(this) }`), `ocrProcessor` (`by lazy { OcrProcessor(localBlobs, opWriter, driveOcr, opStore, onChanged = ...) }`) and every OTHER member/method in `ZyncApp` that references any of these three (e.g. `captureToInbox` uses `replicaCapture` which itself may depend on `localBlobs` via `ReplicaCapture(opWriter, localBlobs, ...)` — check whether `replicaCapture` should move too, or stay in `ZyncApp` referencing `captureGraph.localBlobs`). Map the exact dependency graph before writing any code.

- [ ] **Step 2: Create `CaptureGraph`**

Create `app/src/main/java/dev/njr/zync/CaptureGraph.kt` with the three (or however many Step 1 determined) lazily-constructed members, taking whatever `ZyncApp` context/dependencies they actually need as constructor parameters (likely `opWriter: OpWriter`, `filesDir: File`, `context: Context`, and an `onChanged: () -> Unit` callback for `ocrProcessor` — read Step 1's findings to get the exact real dependency list, don't guess). Preserve each member's exact `by lazy { ... }` construction expression unchanged — this is pure code motion, not a rewrite.

- [ ] **Step 3: Update `ZyncApp` to hold and delegate through `CaptureGraph`**

In `ZyncApp.kt`, replace the three extracted `by lazy` properties with a single `val captureGraph: CaptureGraph by lazy { CaptureGraph(...) }`, and update every call site within `ZyncApp` that referenced `localBlobs`/`driveOcr`/`ocrProcessor` directly to go through `captureGraph.localBlobs`/etc. instead. Check whether anything OUTSIDE `ZyncApp` (other Activities/Workers) references `(applicationContext as ZyncApp).localBlobs` or similar directly (`grep -rn "\.localBlobs\|\.driveOcr\|\.ocrProcessor" app/src/main`) — if so, either keep a thin passthrough property on `ZyncApp` (`val localBlobs get() = captureGraph.localBlobs`) to avoid touching every external call site, or update those call sites too, whichever is the smaller/safer diff. Prefer the passthrough — it's less code touched, lower risk.

- [ ] **Step 4: Manual verification**

No compile check possible. Re-read both files in full and confirm: every symbol reference resolves (no leftover reference to a removed property), the `by lazy` initialization order/timing is unchanged (capture members were already lazy before, so laziness through one more layer of indirection doesn't change when they first construct), and no external caller of the old direct properties was missed (re-run the grep from Step 3 against the final state of the code, not just before editing).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/njr/zync/CaptureGraph.kt app/src/main/java/dev/njr/zync/ZyncApp.kt
git commit -m "refactor: extract capture/OCR construction out of ZyncApp into CaptureGraph"
```

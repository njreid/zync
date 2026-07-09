# zync M3 — Shared Core (`zync-core`, KMP): Op Log, Merge & Clocks

> **For agentic workers:** implement task-by-task; steps use `- [ ]` checkboxes.
> **Semantics source of truth:** `docs/superpowers/specs/2026-07-08-oplog-merge-operator-model.md`.
> This plan is the *build sequence* for the first milestone of the rebuild roadmap
> (`2026-07-08-rebuild-roadmap.md`). Status: 🟡 ready to execute.

**Goal:** a pure, provably-correct, language-shared **`core`** Kotlin Multiplatform
module — the op model, clocks, and merge that the phone and server both depend on
(one implementation, no divergence). **No UI, no server, no networking, no SQL, no
platform APIs.** Its tests (conformance + property) are the deliverable's teeth.

**Context / current state:**
- Repo currently has the single `app/` Android module (v0.2). This milestone adds a
  new **`core`** KMP module alongside it without disturbing `app` yet (later
  milestones add `data`/`web`/`server`/`androidApp` and retire the old code — no
  backwards-compat required).
- Targets: **JVM** (server) + **Android** (phone). Add `native`/`js` later only if
  needed.

## Global constraints

- **Pure `commonMain`** — no `android.*`, no `java.*`-only APIs, no SQL. Merge logic
  runs against an abstract `StateStore` **port** (in-memory impl for tests;
  SQLDelight impl arrives in `data`, M4/M5). Keeps `core` DB- and platform-agnostic.
- **Deterministic & testable:** inject the time source and entropy. HLC physical
  time and ULID randomness come from injected `Clock`/`Random` — **never** call an
  ambient wall clock / RNG inside merge logic. (Also lets property tests reproduce.)
- **Wire format = kotlinx.serialization JSON**, explicitly **versioned**; add golden
  round-trip tests so the format can't drift silently.
- VCS: git, feature branch; `./gradlew :core:test` green at every commit.
- Semantics must match the op/merge spec exactly; if a task reveals a spec gap, fix
  the spec first, then implement.

---

### Task 1: KMP module scaffold + build wiring
**Files:** `core/build.gradle.kts`, `settings.gradle.kts` (include `:core`),
`gradle/libs.versions.toml` (kotlinx-serialization, kotlin-test), `core/src/commonMain`,
`core/src/commonTest`.
- KMP module with **jvm() + androidTarget()**; `commonMain` deps: kotlinx-serialization-json;
  `commonTest`: kotlin-test.
- [x] **Step 1:** Create `:core`; a trivial `commonTest` passes on both JVM and
  Android targets (`./gradlew :core:allTests` → `jvmTest` + `testAndroidHostTest`).
  Note: AGP 9 requires the `com.android.kotlin.multiplatform.library` plugin (not
  `com.android.library`) with the `android {}` KMP DSL; the Android unit-test task
  is `testAndroidHostTest`, not the old `testDebugUnitTest`. The module pins
  `jvmToolchain(17)` because the build daemon runs on a headless JRE with no `javac`.
- [x] **Step 2: Commit** `feat(core): scaffold KMP core module`.

### Task 2: Identity & clocks — ULID + HLC (TDD)
**Files:** `core/…/id/Ulid.kt`, `core/…/clock/Hlc.kt` (+ tests).
- `Ulid` (time+entropy, lexically sortable, string enc/dec) with injected `Clock`+`Random`.
- `Hlc(physical, counter, deviceId)` with `now()`, `observe(remote)`, total-order
  `compareTo` (physical → counter → deviceId), pack/unpack, serialization.
- [x] **Step 1 (RED):** tests — HLC monotonic across `now()`; `observe` advances past
  a remote HLC; tiebreak by deviceId; ULID uniqueness + sort order; determinism under
  fixed clock/RNG.
- [x] **Step 2 (GREEN):** implement. `./gradlew :core:allTests` green (19 tests).
  Note: `now()`/`observe()` live on a stateful `HlcGenerator`; `Hlc` itself is the
  immutable value (compareTo/pack/serialization). ULID uses Crockford Base32.
- [x] **Step 3: Commit** `feat(core): ULID + hybrid logical clock`.

### Task 3: Op model + serialization (TDD)
**Files:** `core/…/op/Op.kt` (sealed: `SetField`, `Move`, `AddTag`, `RemoveTag`,
`AddAttachment`, `Tombstone`), `EntityType`, `Actor` (Human/Operator/Agent).
- [x] **Step 1 (RED):** JSON round-trip for every op type; a **golden** wire-format
  test (fixed JSON strings) to lock the format; `Actor`/provenance encode/decode.
- [x] **Step 2 (GREEN):** implement with kotlinx.serialization (sealed polymorphism).
  Modeled as a sealed `Op` (one subtype per mutation) instead of the spec's
  nullable-field bag; discriminator `type` (snake_case SerialNames). 9 tests green.
- [x] **Step 3: Commit** `feat(core): op model + versioned serialization`.

### Task 4: State + apply — LWW registers, tombstones, tags (TDD)
**Files:** `core/…/state/StateStore.kt` (port), `InMemoryStateStore.kt` (test impl),
`core/…/merge/Apply.kt`.
- `apply(op, store)` for `SetField`/`Tombstone`/`AddTag`/`RemoveTag`/`AddAttachment`:
  LWW by HLC, tombstone-wins, tag LWW-boolean; **idempotent** (dedupe by `opId`) and
  **commutative** for non-move ops. A `project()` fold to a queryable snapshot for
  assertions.
- [x] **Step 1 (RED):** higher-HLC-wins; apply-twice == apply-once; order-independent
  final state for a set of non-move ops; tombstone beats concurrent edit.
- [x] **Step 2 (GREEN):** implement against the `StateStore` port (9 tests green).
  `apply` is total over the sealed `Op`; move integration (`reintegrateMoves`,
  full-replay HLC-ordered + cycle-skip) landed here so the port/store are complete —
  the hard move proofs are Task 5. `StateStore`/`InMemoryStateStore`/`project()` +
  `AddAttachment` stored as an `@attachment` register (uniform tombstone lifecycle).
- [x] **Step 3: Commit** `feat(core): LWW registers, tombstones, tags + apply`.

### Task 5: Tree-move algorithm (TDD) — the hard part
**Files:** `core/…/merge/TreeMove.kt` (move log, HLC-ordered integration, cycle-skip,
undo/redo on late move).
- [x] **Step 1 (RED):** concurrent A→under-B / B→under-A resolves to one winner, **no
  cycle**, both orders converge; a late (lower-HLC) move reorders correctly; moving
  under own descendant is skipped; orphan-free.
- [x] **Step 2 (GREEN):** implement Kleppmann move — `merge/TreeMove.kt`
  (`reintegrateMoves`, full-replay HLC-ordered + cycle-skip; extracted from Apply).
  7 tests green (incl. V3 both-orders, late-move, descendant-skip, 3-party cycle,
  shuffle convergence, acyclic invariant).
- [x] **Step 3: Commit** `feat(core): tree-move CRDT (cycle-safe, late-move reorder)`.

### Task 6: Conformance + property tests (the proof)
**Files:** `core/commonTest/…/ConformanceTest.kt`, `ConvergencePropertyTest.kt`.
- Encode the canonical vectors **V1–V8** from
  `../specs/2026-07-08-merge-conformance-vectors.md` as fixed conformance tests.
- **Property test:** generate random op sets (incl. moves), deliver to two replicas in
  shuffled orders → assert **identical** projected state (convergence). Seeded RNG for
  reproducibility.
- [x] **Step 1:** implement both; both green. V1–V8 (10 cases incl. variants) each
  also checked order-independent; property test = 40 seeds × 60 ops delivered to two
  replicas in independent shuffles → identical projection, + idempotency (re-deliver).
  HLCs kept globally unique in the generator (strict total order, as the real system
  guarantees). V4's emission guard is deferred to Task 7 (pure write-scope predicate).
- [x] **Step 2: Commit** `test(core): conflict conformance + convergence property tests`.

### Task 7: Operator-manifest types + typed-output validation (slim)
**Files:** `core/…/operator/OperatorManifest.kt`, `OutputValidation.kt`.
- **Types + validation only** — the operator *runtime* (LLM calls, scope evaluation,
  triggers, fuel) is **M8**; here we define the shared, pure pieces: manifest data
  types (id, readScope handle, writeScope, trigger kind, output JSON-schema, retries,
  fuel) + **typed-output validation** (validate an LLM result against the schema;
  pure retry-count semantics). Flag that the shape may evolve when M8 lands.
- [x] **Step 1 (RED):** valid output passes; invalid fails; retry-budget accounting.
- [x] **Step 2 (GREEN):** implement; serialization round-trip for manifests (8 tests).
  `OperatorManifest`/`ReadScopeHandle` (opaque)/`WriteScope`/`TriggerKind`/`Fuel`;
  slim typed `OutputSchema.validate` + pure `evaluate(attempts, retries)` retry
  semantics; `WriteScope.permits(op)` is the V4 emission guard (field ownership by
  construction). readScope stays an opaque handle; runtime is M8.
- [x] **Step 3: Commit** `feat(core): operator manifest types + output validation`.

### Task 8: API surface + consumer smoke
- [ ] **Step 1:** review/trim the public API (`internal` where possible); brief KDoc on
  the public types; confirm `:core` is consumable as a project dependency (a throwaway
  `:jvm` consumer or a test that exercises the public surface).
- [ ] **Step 2: Commit** `chore(core): tidy public API + KDoc`.

## Interfaces / decisions
- **`StateStore` port** keeps `core` DB-agnostic; `data` supplies the SQLDelight impl
  (M4/M5), tests use in-memory. `apply` never touches SQL.
- **Server-only concerns excluded:** `seq` assignment, sync endpoints, blob/S3, LLM
  runtime — all live in `server`, not `core`.
- **Determinism** via injected `Clock`/`Random` is a hard rule (testability + no
  `Date.now()`/`Math.random()` in logic).

## Open questions (carry from the op/merge spec)
- Tags: LWW-boolean (chosen) vs OR-Set — keep LWW unless churn shows problems.
- Tombstone policy: tombstone-wins (chosen).
- Operator `readScope` representation (predicate handle vs reused query) — settle when
  M8 builds the runtime; M3 keeps it an opaque typed handle.
- Snapshot/compaction format — not needed until bootstrap (M4/M5); design there.

## Definition of done
`:core` builds on JVM + Android; ULID/HLC/op-model/apply/tree-move/operator-types all
implemented; **conformance + convergence property tests green**; public API documented;
zero platform/SQL deps in `commonMain`. Ready for `data` (M4) to persist and `server`
(M4) to depend on.

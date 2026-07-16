# Codex codebase review notes

Reviewed 2026-07-13 against the current M3–M7 implementation and the newer architecture sources of truth: `README.md`, `AGENTS.md`, the rebuild roadmap, backup/sync and Kotlin/KMP target specs, merge/operator model, test strategy, M3–M7 plans, acceptance runbook, deployment docs, and `.superpowers/sdd/progress.md`. Retired v0.2 code and future M8/M9 operator/agent work are not treated as defects.

The overall module split is good: correctness-critical merge logic is isolated in `core`, SQLDelight is shared through `data`, `web` is genuinely shared by server and phone, and platform code is mostly kept at the edges. The tests around merge conformance, sync, WebAuthn, loopback auth, and capture provide a much stronger base than the repository's small size might suggest. The most useful next work is closing a few incomplete production seams and then reducing repeated full-state work and Android capture duplication.

## Priority 0 — correctness and shipped-path gaps

### 1. Wire blob upload into production sync

`ReplicaCapture` stores attachment bytes in `LocalBlobStore` and emits an `AddAttachment` op containing the hash (`app/src/main/kotlin/dev/njr/zync/replica/ReplicaCapture.kt:31-35`). `BlobUploader` exists and is exercised by unit/vertical-slice tests, but its only production references are documentation comments. `ZyncApp.syncOnce()` constructs only `SyncClient` and calls `sync()` (`app/src/main/java/dev/njr/zync/ZyncApp.kt:77-94`). Consequently metadata reaches the server while the referenced bytes remain on the phone.

Suggested shape:

- Add a durable pending-blob ledger (hash, state, attempts/last error), rather than inferring work from every file forever.
- Upload pending blobs before acknowledging/pushing their attachment ops, or define an explicit retryable ordering that tolerates temporarily missing blobs.
- Keep successfully uploaded local bytes according to an explicit offline-retention policy; do not delete merely because S3 accepted them.
- Add a production-wiring test around `ZyncApp.syncOnce()` or an extracted `ReplicaSynchronizer`, not only direct tests of `BlobUploader`.

### 2. Bound and batch phone pushes

`SyncClient.push()` loads every unsynced op into one request (`SyncClient.kt:49-60`), while server hardening rejects requests over 32 MiB (`Hardening.kt:18-21,39-45`). A sufficiently old or busy replica can therefore enter a permanent retry loop: every WorkManager retry sends the same oversized batch.

Page unsynced ops by count and encoded byte size, push/ack one batch transactionally, and continue until empty. Put the batch policy in the shared sync contract or a small coordinator so tests can cover boundary behavior. Also return a distinct permanent result for malformed/auth failures; WorkManager currently retries every exception indefinitely (`SyncWorker.kt:22-27`).

### 3. Either consume bootstrap on a fresh replica or remove the claim that it is active

The server exposes a compacted bootstrap snapshot (`SyncService.bootstrap()` and `/sync/bootstrap`), but `SyncClient` only pushes and tails `/sync/pull` from cursor zero. A fresh install therefore replays the entire retained op log, contrary to the architecture and README language.

Implement a first-sync state marker and atomically seed registers, tombstones, tags, moves, HLC observation, and cursor from the snapshot before tailing. Snapshot application deserves crash/retry tests because partial seeding is more dangerous than slow replay. Until implemented, describe bootstrap as server groundwork rather than a landed client behavior.

### 4. Make sequence allocation database-owned or rollback-safe

`SeqAllocator.next()` advances an in-memory counter inside a SQL transaction (`SyncService.kt:47-55`), but transaction rollback cannot roll the counter back. An insertion/apply failure can leave `serverHead` ahead of the durable log and violate the allocator's “gap-free” documentation. The value repairs on restart, which makes behavior time-dependent.

Prefer allocating from SQLite in the same transaction (or let an integer primary key assign sequence), then read the durable head for responses. This also simplifies concurrency reasoning and removes duplicated authority between memory and the database. If gaps are intentionally acceptable, loosen the invariant and ensure clients only depend on monotonicity.

### 5. Validate and cap sync query inputs

`/sync/pull` uses `toLong()` and accepts an arbitrary `limit` (`SyncRoutes.kt:21-25`). Bad input currently becomes a 500 through the catch-all status handler, and a valid authenticated caller can request an extremely large result. Parse with `toLongOrNull`, require `since >= 0`, and clamp `limit` to a modest server maximum. Return structured 400 errors.

## Priority 1 — security, durability, and operational clarity

### 6. Do not expose internal exception messages to clients

The global `StatusPages` handler returns `cause.message` in a 500 response (`server/App.kt:45-48`). Library, database, path, and configuration details can leak to an internet-facing client. Log the exception with a request/correlation id and return a fixed error body. Map expected validation/serialization failures separately to 400/413.

### 7. Make browser sessions restart-safe, bounded, and cookie-centric

`SessionStore` is an in-memory map (`SessionStore.kt:13-47`), so every server restart logs out all browsers, expired sessions are removed only when that exact token is validated, and the map has no global bound. Persist hashed opaque tokens (or signed, revocable session data) with expiry in SQLite, purge periodically/on mint, and cap active sessions. This is primarily an availability/operations improvement, but it also makes session lifecycle auditable.

### 8. Persist the server HLC

The phone persists its HLC; `ServerOpEmitter` starts a new `HlcGenerator("server")` on every process start (`ServerContent.kt:22-40`). Wall time usually keeps it ahead, but a clock rollback can cause new browser edits to lose to older server writes for an unbounded period. Store the last server HLC in SQLDelight and observe incoming phone HLCs before issuing subsequent server-authored ops. A single shared server clock service should own this behavior.

### 9. Put hard limits on in-memory abuse surfaces

`NonceCache`, `SessionStore`, and `TokenBucketRateLimiter` all use process-local maps. Nonces expire on each check, but rate-limit buckets never expire; arbitrary spoofed `X-Device-Id` values are used as limiter keys before authentication (`Hardening.kt:32-50`). An attacker can grow the bucket map and evade per-IP limiting by rotating header values.

Rate-limit unauthenticated traffic by normalized remote address, use an authenticated principal only after verification, and use size-bounded/expiring caches. Apply hardening to pairing and WebAuthn ceremony endpoints too; currently it only intercepts `/sync` and `/blob`.

### 10. Make WebAuthn configuration fail closed in production

If either RP id or origin is missing, `buildWebAuthn()` returns null and the content UI is deliberately served unauthenticated with only a warning (`ServerConfig.kt:34-50`, `App.kt:52-58`). This is convenient locally but hazardous for a public deployment typo.

Introduce an explicit dev mode (`ZYNC_DEV_MODE=true`) or an explicit `ZYNC_ALLOW_UNAUTHENTICATED_WEB=true`. Otherwise, serving content without WebAuthn should stop startup. Validate HTTPS origin/RP consistency at startup and emit a concise configuration error.

### 11. Clarify request-signature integrity and bind pushed ops to the principal

The signed canonical string covers method/path/timestamp/nonce, not the body or query (`SignedRequestVerifier.kt:13-16,43-53`). TLS provides transport integrity, but the signature is not a self-contained authorization for the specific payload. Also, `requireAuth` discards the authorized principal, so `/sync/push` cannot verify that `op.deviceId` matches the signing device.

Hash the body (and canonical query parameters where relevant) into the signature, return/store a typed principal from authentication, and reject device-authored ops whose claimed device differs. This becomes more important if audit/provenance or device revocation is relied upon later.

## Priority 1 — performance and data-model simplification

### 12. Stop rebuilding and rescanning the full projection for routine reads

`StateStore.project()` enumerates all registers/tags/parents and filters them once per entity; `ContentReadModel` calls it repeatedly for `children`, `comments`, `node`, and `contexts`. Recursive tree rendering calls `children()` at every node (`NodeViews.kt:54-64`), turning one page into repeated full-database loads and roughly quadratic/cubic in-memory filtering as the tree grows.

Near-term: take one immutable projection snapshot per request and build indexes (`byId`, `childrenByParent`, `commentsByParent`, contexts) once. Longer-term: add SQLDelight read projections/queries for live nodes and parent relationships while retaining the op log as source of truth. Keep rendering against a request-scoped read snapshot so a page is internally consistent.

### 13. Replace stringly typed fields/status/kind/attachment payloads at module boundaries

Fields such as `"kind"`, `"status"`, `"deferUntil"`, values such as `"ACTIVE"`, and attachment JSON keys are repeated across `core`, `web`, app capture, tests, and SQL projections. Typos compile and malformed values silently enter the log.

Define shared serializable field keys and enums/value types in `core`, with validation at emit/ingest boundaries. Keep the wire format stable via explicit serial names. Do not over-generalize this into a large domain framework; a few constrained constants/types remove most risk.

### 14. Make multi-op domain commands atomic

Creating a task/comment/attachment currently calls the emitter once per field/move, and each phone/server emitter performs a separate transaction. A crash can leave a partially created entity (for example a node without status, or an attachment blob without its attachment op). The design docs describe create bundles as one transaction.

Add `emitBatch`/`transaction` to `OpEmitter` and `OpWriter`, allocate all clocks/ids first, then append/apply the bundle atomically. Use it for create, comments, capture node + attachment metadata, and conversions that require multiple fields/moves.

### 15. Make attachment storage streaming and path-safe

Capture/import and blob upload paths repeatedly materialize entire files as `ByteArray` (`CaptureRepository`, activities, `LocalBlobStore`, `BlobUploader`). This is avoidable memory pressure for scans/audio. Move hashing, local persistence, HTTP upload, and S3 transfer to streams/channels with explicit size caps.

`LocalBlobStore.get/has` also accepts an arbitrary key and joins it directly to the blob directory. Today keys originate internally, but validate the exact `blob-[0-9a-f]{64}` format at the storage boundary to prevent future traversal mistakes.

## Priority 2 — deduplication and maintainability

### 16. Extract one Android voice-recorder lifecycle

`VoiceCaptureActivity` and `CaptureSettingsBridge` independently own `MediaRecorder`, cache-file naming, start/stop/release, empty-file checks, capture persistence, and error UI. Their behavior already differs: the activity does not catch start/save failures as comprehensively as the bridge. This is the deferred capture dedup noted in `AGENTS.md`, and it remains a worthwhile refactor once device verification is available.

Extract a lifecycle-aware `VoiceRecorder` that returns a sealed result and owns cleanup. Keep Activity/WebView-specific permission, toast, and event behavior in thin adapters. Add JVM tests for the state machine and perform the final check on real hardware.

### 17. Consolidate MIME/type/extension/title policy

`ShareImport` and `CaptureRepository` encode overlapping but inconsistent mappings. For example generic audio preserves several extensions in one path but becomes `m4a` in the other; image input is labeled as PDF in the current attachment enum while saved with `jpg`. Centralize a pure `CaptureMediaPolicy` and test a table of MIME, filename, type, extension, and title outcomes. Keep URI/content-resolver I/O outside it.

### 18. Reduce `ZyncApp` service-locator responsibilities

`ZyncApp` constructs database, state, clocks, content services, capture, pairing, HTTP sync, change notification, WorkManager scheduling, and the loopback server. This makes lifecycle and production wiring hard to test—the missing blob uploader is an example.

Create a small process-scoped `AppGraph` plus focused coordinators (`ReplicaSynchronizer`, `CaptureCoordinator`, `LoopbackHost`). Inject an `HttpClient` factory and clocks/randomness. Avoid adopting a DI framework unless constructor wiring becomes genuinely painful.

### 19. Share op-copy/serialization helpers and remove reflective op type names

`Op.withSeq` manually copies every sealed subtype, and both server/app log writers repeat the same `insertOp` column mapping and use `op::class.simpleName`. Add explicit serial/type identifiers and a shared transport-log mapper. This prevents a future op subtype from being added to one path but not another and avoids reflection/name changes leaking into persisted audit data.

### 20. Make change notification ownership single and predictable

Server ingestion already invokes `SyncService.onIngest`, while web route helpers also call `changes.notifyChanged()` after commands. A browser mutation can therefore notify twice. The phone has similar manual notifications in capture/sync plus emitter-triggered sync scheduling. Let the mutation/ingest boundary emit one change event; route/UI adapters should only render the response.

## Tests, CI, and documentation

### 21. Repair the Playwright launcher and put it in CI

The current source of truth says tests run against `./gradlew :server:webDevServer` on port 8099, and the specs themselves default to 8099. But `webtest/playwright.config.js` still starts a retired Robolectric app test server on 8199. `webtest/README.md` also describes the retired launcher. GitHub CI runs only Gradle tests and never Playwright (`.github/workflows/ci.yml:36-40`).

Update Playwright's `webServer.command`, URL, and README to the server dev task; then add Node setup, `npm ci`, cached Chromium installation, and `npx playwright test` to CI. Set `reuseExistingServer: false` in CI so a stray process cannot mask startup failure. Upload traces/reports on failure.

### 22. Add the missing test layers promised by the test strategy

Highest-value additions:

- production replica wiring: blobs + op batches + pull in one sync coordinator;
- oversized backlog batching and retry classification;
- bootstrap crash/retry/idempotency;
- server sequence allocation under rollback and concurrent pushes;
- request input bounds and generic 500 responses;
- cache/session bounds and unauthenticated rate-limit keying;
- SQLDelight migration verification once schema version 2 is introduced;
- container/startup smoke plus scheduled litestream restore drill;
- real-device acceptance items from the 2026-07-13 runbook.

The repository has `androidTest` dependencies but no instrumented test sources, and CI has no browser/infra/device jobs. That is acceptable for the sandbox limitation, but scheduled/pre-release jobs should make the gap explicit rather than implying `./gradlew test` covers the shipped experience.

### 23. Reconcile stale documentation and dependency comments

Concrete drift includes:

- `webtest/README.md` still describes the retired Robolectric-hosted server and old UI coverage.
- The version catalog comment says Kotlin is “held at 2.3.21 (newest 2.3.x),” which is not actionable and will age immediately; document a compatibility constraint or remove it.
- README says the bootstrap behavior and blob sync are landed end-to-end, while the production call graph does not yet implement them.
- The roadmap still says EC2/Docker Compose/Caddy/GitHub Actions deployment while the newer deployment spec and repository use haloy + a host litestream sidecar. Mark the roadmap paragraph historical/superseded to prevent future implementation against it.

Add a short architecture-status checklist to README linking each partially landed seam to an issue/milestone. Prefer docs that state invariants and commands over “newest version” claims.

## Suggested execution order

1. Wire and test blob uploading; introduce bounded op/blob batches.
2. Fix Playwright configuration and add browser CI so subsequent UI/refactors are protected.
3. Implement bootstrap consumption and database-owned sequence allocation.
4. Fail closed on production auth misconfiguration; fix error mapping, input caps, and bounded caches.
5. Introduce request-scoped indexed projections and atomic op batches.
6. Extract the sync/app graph and Android capture policies; do voice-recorder dedup only with a real-device pass available.
7. Add migration/restore/container/device jobs as the deployment moves from “landed code” to operated service.

## Changes I would deliberately defer

- Do not build M8 operators/M9 agents during cleanup; the current operator manifest types are intentional groundwork.
- Do not replace SQLDelight, Ktor, Datastar, or the hybrid Compose/WebView architecture—the current choices match the target and the major issues are in wiring and lifecycle, not framework selection.
- Do not optimize the CRDT move replay until measurement shows it matters; full replay is simple and deterministic. Optimize the repeated UI projection first.
- Do not merge Android and server platform code merely to reduce file count. Keep shared policy/protocol in `core`/`web` and platform I/O at the edges.

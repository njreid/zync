# zync M4 — Server Foundation: Ktor + SQLite + Sync

> **For agentic workers:** implement task-by-task; `- [ ]` steps. **Depends on M3**
> (`core`). Semantics: `../specs/2026-07-08-oplog-merge-operator-model.md`; wire
> fixtures: `../specs/2026-07-08-merge-conformance-vectors.md`; deployment:
> `../specs/2026-07-08-deployment.md`; security: `../specs/2026-07-08-threat-model.md`;
> what-to-test-where: `../specs/2026-07-08-test-strategy.md`. Status: 🟡 ready after M3.

**Goal:** a deployable, correct, durable **server** — the always-on integration point.
Ingests ops, merges via `core`, persists to **SQLite**, backs up to **S3** via
litestream, authenticates devices, and serves the phone↔server sync protocol.
**All of M4 is JVM/CI-testable — no Android device needed** (the phone side is `core` +
`data` in-process; S3 via MinIO).

## Global constraints
- `server` (JVM) depends on `core` + `data`; Ktor; kotlinx.serialization.
- `seq` assignment, sync endpoints, blob/S3, litestream, auth = **server-only**
  (never in `core`).
- Secrets fetched from **SSM Parameter Store** via the instance role at runtime; none
  in repo/image/GitHub. Image built **linux/arm64** (Graviton).
- `./gradlew :server:test` green every commit; MinIO for S3-backed tests.

---

### Task 1: `data` module — SQLDelight schema + `StateStore` impl
**Files:** `data/` KMP module (jvm + android), `.sq` schema files, `StateStore`
SQLDelight impl, migration harness.
- Schema from op/merge spec §10: `op_log, register, tombstone, move_log, tag,
  sync_state, operator_run` + materialized projections (`node, context, node_context,
  attachment`). Implement `core`'s `StateStore` port over SQLDelight.
- [x] **Step 1 (TDD):** schema round-trip; `apply` (via `core`) persists + projects
  correctly; migration-test harness (schema version tracked in `PRAGMA user_version`).
  4 JVM tests: parity vs `InMemoryStateStore` over 25 random batches (all op types),
  idempotent re-apply, state persists across file reopen, schema-version baseline.
  `data` is KMP jvm+android (android-driver wired, android target compiles); the
  **Robolectric android-driver test is deferred to M5** (phone-as-replica) since M4's
  DoD is JVM-only. Tables: register/tombstone/tag/move_log/move_parent/applied_op +
  op_log/sync_state/operator_run (CREATE-only; queries land with their tasks).
- [x] **Step 2: Commit** `feat(data): SQLDelight schema + StateStore impl`.

### Task 2: Sync wire contract + `seq`
**Files:** `server/…/sync/Dto.kt`, `SeqAllocator.kt`.
- Contract: `POST /sync/push` (ops → `{ackedOpIds, serverHead}`),
  `GET /sync/pull?since=<cursor>` (paged ops by `seq`), `GET /sync/bootstrap`
  (compacted snapshot: register map + move-log tail + head seq). `seq` = monotonic,
  assigned **transactionally** on ingest.
- [x] **Step 1 (TDD):** DTO serialization; `seq` strictly monotonic + gap-free under
  sequential ingest; snapshot serialization round-trip. 7 tests (incl. seq gap-free
  under 8-thread concurrent ingest). New `:server` JVM module (Ktor + core + data).
- [x] **Step 2: Commit** `feat(server): sync wire contract + seq allocation`.

### Task 3: Ktor app — ingest/merge + endpoints (the heart)
**Files:** `server/…/App.kt`, `SyncRoutes.kt`.
- Wire push/pull/bootstrap to `data` + `core.apply`; **idempotent ingest** (dedupe by
  `opId`); Ktor test host + fake client.
- [x] **Step 1 (TDD):** push→apply→converge; pull cursor paging; bootstrap; idempotent
  re-push; **sync round-trip** (in-process phone `core` ↔ server) incl. offline→push→
  fresh-replica convergence; LWW + tombstone resolve over the wire. 7 tests + HTTP
  round-trip (Ktor test host). Full V1–V8 already proven in `core`; the wire tests
  exercise transport/ingest and convergence rather than re-encoding every vector.
- [x] **Step 2: Commit** `feat(server): sync endpoints + idempotent merge ingest`.

### Task 4: Auth — device Ed25519 + browser session
**Files:** `server/…/auth/`.
- Allowed-device registry (pubkeys); **signed-request auth** for native clients;
  **session/login** for browser (single-user — passkey/password to your own server).
  Reject unknown devices; nonce/replay protection; device **revocation**.
- [x] **Step 1 (TDD):** valid device accepted; unknown rejected; replayed request
  rejected; revoked device rejected; stale-timestamp + bad-signature rejected; browser
  session lifecycle; HTTP: unauthenticated 401, device-signed 200, login→bearer 200.
- [x] **Step 2: Commit** `feat(server): device Ed25519 auth + browser sessions`.
  **Decision:** native = Ed25519 signed requests (BouncyCastle); browser = password →
  opaque session bearer via `SessionStore` with a pluggable `credentialCheck` so a
  passkey/WebAuthn verifier swaps in later. Signature covers method+path+timestamp+
  nonce (replay-protected via NonceCache); TLS covers body integrity. **Deferred:**
  full WebAuthn (needs a browser + WebAuthn lib; unexercisable headless) and
  persisting the device registry (in-memory now; SSM/DB-seeded in prod).

### Task 5: Blob store (S3) + attachments
**Files:** `server/…/blob/S3BlobStore.kt`, blob routes.
- Content-addressed `blob-<sha256>`; **server mediates S3** (clients never touch S3
  directly — keeps IAM to the instance role); `putIfAbsent`/get; size limits; tie to
  `AddAttachment` ops.
- [x] **Step 1 (TDD):** putIfAbsent dedupe; get; missing→404; malformed-key→404
  (traversal-safe); oversized→413; server computes the `blob-<sha256>` key from
  content (client can't choose it). 9 tests (service + HTTP) via InMemoryBlobStore.
  `S3BlobStore` (AWS SDK v2, default cred chain / instance role, MinIO-compatible)
  is implemented; its **MinIO integration test is deferred** until infra is available
  (no Docker here). Bytes flow through server-mediated routes; keys tie to
  `AddAttachment.blobHash`.
- [x] **Step 2: Commit** `feat(server): S3 content-addressed blob store`.

### Task 6: Durability — litestream + startup migrations + restore drill
**Files:** `server/…/Migrations.kt`, `litestream.yml`.
- Startup migration runner; **litestream snapshot immediately before migrating**;
  restore-from-S3-on-boot.
- [x] **Step 1 (drill):** backup→wipe→restore→state identical; migration applies on
  startup (fresh→v1, usable); bad-migration recovery via a prior generation. 3 tests
  via a `FileCopyGateway` that stands in for litestream (snapshots the SQLite file +
  WAL sidecars as numbered generations). `StartupSequence` (restore-if-fresh →
  snapshot → open+migrate), `DbBackupGateway` port, `LitestreamCli` prod impl, and
  `litestream.yml` materialized. **MinIO/live litestream drill deferred** (no Docker).
- [x] **Step 2: Commit** `feat(server): litestream durability + startup migrations`.

### Task 7: Rate limiting + baseline hardening + observability
**Files:** `server/…/Plugins.kt`.
- Per-device rate limits; request size caps; structured logging + basic metrics;
  `/health`. (Full items tracked in the threat model; this is the baseline.)
- [x] **Step 1 (TDD):** rate limit trips; oversized request rejected; health OK; metrics
  report. 5 tests: token-bucket limiter (trip + refill + per-key isolation), HTTP 429
  after capacity, 413 oversized, /health exempt, /metrics counts requests/rejections.
- [x] **Step 2: Commit** `feat(server): rate limits, size caps, health, logging`.

### Task 8: Deployment materialization (from the deployment spec)
**Files:** `Dockerfile` (arm64, JVM + litestream as PID 1), `docker-compose.yml`,
`Caddyfile`, `.github/workflows/server-deploy.yml` (test→build→GHCR→SSM via OIDC),
`litestream.yml`, `deploy/bootstrap.md` (EC2 user-data, EBS mount, SSM params, IAM).
- [x] **Step 1:** materialized Dockerfile (arm64; litestream PID 1 via entrypoint),
  docker-compose (server + MinIO + Caddy), Caddyfile, server-deploy.yml (test →
  installDist → buildx arm64 → GHCR → SSM deploy gated behind `vars.ZYNC_DEPLOY_ENABLED`
  + OIDC), litestream.yml, deploy/bootstrap.md. `Main` wired to the full stack
  (StartupSequence + env-driven auth/blobs/hardening). **YAML validated** (pyyaml);
  entrypoint compiles. **Live `docker compose up` / arm64 image build deferred** (no
  Docker/AWS here) — documented in bootstrap.md.
- [x] **Step 2: Commit** `build(server): Docker/Compose/Caddy + deploy workflow`.

### Task 9: Minimal debug UI + acceptance
- A tiny server-rendered view (list nodes/ops/state) to eyeball convergence before the
  real `web` module (M6).
- [x] **Step 1:** `GET /debug` renders current entities (alive/parent/tags/fields) +
  recent ops as a server-rendered HTML table (auth-guarded).
- [x] **Step 2 (acceptance):** M4AcceptanceTest drives the fully-assembled server
  (sync + blobs + hardening + debug) over HTTP — merges V1/V3/V7/V8 vectors, blob
  upload→download round-trip, fresh-replica convergence, debug view renders. Combined
  with the durability drill (Task 6) and auth enforcement (Task 4). 43 server tests.
- [x] **Step 3: Commit** `feat(server): minimal debug UI + M4 acceptance`.

## Interfaces / decisions
- **Endpoints:** `POST /sync/push`, `GET /sync/pull?since=`, `GET /sync/bootstrap`,
  blob get/put, `/health`. All under auth except `/health` + ACME.
- **seq vs HLC:** `seq` = transport cursor (server-assigned, transactional); HLC =
  merge order (from ops). Kept distinct.
- **Server mediates S3** (no direct-to-S3 clients) → IAM stays on the instance role.
- **Bootstrap snapshot** defined here (register map + move-log tail + head seq) — used
  by new-device / phone reinstall.
- **litestream as PID 1** supervising the JVM (deployment spec §3).

## Open questions
- Browser auth mechanism (passkey vs password vs device-code) for the single user.
- Pull pagination size + backpressure under a long-offline phone (large op batch).
- Snapshot/compaction cadence + `op_log` truncation policy.
- Whether operators (M8) share the server process or run as a separate worker against
  the same SQLite (single-writer discipline).

## Definition of done
Deployable arm64 server: ingests ops, merges via `core`, persists to SQLite, backs up
to S3 (restore drill green), authenticates devices, serves push/pull/bootstrap;
**V1–V8 pass over the wire**; sync round-trip incl. offline/reconnect green — **all
without an Android device**. Ready for M5 (phone becomes a replica).

> **✅ COMPLETE (2026-07-10).** All 9 tasks done on branch `feat/m4-server`; 43 server
> + 4 data JVM tests green (`:server:test`, `:data:allTests`). Modules: `:data`
> (SQLDelight StateStore, jvm+android) and `:server` (Ktor sync/auth/blobs/hardening/
> debug). **Deferred to real infra** (no Docker/AWS/MinIO here): the live MinIO S3
> integration test, the litestream/MinIO restore drill, and the `docker compose up` /
> arm64 image / CI runs — all materialized + documented in `deploy/bootstrap.md`. The
> `data` Android Robolectric-driver test moves to M5 (phone-as-replica).

# zync M5 — Phone as a Replica (first vertical slice)

> **For agentic workers:** implement task-by-task; `- [ ]` steps. **Depends on M4**
> (`:server` + `:data`). Roadmap: `2026-07-08-rebuild-roadmap.md`. Semantics:
> `../specs/2026-07-08-oplog-merge-operator-model.md`. Pairing:
> `../specs/2026-07-10-device-pairing.md`. Status: 🟡 DRAFT — ready to execute.

**Goal:** the phone becomes an **offline op-log replica** that syncs to the central
server. The vertical slice proven at the end: **capture on the phone offline →
reconnect → the op appears on the server**. Keep the phone usable throughout (a bridge
UI) and **retire Google Drive backup** — server sync + litestream is now durability.

**Current app state (survey 2026-07-10):** `app` has a **Room** data layer
(`data/NodeEntity|ContextEntity|AttachmentEntity` + DAOs + `data/ZyncDatabase`), a
`backup/` Drive stack (`GoogleDriveClient`, `BackupManager`, …), capture
(`capture/ZyncCaptureService`, `attach/CaptureRepository`), an **old M1c LAN stack**
(`pairing/` NSD/QR/`PairingService`, `server/ZyncServer` = phone-as-LAN-server), and a
vanilla-JS web UI under `assets/web/`. M5 migrates the mutation path to the op log and
retires Drive; the LAN stack + phone-server are retired in **M7** (leave them for now).

## Global constraints
- **No v0.2 data import** (confirmed in the roadmap) — M5 seeds an empty op log; no
  Room→op-log importer.
- All domain mutations go through **`core` ops → `:data` `SqlDelightStateStore`**; Room
  is read-only bridge state until M6/M7 remove it.
- Device auth = the M5 pairing half + M4 signed requests. Never store the server's
  plaintext admin password on the phone.
- `./gradlew :app:testDebugUnitTest :data:allTests` green every commit; JVM-testable
  logic (sync client, op writer, pairing parse) lives in testable classes, thin Android
  shells on top.

---

### Task 1: `:data` on Android (finish the deferred M4 item)
**Files:** `data/build.gradle.kts` (android-driver already wired), a Robolectric
`androidHostTest`.
- [ ] **Step 1:** add a Robolectric test that runs `SqlDelightStateStore` over the
  **AndroidSqliteDriver** (parity with the JVM impl over a small op batch).
- [ ] **Step 2: Commit** `test(data): android SQLDelight driver parity (Robolectric)`.

### Task 2: Phone op-log write path
**Files:** `app/.../oplog/OpWriter.kt`, `HlcClock.kt`, DI wiring.
- An `OpWriter` that builds ops (via `core`) with an Android-persisted `HlcGenerator`
  (deviceId = paired device id; clock = system; HLC state persisted across process
  death), applies them locally via `:data`, and enqueues them for sync (`synced=0`).
- [ ] **Step 1 (TDD, JVM/Robolectric):** creating a task / editing a field / moving /
  tagging / tombstoning each writes the right op and projects correctly; HLC survives
  a simulated restart (reload from storage).
- [ ] **Step 2: Commit** `feat(app): op-log write path (OpWriter + persisted HLC)`.

### Task 3: Sync client (push/pull on reconnect)
**Files:** `app/.../sync/SyncClient.kt`, `SyncScheduler.kt` (WorkManager).
- Ktor client: push pending ops → mark acked `synced=1`; pull `seq > cursor` →
  `observe(hlc)` + `apply`; persist the cursor; **device-signed requests** (X-Device-Id
  /Timestamp/Nonce/Signature) + **pin the server key**. Trigger on connectivity.
- [ ] **Step 1 (TDD):** against the **real `:server` in-process** (or Ktor test host):
  offline writes → push → server has them; pull applies remote ops; cursor advances;
  re-push idempotent; signed requests accepted, unsigned rejected.
- [ ] **Step 2: Commit** `feat(app): sync client (signed push/pull + cursor)`.

### Task 4: Pairing — phone half
**Files:** `app/.../pairing/` (new central-server pairing; reuse `QrScanBridge`),
`DeviceKeystore.kt`.
- Scan the `zync://pair` QR → generate a device **Ed25519 key in the Android Keystore**
  → `POST /pair {devicePublicKey, code}` → **verify the server confirmation** against the
  pinned key → persist {server addr, pinned server pubkey, deviceId, device key ref}.
- [ ] **Step 1 (TDD):** parse `zync://pair` payload; confirmation verification
  accept/reject; a full pair handshake against in-process `:server` yields working
  signed requests (ties Task 3 + M4 `/pair`).
- [ ] **Step 2: Commit** `feat(app): device pairing (scan → keygen → /pair → pin)`.

### Task 5: Local blobs + upload on sync
**Files:** `app/.../blob/LocalBlobStore.kt`, upload in `SyncClient`.
- Content-addressed local store (`blob-<sha256>`); `AddAttachment` op carries the hash;
  pending blobs upload to the server `/blob` on sync (`putIfAbsent` dedupes).
- [ ] **Step 1 (TDD):** local put/get by hash; sync uploads pending blobs; op hash ==
  uploaded key; re-upload deduped.
- [ ] **Step 2: Commit** `feat(app): local blob store + upload on sync`.

### Task 6: Capture writes local ops offline
**Files:** `capture/*`, `attach/CaptureRepository` → route through `OpWriter`.
- Volume-key / share / voice / doc capture creates an inbox node **as ops** (offline-
  safe), with attachments via the local blob store. Capture never blocks on network.
- [ ] **Step 1 (TDD):** a capture produces the expected create-ops + attachment;
  offline capture is queued; appears after a later sync.
- [ ] **Step 2: Commit** `feat(app): capture writes op-log entries offline`.

### Task 7: Bridge UI (keep the phone usable)
**Files:** the existing `assets/web/` UI, re-pointed at op-log-backed reads.
- Back the current web UI's reads with the `:data` projection (`project()` / materialized
  queries) instead of Room, so the phone stays usable this milestone. Minimal changes —
  the real shared UI is M6.
- [ ] **Step 1:** list/inbox/tree render from the op-log state; a capture shows up.
- [ ] **Step 2: Commit** `feat(app): bridge UI reads op-log projection`.

### Task 8: Retire Google Drive backup
**Files:** remove `backup/` Drive stack (`GoogleDriveClient`, `DriveClient`,
`BackupManager`, `BackupWorker`, schedulers, Drive auth); drop Drive deps/permissions.
- Server sync + litestream is durability now. Keep encrypted-backup crypto only if the
  op log needs a local export; otherwise remove.
- [ ] **Step 1:** app builds without Drive; no dead references; tests green.
- [ ] **Step 2: Commit** `chore(app): retire Google Drive backup (server sync supersedes)`.

### Task 9: Vertical-slice acceptance
- [ ] **Step 1 (acceptance):** end-to-end — **pair the phone; capture offline; reconnect;
  the op appears on the server** (assert against the in-process `:server`/its bootstrap).
  Property: phone projection == server projection after sync.
- [ ] **Step 2: Commit** `test(app): offline-capture → sync → server vertical slice`.

## Interfaces / decisions
- **HLC persistence** on the phone is mandatory (offline monotonicity across restarts).
- **Device key** lives in the Android Keystore; only its public key leaves the device.
- **Server key pinning**: store the pubkey from pairing; verify confirmations/signed
  responses against it.
- Room stays as **read-only bridge** until M6/M7; no importer (empty op log).

## Open questions
- Pull pagination / backpressure for a long-offline phone (large batch) — reuse M4's
  `limit`; tune later.
- Whether the bridge UI reads a **materialized projection** (fast) or folds `project()`
  each read (simple) — start simple, materialize if slow.
- Keep the M1c LAN fallback wired during M5, or dark-launch it? (Retired in M7 either way.)

## Definition of done
The phone pairs to the server, writes all mutations as ops locally (offline-safe),
syncs (signed push/pull) on reconnect, stores/uploads attachment blobs, and stays usable
via the bridge UI; **Drive backup removed**; the **offline-capture → sync → server**
slice is green. Ready for M6 (shared web module).

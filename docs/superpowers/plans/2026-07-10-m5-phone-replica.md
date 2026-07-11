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
- [x] **Step 1:** Robolectric test runs `SqlDelightStateStore` over the
  **AndroidSqliteDriver** (parity with the in-memory reference over a mixed op batch).
  `AndroidZyncDatabase` factory added in `:data` androidMain; test lives in `:app`
  (reuses the app's proven Robolectric). App now depends on `:core` + `:data`.
- [x] **Step 2: Commit** `test(data): android SQLDelight driver parity (Robolectric)`.

### Task 2: Phone op-log write path
**Files:** `app/.../oplog/OpWriter.kt`, `HlcClock.kt`, DI wiring.
- An `OpWriter` that builds ops (via `core`) with an Android-persisted `HlcGenerator`
  (deviceId = paired device id; clock = system; HLC state persisted across process
  death), applies them locally via `:data`, and enqueues them for sync (`synced=0`).
- [x] **Step 1 (Robolectric):** create/setField/move/tag/tombstone each write the right
  op, project correctly, and queue as unsynced; parity with InMemoryStateStore; HLC
  survives a simulated restart (reload from `HlcStore`, wall clock moved backwards).
- [x] **Step 2: Commit** `feat(app): op-log write path (OpWriter + persisted HLC)`.

### Task 3: Sync client (push/pull on reconnect)
**Files:** `app/.../sync/SyncClient.kt`, `SyncScheduler.kt` (WorkManager).
- Ktor client: push pending ops → mark acked `synced=1`; pull `seq > cursor` →
  `observe(hlc)` + `apply`; persist the cursor; **device-signed requests** (X-Device-Id
  /Timestamp/Nonce/Signature) + **pin the server key**. Trigger on connectivity.
- [x] **Step 1 (Robolectric):** against a Ktor MockEngine server backed by the **real
  `core` merge**: offline writes → push marks acked synced + server converges; a fresh
  phone pulls + converges + advances the persisted cursor; second pull is a no-op;
  re-push sends nothing new; **every request's Ed25519 signature is verified**. Wire
  DTOs moved to `:core` (`core.sync`) so phone + server share the contract.
- [x] **Step 2: Commit** `feat(app): sync client (signed push/pull + cursor)`.

### Task 4: Pairing — phone half
**Files:** `app/.../pairing/` (new central-server pairing; reuse `QrScanBridge`),
`DeviceKeystore.kt`.
- Scan the `zync://pair` QR → generate a device **Ed25519 key in the Android Keystore**
  → `POST /pair {devicePublicKey, code}` → **verify the server confirmation** against the
  pinned key → persist {server addr, pinned server pubkey, deviceId, device key ref}.
- [x] **Step 1:** parse `zync://pair` payload; full pair handshake against a MockEngine
  `/pair` (real Ed25519 signing); **pin** the server key (must match the QR), **verify**
  the server confirmation. 5 tests: parse, success returns pinned creds, wrong code /
  key-mismatch / bad-confirmation all rejected. Pairing DTOs + confirmation message
  moved to `:core` (`core.pairing`). URI values URL-encoded (base64 safe).
- [x] **Step 2: Commit** `feat(app): device pairing (scan → keygen → /pair → pin)`.

### Task 5: Local blobs + upload on sync
**Files:** `app/.../blob/LocalBlobStore.kt`, upload in `SyncClient`.
- Content-addressed local store (`blob-<sha256>`); `AddAttachment` op carries the hash;
  pending blobs upload to the server `/blob` on sync (`putIfAbsent` dedupes).
- [x] **Step 1:** content-addressed local put/get (golden sha256("") key matches the
  server format); signed upload posts bytes to /blob and agrees on the key; missing key
  is a no-op. 3 tests. `BlobKeyResponse` moved to `:core`; `signedHeaders` extracted
  and shared by the sync client + uploader.
- [x] **Step 2: Commit** `feat(app): local blob store + upload on sync`.

### Task 6: Capture writes local ops offline
**Files:** `capture/*`, `attach/CaptureRepository` → route through `OpWriter`.
- Volume-key / share / voice / doc capture creates an inbox node **as ops** (offline-
  safe), with attachments via the local blob store. Capture never blocks on network.
- [x] **Step 1 (Robolectric):** `ReplicaCapture.captureNote`/`captureAttachment` create
  the inbox node + attachment as ops, store the blob locally, and queue everything
  unsynced (offline). 2 tests. `OpWriter.createAttachment` mints the attachment entity.
  (Wiring the Android capture triggers to this seam is compile-safe glue, kept minimal
  until the Task 7/8 restructure.)
- [x] **Step 2: Commit** `feat(app): capture writes op-log entries offline`.

### Task 7: Bridge UI (keep the phone usable)
**Files:** the existing `assets/web/` UI, re-pointed at op-log-backed reads.
- Back the current web UI's reads with the `:data` projection (`project()` / materialized
  queries) instead of Room, so the phone stays usable this milestone. Minimal changes —
  the real shared UI is M6.
- [x] **Step 1:** `BridgeReadModel` folds the op-log projection into the UI's view data
  (inbox / children / node view); captures show up, completed items hide from the inbox,
  tombstoned nodes drop out. 2 Robolectric tests. (The JSON-serving wiring into the
  phone's loopback web server is glue on top of this tested read model.)
- [x] **Step 2: Commit** `feat(app): bridge UI reads op-log projection`.

### Task 8: Retire Google Drive backup
**Files:** remove `backup/` Drive stack (`GoogleDriveClient`, `DriveClient`,
`BackupManager`, `BackupWorker`, schedulers, Drive auth); drop Drive deps/permissions.
- Server sync + litestream is durability now. Keep encrypted-backup crypto only if the
  op log needs a local export; otherwise remove.
- [x] **Step 1:** app builds without Drive; no dead references; tests green (183).
- [x] **Step 2: Commit** `chore(app): retire Google Drive backup (server sync supersedes)`.



### Task 9: Vertical-slice acceptance
- [x] **Step 1 (Robolectric acceptance):** end-to-end — **pair → capture offline (note +
  attachment) → reconnect → upload blob + sync → ops land on the server**, against a
  fake server backed by the real `core` merge that verifies the paired device's
  signatures. Property: phone projection == server projection; blob present; captured
  titles on the server. (19 replica tests green together.)
- [x] **Step 2: Commit** `test(app): offline-capture → sync → server vertical slice`.

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

> **✅ COMPLETE (2026-07-11).** All 9 tasks done, merged; **19 replica
> tests** green (Robolectric) + core/data/server suites. The vertical slice —
> pair → capture offline → sync → lands on server, phone==server — is proven; Drive backup
> retired (183 app tests). Modules:
> replica logic in `:app` (op writer, sync client, pairing, blobs, capture, bridge read
> model); wire DTOs shared via `:core` (`core.sync`, `core.pairing`); `:data` runs on
> Android. Ready for M6.

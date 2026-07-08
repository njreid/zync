# zync M3 — Durable Capture & Complete Backup/Restore

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Status: 🟡 DRAFT — awaiting confirmation.** Scope chosen 2026-07-07:
> *attachment robustness* + *finish backup/restore*. Do not execute until the
> owner confirms Task 1's storage decision (see Open Questions).
>
> **⚠️ Tasks 4–5 are under architectural review.** See
> `docs/superpowers/specs/2026-07-08-backup-sync-architecture.md` (ADR): the
> Google-Drive backup direction may be replaced by a self-hosted, E2E-encrypted
> op-log + blob store (Go daemon + S3). If that ADR is accepted, Tasks 4–5 become
> "sync to the zync daemon" rather than "backup to Drive" — though the
> content-addressed encrypted-blob design carries over almost unchanged. Hold on
> executing Tasks 4–5 until the ADR forks are settled.

**Goal:** Make captured attachments durable and make the encrypted Google Drive
backup actually complete and restorable. Two coupled themes carried over from the
M2 review as deferred work: (A) attachment robustness — a portable storage root
that survives reinstall, on-device transcription/OCR, and streaming I/O so large
captures don't OOM; (B) finish Task 4 — incremental content-addressed upload
(stop re-uploading everything), auto-detect-and-restore on a fresh install/new
device, and a real-device Drive verification pass.

**Context / current state (verified 2026-07-07):**
- **Storage root is app-private and lost on uninstall.** `AttachmentStore.defaultRoot`
  (`attach/AttachmentStore.kt`) uses `context.getExternalFilesDir(DIRECTORY_DOCUMENTS)`
  → `Android/data/dev.njr.zync/files/Documents/Zync`, which is deleted on uninstall
  and is not the portable `Documents/Zync` root spec §10a intends. `AttachmentStore`
  is content-addressed (`sha256`, 2-char shard) with a solid path-traversal guard,
  and stores files by `relativePath` recorded on `AttachmentEntity` (Room v3).
- **No on-device transcription/OCR.** `AttachmentType` models `TRANSCRIPT` and
  `OCR_TEXT`, but no capture path produces them (no `SpeechRecognizer`, no ML Kit
  Text Recognition). Raw `AUDIO`/`PDF` are stored; derived text is simply absent.
- **Whole-file-in-memory I/O.** Capture (`CaptureRepository`, `ShareReceiverActivity`,
  the `capture/` activities) and the download route read entire blobs into a
  `ByteArray`; backup builds the full ZIP + ciphertext in memory and
  `GoogleDriveClient.uploadBackup` buffers the whole multipart body. Large PDFs /
  long recordings can OOM.
- **Backup is full-archive, not incremental.** The live path (`BackupManager.createArchive`
  → `BackupCrypto.encrypt` → `GoogleDriveClient.uploadBackup`) zips + encrypts +
  uploads the entire DB snapshot **and every attachment** on every run, as a single
  `zync-<ts>.zyncbackup` blob in Drive `appDataFolder` (last 5 kept). The 2026-07-07
  review already fixed DB snapshot consistency (`wal_checkpoint(TRUNCATE)` on Room's
  connection, main-`.db`-only archive) and made restore verify-before-destroy.
- **Restore only runs when armed on the same install.** `RestoreManager.restoreIfRequested`
  fires only if `BackupSettings.restorePending` is set (via `BackupController.requestRestore`).
  Both the flag and the AES passphrase (keystore-wrapped in `zync_backup` prefs) are
  wiped on uninstall, so a fresh install cannot auto-detect+restore today. Restore is
  correctly awaited **before** `ensureServerStarted()` in `MainActivity`.
- `DriveClient` interface is minimal: `uploadBackup(name, bytes)` + `latestBackup()`.
  `appDataFolder` scope (`drive.appdata`), token via `GoogleAuthUtil`.
- Build: `minSdk 34`, `targetSdk 36`, Room v3, Robolectric `./gradlew test`.

## Global Constraints

- Keep the M1c security posture and the M2 review fixes intact: on-device secrets
  only, ciphertext-only to Drive, fresh IV per encryption, verify-before-destroy
  restore, `allowBackup=false`.
- Prefer Jetpack + GMS patterns already in use (ML Kit doc scanner, GoogleSignIn,
  WorkManager). No new UI frameworks; web settings stay vanilla-JS + CSP-clean.
- `minSdk 34` → scoped storage. Do **not** request `MANAGE_EXTERNAL_STORAGE`
  (Play-policy restricted). Any DB schema change ships a Room migration + test.
- Robolectric-first (`./gradlew test`); on-device-only behaviors (real Drive,
  SpeechRecognizer, MediaStore/SAF, MediaRecorder, restore-on-fresh-install) get a
  final emulator/device verification step, mirroring M1c-T8 / M1d-T7.
- **CI reminder:** the M2 review fixes are static-verified only; run `./gradlew test`
  at the start of M3 to establish a green baseline before changing backup code.

---

### Task 1: Portable attachment storage that survives reinstall (spec §10a)

**Decision first (Step 1):** choose the storage mechanism. Candidates:
- **(a) SAF tree (recommended):** one-time `ACTION_OPEN_DOCUMENT_TREE` to a
  user-picked "Zync" folder; persist the tree URI. Survives uninstall, user-visible
  and portable, no scary permission. Cost: `AttachmentStore` moves from `File` to
  `DocumentFile`/`ContentResolver` URIs (touches every read/write/resolve/backup path).
- **(b) MediaStore Documents:** programmatic writes to the shared Documents
  collection, no folder picker. Cost: content-addressed subtree layout is awkward
  over MediaStore; entries survive uninstall but management is clunkier.
- **(c) Keep app-private, rely on backup for durability:** simplest; portability
  comes entirely from Task 4/5. Contradicts §10a's "portable folder" intent.

**Files:**
- Edit: `attach/AttachmentStore.kt` (storage abstraction behind its current API:
  `write`/`read`/`resolve`/`delete` over the chosen backend; keep content-addressing
  + traversal guard).
- Create: a one-time migration that copies existing `getExternalFilesDir` attachments
  into the new root and rewrites nothing in the DB (relative paths are stable).
- Edit: capture entry points + `server/ApiRoutes.kt` download route to go through
  the abstraction.

- [ ] **Step 1 (decision + TDD):** Pick (a)/(b)/(c) with the owner. Write
  `AttachmentStore` tests for the chosen backend (write→read round-trip, traversal
  rejection, dedup, delete) — Robolectric where possible; a device step otherwise.
- [ ] **Step 2:** Implement the backend + one-time migration of existing files;
  keep `./gradlew test` + `webtest` green.
- [ ] **Step 3:** On-device verification (capture → file lands in the portable root;
  uninstall/reinstall + restore → files return). Deferred device step.
- [ ] **Step 4: Commit** `feat(attach): portable attachment storage root`.

---

### Task 2: On-device transcription + OCR (graceful degradation)

**Files:**
- Create: `attach/Transcriber.kt` (`SpeechRecognizer` / on-device recognition of a
  saved `AUDIO` file → `TRANSCRIPT`), `attach/TextRecognizer.kt` (ML Kit Text
  Recognition on a scanned `PDF`/image → `OCR_TEXT`).
- Edit: capture flow to, after saving the raw attachment, best-effort derive text
  and insert a second `AttachmentEntity` (`TRANSCRIPT`/`OCR_TEXT`) + optionally seed
  the Inbox node title from a transcript/OCR snippet.
- Add dep: `play-services-mlkit-text-recognition` in `gradle/libs.versions.toml`.

**Interfaces / decisions:**
- On-device only; never block capture on a model/network. If recognition is
  unavailable or fails, store the raw attachment and skip the derived text.
- Reuse the no-CAMERA GMS pattern already used for the doc scanner.

- [ ] **Step 1 (TDD):** Unit-test the derivation wiring behind an interface (fake
  recognizer → asserts a `TRANSCRIPT`/`OCR_TEXT` row is added; failure → raw-only).
- [ ] **Step 2:** Implement real recognizers; keep tests green.
- [ ] **Step 3:** On-device verification (speak → transcript; scan text → OCR).
  Deferred device step.
- [ ] **Step 4: Commit** `feat(attach): on-device transcription + OCR`.

---

### Task 3: Streaming I/O — stop loading whole blobs into memory

**Files:**
- Edit: `attach/AttachmentStore` (`writeStream(InputStream)` / `openRead(): InputStream`
  content-addressed via a streaming digest to a temp file, then atomic rename).
- Edit: `attach/CaptureRepository`, `ShareReceiverActivity`, `capture/*Activity`
  to stream `contentResolver.openInputStream` → store, with a size guard.
- Edit: `server/ApiRoutes.kt` download route to stream from disk
  (`call.respondOutputStream` / `respondFile`) instead of `respondBytes(readBytes())`.
- Edit: `backup/BackupManager` + `GoogleDriveClient` to stream ZIP/upload bodies
  (chunked; avoid the triple in-memory copy).

- [ ] **Step 1 (TDD):** Streaming round-trip tests (large synthetic input; assert
  content hash + bytes match without materializing the whole array).
- [~] **Step 2:** Migrate the read/write/download/backup paths to streaming.
  - [x] Download route now streams from disk (`respondOutputStream`) instead of
    `respondBytes(store.read(...))` — works with the current File-based storage
    regardless of the Task 1 decision. (Landed 2026-07-07; needs `./gradlew test`.)
  - [ ] Capture writes (`CaptureRepository`, `ShareReceiverActivity`, `capture/*`)
    and the backup ZIP/upload paths still buffer in memory.
- [ ] **Step 3: Commit** `perf(attach,backup): stream large blobs to avoid OOM`.

---

### Task 4: Incremental content-addressed encrypted Drive backup

**Files:**
- Edit: `backup/DriveClient` + `GoogleDriveClient` — add content-addressed blob ops:
  `listBlobNames()`, `putBlobIfAbsent(name, stream)`, `getBlob(name)`, `deleteBlob(name)`
  over `appDataFolder` (names = `blob-<sha256>` and `snap-<ts>.db`/`manifest-<ts>.json`).
- Edit: `backup/BackupManager` — split the monolithic archive into: an encrypted DB
  snapshot + one encrypted blob per attachment (named by content hash) + an encrypted
  manifest listing DB snapshot + attachment `relativePath`→blob-hash. Upload only
  blobs whose hash is not already present in Drive.
- Edit: retention/GC — keep last N DB snapshots + manifests; delete blobs no
  referenced manifest points at.

**Interfaces / decisions:**
- Single-writer (one phone) keeps it simple: last-writer-wins; keep last N manifests
  for point-in-time restore. Per-blob AES-256-GCM with a fresh IV (existing
  `BackupCrypto`), passphrase-derived key. Drive still sees ciphertext only.
- The DB `AttachmentEntity.relativePath` (already content-addressed) is the natural
  blob key — dedup is free.

- [ ] **Step 1 (TDD):** With a fake `DriveClient`, assert: first backup uploads DB +
  all blobs + manifest; second backup after adding one attachment uploads only the
  new blob + a new manifest (unchanged blobs skipped); GC removes unreferenced blobs.
- [ ] **Step 2:** Implement against `GoogleDriveClient`; keep periodic +
  debounced-on-mutation triggers and observability intact.
- [ ] **Step 3: Commit** `feat(backup): incremental content-addressed Drive backup`.

---

### Task 5: Restore on a fresh install / new device

**Files:**
- Create/Edit: a first-run restore surface (web settings section or a first-run
  screen) — "Restore from Google Drive": sign in → enter passphrase → detect the
  latest manifest → confirm → restore. Keep vanilla-JS + CSP-clean.
- Edit: `backup/RestoreManager` — support detect-then-restore (not just the armed
  `restorePending` flag): list manifests, decrypt with the entered passphrase,
  restore DB + referenced blobs **before** `ensureServerStarted()`.
- Edit: handle "backup newer than app schema" via existing Room migrations; surface
  a clear error if the passphrase is wrong or no backup exists.

**Interfaces / decisions:**
- The keystore-wrapped passphrase is gone after uninstall, so cross-device/fresh
  restore **requires the user to re-enter the passphrase** — make this explicit in
  the UI (and consider an exportable recovery key as a follow-up).
- Restore must complete before the first server start / first DB query (the ordering
  `MainActivity` already relies on) — keep that invariant explicit and tested.

- [ ] **Step 1 (TDD):** `RestoreManager` detect-and-restore round-trip (seed Drive
  via fake client → wipe → restore with passphrase → DB + attachments identical;
  wrong passphrase → clean failure, no partial write).
- [ ] **Step 2:** Wire the first-run restore UI + real Drive detection.
- [ ] **Step 3:** On-device verification (enable backup → edit → see incremental
  backup in Drive → uninstall/reinstall → restore with passphrase → data returns).
  Deferred device step.
- [ ] **Step 4: Commit** `feat(backup): restore on fresh install / new device`.

---

## Open questions to resolve during execution
- **Task 1 storage backend (blocking):** SAF tree (a) vs MediaStore (b) vs
  keep-app-private-and-rely-on-backup (c). (a) best matches §10a's "portable folder"
  but is the largest change; (c) is simplest if Task 4/5 fully own durability. Decide
  with the owner before starting.
- Transcription API: `SpeechRecognizer` on a file (`ACTION_RECOGNIZE_SPEECH` is
  mic-live) vs on-device `SpeechRecognizer.createOnDeviceSpeechRecognizer` — confirm
  file-based on-device support on target devices; degrade gracefully otherwise.
- Recovery key: offer an exportable passphrase/recovery key for cross-device restore,
  or keep passphrase-only? (UX vs portability.)
- Backup format migration: existing users have monolithic `*.zyncbackup` blobs;
  keep restore able to read the old single-archive format for one release.

# zync M2 — Distribution, Capture, Widgets & Automatic Backup

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make zync easy to install and keep, and let the phone capture more than typed text. Four tasks, in this order: (1) signed release + GitHub-Releases publishing so the Android app is easy to install and auto-update; (2) attachment capture (voice notes + document scan) into the Inbox, plus a share-target; (3) a home-screen quick-capture widget; (4) **automatic** encrypted backup of all state to Google Drive.

## Implementation Status — updated 2026-07-07

Tasks 1–3 and the core of Task 4 are implemented and shipped (v0.1 / v0.2). A full
code review on 2026-07-07 hardened the M2 work; the per-task checkboxes below are
the original plan (per-step tracking lived in the gitignored progress tracker).

> **🧭 Superseded direction (2026-07-08):** Task 4's **Google-Drive** backup is
> replaced by sync to a central server (SQLite + S3, litestream) — see
> `docs/superpowers/specs/2026-07-08-backup-sync-architecture.md`. Tasks 1–3 stand as
> shipped; the Task 4 content below is retained as the historical M2 plan.

- **Task 1 — Signed release + CI:** ✅ **Complete.** Signed universal APK,
  `.github/workflows/release.yml` (tag/release-triggered, monotonic `versionCode`
  from the CI run number), Obtainium install docs. The open `allowBackup` decision
  is **resolved → `false`**: app-managed encrypted Drive backup supersedes Android
  Auto Backup, which would otherwise have uploaded `zync.db` + the server TLS
  keystore private key/password to Google.
- **Task 2 — Attachment capture:** ✅ **Implemented** (voice note, doc scan,
  share-target, `/api/nodes/{id}/attachments` list + byte-download route, web
  rendering; Room bumped to v3 with a migration + tests). Code review added
  download-route hardening (`nosniff`, `Content-Disposition`, image content-types)
  and tests, fixed the Inbox double-fetch, and removed dead duplicate activities.
  ⏳ **Deferred:** on-device transcription/OCR (raw `AUDIO`/`PDF` stored, no derived
  `TRANSCRIPT`/`OCR_TEXT` — graceful degradation as the plan allows); real-device
  capture verification (Step 3); portable `Documents/Zync` root — capture currently
  writes app-private external storage, which is wiped on uninstall.
- **Task 3 — Quick-capture widget:** ✅ **Complete.** Glance widget with
  Type/Voice/Scan/Upload. Beyond the plan, an **Accessibility-service volume-key
  gesture** capture path was added (double Vol-Up = voice, double Vol-Down = scan);
  code review fixed an auto-repeat false-trigger and narrowed the service's event
  scope. ⏳ **Deferred:** on-device widget verification (Step 2); a product decision
  on whether the accessibility service is worth its privacy / Play-policy cost.
- **Task 4 — Automatic encrypted Drive backup:** ⏳ **Partially complete.**
  Implemented: AES-256-GCM with a Keystore-wrapped passphrase, `appDataFolder`
  scope, WorkManager periodic + debounced-on-mutation triggers, settings
  toggle/status, and restore. Code review fixed a **torn-snapshot bug** (now
  `wal_checkpoint(TRUNCATE)` on Room's own connection, main-`.db`-only archive),
  made restore **verify-before-destroy**, and removed a dead second backup
  implementation. **Deferred to the next milestone:** (a) incremental
  content-addressed attachment upload — the live path re-encrypts/re-uploads the
  full archive every run; (b) auto-detect-and-restore on a fresh install / new
  device (needs first-run UI + passphrase re-entry, since the wrapped key is wiped
  on uninstall); (c) real-device Drive verification (Step 3).

**Testing caveat:** the 2026-07-07 review fixes were static-verified only — the
Android/Robolectric suite cannot run in the review environment (no Android SDK;
Gradle distribution download is egress-blocked). Run `./gradlew test` before the
next release.

**Context / current state (verified 2026-07-05):**
- Android app `app/`, `applicationId = dev.njr.zync`, `minSdk 34`, `targetSdk 36`, `versionCode 1 / versionName "1.0"`. Phone is the single source of truth (Room `zync.db` + Ktor server; desktop/browsers are remote clients over pinned TLS — see M1c/M1d).
- **Release build is unsigned:** `app/build.gradle.kts` `buildTypes.release` has no `signingConfig`; `isMinifyEnabled = false`. No CI exists (`.github/workflows/` absent).
- **Attachments are a schema stub, not wired:** `data/AttachmentEntity.kt` already models `AttachmentType { AUDIO, TRANSCRIPT, PDF, OCR_TEXT }` with `nodeId` + `relativePath` "relative to the Documents/Zync data root (spec §10a)". It's an entity in `ZyncDatabase` (version 2) but there is **no `AttachmentDao`, no capture UI, no routes, no file storage**. Adding capture is the first real use → a Room migration to v3 is likely (add DAO/queries; entity already exists so schema may be unchanged — confirm against exported `schemas/`).
- **QR scanning already uses `com.google.android.gms:play-services-code-scanner`** (`pairing/QrScanBridge.kt`, no CAMERA permission). The ML Kit **Document Scanner** (`play-services-mlkit-document-scanner`) is the same family and likewise needs no CAMERA permission — reuse this pattern for doc scan.
- **No real backup today:** `AndroidManifest` has `allowBackup="true"` but `res/xml/data_extraction_rules.xml` is the empty sample template. So only Android Auto Backup with defaults applies: best-effort, ≤25 MB, ~daily on unmetered+charging, to the user's hidden Drive app-data quota, DB included but **attachments (in shared storage) excluded**, not verifiable, restore only on reinstall. Insufficient once attachments exist → Task 4 replaces it with an app-managed automatic Drive backup.
- Web UI is framework-less vanilla JS served by the phone (`app/src/main/assets/`), tested by `webtest/` Playwright. Any new capture surfaced in the web UI must stay framework-less and CSP-clean (`default-src 'self'`).

## Global Constraints

- VCS is **jj** (NOT git): `jj commit -m "<msg>"`, no staging; trailer (after a blank line) `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. After committing, run `jj diff -r @-` / `jj log` and CONFIRM the commit contains only your files and rewrote no prior commit (this project has hit jj auto-snapshot entanglement; when another agent is live in the default working copy, implement in an isolated `jj workspace add --revision main <dir>`).
- Secrets never in the repo. Signing keystores, Drive OAuth client secrets, and any tokens are CI secrets / on-device Keystore-wrapped only. `.gitignore` must cover `*.jks`/`*.keystore`/`key.properties`.
- Android tests run under Robolectric via `./gradlew test`; keep them green. On-device-only behaviors (real AndroidKeyStore, real Drive, real MediaRecorder, real widget host) get a final emulator/device verification task-step, mirroring M1c-T8 / M1d-T7.
- Do not regress the security posture from M1c (unpaired LAN reaches only `/pair/*`; sessions constant-time; pinning enforced). Backup encryption keys must not weaken it.
- Keep `minSdk 34`. Prefer Jetpack (WorkManager, Glance, CredentialManager) over legacy APIs.

---

### Task 1: Signed release + GitHub-Releases publishing (easy install & auto-update)

**Files:**
- Edit: `app/build.gradle.kts` (add `signingConfigs.release` reading from `key.properties`/env; wire into `buildTypes.release`; keep `isMinifyEnabled` decision explicit).
- Create: `key.properties.example`, `.gitignore` entries (`key.properties`, `*.jks`, `*.keystore`).
- Create: `.github/workflows/release.yml` (tag/`release`-triggered: set up JDK 17 + Android SDK, decode keystore from secret, `./gradlew assembleRelease`, attach the signed APK to the GitHub Release).
- Create/Update: `docs/INSTALL.md` (sideload instructions + **Obtainium** one-time setup pointing at the repo's Releases for auto-update).

**Interfaces / decisions:**
- **Stable signing key** (one keystore, stored base64 in a CI secret) so every release installs over the previous one — a changed key breaks updates.
- Publish a **universal signed APK** (sideload/Obtainium want an APK, not an AAB). `versionCode` must monotonically increase per release — derive from the tag or a CI counter.
- Recommended distribution: **GitHub Releases + Obtainium** (users add the repo URL once; Obtainium auto-pulls new release APKs). Document F-Droid-repo/Accrescent as heavier alternatives; do not build them now.
- Leave Play Store / AAB out of scope.

- [x] **Step 1 (RED/setup):** Add `signingConfigs.release` + `key.properties` plumbing; generate a throwaway keystore locally; prove `./gradlew assembleRelease` yields a **signed** APK (`apksigner verify`). Document the real keystore/secret setup in `docs/INSTALL.md` (do NOT commit any real key).
- [x] **Step 2:** Write `.github/workflows/release.yml`; validate YAML + `./gradlew assembleRelease` locally (the workflow itself is verified on first real tag). Confirm `versionCode` bump strategy.
- [x] **Step 3: Commit** `feat(android): signed release build + GitHub Releases publish workflow + install docs`.

---

### Task 2: Attachment capture — voice notes + document scan into the Inbox (spec §10a)

**Files:**
- Create: `app/src/main/java/dev/njr/zync/data/AttachmentDao.kt` (insert/query/delete by `nodeId`); wire into `ZyncDatabase` (bump to v3 + `Migration_2_3` if schema changes; entity already present — verify against `schemas/`).
- Create: `app/src/main/java/dev/njr/zync/attach/AttachmentStore.kt` (owns the `Documents/Zync` data root; writes files under `relativePath`; content-addressed names).
- Create: capture entry points — `attach/VoiceCapture.kt` (`MediaRecorder` → `AUDIO`; optional `SpeechRecognizer`/on-device transcription → `TRANSCRIPT`), `attach/DocScanBridge.kt` (ML Kit Document Scanner → `PDF`; optional Text Recognition → `OCR_TEXT`) — mirror `QrScanBridge.kt`'s no-CAMERA-permission GMS pattern.
- Edit: `AndroidManifest.xml` — add an `ACTION_SEND` / `SEND_MULTIPLE` intent-filter (audio, image, `application/pdf`) so other apps can share into the Inbox; a handler activity/route that creates an Inbox node + attachment.
- Edit: server + web UI — an `/api/nodes/{id}/attachments` read route and Inbox rendering so attachments captured on the phone show up (and are downloadable) in the web/desktop UI. Keep vanilla-JS + CSP-clean.
- Add deps in `gradle/libs.versions.toml`: `play-services-mlkit-document-scanner` (and text-recognition if OCR is included).

**Interfaces / decisions:**
- Each capture creates an **Inbox `NodeEntity`** (parent = `INBOX_ID`) plus one or more `AttachmentEntity` rows; the node title defaults to a timestamp/transcript snippet, editable later in Clarify.
- Files live under a single portable **`Documents/Zync`** root (per §10a) referenced by `relativePath`, so Task 4 can back the whole folder up and it survives reinstalls/moves.
- Prefer **on-device** transcription/OCR; if unavailable, store the raw `AUDIO`/`PDF` without the derived `TRANSCRIPT`/`OCR_TEXT` (never block capture on a network/model).
- Permissions: use GMS scanner (no CAMERA); `RECORD_AUDIO` is required for voice — request at capture time.

- [x] **Step 1 (TDD):** `AttachmentDao` + `AttachmentStore` unit tests (Robolectric): insert/query/delete, file written under the data root, cascade/orphan cleanup when a node is deleted. Migration test if v3.
- [x] **Step 2: Implement** capture bridges + share-target + the read route + web UI rendering. Keep `./gradlew test` and `webtest` green.
- [ ] **Step 3:** On-device verification (real MediaRecorder + real ML Kit scan + share from Gallery/Files) — deferred emulator/device step.
- [x] **Step 4: Commit** `feat(android): voice-note + doc-scan attachment capture into Inbox (+ share target)`.

---

### Task 3: Home-screen quick-capture widget (Jetpack Glance)

**Files:**
- Create: `app/src/main/java/dev/njr/zync/widget/QuickCaptureWidget.kt` (Glance `GlanceAppWidget` + receiver), `res/xml/quick_capture_widget_info.xml`, manifest `<receiver>` for the widget.
- Optional: a second "Next actions / today" read-only list widget (Glance) — behind the same task, ship the capture widget first.

**Interfaces / decisions:**
- Widget actions launch the capture entry points from Task 2: **type**, **voice**, **scan** → straight into the Inbox, without fully opening the app.
- Use **Jetpack Glance** (modern `AppWidget`), not legacy `RemoteViews`.
- Widget must not require the server/remote-access to be running — capture writes to Room + `Documents/Zync` directly on the phone.

- [x] **Step 1:** Glance widget with the three capture shortcuts; preview + click wiring.
- [ ] **Step 2:** On-device verification (place widget, tap each shortcut, confirm Inbox rows/attachments) — deferred device step.
- [x] **Step 3: Commit** `feat(android): home-screen quick-capture widget (Glance)`.

---

### Task 4: Automatic encrypted backup of all state to Google Drive

**Files:**
- Create: `app/src/main/java/dev/njr/zync/backup/BackupManager.kt` (snapshot `zync.db` + sync the `Documents/Zync` attachments folder), `backup/DriveClient.kt` (Drive REST via GoogleSignIn/CredentialManager, `drive.file` or `appDataFolder` scope), `backup/BackupWorker.kt` (WorkManager), `backup/RestoreManager.kt`.
- Edit: `AndroidManifest.xml` — set `allowBackup="false"` and remove reliance on the empty Auto-Backup rules (app-managed backup supersedes it; document the trade-off), or keep Auto Backup as a DB-only fallback and explicitly `<exclude>` large/attachment paths — **decide in Step 1**.
- Edit: settings/web UI — a "Backup to Google Drive" toggle + status (last backup time, next run), and a Restore action; keep framework-less + CSP-clean.
- Add deps: `androidx.work:work-runtime-ktx`, Drive API client (or the lighter Drive REST + GoogleSignIn), `androidx.credentials`.

**Interfaces / decisions (this is the emphasized requirement — backup must be AUTOMATIC):**
- **Trigger model:** a **WorkManager periodic job** (e.g. daily) with constraints (network connected, prefer unmetered, battery-not-low) **plus** a **debounced expedited one-shot** enqueued when the domain mutates (hook the repository/mutation path or a `dirty` flag observed via Room `InvalidationTracker`), debounced ~a few minutes so a burst of edits yields one backup. No manual button needed for the happy path (a manual "Back up now" is a convenience, not the mechanism).
- **What's backed up:** a consistent **`zync.db` snapshot** (VACUUM INTO / checkpointed copy — never copy a live WAL) + the **attachments folder** (incremental: content-addressed, upload only new/changed `relativePath`s; the DB carries the manifest). This is the full source of truth since the phone is authoritative.
- **Encryption:** encrypt the snapshot + attachments client-side (AES-256-GCM) with a key wrapped in the **AndroidKeyStore** (reuse the M1c keystore-protection pattern), or a user passphrase for cross-device restore. Drive never sees plaintext. Keys must not weaken the M1c posture.
- **Scope:** Drive **`appDataFolder`** (hidden per-app, simplest, zero user folder-picking) is the default; offer `drive.file` + a user-chosen visible folder as an option. Least-privilege OAuth scope.
- **Restore:** on a fresh install / new device, sign in → detect an existing zync backup → decrypt → restore `zync.db` + attachments **before first server start**; then the normal pairing flow continues. Handle "backup newer than app schema" via the existing Room migrations.
- **Conflict/versioning:** single-writer (one phone) keeps this simple — keep the last N encrypted snapshots for point-in-time restore; last-writer-wins on the folder.
- **Observability:** surface last-success/last-failure + a WorkManager-backed retry with backoff; never silently fail (M1c lesson — no logging backend once bit us).

- [x] **Step 1 (design/decision):** Confirm Drive scope (`appDataFolder` vs `drive.file`), the `allowBackup` decision, and the key strategy (Keystore-wrapped vs passphrase). Write `BackupManager` snapshot + encryption with unit tests (Robolectric: snapshot integrity, encrypt→decrypt round-trip, incremental attachment manifest).
- [x] **Step 2 (TDD):** `BackupWorker` scheduling (periodic + debounced-on-change) with a fake `DriveClient`; assert automatic enqueue on mutation and constraint gating. `RestoreManager` round-trip test (backup → wipe → restore → DB+attachments identical).
- [ ] **Step 3:** Wire the settings toggle/status + real Drive client; on-device verification (enable → edit → observe automatic backup appears in Drive → reinstall → restore) — deferred device step.
- [ ] **Step 4: Commit** `feat(android): automatic encrypted Google Drive backup + restore (WorkManager)`.

---

## Open questions to resolve during execution
- Room version: does adding `AttachmentDao` require a v3 migration, or is the existing v2 `attachment` table sufficient (entity already declared)? Verify against exported `schemas/2.json`.
- Transcription/OCR: on-device only (SpeechRecognizer / ML Kit Text Recognition) vs optional cloud — default on-device, degrade gracefully.
- Backup encryption key: AndroidKeyStore-wrapped (seamless, device-bound, but a lost phone without the key can't restore elsewhere) vs user passphrase (portable restore, worse UX). Consider offering both (Keystore for convenience + an optional exportable recovery key).
- `allowBackup`: turning it off cleanly hands backup to Task 4; keep it on (DB-only, attachments excluded) as a belt-and-suspenders fallback? Decide in Task 4 Step 1.

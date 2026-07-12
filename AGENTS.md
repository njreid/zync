# AGENTS.md

Guidance for coding agents working in this repository.

## Project Shape

> **🧭 Architecture is pivoting (2026-07-08).** This section describes the **shipped
> v0.2** design. The agreed target is a central always-on server (SQLite + S3) with
> the **phone as an offline replica**, an **op-log sync core**, and an
> **all-Kotlin/KMP** rebuild (thin native Compose capture + a shared web UI served by
> both the server and the phone's loopback Ktor). New *architectural* work should
> align to the target — see `docs/superpowers/specs/2026-07-08-backup-sync-architecture.md`
> and `docs/superpowers/specs/2026-07-08-kotlin-kmp-target-architecture.md`. Until the
> rebuild lands, the **current code still follows the v0.2 design below** — keep edits
> to shipped code consistent with what's actually in the tree, not the target.

Zync is a local-first GTD system being rebuilt toward a central always-on server
(SQLite + S3) with the **phone as an offline op-log replica** and a **shared web
UI** served both by the server and by the phone's own loopback. The M1c LAN
remote-access stack (phone-as-LAN-server, QR/TLS pairing, mDNS, the Tauri desktop
client) and the old Room content layer / vanilla-JS UI have been **retired**.

- `core/` is the KMP op-log core: ULID/HLC, `Op` serialization, `StateStore`,
  merge/projection, tree-move.
- `data/` is the SQLDelight store (`dev.njr.zync.data.db.ZyncDatabase`) for both
  the server and the phone replica.
- `web/` is the shared UI: server-rendered kotlinx.html + Datastar hypermedia over
  SSE (see the Datastar section below). Both the server and the phone loopback
  serve it.
- `server/` is the central Ktor server: op-log sync (push/pull/bootstrap), Ed25519
  device pairing (terminal-QR), S3 blob store, litestream durability.
- `app/` is the Android app: the op-log replica (`replica/`), capture
  (voice/doc/share via `ReplicaCapture`), the loopback `ZyncServer` that serves
  `:web` under a per-boot token, and `SyncWorker` (WorkManager) for background sync.
- `webtest/` is the Playwright suite for the shared `:web` UI (headless Chromium).
- `docs/superpowers/plans/` contains the dated milestone plans and is the design
  source of truth. `.superpowers/sdd/progress.md` has task/review history.

## Before Editing

- Read the relevant milestone plan before changing behavior, especially op-log
  merge semantics, sync, device pairing, server auth, or capture flows.
- Check `git status --short` first. Do not revert or overwrite unrelated local
  changes.
- Keep changes scoped to the subsystem being modified (`core`/`data`/`web`/
  `server`/`app`/`webtest`).

## Build And Test Commands

Use the narrowest command that covers the change, then broaden when touching
shared contracts.

- All JVM/Android tests: `./gradlew test`
- One module: `./gradlew :server:test` / `:app:testDebugUnitTest` / `:core:allTests`
- One Android test class: `./gradlew :app:testDebugUnitTest --tests dev.njr.zync.server.ZyncServerSmokeTest`
- Shared `:web` UI functional tests: `cd webtest && npx playwright test`
  (against `./gradlew :server:webDevServer`)

First-time web work may need `npm install` in `webtest/` and
`npx playwright install chromium`.

## Android Notes

- The phone is an op-log replica. Content lives in the SQLDelight op-log store via
  `replica/OpWriter` + `ReplicaCapture`; there is no Room content layer. Mutations
  queue locally and reconcile through `SyncWorker`/`SyncClient`.
- `ZyncServer` is loopback-only: it binds `127.0.0.1`, serves `:web` over
  `WebContent`, and gates every request on the per-boot loopback token
  (`tokenGuard` + constant-time compare). The in-app WebView loads it once at
  launch with `?token=`; keep that server/port alive for the whole process and
  don't let a stale cookie outrank a fresh query token.
- Ed25519 device signing (replica sync) uses BouncyCastle; keep that dependency.
- Android behavior can differ from Robolectric/JVM. WebView-token and
  capture-path changes benefit from emulator/device verification when feasible.

## Web UI Notes

- The UI is the shared `:web` module (`web/src/...`), served by both the server and
  the phone loopback — not a phone-only asset dir.
- Treat all rendered task/context text as untrusted; rely on kotlinx.html escaping.
- Preserve loopback auth: the WebView receives a fresh token in the URL and
  exchanges it for the `zync_token` cookie on the document route; stale cookies
  must not outrank it.
- Add or update Playwright tests in `webtest/tests/` for user-visible changes.

## Current Planning Context

M1 (a–d) and the bulk of M2 are implemented and shipped (v0.2). M2 is tracked in
`docs/superpowers/plans/2026-07-05-m2-capture-backup-distribution.md`; see its
"Implementation Status" section for what's done vs deferred. A 2026-07-07 code
review hardened the M2 work (backup snapshot consistency, `allowBackup=false`,
download-route hardening, verify-before-destroy restore, dead-code removal). The
next milestone (M3) is not yet written — confirm scope before starting.

Known deferred / follow-up work:

- Backup: incremental content-addressed attachment upload is not implemented (the
  live path re-encrypts/re-uploads the full archive each run); auto-detect-restore
  on a fresh install is not wired; real-device Drive verification is pending.
- Attachments: on-device transcription/OCR is not implemented (raw `AUDIO`/`PDF`
  only); capture writes app-private external storage, not a portable
  `Documents/Zync` root; real-device capture verification pending.
- The quick-capture Accessibility-service (volume-key) path was added beyond the
  M2 plan; revisit its privacy / Play-policy cost.
- The Android/Robolectric suite could not run in the code-review environment
  (no SDK; Gradle download egress-blocked) — run `./gradlew test` before releases.

## Style

- Kotlin uses the existing package layout under `dev.njr.zync` and JVM toolchain
  17.
- Add comments only for non-obvious security, lifecycle, or protocol choices.

## Datastar (shared `:web` UI)

The `:web` module renders server-side hypermedia with **Datastar v1** (vendored at
`web/src/commonMain/resources/datastar.js`, served at `/assets/datastar.js`). Datastar's
keyed attributes use a **colon separator**, NOT a hyphen:

- Events: `data-on:click`, `data-on:load` (e.g. `data-on:click="@post('/x')"`).
- Two-way bind: `data-bind:<signal>` (e.g. `data-bind:title` — the signal is the key, no
  value), referenced in expressions as `$title`.
- Actions in expressions: `@get('/url')`, `@post('/url')` — a fetch whose response is a
  Datastar SSE stream that patches the DOM.

`data-on-click` / `data-bind="title"` (hyphen/value form) silently do nothing — Datastar
never binds them. Server pushes patches as SSE events `datastar-patch-elements` /
`datastar-patch-signals` (see `web/.../sse/Datastar.kt`); default patch mode is `outer`
(morph by element id). Verify the client side headlessly with the Playwright suite in
`webtest/` against `./gradlew :server:webDevServer`.

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

Zync is a local-first GTD system where the Android app is both the datastore and
the server. Other clients connect to the phone over LAN using QR-based pairing,
pinned TLS, and bearer sessions.

- `app/` is the Android app: Room database, domain repository, Ktor server,
  pairing, remote-access lifecycle, and the phone-hosted web UI.
- `app/src/main/assets/web/` is the production web UI served by the phone. It is
  framework-less vanilla JavaScript, no bundler.
- `desktop/` is the Tauri desktop client. Rust owns discovery, pairing, TLS
  pinning, paired-device storage, and the local reverse proxy. `desktop/ui/` is
  the small vanilla-JS shell shown before the phone web UI is proxied in.
- `webtest/` is the Playwright suite for the phone-hosted web UI. It starts a
  Robolectric-backed dev server through Gradle.
- `docs/superpowers/plans/` contains the dated milestone plans and is the design
  source of truth. `.superpowers/sdd/progress.md` has detailed task/review
  history, current follow-ups, and known deferred work.

## Before Editing

- Read the relevant milestone plan before changing behavior, especially pairing,
  server auth, proxying, backup, or capture flows.
- Check `git status --short` first. Do not revert or overwrite unrelated local
  changes.
- Keep changes scoped to the subsystem being modified. Android, desktop Rust,
  desktop UI, and Playwright tests have separate ownership boundaries.
- Prefer existing local patterns over introducing frameworks. The web UIs are
  intentionally vanilla JS plus PicoCSS.

## Build And Test Commands

Use the narrowest command that covers the change, then broaden when touching
shared contracts.

- Android/domain/server tests: `./gradlew test`
- One Android test class: `./gradlew test --tests dev.njr.zync.server.ServerAuthTest`
- Web UI functional tests: `cd webtest && npx playwright test`
- Desktop Rust tests: `cd desktop/src-tauri && cargo test`
- Desktop UI syntax gate: `cd desktop && node --check ui/*.js`
- Tauri dev app: `cd desktop && npm run tauri dev`

First-time web or desktop work may need `npm install` in `webtest/` or
`desktop/`, and Playwright may need `npx playwright install chromium`.

## Android Notes

- Room schemas live in `app/schemas/` and are wired into debug assets for
  Robolectric migration tests. Any database version change must update schemas
  and add/adjust migration tests.
- `ZyncServer` has separate loopback and LAN concerns. Loopback is for the
  on-phone WebView; LAN is TLS-protected and must keep auth boundaries intact.
- Pairing and remote access are security-sensitive. Preserve loopback-only
  approval, one-time nonce/session behavior, revocation immediacy, constant-time
  comparisons, and generic LAN error responses.
- Android TLS behavior can differ from Robolectric/JVM behavior. Certificate,
  Netty, ALPN, keystore, NSD, and WebView-token changes need emulator or device
  verification when feasible.
- Keep `MainActivity`, `RemoteAccessForegroundService`, `ZyncApp`, and
  `RemoteAccessManager` lifecycle changes conservative; remote-access toggles
  must not kill the local WebView server.

## Web UI Notes

- The production web UI is in `app/src/main/assets/web/`, not `webtest/`.
- It uses hash routing and direct DOM manipulation. Avoid adding a build step.
- Treat all rendered task/context text as untrusted. Use `textContent` or the
  existing escaping patterns, not raw `innerHTML`.
- Preserve auth behavior for document loads, API calls, and `/api/events`
  WebSocket reconnects. The phone WebView receives a fresh token in the URL;
  stale cookies must not outrank it.
- Add or update Playwright tests in `webtest/tests/` for user-visible workflow
  changes.

## Desktop Notes

- The system WebView cannot pin the phone's self-signed cert. Rust must connect
  to the phone through the pinned TLS client, then expose a local loopback proxy
  for the WebView.
- Do not replace the pinned client with `reqwest::Client::new()` or any default
  WebPKI client for phone traffic.
- Persist and reuse the captured/cross-checked phone fingerprint; do not
  silently re-derive trust from a later connection.
- Keep bearer tokens inside Rust/proxy code. The desktop WebView should load the
  local proxy and should not receive the phone session token directly.
- `desktop/src-tauri/tests/fake_phone.rs` is the main no-device harness for
  pairing, pinning, proxying, and WebSocket behavior. Use it for regressions.

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
- Desktop localhost proxy has no additional local auth.
- Active-relay MITM hardening still relies on QR-scan trust and
  fingerprint/confirm-code checks.
- Real-device mDNS still needs verification beyond unit tests.
- The Android/Robolectric suite could not run in the code-review environment
  (no SDK; Gradle download egress-blocked) — run `./gradlew test` before releases.

## Style

- Kotlin uses the existing package layout under `dev.njr.zync` and JVM toolchain
  17.
- Rust is edition 2021 and keeps command wrappers thin over testable logic.
- JavaScript is plain ES modules. Keep UI text and state transitions explicit
  and testable without a bundler.
- Add comments only for non-obvious security, lifecycle, or protocol choices.

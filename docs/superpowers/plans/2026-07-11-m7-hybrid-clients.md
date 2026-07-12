# zync M7 — Phone Hybrid UI + Thin Clients

> **For agentic workers:** implement task-by-task; `- [ ]` steps. **Depends on M6**
> (`:web`). Roadmap: `2026-07-08-rebuild-roadmap.md`. Pairing:
> `../specs/2026-07-10-device-pairing.md`. Status: 🟡 DRAFT.

**Goal:** the target client experience. The phone runs a **native Compose** shell
(launcher/capture/settings) with a **WebView hosting the shared `:web` module via
loopback** for content. **Desktop/browser become thin online-only clients** of the
central server (plain HTTPS). **Retires** the vanilla-JS UI, the M1c/M1d **LAN stack**
(phone-as-LAN-server, QR LAN pairing, mDNS/NSD, TLS pinning, Tauri reverse-proxy
desktop), and the old Room content layer. **Absorbs the deferred M6 phone-loopback
cutover** (Tasks 1–2 below) so it's built once on the new shell, not twice.

**Current app state:** loopback + LAN dual `ZyncServer` (Room `ApiRoutes` + vanilla-JS
assets, token/session auth), M1c `pairing/` (NSD/QR/RemoteAccessManager, phone-as-LAN),
capture writing Room via `NodeRepository`. M5 delivered (unit-tested) the op-log replica
stack (`OpWriter`/`SyncClient`/`PairingClient`/`ReplicaCapture`) not yet wired live.

## Global constraints
- One UI = `:web`; the phone hosts it via **loopback** (offline), desktop/browser via the
  **central server** (online). Native surface stays **thin** (launcher/capture/shell).
- All content mutations flow through the **op log**; Room content layer is deleted here.
- Phone↔server auth = M5 **QR pairing** + signed sync; browser auth = **WebAuthn**.
- `./gradlew :app:testDebugUnitTest :web:allTests :server:test` green each commit.

---

### Task 1: Wire the op-log stack into the phone + loopback serves `:web` (M6 cutover, part 1)
**Files:** `ZyncApp` (DI), app `server/ZyncServer` loopback.
- `ZyncApp`: build the op-log stack — `AndroidZyncDatabase` + `SqlDelightStateStore` +
  `OpWriter`(persisted deviceId + `AndroidHlcStore` `LocalHlc`) + `ReplicaCapture` +
  `ContentReadModel` + `ContentCommands(PhoneOpEmitter)` + `ChangeNotifier`.
- Loopback serves `:web`: `install(SSE)` + `webRoutes(...)`; loopback-appropriate auth
  (token via cookie set on page load, so Datastar `@get/@post` carry it — or none for
  127.0.0.1). Remove the vanilla-JS asset catch-all + `apiRoutes` from the loopback.
- [x] **Step 1:** op-log stack in `ZyncApp` + the loopback now **serves `:web`** — `zyncModule`
  swapped `apiRoutes`+vanilla-JS catch-all for `install(SSE)`+`webRoutes` over `WebContent`;
  token-cookie auth carries Datastar's fetches; CSP gains `script-src 'unsafe-eval'`
  (empirically required — Playwright csp.spec: strict CSP kills Datastar, the carve-out
  fixes it). `ZyncServerSmokeTest` verifies the real loopback serves `:web` under a token.
- [x] **Step 2: Commit** `feat(app): phone loopback serves the shared web UI`.

### Task 2: Route capture through the op log + retire the vanilla-JS UI
**Files:** `attach/CaptureRepository`, capture Activities, delete `assets/web/`, `webtest/`.
- Reroute all capture (`quickAddTask`/`captureToInbox`/`importUri`, Voice/Doc/Share) to
  `ReplicaCapture` (op log + `LocalBlobStore`), mapping `AttachmentType`→string. Delete the
  vanilla-JS UI (`assets/web/`) and its Playwright suite (`webtest/`); key flows are `:web`
  tests now. Add a phone blob route so `:web` can show attachments.
- [x] **Step 1:** all capture (Voice/Doc/Share/quick-add/import) routed through
  `ReplicaCapture` (op log + `LocalBlobStore`) via `ZyncApp.captureToInbox`; deleted the
  vanilla-JS UI (`assets/web/`), `ApiRoutes`, `AndroidAssets`, and the retired server/apiRoutes
  tests (AuthGuard security coverage kept). App builds + 133 tests green; APK assembles.
  **Datastar client verified headlessly** via Playwright (`webtest/web-ux.spec`). (Attachment
  display in `:web` + `webtest/` Playwright-in-CI are follow-ups.)
- [x] **Step 2: Commit** `feat(app): capture via op log; retire vanilla-JS UI`.

### Task 3: Wire the live sync client (phone ↔ central server)
**Files:** `sync/` scheduler (WorkManager), pairing wiring.
- Wire M5 `PairingClient` (scan central-server QR → pair) + `SyncClient` (signed push/pull
  on connectivity) + `BlobUploader` live. Store paired-server creds; retire `Google Drive`
  remnants (already gone) — durability is server sync.
- [x] **Step 1 (Robolectric):** `AndroidPairingStore` persists paired-server creds;
  `ZyncApp.syncOnce()` builds a `SyncClient` (shared `localHlc`, device signer) from them
  and syncs (no-op unpaired); `SyncWorker`/`SyncScheduler` run it connectivity-gated
  (one-shot + periodic). 3 tests (creds round-trip, unpaired no-op, scheduler enqueue).
  Live QR-scan pairing UI wires in with the Compose shell (Task 5).
- [x] **Step 2: Commit** `feat(app): live sync client (pairing store + WorkManager)`.

### Task 4: Retire the M1c/M1d LAN stack
**Files:** delete phone-as-LAN `ZyncServer` LAN path, `pairing/` NSD/QR-LAN/
`RemoteAccessManager`/TLS-pinning, the Tauri reverse-proxy desktop; drop mDNS deps.
- The phone talks only to the central server; no LAN server, no LAN pairing, no mDNS.
- [ ] **Step 1:** app builds without the LAN stack; no dead refs; tests green.
- [ ] **Step 2: Commit** `chore: retire the M1c/M1d LAN stack`.

### Task 5: Native Compose shell
**Files:** `ui/` Compose (launcher/capture entry/settings), WebView content host.
- Compose launcher + settings + capture entry points; a WebView loads the loopback `:web`
  content. Shared design tokens (Geist/Inter) across Compose + `:web`.
- [ ] **Step 1 (Robolectric/Compose test):** the shell renders; the WebView host targets
  the loopback; settings actions work.
- [ ] **Step 2: Commit** `feat(app): native Compose shell + WebView content host`.

### Task 6: Desktop/browser thin clients + WebAuthn
**Files:** `server` (WebAuthn registration/assertion → session), desktop packaging.
- Desktop/browser are plain-HTTPS clients of the central server's `:web`. Implement
  **WebAuthn/passkey** (registration + assertion → `SessionStore` token), replacing the
  password fallback. Desktop = a thin browser wrapper (retire the Tauri reverse-proxy).
- [ ] **Step 1 (TDD where testable):** WebAuthn assertion → session; server `:web`
  reachable by a browser session. (Ceremony parts needing a browser: note + integration.)
- [ ] **Step 2: Commit** `feat(server): WebAuthn browser auth for thin clients`.

### Task 7: Retire the old Room content layer
**Files:** delete `domain/NodeRepository`, `data/NodeDao|NodeEntity|ContextEntity|
ContextDao` (+ tests), thin Room to only what's still needed (if anything).
- With capture + UI on the op log, the Room content layer is dead. Remove it; keep only
  any non-content Room use (revisit pairing/attachment storage).
- [x] **Step 1:** deleted `NodeRepository`, `NestingRules`, `Node/Context/Attachment`
  entities + DAOs, `AttachmentStore`, content DTOs (`Dto.kt`), and their tests. Extracted
  `AttachmentType` to its own non-Room file (live capture still needs it) and preserved the
  shared `ErrorDto`. Room `ZyncDatabase` thinned in place to ONLY `AllowedDeviceEntity`/
  `allowedDeviceDao` (LAN pairing, retired later in Task 4) — content and pairing share one
  inseparable `@Database`. Added `Migration_3_4` (drops the retired content tables, keeps
  `allowed_device`) with a migration test; schema v4 exported. Dropped dead `ZyncApp.repository`/
  `attachmentStore` + the Room-invalidation `changesFlow` (the `:web` UI uses `ChangeNotifier`).
  App builds, 55-task suite + APK green.
- [x] **Step 2: Commit** `chore(app): retire Room content layer`.

### Task 8: Acceptance
- [ ] **Step 1 (acceptance):** phone (native shell + loopback `:web`) and browser (server
  `:web`) both drive the same op-log content; capture→sync→server→browser end-to-end;
  no LAN stack, no vanilla-JS, no Room content layer.
- [ ] **Step 2: Commit** `test: M7 hybrid-client acceptance`.

## Interfaces / decisions
- **Loopback auth:** token-as-cookie (set on `:web` page load) so Datastar fetches carry
  it, or drop auth for 127.0.0.1 (single device). Decide in Task 1.
- **Native surface thin:** content lives in `:web`, not rebuilt in Compose (roadmap:
  avoid Compose scope creep).
- **WebAuthn** replaces the password fallback for browser sessions (M4 `SessionStore`
  `credentialCheck` swap); needs a browser + a WebAuthn lib — integration-verify.

## Open questions
- WebAuthn server lib for Kotlin/JVM (e.g. webauthn4j) + Android passkey for the phone?
- Desktop packaging without Tauri (plain browser PWA vs a minimal wrapper).
- Attachment display in `:web` (phone blob route + `<img>`/download links).

## Definition of done
The phone runs a native shell hosting `:web` via loopback over the op log; desktop/browser
are thin `:web` clients of the central server with WebAuthn; the vanilla-JS UI, the M1c/M1d
LAN stack, and the Room content layer are all retired; capture→sync→server→browser works
end-to-end. The target architecture is reached.

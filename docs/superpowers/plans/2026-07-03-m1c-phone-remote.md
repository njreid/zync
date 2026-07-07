# zync M1c(1) — Phone-Side Remote Access: Hardening, TLS, Pairing, mDNS Implementation Plan

> **Status: ✅ COMPLETE** (shipped). Pre-LAN hardening, self-signed TLS with a
> stable fingerprint, Ed25519 QR pairing, challenge–response sessions, mDNS
> advertising, the foreground service, and the device-management UI all landed.
> ⏳ Still deferred: active-relay MITM hardening (protection relies on QR-scan
> trust + fingerprint/confirm-code checks) and real-device mDNS verification. The
> inline `- [ ]` checkboxes are the original plan, not maintained inline.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the phone's embedded server safely reachable from the LAN: pre-LAN security hardening, self-signed TLS with a stable fingerprint, QR-nonce pairing with Ed25519 device identities, challenge–response sessions, mDNS advertising, a foreground service, and a Settings surface for remote toggle + device revocation. Spec §8b. The Tauri desktop shell is the NEXT plan — this plan's "desktop" is test code.

**Architecture:** `ZyncServer` grows a second connector (HTTPS on the LAN interface, cert generated once via BouncyCastle and persisted). The token guard becomes a three-way gate: loopback token (unchanged), pairing endpoints (nonce-gated), and LAN sessions (Ed25519 challenge–response → short-lived bearer token). Pairing state is an in-memory state machine keyed by QR nonce; approved devices persist in a new `allowed_device` Room table (schema v2). NSD advertises `_zync._tcp`; a foreground service keeps the process alive while remote access is on.

**Tech Stack:** Existing Ktor 3.5.1 (engine may switch CIO→Netty for TLS — T3 verifies), BouncyCastle (bcprov/bcpkix), Room migration v1→v2, Play Services code-scanner (`GmsBarcodeScanner`) for QR, NSD (`NsdManager`), Playwright suite in `webtest/` for UI-level tests.

## Global Constraints

- Package `dev.njr.zync`; pairing/TLS code in `dev.njr.zync.server.remote`.
- VCS is **jj** (NOT git): `jj commit -m "<msg>"` (path-scope with filesets if other agents share the tree); trailer (after blank line): `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- curl/wget blocked; use `mcp__plugin_context-mode_context-mode__ctx_execute` for any HTTP fetches.
- Domain layer frozen EXCEPT: the `allowed_device` entity/DAO + Room migration v1→v2 (T2's explicit scope).
- Loopback behavior must not regress: the WebView flow (token → cookie → fetch/WS) keeps working; all 42 Kotlin + 9 Playwright tests stay green throughout.
- Security invariants (from M1b final review — each needs a test):
  1. CSP on every HTML/document response: `default-src 'self'; connect-src 'self' ws: wss:; img-src 'self' data:; style-src 'self'`.
  2. `?token=` query accepted ONLY on the document route (`/` / `/index.html`), never on `/api/*`.
  3. All secret comparisons via `MessageDigest.isEqual` on UTF-8 bytes (loopback token, session tokens, nonces).
  4. Cookie: `SameSite=Strict`; `Secure` flag set iff the request arrived over TLS.
  5. LAN (non-loopback) requests NEVER accepted via the loopback token or cookie — only via pairing endpoints or a valid session token. Unpaired LAN requests must not receive UI assets.
  6. Session tokens: 32 random bytes (SecureRandom, Base64url), 24 h expiry, in-memory only.
- Wire formats are contracts for the Tauri plan — implement exactly as specified in T4.
- TDD throughout; Ktor `testApplication` where possible, real-socket Robolectric tests for TLS.

---

### Task 1: Pre-LAN hardening batch

**Files:**
- Modify: `app/src/main/java/dev/njr/zync/server/ZyncServer.kt`
- Modify: `app/src/main/assets/web/js/api.js`
- Test: `app/src/test/java/dev/njr/zync/server/HardeningTest.kt` (new), existing `ServerAuthTest.kt` updated where behavior changes

**Interfaces:**
- CSP header added to document/asset responses (invariant 1); `X-Content-Type-Options: nosniff` too.
- Token guard changes: `?token=` honored only when the request path is `/` or `/index.html` (invariant 2); comparisons via a new `private fun constantTimeEquals(a: String?, b: String): Boolean` using `MessageDigest.isEqual` (invariant 3); cookie append gains `extensions["SameSite"] = "Strict"` and `secure = call.request.origin.scheme == "https"` (invariant 4).
- `api.js`: WS URL scheme derived (`location.protocol === 'https:' ? 'wss' : 'ws'`); reconnect delay becomes exponential backoff with jitter (base 1 s, cap 30 s, `delay = min(cap, base * 2^attempt) * (0.5 + random()/2)`), attempt counter reset on successful open.

- [ ] **Step 1: Write failing tests** — `HardeningTest.kt` with `zyncTestApplication`:

```kotlin
package dev.njr.zync.server

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HardeningTest {
    @Test fun `document responses carry CSP and nosniff`() = zyncTestApplication { _, _, client ->
        val res = client.get("/index.html")
        assertTrue(res.headers["Content-Security-Policy"]!!.contains("default-src 'self'"))
        assertEquals("nosniff", res.headers["X-Content-Type-Options"])
    }

    @Test fun `query token rejected on api routes`() = zyncTestApplication { _, _, _ ->
        val bare = createClient { }
        assertEquals(HttpStatusCode.Unauthorized, bare.get("/api/roots?token=test-token").status)
    }

    @Test fun `query token still accepted on document route`() = zyncTestApplication { _, _, _ ->
        val bare = createClient { }
        assertEquals(HttpStatusCode.OK, bare.get("/?token=test-token").status)
        assertEquals(HttpStatusCode.OK, bare.get("/index.html?token=test-token").status)
    }

    @Test fun `cookie carries SameSite Strict`() = zyncTestApplication { _, _, _ ->
        val bare = createClient { }
        val res = bare.get("/index.html?token=test-token")
        val setCookie = res.headers["Set-Cookie"]!!
        assertTrue(setCookie.contains("SameSite=Strict"))
    }
}
```
Also update any `ServerAuthTest` case that relied on `?token=` working on `/api/*` (the WS test in `ContextsApiTest` connects to `/api/events?token=test-token` — it must switch to cookie or header auth: use a client with the default token header and `HttpCookies`, or open the WS with the header).

- [ ] **Step 2: RED** — run the two test files; new tests fail (no CSP; query token currently accepted everywhere).

- [ ] **Step 3: Implement** — in `zyncModule`: add an intercept (or extend the guard) appending CSP/nosniff to responses; rework `tokenGuard` per the interface spec (path check for query-token acceptance; `MessageDigest.isEqual`; cookie attributes). In `api.js`: scheme derivation + backoff-with-jitter reconnect.

- [ ] **Step 4: GREEN** — Kotlin suite green; then `cd webtest && npx playwright test` (dev server serves over http → ws path still selected; 9/9).

- [ ] **Step 5: Commit** — `jj commit -m "feat: pre-LAN hardening — CSP, scoped query token, constant-time compares, cookie attrs, WS backoff"` (+ trailer).

---

### Task 2: `allowed_device` table + Room migration v1→v2

**Files:**
- Create: `app/src/main/java/dev/njr/zync/data/AllowedDeviceEntity.kt`, `AllowedDeviceDao.kt`
- Modify: `app/src/main/java/dev/njr/zync/data/ZyncDatabase.kt` (version 2, migration, new DAO)
- Test: `app/src/test/java/dev/njr/zync/data/AllowedDeviceTest.kt`, `MigrationTest.kt`

**Interfaces:**
- `AllowedDeviceEntity(id: Long = 0, name: String, pubkey: String /* base64url Ed25519, 32 bytes */, addedAt: Long, lastSeen: Long? = null, revoked: Boolean = false)` — table `allowed_device`, unique index on `pubkey`.
- `AllowedDeviceDao`: `insert`, `findByPubkey(pubkey): AllowedDeviceEntity?`, `observeAll(): Flow<List<AllowedDeviceEntity>>`, `revoke(id)`, `touchLastSeen(id, at)`.
- `ZyncDatabase` version = 2; `MIGRATION_1_2` = `CREATE TABLE allowed_device (...)` + unique index (write SQL to match the exported schema exactly); `build()` gains `.addMigrations(MIGRATION_1_2)`. Export schema v2 (committed under `app/schemas/`).
- Migration test uses `MigrationTestHelper` (add `androidx.room:room-testing` to catalog as `room-testing`, `testImplementation`) validating v1→v2 against the exported schemas.

- [ ] Steps: failing tests (CRUD roundtrip incl. revoke + unique-pubkey conflict; migration test) → RED → implement → GREEN (full suite) → commit `"feat: allowed_device table, Room v2 migration"` (+ trailer).

---

### Task 3: TLS — cert generation, dual connector, fingerprint

**Files:**
- Create: `app/src/main/java/dev/njr/zync/server/remote/ServerIdentity.kt`
- Modify: `app/src/main/java/dev/njr/zync/server/ZyncServer.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (BouncyCastle: `org.bouncycastle:bcprov-jdk18on` + `bcpkix-jdk18on`, current stable version via ctx_execute fetch of maven metadata)
- Test: `app/src/test/java/dev/njr/zync/server/remote/ServerIdentityTest.kt`, `TlsConnectorTest.kt`

**Interfaces:**
- `class ServerIdentity(storageDir: File)`:
  - `fun ensure(): ServerIdentity` — on first call generates an EC P-256 keypair + self-signed X509 cert (CN=zync, 25-year validity, BouncyCastle `JcaX509v3CertificateBuilder`), persists as a password-less PKCS12 (`identity.p12`, password `"zync"` is fine — the file lives in app-private storage) under `storageDir`; subsequent calls load it (stable across restarts).
  - `val keyStore: KeyStore`, `val certificate: X509Certificate`
  - `fun fingerprint(): String` — SHA-256 of the DER-encoded cert, lowercase hex, colon-separated pairs (`ab:cd:…`). This exact format is the pairing-wire contract.
- `ZyncServer` gains constructor params `identity: ServerIdentity?` and `lanPort: Int = 0`; when `identity != null`, the engine binds BOTH `127.0.0.1` HTTP (existing behavior) AND `0.0.0.0` HTTPS via `sslConnector(identity.keyStore, "zync", { "zync".toCharArray() }, ...)`. `start()` returns loopback port; new `fun lanPort(): Int?` returns the bound HTTPS port (null when identity absent).
- **Engine check (do this FIRST):** Ktor CIO does not support `sslConnector` — verify against 3.5.1 (attempt compile). Expected outcome: switch the engine to **Netty** (`io.ktor:ktor-server-netty`, same version; replace the CIO dependency; keep `embeddedServer(Netty, applicationEngineEnvironment { connector {...}; sslConnector {...} })` — adapt to Ktor 3.5.1's environment/config DSL). All existing behavior (including `resolvedConnectors` port resolution) must keep working; if Netty pulls unacceptable APK weight, note the delta in the report (informational, not blocking).
- `TlsConnectorTest` (Robolectric, real sockets): start server with an identity; connect to the HTTPS port with an `SSLSocketFactory` whose TrustManager trusts-all-but-records the presented cert; assert the presented cert's fingerprint equals `identity.fingerprint()`; assert a default (untrusting) client fails handshake; assert loopback HTTP still serves.
- `ServerIdentityTest`: two `ensure()` calls on the same dir → identical fingerprint; fresh dir → different; fingerprint format matches `^([0-9a-f]{2}:){31}[0-9a-f]{2}$`.

- [ ] Steps: engine verification spike → failing tests → RED → implement → GREEN (full suite incl. existing smoke) → commit `"feat: self-signed TLS identity + LAN HTTPS connector"` (+ trailer).

---

### Task 4: Pairing protocol + Ed25519 session auth

**Files:**
- Create: `app/src/main/java/dev/njr/zync/server/remote/PairingManager.kt`, `SessionManager.kt`, `RemoteRoutes.kt`
- Modify: `app/src/main/java/dev/njr/zync/server/ZyncServer.kt` (guard: three-way gate; mount remote routes)
- Test: `app/src/test/java/dev/njr/zync/server/remote/PairingFlowTest.kt`

**Wire contracts (FROZEN for the Tauri plan):**
```
POST /pair/request   {"pubkey": "<b64url 32B>", "name": "njr-desktop", "nonce": "<b64url ≥16B>"}
  → 202 {"status": "pending"}            (creates/refreshes pending entry, 120s TTL)
  → 409 {"error": "already paired"}      (pubkey already in allowed_device, not revoked)
GET  /pair/status?nonce=<b64url>
  → 200 {"status": "pending"}
  → 200 {"status": "approved", "fingerprint": "ab:cd:…", "confirmationCode": "483 921", "sessionToken": "<b64url 32B>"}
  → 404 {"error": "unknown nonce"}       (expired or never seen)
POST /auth/challenge {"pubkey": "<b64url>"}
  → 200 {"challenge": "<b64url 32B>"}    (60s TTL, single use)
  → 403 {"error": "unknown or revoked device"}
POST /auth/session   {"pubkey": "<b64url>", "signature": "<b64url Ed25519 sig over the challenge bytes>"}
  → 200 {"sessionToken": "<b64url 32B>", "expiresAt": <epoch ms>}
  → 403 {"error": "bad signature"}
Authenticated LAN calls: header `Authorization: Bearer <sessionToken>`
```
- `confirmationCode`: first 20 bits of SHA-256(nonce || phonePubCert-DER || desktopPubkey) rendered as two 3-digit groups — both sides can compute it; user compares visually.
- `PairingManager` (in-memory): `request(pubkey, name, nonce)`, `pending(): List<PendingPair>`, `approve(nonce): ApprovedPair` (inserts into `allowed_device`, issues session token), `status(nonce)`, TTL sweep. Approval is invoked by the native layer after QR scan + user confirm (T5); for THIS task it's called directly by tests and exposed via an internal method — **no approval HTTP endpoint exists** (approval must never be reachable from the network).
- `SessionManager`: `issue(deviceId): Session`, `validate(token): Session?` (constant-time lookup — iterate and `MessageDigest.isEqual`), 24 h expiry, `revokeForDevice(deviceId)`.
- Guard three-way gate (order): loopback address → existing token/cookie logic; else if path starts `/pair/` or `/auth/` → allow through (routes self-validate); else → require valid Bearer session token, 403 otherwise. LAN requests never honor the loopback cookie/token (invariant 5).
- Ed25519 verify via BouncyCastle (`Ed25519PublicKeyParameters` + `Ed25519Signer`); test keypairs generated with BC in the test.
- `PairingFlowTest`: full happy path (request → status pending → approve → status approved with correct fingerprint/code/token → challenge → sign → session → Bearer GET /api/roots on the LAN-simulated call succeeds) + sad paths (revoked device 403 on challenge; wrong signature 403; expired nonce 404; LAN call with loopback cookie 403; second `status` after approval — decide: token returned once, subsequent approved responses omit sessionToken; test pins that). Simulating "LAN" in testApplication: the guard needs the remote address — abstract via a `isLoopback(call)` function that tests can override through a constructor flag on `zyncModule` (e.g. `forceRemote: Boolean = false` test hook) — cleaner than faking sockets.

- [ ] Steps: failing tests → RED → implement (PairingManager, SessionManager, RemoteRoutes, guard rework) → GREEN (all suites; Playwright 9/9 — loopback unaffected) → commit `"feat: QR-nonce pairing protocol + Ed25519 challenge-response sessions"` (+ trailer).

---

### Task 5: Native integration — QR scan approval, NSD, foreground service, Settings UI

**Files:**
- Create: `app/src/main/java/dev/njr/zync/remote/RemoteAccessService.kt` (foreground service), `PairingScanner.kt`
- Modify: `app/src/main/java/dev/njr/zync/ZyncApp.kt`, `MainActivity.kt` (JS bridge), `AndroidManifest.xml`
- Create: `app/src/main/assets/web/js/views/settings.js`; Modify: `index.html` (nav), `js/app.js` (route `#/settings`)
- Modify: `app/src/main/java/dev/njr/zync/server/ApiRoutes.kt` (loopback-only settings API)
- Test: `app/src/test/java/dev/njr/zync/server/remote/SettingsApiTest.kt`; Playwright `webtest/tests/settings.spec.js`

**Interfaces:**
- Settings API (loopback-only — guard rejects these paths for LAN sessions): `GET /api/remote` → `{"enabled": bool, "lanPort": int?, "fingerprint": str?, "pending": [{"name","nonce","confirmationCode"}], "devices": [{"id","name","addedAt","lastSeen","revoked"}]}`; `POST /api/remote/enable` / `disable`; `POST /api/remote/devices/{id}/revoke` (also kills its sessions); `POST /api/remote/pair/approve {"nonce"}` — called by the web UI AFTER the native scan flow deep-links back (see bridge). LAN-session calls to any `/api/remote/*` → 403 (test this).
- JS bridge (`@JavascriptInterface` object `zync` on the WebView): `scanPairingQr()` — launches `GmsBarcodeScanner` (dependency `com.google.android.gms:play-services-code-scanner`, current version via ctx_execute maven lookup); on result parses the QR JSON `{pubkey, name, nonce}`, shows a native confirm dialog ("Pair with njr-desktop? Code: 483 921" — compute code via PairingManager), on confirm calls `PairingManager.approve(nonce)` directly (native side, never via HTTP), then `webView.evaluateJavascript("window.dispatchEvent(new Event('zync-paired'))")`. Settings view listens and refreshes.
- `RemoteAccessService`: foreground service (`specialUse` or `connectedDevice` type — pick what AGP/targetSdk 36 accepts; declare permission + `<service>`), started on `POST /api/remote/enable`, holds the server's LAN connector + NSD registration, persistent notification "zync remote access on · N devices", stopped on disable. NSD: `NsdManager.registerService` `_zync._tcp` port = lanPort, TXT record `fp=<first 8 hex of fingerprint>`.
- ZyncApp: remote-enabled flag persisted in `SharedPreferences`; on app start, if enabled, restart service.
- `settings.js` view: remote toggle, fingerprint display, "Pair new device" button (`zync.scanPairingQr()` — feature-detect `window.zync` and hide on desktop), pending list with confirmation codes, device list with revoke buttons.
- Playwright `settings.spec.js`: settings renders; toggle calls enable/disable (dev server: NSD/service are Android-only — `zyncModule` takes a `RemoteControl` interface; the Robolectric dev server uses a fake recording enable/disable; the test asserts UI state changes); device list renders a seeded device; revoke updates the list. (Seed a device through a test-only loopback endpoint? No — seed via the dev server's DB directly in DevServer.kt before serving: insert one allowed_device row.)
- `SettingsApiTest`: enable/disable roundtrip against the fake RemoteControl; revoke kills sessions (validate a token stops working); LAN-session 403 on `/api/remote/*` (using T4's forceRemote hook).

- [ ] Steps: failing tests → RED → implement → GREEN (Kotlin suite + Playwright incl. new spec) → commit `"feat: remote access service, NSD, QR pairing approval, settings UI"` (+ trailer).

---

### Task 6: Milestone smoke (emulator, WebView-wiring items only)
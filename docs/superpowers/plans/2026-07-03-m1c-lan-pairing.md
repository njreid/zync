# zync M1c — LAN Exposure, TLS & QR Pairing (phone side) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a paired desktop reach the phone's web UI securely over the LAN: self-signed TLS with fingerprint pinning, Ed25519 device pairing via QR scan, mDNS discovery, session tokens, and a device-management UI — plus the security hardening the M1b final review gated to this milestone. Ends with the phone side pairing-ready; the Tauri desktop client is a separate plan written afterward.

**Architecture:** The embedded server gains a second connector — loopback HTTP (existing, for the in-app WebView) stays, and a LAN HTTPS connector binds the Wi-Fi interface only when remote access is enabled. A `PairingService` owns Ed25519 device identities (`allowed_device` table), the self-signed server cert (generated once, persisted), and challenge–response session issuance. Desktops discover the phone via NSD/mDNS, POST a pairing request carrying their pubkey + the QR nonce; the user scans the phone-displayed QR and confirms; the response hands back the cert fingerprint + a confirmation code. Spec §8b, §8c.

**Tech Stack:** Ktor 3.5.1 (Netty engine for TLS — see Task 3), BouncyCastle 1.84 (`bcpkix-jdk18on` — cert gen + Ed25519), Play Services code scanner 16.1.0 (`play-services-code-scanner`, no camera permission), Android NSD (`android.net.nsd`, framework — no dep), existing Room/repository/server.

## Global Constraints

- Package `dev.njr.zync`; new server-security code in `dev.njr.zync.pairing`.
- VCS is **jj** (NOT git): `jj commit -m "<msg>"`, no staging; trailer (after blank line) `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Domain layer (`data/`, `domain/`) frozen except explicit schema additions (Task 1 adds `allowed_device`; this is a Room migration — bump `ZyncDatabase` version to 2 and supply a `Migration(1,2)`, do NOT `fallbackToDestructiveMigration`).
- The loopback HTTP path and its per-boot loopback token are UNCHANGED — the WebView keeps working exactly as today. LAN clients use a *different* auth path (device key → session token), never the loopback token.
- **Threat model (write it into code comments where relevant):** attacker is another host on the same Wi-Fi. TLS + cert pinning stops passive sniffing and MITM; the QR nonce (never transmitted until the desktop already knows the phone's cert) binds pairing to physical possession of the phone. An unpaired/revoked device gets 403 and is served zero UI/API.
- Carried-forward hardening items from the M1b final review — each is a task step below, not optional: CSP header; restrict `?token=` acceptance to the document route only; `ws://`→scheme-derived; cookie `SameSite`/`Secure` on the HTTPS connector; constant-time token/fingerprint compare; WS reconnect backoff+jitter.
- Tests: Kotlin via `./gradlew :app:testDebugUnitTest` (Robolectric, sdk=34). Ktor `testApplication` for routes; real BouncyCastle crypto in unit tests (no mocks for signature verify). Playwright suite (`webtest/`) must stay green (9/9); loopback path unaffected. Existing suite is 42 Kotlin tests — keep green.
- No real LAN/mDNS in unit tests — NSD and the actual socket bind are verified by an instrumented/emulator step (Task 8), not JVM tests.

---

### Task 1: allowed_device schema + migration

**Files:**
- Create: `app/src/main/java/dev/njr/zync/data/AllowedDeviceEntity.kt`
- Create: `app/src/main/java/dev/njr/zync/data/AllowedDeviceDao.kt`
- Modify: `app/src/main/java/dev/njr/zync/data/ZyncDatabase.kt` (version 2, add entity + dao + Migration(1,2))
- Test: `app/src/test/java/dev/njr/zync/data/AllowedDeviceDaoTest.kt`, `app/src/test/java/dev/njr/zync/data/MigrationTest.kt`

**Interfaces:**
- `AllowedDeviceEntity(id: Long = 0, name: String, pubkey: String /* base64 Ed25519 */, addedAt: Long, lastSeen: Long?, revoked: Boolean = false)` — `@Entity(tableName = "allowed_device", indices = [Index(value=["pubkey"], unique=true)])`.
- `AllowedDeviceDao`: `suspend insert(d): Long`, `suspend byPubkey(pubkey: String): AllowedDeviceEntity?`, `fun observeAll(): Flow<List<AllowedDeviceEntity>>`, `suspend setRevoked(id: Long, revoked: Boolean)`, `suspend touch(id: Long, lastSeen: Long)`.
- `ZyncDatabase` v2 exposes `allowedDeviceDao()`; `Migration_1_2` creates the table matching the entity.

- [ ] **Step 1: Write the failing tests**

`MigrationTest.kt` (Room `MigrationTestHelper`, schemas exported at `app/schemas/`):
```kotlin
package dev.njr.zync.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ZyncDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migrate1To2_addsAllowedDevice_preservesNodes() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO node (id,kind,parentId,title,notes,status,deferUntil,createdAt,completedAt,sortOrder,builtin) " +
                "VALUES (100,'TASK',1,'survives','','ACTIVE',NULL,1,NULL,0,0)")
        }
        helper.runMigrationsAndValidate(TEST_DB, 2, true, Migration_1_2).use { db ->
            val c = db.query("SELECT title FROM node WHERE id=100")
            assertTrue(c.moveToFirst()); assertTrue(c.getString(0) == "survives"); c.close()
            db.query("SELECT * FROM allowed_device").use { it } // table exists → no throw
        }
    }
    companion object { const val TEST_DB = "migration-test" }
}
```
`AllowedDeviceDaoTest.kt`: insert a device, `byPubkey` returns it, unique-pubkey conflict, `setRevoked` flips the flag, `observeAll` emits, `touch` updates lastSeen.

- [ ] **Step 2: RED** — `./gradlew :app:testDebugUnitTest --tests "dev.njr.zync.data.MigrationTest" --tests "dev.njr.zync.data.AllowedDeviceDaoTest"` → compile failure.

- [ ] **Step 3: Implement** entity, dao, and in `ZyncDatabase`: add `AllowedDeviceEntity` to `@Database(entities=[…], version = 2)`, add `abstract fun allowedDeviceDao()`, define:
```kotlin
val Migration_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS allowed_device (" +
            "id INTEGER PRIMARY KEY AUTOGENERATE, name TEXT NOT NULL, pubkey TEXT NOT NULL, " +
            "addedAt INTEGER NOT NULL, lastSeen INTEGER, revoked INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_allowed_device_pubkey ON allowed_device(pubkey)")
    }
}
```
(Match the generated schema EXACTLY — `AUTOINCREMENT` vs Room's rowid alias: Room uses `INTEGER PRIMARY KEY AUTOINCREMENT` for autoGenerate Long PKs; copy the exact DDL from the exported `app/schemas/dev.njr.zync.data.ZyncDatabase/2.json` after a first build, then reconcile the migration to it. The migration test's `validateDroppedTables=true` will catch any mismatch.) Wire `.addMigrations(Migration_1_2)` into `ZyncDatabase.build()`; `inMemory()` keeps building v2 fresh.

- [ ] **Step 4: GREEN + full suite** → `./gradlew :app:testDebugUnitTest` all green (42 + new).

- [ ] **Step 5: Commit** `feat: allowed_device schema + Room 1→2 migration`

---

### Task 2: Crypto primitives — key identity, cert generation, fingerprint

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (add BouncyCastle)
- Create: `app/src/main/java/dev/njr/zync/pairing/Crypto.kt`
- Test: `app/src/test/java/dev/njr/zync/pairing/CryptoTest.kt`

**Interfaces:**
- Catalog: `bouncycastle-bcpkix = { group="org.bouncycastle", name="bcpkix-jdk18on", version="1.84" }` (pulls `bcprov`).
- `object Crypto`:
  - `fun verifyEd25519(pubkeyB64: String, message: ByteArray, signatureB64: String): Boolean` (BouncyCastle `Ed25519Signer`; returns false on any parse/verify failure, never throws).
  - `fun generateSelfSignedCert(cn: String = "zync"): ServerIdentity` — RSA-2048 or EC P-256 keypair + self-signed X.509 valid ~10y; returns `ServerIdentity(keyStoreBytes: ByteArray, keyStorePassword: CharArray, certFingerprintSha256: String /* hex, colon-separated */)`.
  - `fun sha256Fingerprint(certDer: ByteArray): String`.
  - `fun constantTimeEquals(a: String, b: String): Boolean` (wraps `MessageDigest.isEqual` on UTF-8 bytes).

- [ ] **Step 1: Write the failing tests** — real crypto, no mocks:
```kotlin
@Test fun verifyEd25519_acceptsValidRejectsTampered() {
    // generate a keypair with BouncyCastle in the test, sign a message,
    // assert verify true; flip a byte → false; garbage b64 → false (no throw)
}
@Test fun selfSignedCert_loadsIntoKeyStore_andFingerprintIsStable() {
    val id = Crypto.generateSelfSignedCert()
    // load id.keyStoreBytes with id.keyStorePassword → contains a PrivateKeyEntry;
    // recompute fingerprint from the cert → equals id.certFingerprintSha256
}
@Test fun constantTimeEquals_matchesSemantics() { /* equal→true, diff→false, diff length→false */ }
```

- [ ] **Step 2: RED** → compile failure.

- [ ] **Step 3: Implement** `Crypto.kt` using BouncyCastle (`Ed25519Signer`/`Ed25519PublicKeyParameters`, `JcaX509v3CertificateBuilder` + `JcaContentSignerBuilder`, a `KeyStore` of type `PKCS12`). Register the BC provider once (`Security.addProvider(BouncyCastleProvider())` guarded by an `if` on provider presence).

- [ ] **Step 4: GREEN + full suite.**

- [ ] **Step 5: Commit** `feat: Ed25519 verify + self-signed cert generation (BouncyCastle)`

---

### Task 3: TLS-capable server — engine selection + dual connector

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (Ktor engine for TLS)
- Modify: `app/src/main/java/dev/njr/zync/server/ZyncServer.kt`
- Test: `app/src/test/java/dev/njr/zync/server/TlsConnectorTest.kt`

**Interfaces:**
- `ZyncServer` gains an optional LAN HTTPS connector: `ZyncServer(db, repo, loopbackToken, assets, httpPort=0, lan: LanConfig? = null)` where `LanConfig(keyStore: KeyStore, keyStorePassword: CharArray, keyAlias: String, tlsPort: Int = 0, host: String)`. When `lan != null`, the server binds BOTH a loopback HTTP connector (127.0.0.1) and an HTTPS connector on `lan.host`. `start()` returns the HTTP port; add `fun tlsPort(): Int?`.
- **Engine caveat (validate first):** Ktor CIO's server TLS support is limited/absent across versions. Task Step 1 spikes it; if CIO cannot serve HTTPS under 3.5.1, switch the engine to **Netty** (`ktor-server-netty`) for the whole server (loopback + LAN) — Netty supports `sslConnector`. Keep the engine choice in ONE place.

- [ ] **Step 1: Spike the engine** — before writing tests, in a scratch Robolectric test, attempt an `embeddedServer(CIO){}` with an `sslConnector`; if the API is absent or throws, use `embeddedServer(Netty){ … sslConnector(keyStore, keyAlias, {pw}, {pw}) { port=…; host=… } … }`. Record the decision in the report. (Netty adds a dependency + is heavier but is the documented TLS server engine.) Update `AndroidAssets`/existing `ServerAuthTest`/`ZyncServerSmokeTest` only if the engine swap changes construction — the module (`zyncModule`) is engine-agnostic and should NOT change.

- [ ] **Step 2: Write the failing test** `TlsConnectorTest.kt`:
```kotlin
@Test fun servesHttpsWithGeneratedCert_andHttpLoopbackStillWorks() {
    // Crypto.generateSelfSignedCert → load KeyStore → LanConfig(host="127.0.0.1", tlsPort=0)
    // start ZyncServer; assert start() http port works over plain HTTP with loopback token;
    // assert tlsPort() responds over HTTPS using an HttpClient(CIO) { engine { https { trustManager = <trust-all-for-test> } } }
    // to GET /api/roots with a session/allowed path — for THIS task, assert TLS handshake + 401
    // (no device yet) suffices: proves the HTTPS connector is live with the cert.
}
```
(Trust-all in the *test client* only; production pins the fingerprint on the Tauri side.)

- [ ] **Step 3: RED → implement the dual connector → GREEN.**

- [ ] **Step 4: Full suite** (loopback tests unchanged and green).

- [ ] **Step 5: Commit** `feat: TLS LAN connector alongside loopback HTTP (<engine> engine)`

---

### Task 4: PairingService — identities, sessions, hardening

**Files:**
- Create: `app/src/main/java/dev/njr/zync/pairing/PairingService.kt`
- Create: `app/src/main/java/dev/njr/zync/pairing/PairingDto.kt`
- Test: `app/src/test/java/dev/njr/zync/pairing/PairingServiceTest.kt`

**Interfaces:**
- `class PairingService(dao: AllowedDeviceDao, now: () -> Long = System::currentTimeMillis, randomNonce: () -> String)`:
  - `fun beginPairing(): PendingPairing` — creates a one-time `nonce` + short human `confirmCode` (derived from nonce), TTL 2 min; only one pending at a time (new call supersedes).
  - `suspend fun approveScanned(scannedPayload: String): ApprovedDevice` — called after the phone scans the desktop's QR; the QR payload carries `{devicePubkey, deviceName, nonce}`; verify the nonce matches the current pending; insert into `allowed_device`; returns the device + the confirmCode to display.
  - `suspend fun completePairingRequest(devicePubkeyB64: String, nonceFromDesktop: String): PairingResult` — the desktop's polled request; succeeds only once the phone has approved this pubkey (device row exists, not revoked) AND the nonce matches; returns `{certFingerprint, confirmCode}`.
  - `suspend fun issueSession(devicePubkeyB64: String, challenge: String, signatureB64: String): String?` — verifies the device is allowed+not-revoked and the signature over the server-issued challenge; returns an opaque session token (random, stored in-memory with TTL) or null.
  - `fun validateSession(token: String): Boolean` (constant-time), `fun newChallenge(): String`, `suspend fun revoke(id: Long)`, `fun setCertFingerprint(fp: String)`.
- DTOs `@Serializable`: `QrPayload(devicePubkey, deviceName, nonce)`, `PairRequestBody(devicePubkey, nonce)`, `PairResultDto(certFingerprint, confirmCode)`, `ChallengeDto(challenge)`, `SessionRequestBody(devicePubkey, challenge, signature)`, `SessionDto(token)`.

- [ ] **Step 1: Write failing tests** (real Ed25519 keypair generated in-test via BouncyCastle):
```
- beginPairing produces nonce + confirmCode; expires after TTL (inject now()).
- approveScanned with matching nonce inserts device; wrong/expired nonce → throws IllegalArgumentException.
- completePairingRequest before approval → fails; after approval with right nonce → returns fingerprint+confirmCode; replaying a consumed nonce → fails (one-time).
- issueSession: allowed device + valid signature over challenge → non-null token; wrong signature → null; revoked device → null; unknown pubkey → null.
- validateSession true for issued token, false after nothing/garbage; constant-time path exercised.
- challenge is single-use (issuing a session consumes it).
```

- [ ] **Step 2: RED → implement → GREEN.** Sessions + challenges live in-memory (`ConcurrentHashMap` with expiry); nonces one-time. Use `Crypto.verifyEd25519` and `Crypto.constantTimeEquals`.

- [ ] **Step 3: Full suite. Commit** `feat: PairingService — Ed25519 device pairing, challenge-response sessions`

---

### Task 5: Pairing & LAN auth routes + carried-forward hardening

**Files:**
- Modify: `app/src/main/java/dev/njr/zync/server/ZyncServer.kt` (guard: accept loopback token OR valid LAN session; scope `?token=` to document route; CSP header; cookie flags)
- Create: `app/src/main/java/dev/njr/zync/server/PairingRoutes.kt`
- Modify: `app/src/main/assets/web/js/api.js` (ws scheme derivation, reconnect backoff+jitter)
- Modify: `app/src/main/assets/web/index.html` (CSP `<meta>` as belt-and-braces to the header)
- Test: `app/src/test/java/dev/njr/zync/server/PairingRoutesTest.kt`

**Interfaces:**
- Routes (on the server, reachable over both connectors unless noted):
  - `POST /pair/request` `PairRequestBody` → 202 `{status:"pending"}` until approved, then `PairResultDto` (desktop polls). Unauthenticated by design (pre-pairing) but rate-limited + only meaningful with a live nonce.
  - `GET /pair/challenge?devicePubkey=…` → `ChallengeDto` (issues a single-use challenge).
  - `POST /pair/session` `SessionRequestBody` → `SessionDto` (200) or 401. The token goes in an `Authorization: Bearer` header or a `SameSite=Strict; Secure` cookie for subsequent calls.
  - Existing `/api/**`, `/api/events`, static assets: the guard now accepts **either** the loopback token (127.0.0.1 only) **or** a valid LAN session token. A request on the LAN connector with neither → 403, zero body.
- Hardening (each an explicit step, from the M1b final review):
  1. **CSP**: `Content-Security-Policy: default-src 'self'; connect-src 'self' ws: wss:; img-src 'self' data:` header installed in `zyncModule` for all responses; matching `<meta http-equiv>` in index.html.
  2. **`?token=` scoping**: the query-token→cookie exchange fires ONLY on the document route (`/` and `/index.html`), never on `/api/**` or assets — move that logic out of the global guard.
  3. **ws scheme**: `api.js` derives `ws:`/`wss:` from `location.protocol`.
  4. **Cookie flags**: loopback cookie stays as-is; any cookie set on the HTTPS connector gets `Secure; SameSite=Strict`.
  5. **Constant-time**: loopback-token and session-token comparisons use `Crypto.constantTimeEquals`.
  6. **WS reconnect**: `api.js` backoff (e.g. 1s→30s exponential + jitter), reset on successful open.

- [ ] **Step 1: Write failing tests** `PairingRoutesTest.kt` (Ktor `testApplication` with a `PairingService` over in-memory dao; drive the full handshake with a real in-test keypair):
```
- full pairing: beginPairing (simulate phone side) → POST /pair/request pending → approve → poll returns fingerprint+confirmCode.
- session: GET /pair/challenge → sign → POST /pair/session → token; then GET /api/roots with the session token → 200.
- LAN request with no session → 403 and empty body; revoked device's session rejected.
- ?token= on /api/roots does NOT set a cookie / is ignored (scoping); on / it still works for loopback.
- CSP header present on document + api responses.
```

- [ ] **Step 2: RED → implement routes + refactor guard + JS hardening → GREEN.**

- [ ] **Step 3: Playwright** (`cd webtest && npx playwright test`) still 9/9 — loopback path unbroken by the guard refactor and CSP (watch: CSP must allow the app's own inline handlers? The app uses external ES modules + addEventListener, NO inline scripts, so `default-src 'self'` is fine — verify no inline `onclick` attributes exist; the views use `.onclick =` in JS, which CSP allows). Full Kotlin suite green.

- [ ] **Step 4: Commit** `feat: pairing/session routes + M1b-deferred hardening (CSP, token scoping, cookie flags, constant-time, ws backoff)`

---

### Task 6: Remote-access lifecycle, NSD advertising, ZyncApp wiring

**Files:**
- Create: `app/src/main/java/dev/njr/zync/pairing/NsdAdvertiser.kt`
- Create: `app/src/main/java/dev/njr/zync/pairing/RemoteAccessManager.kt`
- Modify: `app/src/main/java/dev/njr/zync/ZyncApp.kt`
- Modify: `app/src/main/AndroidManifest.xml` (ACCESS_NETWORK_STATE, foreground service for remote access)
- Test: `app/src/test/java/dev/njr/zync/pairing/RemoteAccessManagerTest.kt`

**Interfaces:**
- `RemoteAccessManager(app, server, pairingService, certStore)`:
  - `fun enable(): RemoteInfo` — generates/loads the persisted server cert (store the keystore bytes + password in app-private files, NOT shared storage), (re)starts the server with a `LanConfig` bound to the current Wi-Fi IP, starts NSD advertising, returns `{ip, tlsPort, certFingerprint}`. Idempotent.
  - `fun disable()` — stop NSD, drop the LAN connector (rebind loopback-only), keep pairings.
  - `fun state(): RemoteState` (disabled | enabled with info).
  - persists the cert so the fingerprint is STABLE across app restarts (re-pairing not required each launch).
- `NsdAdvertiser`: registers `_zync._tcp` on the tls port with a TXT record carrying a short fingerprint hint + device name; `register(port, name)` / `unregister()`.
- `ZyncApp` exposes `val remoteAccess: RemoteAccessManager` and `val pairingService`.
- The current Wi-Fi IPv4 obtained via `ConnectivityManager`/`LinkProperties` (not deprecated `WifiManager.getIpAddress` where avoidable).

- [ ] **Step 1: Write failing tests** — `RemoteAccessManagerTest` with a fake NSD + fake server (interfaces so JVM-testable): enable() persists a keystore and returns a stable fingerprint across two `enable()` calls (cert reused, not regenerated); disable() unregisters NSD; cert file lives under app-private dir. Real NSD/socket bind is Task 8 (emulator), NOT here — inject a `NsdAdvertiser` interface with a fake in the test.

- [ ] **Step 2: RED → implement → GREEN.** Cert persistence: `Crypto.generateSelfSignedCert()` once, write keystore to `filesDir/zync-server.p12`, reload thereafter.

- [ ] **Step 3: Full suite. Commit** `feat: remote-access lifecycle + NSD advertising + persistent server cert`

---

### Task 7: Pairing & device-management UI (native + web)

**Files:**
- Create: `app/src/main/java/dev/njr/zync/pairing/QrScanBridge.kt` (JS bridge → GmsBarcodeScanner)
- Modify: `app/src/main/java/dev/njr/zync/MainActivity.kt` (register the bridge, plumb approveScanned)
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (play-services-code-scanner)
- Create: `app/src/main/assets/web/js/views/settings.js`
- Modify: `app/src/main/assets/web/js/app.js` (add `#/settings` route), `index.html` nav
- Modify: `app/src/main/java/dev/njr/zync/server/PairingRoutes.kt` (settings-facing endpoints: list/revoke devices, enable/disable remote, current pairing status)
- Test: `app/src/test/java/dev/njr/zync/server/DeviceMgmtRoutesTest.kt`

**Interfaces:**
- JS bridge (loopback/in-WebView only — guard that these endpoints/bridge are reachable only from the phone's own WebView, not LAN clients): `window.ZyncNative.scanPairingQr()` → triggers `GmsBarcodeScanner`, returns the scanned payload to JS via a callback/promise shim; JS posts it to `POST /pair/approve` (loopback-guarded) which calls `PairingService.approveScanned`.
- Settings web view (`#/settings`): a Remote Access toggle (calls `POST /remote/enable|disable`, shows IP + a QR? No — the DESKTOP shows the QR; the phone SCANS. So settings shows: remote on/off + current fingerprint + "Pair a browser" button → `ZyncNative.scanPairingQr()` → on success shows the confirmCode to compare with the desktop) and a device list (`GET /devices`) with per-device last-seen + Revoke button (`POST /devices/{id}/revoke`).
- Endpoints (loopback-guarded, i.e. rejected on the LAN connector): `POST /remote/enable`→RemoteInfo, `POST /remote/disable`, `GET /remote/state`, `POST /pair/approve` (body: scanned payload)→confirmCode, `GET /devices`→[AllowedDeviceDto], `POST /devices/{id}/revoke`.

- [ ] **Step 1: Write failing tests** `DeviceMgmtRoutesTest.kt`: `/devices` lists inserted devices with lastSeen; revoke flips revoked and a subsequent session issuance for that device fails; `/remote/*` and `/pair/approve` return 403 when called over the LAN connector (simulate by marking the call's local address / a connector tag) but 200 over loopback. (If distinguishing connectors in `testApplication` is hard, assert the guard function's logic directly in a focused unit test and note it.)

- [ ] **Step 2: RED → implement.** `QrScanBridge` uses `GmsBarcodeScanning.getClient(context).startScan()` (no camera permission needed). The `@JavascriptInterface` method kicks the scan and resolves back into the WebView via `evaluateJavascript` calling a JS callback.

- [ ] **Step 3: GREEN + Playwright.** Add a Playwright test for the settings *web* pieces that don't need the native bridge: device list renders from `/devices`, revoke button posts and the row updates; stub `window.ZyncNative` in the test page context so "Pair a browser" degrades gracefully.

- [ ] **Step 4: Commit** `feat: pairing + device-management UI (QR scan bridge, settings view, revoke)`

---

### Task 8: Instrumented/emulator verification — real TLS handshake, NSD, pairing dry-run

**Files:** none (verification; fixes committed if found).

- [ ] **Step 1: Build + deploy** to emulator (`./gradlew :app:assembleDebug`, `android emulator start`, `android run …`). Prefer web-level checks where possible per the M1b learning, but TLS/NSD are genuinely native and need the device.

- [ ] **Step 2: Verify (each PASS/FAIL with evidence — screenshot or adb/logcat):**
  1. Enable Remote Access in Settings → server rebinds; `adb shell` `netstat`/`ss` shows a listener on the Wi-Fi IP:tlsPort (not just loopback).
  2. From the host, `openssl s_client -connect <emu-ip>:<tlsPort>` (run via ctx_execute sandbox — curl is blocked, openssl is not HTTP) → presents the self-signed cert; SHA-256 fingerprint matches what Settings displays.
  3. An HTTPS `GET /api/roots` with NO session → 403.
  4. Full pairing dry-run with a scripted "desktop": a small local script (ctx_execute, node/python) that (a) generates an Ed25519 keypair, (b) discovers `_zync._tcp` via mDNS (or is given the IP), (c) POSTs `/pair/request`; meanwhile drive the phone UI "Pair a browser" and feed it the script's QR payload (via `adb shell am start`/intent or by rendering the payload as a QR the emulator camera can't see — instead: since the phone SCANS, simulate approveScanned by hitting `/pair/approve` over loopback with the payload); then the script completes challenge→session→`GET /api/roots` over pinned TLS → 200.
  5. Revoke the device in Settings → the script's session token now 403s.
  6. Loopback WebView UI still works unchanged (regression).
- [ ] **Step 3: Fix findings (TDD where testable), stop emulator, commit** `chore: M1c LAN pairing emulator-verified`.

---

## Self-Review Notes

- **Spec §8b/§8c coverage:** self-signed TLS + fingerprint (T2/T3), Ed25519 device keys + allowed_device (T1/T2/T4), QR pairing with nonce (T4/T7), challenge-response sessions (T4/T5), mDNS/NSD discovery (T6), revocation UI (T7). Tauri desktop client (§8c) is explicitly a SEPARATE plan written after this — this plan makes the phone pairing-ready and verifies it with a scripted desktop stand-in (T8).
- **All six M1b-deferred hardening items land in T5** (CSP, ?token= scoping, ws scheme, cookie flags, constant-time, ws backoff) — cross-referenced to the ledger.
- **Migration risk:** T1 bumps the DB to v2 with a real migration + a migration test; no destructive fallback (protects any real captured data).
- **Engine risk:** T3 Step 1 explicitly spikes CIO-vs-Netty for TLS before committing to an approach — the one place this plan could fork.
- **Threat-model honesty:** pre-pairing `/pair/request` is unauthenticated by necessity; security rests on the one-time QR nonce (possession of the phone) + TLS pinning. Rate-limit it (T5) so it can't be brute-forced. Written into code comments per Global Constraints.
- **Not in scope (future):** WS reconnect backoff tuning under many clients, multi-pending-pairing, cert rotation, the actual Tauri app.

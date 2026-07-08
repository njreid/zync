# zync M1d — Tauri Desktop Client Implementation Plan

> **Status: ✅ COMPLETE** (shipped, incl. the v0.2 desktop cask under `Casks/`).
> The Tauri client discovers the phone over mDNS, pairs via QR + Ed25519, pins the
> phone's TLS cert, and proxies the phone's web UI through a local pinned-TLS
> reverse proxy; `desktop/src-tauri/tests/fake_phone.rs` is the no-device harness.
> ⏳ Still deferred: the localhost proxy has no additional local auth. The inline
> `- [ ]` checkboxes are the original plan, not maintained inline.
>
> **🧭 Largely superseded (2026-07-08):** under the target architecture the desktop
> becomes a **thin, online-only client of the central server** over ordinary HTTPS —
> so this Tauri discovery / pinning / reverse-proxy stack mostly retires. See
> `docs/superpowers/specs/2026-07-08-kotlin-kmp-target-architecture.md`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A cross-platform Tauri desktop app that discovers a paired zync phone over mDNS, pairs to it via QR + Ed25519 (the phone scans the desktop's QR), pins the phone's self-signed TLS cert, and hosts the phone's existing web UI over a local pinned-TLS reverse proxy — so the same GTD UI runs on the desktop against the phone's data.

**Architecture:** A Tauri 2 app in `desktop/`. The **Rust core** owns: an Ed25519 device identity persisted in the OS keychain; mDNS discovery (`_zync._tcp`); a pinned HTTPS client (reqwest + rustls with a custom fingerprint-checking verifier — TOFU-capture during pairing, then enforce); the pairing handshake driver; and a **local reverse proxy** (axum on `127.0.0.1:<random>`) that injects the session bearer token and forwards HTTP + WebSocket to the phone over the pinned client. The **webview** loads a small bundled vanilla-JS pairing screen; once paired it navigates to the proxy root, which streams the phone's web app. Cert pinning lives in Rust because the system webview can't pin a self-signed cert itself. Spec §8b, §8c.

**Phone-side contract (FROZEN — this is the server we target, already shipped & on-device-verified in M1c):**
- Discovery: mDNS/DNS-SD service `_zync._tcp`, TXT attribute `fp` = short fingerprint hint. Resolve → host IP + TLS port.
- QR the desktop displays (phone scans it with its camera): JSON `QrPayload { devicePubkey: String (base64-standard, raw 32-byte Ed25519 pubkey), deviceName: String, nonce: String }`.
- `POST /pair/request` body `{ devicePubkey, nonce }` over TLS → `202` `PairPendingDto` (`{status:"pending"}`) until the phone user approves the scan, then `PairResultDto { certFingerprint: String, confirmCode: String }`. Desktop polls.
- `GET /pair/challenge?devicePubkey=<b64>` → `ChallengeDto { challenge: String }` (single-use).
- `POST /pair/session` body `{ devicePubkey, challenge, signature }` (signature = base64-standard of Ed25519 signature over `challenge.getBytes(UTF-8)` — sign the RAW challenge string bytes) → `SessionDto { token: String }` or 401.
- All `/api/**` + assets over the LAN TLS connector require the session token via `Authorization: Bearer <token>` (or a `Secure; SameSite=Strict` cookie). `/pair/*` are pre-auth. Unpaired/revoked → 403.
- `certFingerprint` / hint format: uppercase, colon-separated SHA-256 hex over the cert DER.
- `confirmCode` = first 8 uppercase hex chars of SHA-256(nonce UTF-8) (the desktop can compute this locally to auto-verify the phone it reached is the one that scanned its QR — a programmatic MITM check on top of the human visual compare).

## Global Constraints

- New code lives in `desktop/` (a Cargo project + Tauri app); it does NOT touch the Android app. This plan adds NO Android/Kotlin changes.
- VCS is **jj** (NOT git): `jj commit -m "<msg>"`, no staging; trailer (after blank line) `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Rust edition 2021; pinned crate versions: `tauri = "2.11"`, `ed25519-dalek = "2.2"`, `mdns-sd = "0.20"`, `reqwest = "0.13"` (default-features off; `rustls-tls-manual-roots` + `json` + `stream`), `rustls = "0.23"`, `rustls-pki-types = "1.15"`, `keyring = "4.1"`, `qrcode = "0.14"`, `rand = "0.10"`, `tokio = "1"` (`rt-multi-thread`, `macros`, `net`), `axum = "0.8"` (with `ws`), `sha2 = "0.11"`, `base64 = "0.22"`, `tokio-tungstenite = "0.29"`, `serde`/`serde_json = "1"`. Tauri CLI `@tauri-apps/cli@2.11`.
- **Base64 must be standard (with padding)** to match the phone's `java.util.Base64` decoder — use `base64::engine::general_purpose::STANDARD`.
- **Signing target must be the raw challenge string's UTF-8 bytes** — NOT a hash, NOT decoded. Verified against phone `PairingService.issueSession` (`challenge.toByteArray(UTF_8)` → `verifyEd25519`).
- The pinned-TLS verifier must ENFORCE the pinned fingerprint on every connection after pairing; during the first pairing exchange it operates in capture-once (TOFU) mode, and the capture is validated against the `/pair/request` response's `certFingerprint` AND the locally-computed `confirmCode` before being persisted. Never silently trust any cert outside these two modes.
- Secrets (Ed25519 private key, per-phone pinned fingerprint + session token) persist in the OS keychain via `keyring`, never in plaintext files.
- Tests: `cargo test` in `desktop/src-tauri`. The pairing/proxy logic is unit-tested against a **fake-phone axum harness** (in `desktop/src-tauri/tests/`) that mimics the phone's pairing + `/api` routes with a throwaway self-signed cert — so the whole handshake and proxy are testable with NO real device. A final task does a real-device/emulator e2e.
- curl/wget blocked by a repo hook; use `cargo`, `openssl`, `ctx_execute` sandbox fetch as needed. `cargo`/`rustup` assumed installed — Task 1 Step 1 verifies and reports BLOCKED if not.

---

### Task 1: Scaffold the Tauri app + workspace

**Files:**
- Create: `desktop/` Tauri 2 project (`desktop/src-tauri/Cargo.toml`, `desktop/src-tauri/src/main.rs`, `desktop/src-tauri/tauri.conf.json`, `desktop/ui/` bundled assets, `desktop/package.json` for the CLI only).

**Interfaces:**
- Produces: a Tauri app that builds (`cargo build` in `src-tauri`) and runs (`npm run tauri dev`) showing a placeholder window loading `desktop/ui/index.html`. No frontend framework — vanilla JS, no bundler; `tauri.conf.json` points `frontendDist` at `desktop/ui`.

- [ ] **Step 1: Verify toolchain**

Run: `rustc --version && cargo --version && node --version`
Expected: all present. If `cargo`/`rustc` missing, report BLOCKED (needs `rustup`). Note the OS (the keychain + webview backends differ per platform; develop/verify on this Linux host — `keyring` uses the Secret Service on Linux; note that a headless CI may lack it, see Task 2).

- [ ] **Step 2: Scaffold**

```bash
cd /home/njr/code/zync
npm create tauri-app@latest desktop -- --template vanilla --manager npm --yes 2>&1 | tail -20
# if the interactive template differs, scaffold manually: create desktop/src-tauri with the Cargo.toml below and a minimal main.rs
```
Set in `desktop/src-tauri/tauri.conf.json`: `productName: "zync"`, `identifier: "dev.njr.zync.desktop"`, `build.frontendDist: "../ui"`, and a single window titled "zync". Put a placeholder `desktop/ui/index.html` (`<h1>zync desktop</h1>` + `<script type="module" src="/main.js">`) and empty `desktop/ui/main.js`.

- [ ] **Step 3: Cargo deps**

Set `desktop/src-tauri/Cargo.toml` `[dependencies]` to the pinned versions from Global Constraints. Add `[dev-dependencies]`: `axum = { version = "0.8", features = ["ws"] }`, `rcgen = "0.13"` (throwaway certs for the fake phone), `tokio = { version = "1", features = ["full"] }`, `reqwest` (already), `tempfile = "3"`.

- [ ] **Step 4: Build + run**

Run: `cd desktop/src-tauri && cargo build 2>&1 | tail -15`
Expected: `Finished`. (A full `tauri dev` needs a display; if headless, `cargo build` compiling the Tauri app is the gate, and note that windowed run is verified in Task 7.)

- [ ] **Step 5: Add `desktop/.gitignore`** (`target/`, `node_modules/`, `dist/`) and commit.

```bash
jj commit -m "feat(desktop): scaffold Tauri 2 vanilla app

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Device identity — Ed25519 keypair + keychain persistence + wire encoding

**Files:**
- Create: `desktop/src-tauri/src/identity.rs`
- Modify: `desktop/src-tauri/src/main.rs` (module decl)
- Test: inline `#[cfg(test)]` in `identity.rs`

**Interfaces:**
- Produces:
  - `struct DeviceIdentity { signing: SigningKey, device_name: String }`
  - `fn load_or_create(device_name: &str) -> Result<DeviceIdentity>` — loads the Ed25519 private key from the OS keychain (service `"dev.njr.zync.desktop"`, account `"device-key"`); creates + persists on first run. **Keychain-unavailable fallback:** if `keyring` errors (headless Linux without Secret Service), fall back to a `0600` file under the app config dir and log a warning — pairing must still work in dev/CI. Gate the fallback behind a clear `IdentityStore` trait so tests inject an in-memory store.
  - `fn public_key_b64(&self) -> String` — STANDARD base64 of the raw 32-byte public key (matches phone decoder).
  - `fn sign_challenge_b64(&self, challenge: &str) -> String` — STANDARD base64 of the Ed25519 signature over `challenge.as_bytes()` (raw UTF-8 string bytes).
  - `fn confirm_code(nonce: &str) -> String` — first 8 uppercase hex chars of SHA-256(nonce bytes).

- [ ] **Step 1: Write failing tests**

```rust
#[test] fn pubkey_is_standard_base64_32_bytes() {
    let id = DeviceIdentity::from_seed([7u8;32], "test");
    let raw = base64::engine::general_purpose::STANDARD.decode(id.public_key_b64()).unwrap();
    assert_eq!(raw.len(), 32);
}
#[test] fn signature_verifies_over_raw_challenge_bytes() {
    // sign "hello-challenge"; verify with ed25519-dalek VerifyingKey over the SAME raw bytes,
    // and confirm the signature is standard-base64 64 bytes — this is exactly what the phone checks.
    let id = DeviceIdentity::from_seed([9u8;32], "test");
    let sig_b64 = id.sign_challenge_b64("hello-challenge");
    let sig = Signature::from_slice(&STANDARD.decode(&sig_b64).unwrap()).unwrap();
    id.verifying_key().verify_strict(b"hello-challenge", &sig).unwrap();
}
#[test] fn confirm_code_matches_phone_formula() {
    // SHA-256("abc") first 8 hex upper. Precompute the expected constant and assert.
    assert_eq!(DeviceIdentity::confirm_code("abc"), "BA7816BF"); // first 8 hex of sha256("abc"), uppercased
}
#[test] fn identity_roundtrips_through_injected_store() {
    let store = InMemoryStore::default();
    let a = DeviceIdentity::load_or_create_with("dev", &store).unwrap();
    let b = DeviceIdentity::load_or_create_with("dev", &store).unwrap();
    assert_eq!(a.public_key_b64(), b.public_key_b64()); // same key reloaded, not regenerated
}
```
(Verify the `confirm_code("abc")` constant by computing SHA-256("abc") = `ba7816bf...`; first 8 hex upper = `BA7816BF`. This pins byte-for-byte agreement with the phone's `deriveConfirmCode`.)

- [ ] **Step 2: RED** — `cargo test identity` → fails to compile.

- [ ] **Step 3: Implement** using `ed25519-dalek` `SigningKey`, `sha2::Sha256`, `base64 STANDARD`, `keyring::Entry` behind an `IdentityStore` trait (`KeychainStore`, `FileStore` fallback, `InMemoryStore` for tests). `from_seed` is a test-only constructor.

- [ ] **Step 4: GREEN + commit** `feat(desktop): Ed25519 identity, keychain persistence, phone-matching wire encoding`

---

### Task 3: mDNS discovery

**Files:**
- Create: `desktop/src-tauri/src/discovery.rs`
- Test: inline + a `tests/` integration test that registers a fake `_zync._tcp` service and discovers it.

**Interfaces:**
- Produces:
  - `struct DiscoveredPhone { name: String, host: IpAddr, port: u16, fp_hint: Option<String> }`
  - `fn browse(timeout: Duration) -> Result<Vec<DiscoveredPhone>>` — uses `mdns-sd` to browse `_zync._tcp.local.`, resolves each to host/port, reads the `fp` TXT attribute. De-dupes by name.
  - An async streaming variant `fn subscribe() -> Receiver<DiscoveryEvent>` for the UI to update live (Added/Removed).

- [ ] **Step 1: Write the failing integration test** (`tests/discovery.rs`): register a service via `mdns-sd`'s own `ServiceDaemon` with type `_zync._tcp.local.`, TXT `fp=AA:BB`, on some port; call `browse(2s)`; assert the phone appears with that name/port/fp_hint. (Loopback mDNS works on the dev host.)

- [ ] **Step 2: RED → implement → GREEN.** Handle the common `mdns-sd` gotcha: you must keep the `ServiceDaemon` alive for the browse duration and drain its event receiver; resolve `ServiceResolved` events for host+port+TXT.

- [ ] **Step 3: Commit** `feat(desktop): mDNS discovery of _zync._tcp phones`

---

### Task 4: Pinned-TLS client + pairing handshake driver

**Files:**
- Create: `desktop/src-tauri/src/pinning.rs` (custom rustls verifier)
- Create: `desktop/src-tauri/src/pairing.rs` (handshake driver)
- Create: `desktop/src-tauri/tests/fake_phone.rs` (reusable fake-phone harness)
- Test: `desktop/src-tauri/tests/pairing_e2e.rs`

**Interfaces:**
- `pinning.rs`:
  - `enum PinMode { CaptureOnce(Arc<Mutex<Option<String>>>), Enforce(String) }` — CaptureOnce records the leaf cert's SHA-256 fingerprint (uppercase colon-hex) and accepts it; Enforce accepts ONLY if the leaf fingerprint equals the pinned string, else TLS error.
  - `fn pinned_client(mode: PinMode) -> reqwest::Client` — builds a reqwest client whose rustls `ClientConfig` uses a `ServerCertVerifier` implementing `PinMode`. (Skip WebPKI/root validation deliberately — pinning replaces it.)
  - `fn leaf_fingerprint(cert_der: &[u8]) -> String` — SHA-256, uppercase, colon-separated (byte-for-byte the phone's format).
- `pairing.rs`:
  - `struct PairedPhone { host: IpAddr, tls_port: u16, fingerprint: String, session_token: String }`
  - `async fn pair(id: &DeviceIdentity, phone: &DiscoveredPhone, on_confirm_code: impl Fn(&str)) -> Result<PairedPhone>`:
    1. Generate a random `nonce` (URL-safe, ≥128-bit).
    2. Emit the QR payload JSON `{devicePubkey, deviceName, nonce}` to the UI (via a channel/callback) so it renders the QR; the phone scans + user approves.
    3. Build a `CaptureOnce` pinned client; poll `POST /pair/request {devicePubkey, nonce}` (backoff, ~2 min budget) until it returns `PairResultDto` (not the 202 pending).
    4. **Verify:** captured leaf fingerprint == `result.certFingerprint` (the server's claim about itself must match the cert we actually terminated TLS against) AND `DeviceIdentity::confirm_code(nonce)` == `result.confirmCode`. Call `on_confirm_code(&result.confirmCode)` so the human can also eyeball it against the phone. If either check fails → abort (possible MITM).
    5. Switch to an `Enforce(fingerprint)` client. `GET /pair/challenge?devicePubkey` → sign → `POST /pair/session` → token.
    6. Return `PairedPhone`; persist `{fingerprint, session_token}` keyed by phone name via the identity store/keychain.
- `fake_phone.rs`: `async fn spawn_fake_phone() -> FakePhone` — an axum HTTPS server (rcgen self-signed cert) implementing `/pair/request` (returns pending N times then the result with ITS real fingerprint + `confirm_code(nonce)`), `/pair/challenge` (returns a random challenge), `/pair/session` (verifies the Ed25519 sig over the challenge with the pubkey from the request, issues a token), and a token-guarded `GET /api/roots` returning `[{"title":"Inbox"},{"title":"Someday"}]`. Exposes its host/port/fingerprint.

- [ ] **Step 1: Write the failing e2e test** (`tests/pairing_e2e.rs`):
```
- spawn_fake_phone(); build a DiscoveredPhone pointing at it.
- pair(id, phone, |_| {}) succeeds, returns a token and the fake's fingerprint.
- Using an Enforce client with that fingerprint + Bearer token, GET /api/roots → 200, body has Inbox+Someday.
- Negative: a SECOND fake phone with a DIFFERENT cert but returning the same claimed certFingerprint → pair() fails at the fingerprint cross-check (captured != claimed).
- Negative: fake returns a wrong confirmCode → pair() aborts.
- Negative: after pairing, an Enforce client pointed at a DIFFERENT cert → TLS error (pin holds).
```

- [ ] **Step 2: RED → implement pinning.rs + pairing.rs + the harness → GREEN.** The rustls custom verifier implements `danger::ServerCertVerifier` (verify_server_cert computes the leaf fingerprint and applies `PinMode`; return `HandshakeSignatureValid::assertion()` for the tls12/tls13 signature callbacks). Note reqwest 0.13 + rustls 0.23 wiring: use `reqwest::ClientBuilder::use_preconfigured_tls(rustls_config)`.

- [ ] **Step 3: Commit** `feat(desktop): pinned-TLS client + Ed25519 pairing handshake (fake-phone tested)`

---

### Task 5: Local reverse proxy + Tauri commands

**Files:**
- Create: `desktop/src-tauri/src/proxy.rs`
- Create: `desktop/src-tauri/src/commands.rs`
- Modify: `desktop/src-tauri/src/main.rs` (wire state + `invoke_handler`)
- Test: `desktop/src-tauri/tests/proxy.rs`

**Interfaces:**
- `proxy.rs`:
  - `async fn start_proxy(paired: PairedPhone) -> Result<ProxyHandle>` — binds axum on `127.0.0.1:0`, returns the bound port. Every inbound request is forwarded to `https://<phone-host>:<tls_port><path>` via the `Enforce`-pinned client with `Authorization: Bearer <token>` injected; response streamed back (status, headers minus hop-by-hop, body). `GET /api/events` (WebSocket) is upgraded and bridged bidirectionally to the phone's `wss://.../api/events` via `tokio-tungstenite` over a pinned TLS connector. Strips/handles CORS/host as needed so the phone's `default-src 'self'` CSP is satisfied (origin is the proxy).
  - `ProxyHandle { port: u16, shutdown: oneshot::Sender<()> }`.
- `commands.rs` (Tauri `#[tauri::command]`s the JS calls): `discover() -> Vec<DiscoveredPhoneDto>`, `pair(phone_name) -> PairingStartedDto` (kicks pairing, streams QR payload + confirm code via Tauri events `qr-payload`, `confirm-code`, `paired`/`pair-failed`), `connection_state()`, `forget(phone_name)`, and `proxy_url() -> String` (the `http://127.0.0.1:<port>/` the webview should load once paired).

- [ ] **Step 1: Write failing test** (`tests/proxy.rs`): spawn_fake_phone + pair → start_proxy → an ordinary (non-pinned, plain-HTTP) reqwest GET to `http://127.0.0.1:<proxyport>/api/roots` (NO auth header from the caller) returns 200 with Inbox/Someday — proving the proxy injected the Bearer token and forwarded over pinned TLS. Add a WS test: connect `ws://127.0.0.1:<proxyport>/api/events`, assert the `hello` frame arrives (the fake phone must implement the events WS).

- [ ] **Step 2: RED → implement → GREEN.** Hop-by-hop header care (don't forward `connection`, `upgrade` except for the WS path). Use `axum::extract::ws` on the inbound side and `tokio-tungstenite` on the phone side.

- [ ] **Step 3: Commit** `feat(desktop): pinned reverse proxy (HTTP+WS) + Tauri commands`

---

### Task 6: Pairing & connection UI (bundled vanilla JS)

**Files:**
- Replace: `desktop/ui/index.html`, `desktop/ui/main.js`
- Create: `desktop/ui/pairing.js`, `desktop/ui/style.css` (vendor PicoCSS for visual consistency with the phone app — fetch via ctx_execute sandbox to `desktop/ui/vendor/pico.min.css`)
- Create: `desktop/ui/qr.js` (or vendor a tiny QR renderer; simplest: the RUST side renders the QR to an SVG/PNG data-URI using the `qrcode` crate and passes it via the `qr-payload` event, so the JS just sets an `<img src>` — prefer this, no JS QR lib needed)

**Interfaces:**
- Startup screen: calls `discover()`, lists phones (name + fp hint), each with a **Pair** button. Pair → calls `pair(name)`; listens for the `qr-payload` event → shows the QR image with "Scan this in the zync app → Pair a browser"; on `confirm-code` → shows the 8-char code "Confirm this matches your phone"; on `paired` → calls `proxy_url()` and navigates `window.location = proxyUrl` (the webview now loads the phone's web app through the proxy). On `pair-failed` → error with retry.
- A small persistent "connection" affordance (once paired on later launches, auto-`discover()` the known phone, `start_proxy`, and jump straight to `proxy_url()`), plus a "Forget device" action.

- [ ] **Step 1:** Because there's no JS test runner here, the gate is: `cargo build` succeeds, `desktop/ui/*.js` pass `node --check`, and the flow is exercised in Task 7's real run. Prefer Rust-rendered QR (qrcode crate → SVG data URI) so the only JS is DOM wiring.

- [ ] **Step 2: Implement**, `node --check` each JS file, `cargo build`.

- [ ] **Step 3: Commit** `feat(desktop): pairing + connection UI, Rust-rendered QR, PicoCSS`

---

### Task 7: Real end-to-end verification (desktop ⇄ phone/emulator)

**Files:** none (verification; fixes committed if found).

- [ ] **Step 1:** Start the Android app on an emulator (or use a real Pixel 9 on the same LAN), enable Remote Access in its Settings so the LAN TLS server + mDNS advertising are live. For an emulator, bridge the LAN port to the host (`adb forward`) or run the emulator with a bridged network; note the approach (mDNS may not cross the emulator NAT — if so, verify discovery against a real device and fall back to manual host:port entry for the emulator).
- [ ] **Step 2:** `npm run tauri dev` on the desktop. Verify, each with evidence (screenshot you Read / logs):
  1. Discovery lists the phone (or manual entry if mDNS can't cross the emulator boundary).
  2. Pair → the desktop shows a QR; scan it in the phone's "Pair a browser" → phone shows a confirm code; the desktop shows the SAME code; approve.
  3. The desktop webview loads the zync web app through the proxy: Inbox renders with the phone's real tasks.
  4. Create a task on the desktop → it appears on the phone (and vice-versa) live (WebSocket push through the proxy).
  5. Revoke the desktop from the phone's device list → the desktop's next request fails (session rejected); the UI drops back to the pairing screen.
  6. Relaunch the desktop → it auto-reconnects to the known phone without re-pairing (persisted fingerprint + token), pin still enforced.
  7. MITM sanity: point the desktop at a different self-signed cert (e.g. a stub server) → connection refused by the pin.
- [ ] **Step 3:** Fix anything found (TDD in the Rust layer where possible), then commit `chore(desktop): M1d end-to-end verified against phone`.

---

## Self-Review Notes

- **Spec §8c coverage:** Rust core with mDNS discovery (T3), Ed25519 identity + keychain (T2), pinned-TLS client (T4), pairing handshake as the desktop side of the phone's §8b flow (T4), reverse proxy hosting the phone's web UI (T5), pairing/connection UI with QR (T6), real e2e (T7).
- **Why a reverse proxy, not direct webview TLS:** the system webview validates certs against OS roots and will reject the phone's self-signed cert; it can't be told to pin. Terminating pinned TLS in Rust and serving the webview plain HTTP on loopback is the standard, secure way to pin with a system webview. The phone's `connect-src 'self'` CSP is satisfied because the web app's origin is the loopback proxy.
- **Testability without a device:** the fake-phone axum harness (rcgen cert) makes the entire handshake, pinning (positive + MITM negatives), and proxy token-injection/WS-bridging unit-testable via `cargo test`; only T7 needs a real phone/emulator.
- **Security invariants pinned by tests:** captured-fingerprint == claimed-fingerprint AND locally-computed confirmCode == returned confirmCode before trusting; Enforce mode rejects any other cert; signature is over the raw challenge string bytes with standard base64 (byte-for-byte phone agreement — the `confirm_code("abc")==BA7816BF` and signature-verify tests lock this).
- **Known cross-boundary risk (flagged for T7):** mDNS may not traverse the Android emulator's NAT; the plan falls back to manual host:port entry for emulator testing and validates discovery on a real device. Not a code defect — an environment note.
- **Out of scope (future):** multi-phone from one desktop; desktop-initiated sync/offline cache; auto-update; code-signing/notarization of the desktop binaries; Windows/macOS keychain verification (developed on Linux; the `keyring` crate abstracts them but only Linux Secret Service is verified here).

# zync desktop

The zync desktop client: a [Tauri](https://tauri.app) app (Rust core + a system
WebView) that discovers a paired zync phone on the LAN, completes the Ed25519
pairing handshake over pinned TLS, and then loads the phone's web app through a
local reverse proxy.

## Why a reverse proxy

The phone terminates TLS with a self-signed certificate that only the desktop
knows how to trust (via certificate pinning). A system WebView can't be told to
pin a self-signed cert, so the Rust side runs a loopback reverse proxy
(`src-tauri/src/proxy.rs`): the WebView loads `http://127.0.0.1:<port>/`, and
the proxy forwards every request — including the `/api/events` WebSocket — to
`https://<phone>:<tls_port>` over a pinned-TLS client, injecting the desktop's
session token as `Authorization: Bearer …`. The WebView never sees the token or
the pinned connection.

## Layout

- `src-tauri/src/` — the Rust core:
  - `discovery.rs` — mDNS (`_zync._tcp`) browse + live subscribe.
  - `identity.rs` — this device's Ed25519 keypair (OS keychain, file fallback).
  - `pairing.rs` — the pairing handshake (QR payload, MITM cross-checks).
  - `pinning.rs` — the pinned-TLS cert verifier.
  - `paired_store.rs` — persistence of paired phones (keychain / file).
  - `proxy.rs` — the loopback reverse proxy (HTTP + WS).
  - `commands.rs` — thin `#[tauri::command]` wrappers over testable `*_logic`.
- `ui/` — the vanilla-JS pairing/connection UI (PicoCSS, no bundler). Once
  paired, the WebView navigates away to the proxied phone app.
- `src-tauri/tests/` — a `fake_phone` axum+rcgen harness makes the whole
  handshake, pinning, and proxy testable with no real device.

## Develop

```sh
cd desktop
npm install
npm run tauri dev      # run the app
```

Rust tests (handshake / pinning / proxy against the fake phone):

```sh
cd desktop/src-tauri
cargo test
```

The UI has no JS test runner; the gate is `node --check ui/*.js` plus the Rust
tests above. The full windowed pairing flow is exercised separately against a
real phone (real-device end-to-end).

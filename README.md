# zync

A self-hosted GTD (Getting Things Done) system that runs **on your phone** and
is reachable from your other devices over your LAN — no cloud, no account. The
Android app is both the datastore and the server; browsers and the desktop
client connect to it directly over pinned TLS after a QR-based pairing.

## Architecture

```
                    ┌─────────────────────────── Android phone ───────────────────────────┐
                    │  Room DB  ──  domain core  ──  Ktor server (loopback + LAN TLS)       │
                    │                                     │                                 │
                    │                          WebView (the web UI)                         │
                    └─────────────────────────────────────┼─────────────────────────────────┘
                                                           │  _zync._tcp mDNS + pinned TLS
                                                           │  Ed25519 pairing, Bearer sessions
                    ┌──────────────────────────────────────┼─────────────────────────────────┐
                    │  Desktop (Tauri): pinned-TLS reverse proxy  ──  system WebView          │
                    └─────────────────────────────────────────────────────────────────────────┘
```

- **Domain core + Room** — the GTD model (inbox, projects, tasks, contexts,
  tree) with a Room-backed store. (`app/`)
- **Ktor server** — serves the web UI and a JSON `/api/**` + `/api/events`
  WebSocket, guarded by a session-token auth filter. Loopback for the on-phone
  WebView; LAN over self-signed TLS for remote devices. (`app/`)
- **Web UI** — a framework-less vanilla-JS app (hash router, no bundler) served
  by the phone and rendered both in the phone's WebView and, remotely, in the
  desktop client. (`app/src/main/assets/` + `webtest/` Playwright suite)
- **LAN pairing** — a device scans a QR the peer shows (`{devicePubkey,
  deviceName, nonce}`), then both sides cross-check the TLS leaf fingerprint and
  a SHA-256(nonce) confirm code before issuing an Ed25519-authenticated session.
- **Desktop client** — a Tauri app that discovers the phone via mDNS, pairs, and
  loads the phone's web app through a local pinned-TLS reverse proxy (a system
  WebView can't pin a self-signed cert itself). See `desktop/README.md`.

## Repo layout

| Path | What |
|------|------|
| `app/` | Android app: GTD domain core, Room DB, Ktor server, pairing, web UI assets |
| `desktop/` | Tauri desktop client (Rust core + vanilla-JS pairing UI) |
| `webtest/` | Playwright functional tests for the web UI (see `webtest/README.md`) |
| `docs/superpowers/plans/` | Design docs / milestone implementation plans (the authoritative design) |
| `.superpowers/sdd/` | Per-task briefs, reports, and the `progress.md` milestone tracker |

## Design docs

There is no single `DESIGN.md`; the design is captured per milestone as dated
plans under `docs/superpowers/plans/` (server API, web UI, LAN pairing / phone
remote access, and the Tauri desktop client). `.superpowers/sdd/progress.md`
tracks milestone/task status.

## Building & testing

- **Android / server / web UI:** `./gradlew test` (JVM + Robolectric).
- **Web UI functional tests:** `cd webtest && npx playwright test`.
- **Desktop:** `cd desktop/src-tauri && cargo test`; UI gate is
  `node --check desktop/ui/*.js`.

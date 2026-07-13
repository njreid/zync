# zync

A self-hosted GTD (Getting Things Done) system — no cloud account, no third-party
service holding your data. A small **always-on server** you run owns the data; your
**phone is an offline replica** that keeps working with no signal and syncs when it
can; **browsers** are thin clients that sign in with a passkey. All three run the
**same web UI**.

> zync is mid-rebuild. This README describes the current (target) architecture that
> has landed on `main`. Design docs live under `docs/superpowers/`
> (see [Design docs](#design-docs)); the newest source-of-truth specs are the
> [backup/sync architecture](docs/superpowers/specs/2026-07-08-backup-sync-architecture.md)
> and the [Kotlin/KMP target](docs/superpowers/specs/2026-07-08-kotlin-kmp-target-architecture.md).

## Architecture

```
        ┌──────────────── central server (you host) ─────────────────┐
        │  Ktor  ·  SQLite (op-log)  ·  S3 blobs  ·  litestream → S3   │
        │  op-log sync (push/pull/bootstrap)  ·  serves the :web UI    │
        └───────▲───────────────────────────────────────▲─────────────┘
    Ed25519 device│ sync (signed)                passkey │ WebAuthn session
        ┌─────────┴──────────┐                  ┌─────────┴──────────┐
        │  phone (replica)   │                  │  browser (thin)    │
        │  op-log + blobs    │                  │  the :web UI over  │
        │  Compose shell →   │                  │  plain HTTPS       │
        │  loopback :web     │                  └────────────────────┘
        └────────────────────┘
```

- **`core/`** — the op-log CRDT: ULID + Hybrid Logical Clocks, a sealed `Op` type
  with golden-locked serialization, LWW/tombstone projection, and Kleppmann
  tree-move. Shared, pure Kotlin (KMP).
- **`data/`** — the SQLDelight op-log store, used by both the server and the phone.
- **`web/`** — the shared UI: server-rendered `kotlinx.html` + **Datastar** hypermedia
  over SSE. Both the server and the phone's loopback serve it, so there is one UI.
- **`server/`** — the central Ktor server: op-log sync, Ed25519 **device pairing**
  (terminal-QR), an S3 content-addressed blob store, litestream durability, rate-limit
  / metrics hardening, and **WebAuthn passkey** auth gating the browser UI.
- **`app/`** — the Android app: an offline op-log replica (capture via voice / doc-scan
  / share / quick-add), a native **Compose shell** hosting a WebView that loads the
  `:web` UI over a loopback `ZyncServer`, and WorkManager background sync.

## Repo layout

| Path | What |
|------|------|
| `core/` | KMP op-log CRDT (ULID/HLC, `Op`, merge, tree-move) |
| `data/` | SQLDelight op-log store (server + phone) |
| `web/` | Shared `kotlinx.html` + Datastar `:web` UI |
| `server/` | Central Ktor server (sync, pairing, blobs, WebAuthn, durability) |
| `app/` | Android replica + Compose shell + loopback `:web` |
| `webtest/` | Playwright functional tests for the shared `:web` UI |
| `deploy/` | Server deployment (haloy + litestream sidecar) — see `deploy/bootstrap.md` |
| `docs/superpowers/` | Design specs + dated milestone plans (the authoritative design) |

## Getting started

Prerequisites: a JDK 17 toolchain (Gradle provisions one via foojay if the host has
none) and the Android SDK for the app. For a real deployment: a Linux host with Docker
+ [haloy](https://haloy.dev), an S3 bucket, and litestream on the host.

### Run the server (locally)

```sh
./gradlew :server:run           # http://localhost:8080  (mainClass MainKt)
curl localhost:8080/health      # -> ok
```

Configure via environment variables (12-factor):

| Var | Default | Purpose |
|-----|---------|---------|
| `ZYNC_DB_PATH` | `zync.db` | SQLite op-log path (use a persistent volume in prod) |
| `ZYNC_PORT` | `8080` | HTTP port |
| `ZYNC_SERVER_KEY_FILE` | `server-identity.key` | Ed25519 server identity — **persist it** (paired phones pin it) |
| `ZYNC_PUBLIC_ADDR` | `https://localhost` | address embedded in the pairing QR |
| `ZYNC_BLOB_BUCKET` | *(unset)* | S3 bucket for attachment blobs (+ `AWS_REGION` / AWS creds) |
| `ZYNC_LITESTREAM_URL` | *(unset)* | in-app litestream target; **leave unset in prod** — the host sidecar owns replication |
| `ZYNC_WEBAUTHN_RP_ID` | *(unset)* | passkey relying-party id, i.e. the registrable domain (`zync.example.com`) |
| `ZYNC_WEBAUTHN_ORIGIN` | *(unset)* | passkey origin — the exact scheme+host (`https://zync.example.com`) |
| `ZYNC_WEBAUTHN_RP_NAME` | `zync` | display name shown in the passkey prompt |
| `ZYNC_WEBAUTHN_REG_TOKEN` | *(unset)* | one secret that gates passkey enrolment |

> **Browser auth is opt-in.** If `ZYNC_WEBAUTHN_RP_ID` / `ZYNC_WEBAUTHN_ORIGIN` are
> unset, the server serves the `:web` UI **with no login gate** (fine for local dev; the
> server logs a warning). A production deploy must set the four `ZYNC_WEBAUTHN_*` vars.

### Deploy the server (prod)

litestream runs as a **host sidecar** (not in the app container): it restores the DB
before the app starts and replicates it to S3. See
[`deploy/bootstrap.md`](deploy/bootstrap.md) for the one-time host setup; then:

```sh
./gradlew :server:installDist    # build the JVM distribution the image runs
haloy validate-config && haloy deploy
```

### Pair a phone

On the server host, mint a one-time pairing code + QR:

```sh
./gradlew :server:run --args="pair"        # or: server/build/install/server/bin/server pair
```

Scan it from the phone app — it registers the phone's Ed25519 device key and pins the
server's identity. Captures then sync push/pull automatically (connectivity-gated).

### Install the phone app

Build it, or distribute via **[Obtainium](https://github.com/ImranR98/Obtainium)** (it
installs + auto-updates APKs straight from GitHub Releases):

```sh
./gradlew :app:assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

- **Release builds** (`:app:assembleRelease`) need signing config in `key.properties`
  or `ZYNC_KEYSTORE_*` env vars — run `scripts/make-release-keystore.sh` once.
- **Obtainium:** point it at this repo (`obtainium://add/https://github.com/njreid/zync`,
  or paste the repo URL). It picks the single universal APK attached to the latest
  GitHub Release. Auto-updates only install over an existing app if every release is
  signed with the **same** key and the `versionCode` increases — the release workflow
  (`.github/workflows/release.yml`) handles both.

### Sign in from a browser

Visit `https://<host>/login`, enrol a passkey once (using `ZYNC_WEBAUTHN_REG_TOKEN`),
then sign in — the `:web` UI unlocks for that session.

### Develop the web UI

```sh
./gradlew :server:webDevServer          # in-memory, seeded, no auth — on :8099
cd webtest && npm install && npx playwright test   # first run: npx playwright install chromium
```

## Building & testing

- All JVM/Android tests: `./gradlew test`
- Per module: `./gradlew :server:test` · `:app:testDebugUnitTest` · `:core:allTests`
  (`allTests` covers the KMP `:core`/`:data`/`:web` jvm + android host tests)
- Shared `:web` UI (headless Chromium): `cd webtest && npx playwright test`

## Troubleshooting

- **Browser shows the UI with no login.** `ZYNC_WEBAUTHN_RP_ID` / `_ORIGIN` are unset,
  so the passkey gate is off. Set them (and `ZYNC_WEBAUTHN_REG_TOKEN` to enrol).
- **Passkey won't register / sign in.** `ZYNC_WEBAUTHN_ORIGIN` must exactly match the
  browser's scheme+host and `ZYNC_WEBAUTHN_RP_ID` must be the registrable domain — a
  mismatch fails silently client-side. The server logs the verification error (WARNING).
- **Obtainium update won't install** (`signatures do not match`). Every release must be
  signed with the same key; a debug-signed and a release-signed APK can't update each
  other. Reinstall from a consistent source.
- **Phone WebView shows 401 on launch.** The loopback exchanges the `?token=` for a
  cookie on the document; a fresh token must beat a stale cookie from a prior process —
  if you see this after a reinstall, clear the app's WebView data.
- **Local Android emulator won't boot.** The dev sandbox has no `/dev/kvm`; see
  `AGENTS.md` → "Emulator & Environment Notes". Use a real device.

## Design docs

The design is captured as specs + dated milestone plans under `docs/superpowers/`
(there is no single `DESIGN.md`). The rebuild roadmap is
[`plans/2026-07-08-rebuild-roadmap.md`](docs/superpowers/plans/2026-07-08-rebuild-roadmap.md);
end-to-end acceptance checks are in
[`plans/2026-07-13-acceptance-runbook.md`](docs/superpowers/plans/2026-07-13-acceptance-runbook.md).

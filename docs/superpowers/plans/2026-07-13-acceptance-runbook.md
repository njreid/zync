# zync — acceptance runbook (M7 Task 8)

End-to-end things to try on **real hardware**, exercising the rebuilt architecture:
a central always-on server (Ktor + SQLite + S3, op-log sync), the **phone as an
offline op-log replica** running the shared `:web` UI over its loopback, and
**browsers as thin WebAuthn clients** of the server-hosted `:web`.

This can't be run in the dev sandbox (no bootable emulator — no `/dev/kvm`; see
AGENTS.md "Emulator & Environment Notes"). The server-side verification each item
drives is already unit/emulator-tested; this list is the human, device-level pass.

Legend: **Setup** = one-time prep · each `- [ ]` is a check with its **Expect:** line.

---

## 0. Prerequisites & bring-up

**Server** (a Linux host with Docker + haloy, or run the JAR directly):

- Env the server reads (`grep System.getenv server/src/main/kotlin`):
  `ZYNC_DB_PATH`, `ZYNC_PORT` (default 8080), `ZYNC_SERVER_KEY_FILE`,
  `ZYNC_BLOB_BUCKET` (+ AWS creds), `ZYNC_LITESTREAM_URL`, `ZYNC_PUBLIC_ADDR`;
  WebAuthn: `ZYNC_WEBAUTHN_RP_ID`, `ZYNC_WEBAUTHN_ORIGIN`, `ZYNC_WEBAUTHN_RP_NAME`,
  `ZYNC_WEBAUTHN_REG_TOKEN` (dev-only escape hatch: `ZYNC_ALLOW_UNAUTHENTICATED_WEB`);
  ops: `ZYNC_OPLOG_RETAIN_OPS` / `ZYNC_OPLOG_RETAIN_DAYS`,
  `ZYNC_COMPACT_INTERVAL_MINUTES`, `ZYNC_QUOTA_OPLOG_MB`;
  operators (M8): `ANTHROPIC_API_KEY`, `ZYNC_LLM_MODEL`.
- Deploy: `haloy deploy` (see `haloy.yaml` + `deploy/`), or locally
  `./gradlew :server:run` (mainClass `dev.njr.zync.server.MainKt`).

> **Upgrading an existing deployment across schema v2 (2026-07-14):** the DB
> migrates automatically on boot (`PRAGMA user_version` 1→2), but request
> signatures now cover the query string + body hash and pushes require the
> pairing→replica binding — **phones paired before this release must re-pair.**

- [ ] **Server starts and is healthy.** `curl https://<host>/health` → `ok`.
- [ ] **Fails closed without browser auth.** Start with content enabled but the
  `ZYNC_WEBAUTHN_*` vars unset (and no `ZYNC_ALLOW_UNAUTHENTICATED_WEB=true`).
  **Expect:** startup REFUSES with "refusing to serve :web without browser auth" —
  a typo'd WebAuthn env can never silently ship an open UI.
- [ ] **Old DB migrates.** Boot against a pre-2026-07-14 database file. **Expect:**
  clean start; `sqlite3 <db> 'PRAGMA user_version'` → `2`; existing content intact.
- [ ] **HTTPS/origin match.** `ZYNC_WEBAUTHN_ORIGIN` exactly matches the scheme+host
  the browser will use (e.g. `https://zync.example.com`), and `ZYNC_WEBAUTHN_RP_ID`
  is the registrable domain (`zync.example.com`). **Expect:** mismatches make WebAuthn
  silently fail later — verify now.
- [ ] **Server identity persists.** Restart the server; the Ed25519 server key
  (`ZYNC_SERVER_KEY_FILE`) is reused. **Expect:** the pinned key is unchanged, so
  phones paired *on this release* keep working (pre-v2 pairings still need the
  one-time re-pair above).

**Phone**: install the APK — `./gradlew :app:assembleDebug` →
`app/build/outputs/apk/debug/app-debug.apk`, `adb install` (or `assembleRelease`
with signing env for a signed build).

- [ ] **Upgrade over v0.2 starts clean.** Install the new APK OVER a v0.2 install
  (same appId/signing key — the Obtainium path). **Expect:** the app launches into a
  fresh, working inbox — the leftover v0.2 Room `zync.db` is purged on first open
  (v0.3.1 fix; no v0.2 data is imported by design). A white screen with
  `{"error":"internal error"}` means a pre-oplog file survived — regression.

**Browser**: any WebAuthn/passkey-capable browser (platform authenticator or a
security key).

---

## 1. Device pairing (phone → server)

**Setup:** in an authenticated browser session open `/settings/pairing` — it mints a
one-time code and renders the QR + a tappable `zync://pair` link. (CLI fallback: on
the server host, `server pair` prints the same QR in the terminal.)

- [ ] **Pairing page is session-gated.** `/settings/pairing` without a session → 401;
  with a passkey session it renders the QR. **Expect:** only an authenticated browser
  can mint pairing codes.
- [ ] **Phone pairs via the QR / deep link.** Scan the QR with the phone camera (or
  open the page on the phone and tap the link) — the `zync://pair` deep link opens the
  app, which toasts the outcome. **Expect:** phone POSTs `/pair` with its Ed25519
  device key, the one-time code, and its op-authoring **replicaId**; the server
  registers the device, binds the replica id, and returns its identity; the phone pins
  the server's key fingerprint and kicks a first sync.
- [ ] **The device shows in the server allow-list with its binding.** (server
  `device` table / debug UI — `replica_id` is non-NULL.)
- [ ] **Pushes are bound to the pairing.** After pairing, captures sync normally.
  **Expect:** a device row with a NULL `replica_id` (pre-v2 pairing) gets **403
  "re-pair"** on push, and the binding is immutable — the same device key
  re-pairing with a *different* replica id is rejected at `/pair`.
- [ ] **One-time code is single-use + windowed.** Re-scanning the same QR after use,
  or after the window, fails. **Expect:** `/pair` rejects a spent/expired code.
- [ ] **Unpaired phone can't sync.** Before pairing, a sync attempt is rejected.
  **Expect:** `syncOnce()` is a no-op / server returns 401 for an unknown device.
- [ ] **Revocation is immediate and final.** Revoke the device on the server; the next
  phone sync is rejected. **Expect:** signed requests from a revoked device → 401/403,
  and `SyncWorker` records a permanent failure (no infinite retry loop in the
  WorkManager inspector).

---

## 2. Phone capture → op log (offline-first)

Each capture must create an **op-log inbox node** (+ a blob for attachments) via
`ReplicaCapture`, locally, with no network.

- [ ] **Quick-add** a task in the phone `:web` inbox. **Expect:** appears immediately.
- [ ] **Voice note** — double **Volume-Up** (accessibility gesture) records; on stop
  it lands in Inbox with an audio attachment. **Expect:** inbox node + blob.
- [ ] **Doc scan** — double **Volume-Down** opens the scanner; a scan lands as a PDF
  attachment. **Expect:** inbox node + PDF blob.
- [ ] **Share-in** — from another app, Share → zync an audio file and a PDF (and a
  multi-share). **Expect:** each becomes an Inbox item (`ShareReceiverActivity`).
- [ ] **Quick-capture widget** — the home-screen widget captures to Inbox.
- [ ] **Fully offline.** Enable airplane mode, capture several items. **Expect:** all
  succeed locally and queue as unsynced ops (nothing lost, no crash).

---

## 3. Sync & convergence (the core of the rebuild)

- [ ] **Push:** with the phone paired and online, `SyncWorker` (or a manual trigger)
  pushes queued ops. **Expect:** the items captured offline appear on the server
  (check via a browser session, §4). Attachment blobs upload **before** their ops —
  the server never holds metadata whose bytes only exist on the phone.
- [ ] **Large backlog pages.** Capture a big offline backlog (dozens of items incl.
  attachments), then sync. **Expect:** the push completes across multiple bounded
  batches (server access log shows several `/sync/push` requests); everything lands.
- [ ] **Pull:** create a task in the browser `:web`; the phone pulls it. **Expect:**
  it appears on the phone after a sync cycle.
- [ ] **Bidirectional convergence:** create distinct items on phone (offline) and
  browser simultaneously, then let both sync. **Expect:** both clients end with the
  same full set (no loss, no dup).
- [ ] **LWW conflict:** offline, rename node X on the phone; also rename X in the
  browser; sync both. **Expect:** deterministic last-writer-wins by HLC — both
  converge to the same title (no error, no split).
- [ ] **Tree move / no-cycle:** move node A under B on the phone and B under A in the
  browser (offline), then sync. **Expect:** convergence with the cycle broken per the
  Kleppmann tree-move rule — no orphan, no infinite nesting.
- [ ] **Tombstone:** trash a node on one client. **Expect:** it's removed on the other
  after sync and does not resurrect.
- [ ] **Attachment across devices:** a voice note captured on the phone → after sync,
  its blob is downloadable/openable from the browser `:web` (S3 round-trip), and vice
  versa. **Expect:** blob content matches.
- [ ] **All mutations replicate:** complete / reopen / defer / move / comment /
  decompose done on one client show on the other after sync.

---

## 4. Browser WebAuthn auth (thin client)

- [ ] **Enrolment (first run).** Visit `https://<host>/login` → "Enrol a new passkey",
  enter `ZYNC_WEBAUTHN_REG_TOKEN`, complete the platform prompt. **Expect:** "registered".
- [ ] **Wrong / missing reg token is rejected.** **Expect:** 403; no credential stored.
- [ ] **Sign in.** "Sign in with a passkey" → platform prompt → redirected to `/`.
  **Expect:** the `:web` UI loads; a `zync_session` cookie is set.
- [ ] **Gate holds.** In a fresh private window (no session): visiting `/` redirects to
  `/login`; hitting `/tree` or a mutation `POST` returns **401**. **Expect:** no `:web`
  data or mutations without a session.
- [ ] **Session persists** across reloads; **logout** (`POST /auth/logout`) drops it and
  `/` bounces to `/login` again.
- [ ] **Second passkey / second device** can be enrolled and used.
- [ ] **(Advanced) replay/sign-counter:** a captured-and-replayed assertion is rejected;
  the stored sign counter only increases.

---

## 5. Phone native shell + loopback

- [ ] **Launch.** The app opens into the Compose shell hosting the WebView, which loads
  `http://127.0.0.1:<port>/?token=…` over the loopback. **Expect:** the `:web` inbox
  renders; Datastar is reactive (clicking complete/defer works).
- [ ] **Loopback auth.** The `?token=` is exchanged for the `zync_token` cookie on the
  document; assets/data load. **Expect:** no 401 on cold start (a fresh token beats any
  stale cookie).
- [ ] **WebView survives recomposition and rotation.** Interact heavily, then rotate the
  device. **Expect:** the WebView (and its live loopback connection) persists with no
  reload/flash — the activity declares `configChanges` so it isn't recreated.
- [ ] **Edge-to-edge.** The `:web` content is not drawn under the status/navigation bars
  or the display cutout, and the IME doesn't cover the quick-add field. **Expect:** the
  shell applies `WindowInsets.safeDrawing` (targetSdk 36 enforces edge-to-edge).
- [ ] **Predictive back.** The back-gesture preview animation shows (Android 14+); a full
  swipe walks WebView history / exits (`enableOnBackInvokedCallback`).
- [ ] **Back navigation** walks WebView history, then exits.
- [ ] **Capture bridge.** Trigger voice capture from the `:web` settings surface
  (`ZyncCapture` bridge) — records, saves, and the UI updates via a dispatched event.
- [ ] **Permission flow.** First voice capture requests `RECORD_AUDIO`; granting proceeds,
  denying fails gracefully.

---

## 6. Durability & ops

- [ ] **litestream is replicating.** After writes, the S3 replica (`ZYNC_LITESTREAM_URL`)
  advances.
- [ ] **Restore-on-fresh.** Stand up a *fresh* server instance pointed at the same
  `ZYNC_LITESTREAM_URL` + empty local DB. **Expect:** startup restores the DB from S3
  and all content is present (the `StartupSequence` restore-if-fresh path).
- [ ] **haloy zero-downtime deploy.** Redeploy a new build with `haloy deploy` while a
  client is connected. **Expect:** the swap completes without a visible outage; in-flight
  requests are not dropped.
- [ ] **Hardening.** Hammer an endpoint past the rate limit → throttled (429/backoff).
  `GET /metrics` reports counters (traffic, rejections, auth failures) and storage
  gauges (op-log ops/bytes, compaction floor).
- [ ] **Blob store.** Attachments are content-addressed in S3 (`ZYNC_BLOB_BUCKET`);
  re-uploading identical bytes doesn't duplicate.
- [ ] **Restore drill (scheduled).** Run `systemctl start restore-drill` once (see
  `deploy/restore-drill.sh`). **Expect:** "restore drill OK: ops=… head=… lag=…" —
  the S3 replica is restorable and fresh, without touching the live DB.
- [ ] **Compaction preserves state.** Set aggressive retention (e.g.
  `ZYNC_OPLOG_RETAIN_OPS=100`, `ZYNC_OPLOG_RETAIN_DAYS=0`,
  `ZYNC_COMPACT_INTERVAL_MINUTES=1`), write past it. **Expect:** `/metrics` shows
  `compactionRuns`/`opsCompacted` advancing and the floor gauge rising; all content
  still renders correctly in the `:web` UI; an in-sync phone keeps syncing normally.
- [ ] **Quota guard.** Set a tiny `ZYNC_QUOTA_OPLOG_MB` (e.g. 1) and push past it.
  **Expect:** `/sync/push` → **507**, `/metrics` `quotaRejected` increments; after
  compaction frees space (or the quota is raised), pushes resume.

---

## 7. Regression sweep (the shared `:web`, both hosts)

Run the same UI checks in **both** the browser (server-hosted) and the phone WebView
(loopback-hosted) — they render the same `:web` module:

- [ ] Inbox list, tree navigation, node detail.
- [ ] complete / reopen / trash / defer / move / add-subtask / comment / decompose.
- [ ] Reading view + comments.
- [ ] Live updates: a change made elsewhere streams in via SSE (`/updates`) without a
  manual refresh.
- [ ] CSP: the phone loopback CSP includes `script-src 'unsafe-eval'` (Datastar needs it);
  the browser server serves `:web` without Datastar breakage. **Expect:** no CSP console
  errors, reactivity works on both.

---

## 8. Operators (M8) & agent proposals

- [ ] **Disabled gracefully.** Start the server WITHOUT `ANTHROPIC_API_KEY`.
  **Expect:** log line "operators disabled: ANTHROPIC_API_KEY not set"; everything
  else works normally.
- [ ] **Auto-clarify fires.** With `ANTHROPIC_API_KEY` set, capture an inbox item.
  **Expect:** the reference operator reacts to the ingest, emits provenance-tagged
  ops (`actor=Operator("…")`, visible in the op log / debug UI), and never touches a
  human-owned field. Re-syncing the same op does not re-fire it (idempotent by
  input version — check the `operator_run` table).
- [ ] **Proposals review panel.** A `proposed`-flagged node (agent-authored; until
  the M9 runtime lands, seed one like the dev server's stub) appears under
  "Proposals" in the `:web` inbox — on BOTH hosts — and never as a normal task/
  comment. **Accept** clears the flag and it becomes ordinary content; **Reject**
  removes it (reversible trash). Both actions stream via SSE without a refresh.

---

## Known limitations to confirm, not fix

- **Compacted-away cursors aren't client-handled yet.** A phone whose pull cursor is
  below the compaction floor gets **410 Gone** and its sync worker fails permanently —
  the re-bootstrap flow (client-side `/sync/bootstrap` consumption) is not implemented.
  With default 30-day/10k retention this cannot hit an actively syncing phone; confirm
  the 410 behavior, don't expect recovery.
- The M9 agent *runtime* is design-only (`2026-07-14-m9-agents.md`); the proposals
  panel is exercised via the stub producer.
- The `/login` page ceremony needs a real browser/virtual authenticator (unit tests use
  webauthn4j's emulator).
- Emulator-based CI for the phone is not set up (no KVM locally); phone checks here are
  manual/real-device.

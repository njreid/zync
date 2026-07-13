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
  `ZYNC_BLOB_BUCKET` (+ AWS creds), `ZYNC_LITESTREAM_URL`, `ZYNC_PUBLIC_ADDR`,
  and WebAuthn: `ZYNC_WEBAUTHN_RP_ID`, `ZYNC_WEBAUTHN_ORIGIN`,
  `ZYNC_WEBAUTHN_RP_NAME`, `ZYNC_WEBAUTHN_REG_TOKEN`.
- Deploy: `haloy deploy` (see `haloy.yaml` + `deploy/`), or locally
  `./gradlew :server:run` (mainClass `dev.njr.zync.server.MainKt`).

- [ ] **Server starts and is healthy.** `curl https://<host>/health` → `ok`.
- [ ] **HTTPS/origin match.** `ZYNC_WEBAUTHN_ORIGIN` exactly matches the scheme+host
  the browser will use (e.g. `https://zync.example.com`), and `ZYNC_WEBAUTHN_RP_ID`
  is the registrable domain (`zync.example.com`). **Expect:** mismatches make WebAuthn
  silently fail later — verify now.
- [ ] **Server identity persists.** Restart the server; the Ed25519 server key
  (`ZYNC_SERVER_KEY_FILE`) is reused. **Expect:** already-paired phones keep working
  (pinned key unchanged).

**Phone**: install the APK — `./gradlew :app:assembleDebug` →
`app/build/outputs/apk/debug/app-debug.apk`, `adb install` (or `assembleRelease`
with signing env for a signed build).

**Browser**: any WebAuthn/passkey-capable browser (platform authenticator or a
security key).

---

## 1. Device pairing (phone → server)

**Setup:** on the server host run `./gradlew :server:run --args=pair`
(or the container's `server pair`) — it mints a one-time code and prints a QR.

- [ ] **Phone pairs by scanning the QR.** In the phone app, start pairing and scan.
  **Expect:** phone POSTs `/pair` with its Ed25519 device key + the one-time code;
  the server registers the device and returns its identity; the phone pins the
  server's key fingerprint.
- [ ] **The device shows in the server allow-list.** (server `device` table / debug UI).
- [ ] **One-time code is single-use + windowed.** Re-scanning the same QR after use,
  or after the window, fails. **Expect:** `/pair` rejects a spent/expired code.
- [ ] **Unpaired phone can't sync.** Before pairing, a sync attempt is rejected.
  **Expect:** `syncOnce()` is a no-op / server returns 401 for an unknown device.
- [ ] **Revocation is immediate.** Revoke the device on the server; the next phone
  sync is rejected. **Expect:** signed requests from a revoked device → 401/403.

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
  (check via a browser session, §4).
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
  `GET /metrics` reports counters.
- [ ] **Blob store.** Attachments are content-addressed in S3 (`ZYNC_BLOB_BUCKET`);
  re-uploading identical bytes doesn't duplicate.

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

## Known limitations to confirm, not fix

- Rotation recreates the phone activity + WebView (no `configChanges`); acceptable for now.
- The `/login` page ceremony needs a real browser/virtual authenticator (unit tests use
  webauthn4j's emulator).
- Emulator-based CI for the phone is not set up (no KVM locally); phone checks here are
  manual/real-device.

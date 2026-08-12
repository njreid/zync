# FCM push-to-sync — design (2026-08-11)

> Status: DESIGN (approved, pending implementation plan). Adds a Firebase Cloud Messaging (FCM)
> push channel so the server can wake the Android app to sync near-instantly, instead of the app
> only learning about remote changes (web UI edits, MCP proposals, another device) on its
> existing 15-minute periodic poll or next foreground. This is a **personal/beta-test app**, so
> depending on Google Play Services/FCM is an accepted trade-off — no de-Googled-build constraint.

## 0. Problem and goal

Today (see `app/src/main/kotlin/dev/njr/zync/sync/SyncWorker.kt`, `ZyncApp.kt`):

- The app's own edits sync out promptly — every local mutation fires a one-shot WorkManager job.
- Remote edits only reach the phone via a 15-minute connectivity-gated periodic `WorkManager`
  job, or immediately on next app foreground/open. There is no server-initiated wakeup.

Goal: cut that worst-case latency to near-instant, **without materially costing battery** — i.e.
without holding an app-managed persistent connection (WebSocket/SSE) alive through Doze, which is
exactly the failure mode polling was already avoiding. FCM is the standard low-cost mechanism
because it rides the wakeup channel Google Play Services already maintains system-wide; the app
doesn't manage its own connection.

**This is strictly a latency optimization, not a new correctness mechanism.** The periodic poll
stays exactly as it is today, unchanged, as the fallback safety net — FCM delivery isn't
guaranteed (Doze deferral, Play Services absent/broken, token expiry), so nothing about
correctness may depend on a push arriving.

## 1. Architecture

```
Server: op ingested → SyncService.onIngest → ChangeNotifier.notifyChanged()
                                                        │
                                          FcmPusher subscribes, .debounce(500ms)
                                                        │
                                    reads device tokens from SqlDeviceRegistry
                                                        │
                                    FcmSender.send() per token (best-effort)


App:    FCM data message → ZyncFirebaseMessagingService.onMessageReceived
                                                        │
                                    SyncScheduler.requestSync(context) — the
                                    SAME one-shot WorkManager path local
                                    mutations already use


Token registration (fires on install and on refresh):
App:    FirebaseMessagingService.onNewToken(token)
                                                        │
                                    POST /sync/fcm-token (signed-request device
                                    auth, same mechanism as /sync/push)
                                                        │
                                    SqlDeviceRegistry stores it on that device row
```

**Why subscribe to `ChangeNotifier` rather than hook `SyncService.onIngest` directly:**
`ChangeNotifier.changes` (`web/src/commonMain/kotlin/dev/njr/zync/web/sse/Datastar.kt`) is
already a `MutableSharedFlow<Unit>` fired on every ingest — it's what drives the browser's SSE
feed today. `FcmPusher` subscribes to that same flow with `.debounce(500.milliseconds)`, so a
burst of mutations (e.g. accepting five MCP proposals in a row) collapses into **one** push, not
five — the phone wakes once, pulls once, instead of waking repeatedly for changes it hasn't even
finished pulling yet. This costs no change to `SyncService` or the ingest path itself.

**Why this stays optional/degrading:** `FcmPusher` is constructed in `Main.kt` only when
`ZYNC_FCM_SERVICE_ACCOUNT_FILE` is set, exactly like `AnthropicLlmClient.fromEnv()` and
`OllamaEmbeddingClient.fromEnv()` degrade to `null`/disabled today. No env var ⇒ zero behavior
change, `FcmPusher` never constructed, never subscribes.

## 2. Components

### Server

- **Schema migration**: add `fcm_token TEXT NULL` to the `device` table (SQLDelight `.sq`
  migration + regenerated queries — `setFcmToken(deviceId, token)`,
  `fcmTokensForNonRevokedDevices(): List<String>` or equivalent).
- **`POST /sync/fcm-token`** in `SyncRoutes.kt`: body `{ "token": "<fcm-token>" }`, gated by the
  existing `call.requireAuth(auth.authenticator)` signed-request device auth — the same check
  `/sync/push` uses. No new auth mechanism.
- **`FcmSender`** (interface, new file `server/.../push/FcmSender.kt`):
  `fun send(token: String, data: Map<String, String>)`. One production implementation wrapping
  the Firebase Admin SDK; tests inject a fake. Mirrors the existing `LlmClient`/`EmbeddingClient`
  port pattern used for other optional integrations.
- **`FcmPusher`** (new file `server/.../push/FcmPusher.kt`): `fun start(scope: CoroutineScope,
  changes: ChangeNotifier, registry: SqlDeviceRegistry, sender: FcmSender)` — subscribes to
  `changes.changes.debounce(500.milliseconds)`, on each tick reads all non-revoked device tokens
  and calls `sender.send(token, mapOf("type" to "sync"))` for each, catching and logging
  (`warn`) any per-token failure without propagating.
- **Wiring** (`Main.kt`): constructed alongside the other optional integrations, right next to
  `embedder`/`llm` construction; `ZYNC_FCM_SERVICE_ACCOUNT_FILE` (file path, same shape as
  `ZYNC_SERVER_KEY_FILE`) — absent ⇒ `FcmPusher` not built.
- **Gradle**: `firebase-admin` dependency added to `server/build.gradle.kts`.
- **Deploy** (`deploy/haloy.yaml`): new env var entry + credential file mount, following the
  existing pattern for `ZYNC_SERVER_KEY_FILE`.

### App

- **`ZyncFirebaseMessagingService`** (new file, extends `FirebaseMessagingService`):
  - `onMessageReceived(message)`: data message with `type=sync` → `SyncScheduler.requestSync(context)`.
    No notification is shown (silent data message) — this is a background wakeup, not a
    user-facing alert.
  - `onNewToken(token)`: POST it to `/sync/fcm-token` using the existing signed-request client
    machinery already used for `/sync/push`.
- **`ZyncApp.kt`**: one new call on app start to proactively fetch the current FCM token and send
  it (covers the case where `onNewToken` already fired before the device was paired, or the
  server-side token row was lost/cleared).
- **Gradle/manifest**: `google-services` Gradle plugin + `firebase-messaging` dependency in
  `app/build.gradle.kts`; `google-services.json` committed to the repo (this is a public
  app-identifier file tied to the Firebase project, not a secret — unlike the server-side
  service-account credential, which must NEVER be committed); new `<service>` entry in
  `AndroidManifest.xml` alongside the app's existing service declarations.

### Infra prerequisite

A Firebase project must exist before any of this can be wired up for real — it's the source of
both `google-services.json` (app) and the service-account JSON (server, kept out of git, provided
via `ZYNC_FCM_SERVICE_ACCOUNT_FILE` at deploy time). Creating that project is a manual,
one-time, out-of-repo step.

## 3. Error handling and degrade behavior

| Condition | Behavior |
|---|---|
| `ZYNC_FCM_SERVICE_ACCOUNT_FILE` unset | `FcmPusher` never constructed. Zero behavior change from today. |
| A single token's send fails (expired, invalid, Firebase transient error) | Caught per-token, logged at `warn`, loop continues to the next token. Never throws into the ingest path — `FcmPusher` only observes `ChangeNotifier`, it cannot block or fail a mutation. |
| Token registration POST fails (network, server down) | App already tolerates this for its normal sync calls; retried on next app launch/foreground via the existing `ZyncApp.kt` call site. No new retry logic needed. |
| A token goes stale (app uninstalled/reinstalled, device revoked) and isn't pruned | Explicitly deferred (see §4) — worst case is one wasted Admin SDK call per debounced ingest until the device re-pairs with a fresh token. Logged, harmless. |

## 4. Explicitly out of scope for this pass

- **Multi-device token pruning**: `FcmSender` isn't wired to detect and clear
  `NotRegistered`/`InvalidArgument` responses from FCM by clearing the stored token. Fine for a
  single-phone setup; revisit if/when a second device makes stale tokens costly enough to matter.
- **Richer payloads**: the push carries no cursor/seq information — it's a pure wake signal. The
  app's existing pull (`GET /sync/pull?since=...`) is already cheap and idempotent, so there's no
  efficiency to gain from a smarter payload.
- **Reducing/removing the periodic poll**: explicitly rejected (§0) — push is additive, the poll
  stays as the correctness fallback.

## 5. Testing

- **`FcmPusher`**: unit tests against a fake `FcmSender` — assert a burst of N
  `ChangeNotifier.notifyChanged()` calls within the debounce window produces exactly one `send()`
  call per registered token, and that a fake `send()` throwing doesn't propagate or stop
  subsequent sends.
- **`POST /sync/fcm-token`**: a `SyncRoutesTest`-style Ktor `testApplication` test — authenticated
  device can set/update its token; unauthenticated request 401s. Same shape as existing route
  tests in that file.
- **App side**: not realistically unit-testable without a real device and a real Firebase
  project. Verified manually once the Firebase project exists: install, confirm the token
  registers server-side, trigger a server-side change (e.g. via `/api/ops` or the MCP door),
  confirm the phone syncs without the user opening the app.

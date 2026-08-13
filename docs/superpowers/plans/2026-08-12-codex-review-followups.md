# Outstanding items from the 2026-07-13 Codex review (2026-08-12 re-check)

`CODEX_NOTES.md` (a 23-item review dated 2026-07-13) was checked item-by-item against the
current `main` before deleting it, so nothing real gets lost. 7 of 23 items are now resolved
outright, a few are partial, and the rest are obsolete or superseded. This doc keeps only what's
still real and worth doing, with current evidence — not the full original review.

## Still outstanding (confirmed against current code)

1. **Bootstrap consumption never wired.** `SyncClient` still only pushes and tails
   `/sync/pull` from cursor zero — no reference to `/sync/bootstrap` anywhere in
   `app/src/main/kotlin/dev/njr/zync/replica/SyncClient.kt`. A fresh phone install replays the
   entire retained op log instead of seeding from the server's compacted snapshot. (Original #3.)
2. **Browser session store is unpersisted and unbounded.** `SessionStore` (`server/.../auth/SessionStore.kt:17`)
   is still an in-memory map; a restart logs every browser out, and expired sessions are only
   swept on next validation of that exact token — no periodic purge, no cap. (Original #7.)
3. **Server HLC isn't persisted across restarts.** `ServerContent.kt:28` still constructs a fresh
   `HlcGenerator("server", clock)` on every process start; the phone's HLC persists, the server's
   doesn't. A clock rollback can make new browser edits lose to older server writes. (Original #8.)
4. **Repeated full-projection scans on every read.** `ContentReadModel`'s `children()`/`comments()`/
   `node()` etc. each independently call `store.project()`/`snapshots()` — no request-scoped
   snapshot or index (`byId`, `childrenByParent`) is built once and reused. Recursive tree
   rendering compounds this per page render. (Original #12.)
5. **Android voice-recorder lifecycle duplicated.** `VoiceCaptureActivity` and
   `CaptureSettingsBridge` still independently own `MediaRecorder` start/stop/release and
   error handling, with diverging failure-handling coverage. No extracted `VoiceRecorder`.
   (Original #16.)
6. **MIME/type/extension/title policy duplicated and inconsistent.** `ShareImport` and
   `CaptureRepository` still encode overlapping, inconsistent mappings (no `CaptureMediaPolicy`
   exists). (Original #17.)
7. **`ZyncApp` service-locator keeps growing.** Still constructs db/state/clocks/content/capture/
   pairing/sync/server/etc. in one class (~37 top-level members) — and it grew further with the
   FCM push-to-sync work (`sendFcmToken`, `sendCurrentFcmTokenIfPaired`). Worth extracting an
   `AppGraph` + focused coordinators before it grows more. (Original #18.)
8. **Reflection-based op-type naming duplicated.** `op::class.simpleName` is still used
   independently in both `OpWriter.kt:85` and `SyncService.kt:155` — no shared serial/type
   identifier. (Original #19.)
~~9. Double `notifyChanged()` on browser mutations.~~ **CORRECTED 2026-08-12, not a bug.** This
   doc originally claimed `WebRoutes.kt`'s `applied {}` calling `changes?.notifyChanged()`
   after `commands.mutate()` was a double-fire, because `commands.mutate()` "already routes
   through `onIngest → notifyChanged()`." That's false: `SyncService.ingestLocal(op)` — the path
   `ContentCommands`/`OpEmitter` actually takes for a web mutation — has an explicit doc comment
   stating it **deliberately does NOT fire `onIngest`**; that callback is reserved for the
   replica-push boundary (`push()`), a different path entirely. `WebRoutes.kt`'s explicit
   `changes?.notifyChanged()` is the *only* trigger for a browser mutation's SSE notification, by
   design — removing it (as this doc originally recommended) would have broken live UI updates
   for every browser mutation. Caught during implementation by an implementer who verified
   empirically (a diagnostic test counting emissions) instead of trusting this doc's diagnosis.
   (Original #20 — the original 2026-07-13 Codex review's diagnosis was itself wrong here.)

## Partially resolved — may want a follow-up pass

- **Atomic multi-op commands** (original #14): `OpWriter` wraps writes in `db.transaction {}`
  and the bot-op path batches via a recording emitter + single `ingestLocalBatch` call, but
  there's still no generic `emitBatch` on the `OpEmitter` interface itself for other callers.
- **Streaming blob storage** (original #15): path-safety is fixed (`LocalBlobStore.kt:36`'s
  `KEY_FORMAT` regex), but storage is still whole-`ByteArray`-based, not streaming.
- **Test-layer gaps** (original #22): batching, input-bounds, and migration tests now exist;
  still no dedicated bootstrap-crash/retry test or a container/litestream-restore-drill CI job.
- **Stale docs** (original #23): the flagged "Kotlin held at 2.3.21" comment is gone; the
  README's bootstrap-landed claim and the roadmap's EC2/Compose/Caddy deployment description
  weren't individually re-verified in this pass.

## Resolved since 2026-07-13 (no action needed)

Blob upload wiring into production sync, bounded/paged phone pushes, database-owned sequence
allocation (`SyncService.headSeq()` now reads `MAX(seq)` live), exception-message leakage on 500s,
rate-limit keying by remote address instead of a spoofable header, fail-closed WebAuthn config,
signed-request integrity (body hash + query) and device-binding on `/sync/push`, typed
`Fields`/`Status` constants replacing stringly-typed values, and the Playwright/CI launcher fix.

## Note

The original file's "deliberately defer" section said not to build M8 operators during cleanup —
that's now stale: `OperatorRuntime`/`OperatorManifests`/`AnthropicLlmClient` etc. already exist
and are wired into `Main.kt` (`wireOperators`). M8 work has started since that review.

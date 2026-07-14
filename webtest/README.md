# zync web functional tests

> These Playwright tests drive the shared `:web` UI (kotlinx.html + Datastar) — the
> same module the server and the phone loopback serve — against the server's dev
> instance (`./gradlew :server:webDevServer`, port 8099), which the Playwright
> `webServer` config starts automatically.

Run: `cd webtest && npx playwright test` (first run also needs `npm install` and
`npx playwright install chromium`). Override the target with `ZYNC_BASE` /
`ZYNC_DEV_PORT` to point at an already-running server.

Covers: Inbox quick-add, Clarify (Done/Someday), Tree (folder/project/task creation),
Contexts (create + chip filter), Detail (edit/persist, defer/clear), and the
CSP policy the phone loopback applies (csp.spec, via `ZYNC_DEV_CSP`).

Not covered here: the Android WebView shell itself (token injection into the
WebView, back-button handling, native chrome) — that still needs an emulator smoke
test — and the loopback token-gate 401 path, which is unit-tested in `:app`.

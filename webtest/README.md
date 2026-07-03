# zync web functional tests

Run: `cd webtest && npx playwright test` (spins up a Robolectric-hosted `ZyncServer`
dev instance via Gradle automatically; first run also needs `npm install` and
`npx playwright install chromium`).

Covers: Inbox quick-add, Clarify (Done/Someday), Tree (folder/project/task creation),
Contexts (create + chip filter), Detail (edit/persist, defer/clear), and the
unauthenticated-load 401 path — driven against the real web UI in a headless browser.

Not covered here: the Android WebView shell itself (token injection into the
WebView, back-button handling, native chrome) — that still needs an emulator smoke test.

# zync web functional tests

> **🧭 Note (2026-07-08):** these Playwright tests cover the **shipped v0.2**
> vanilla-JS web UI, which the target architecture replaces with a shared Kotlin
> `web` module (kotlinx.html + Datastar). Expect this suite to be rewritten against
> the new UI. See `../docs/superpowers/specs/2026-07-08-kotlin-kmp-target-architecture.md`.

Run: `cd webtest && npx playwright test` (spins up a Robolectric-hosted `ZyncServer`
dev instance via Gradle automatically; first run also needs `npm install` and
`npx playwright install chromium`).

Covers: Inbox quick-add, Clarify (Done/Someday), Tree (folder/project/task creation),
Contexts (create + chip filter), Detail (edit/persist, defer/clear), and the
unauthenticated-load 401 path — driven against the real web UI in a headless browser.

Not covered here: the Android WebView shell itself (token injection into the
WebView, back-button handling, native chrome) — that still needs an emulator smoke test.

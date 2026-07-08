# zync — Kotlin/KMP Target Architecture

> **Status: 🟢 TARGET (2026-07-08).** Companion to
> `2026-07-08-backup-sync-architecture.md`. Locked decisions: **all-Kotlin, Kotlin
> Multiplatform, no backwards-compatibility constraint** (green-field restructure),
> maximize code shared between the Android app and the server. SQLite + S3, central
> trusted always-on server, phone = only offline replica, desktop/browser = online
> thin server clients, op-log source of truth, phone↔server lightweight merge.

## 1. Guiding idea

Both the **phone (Android/ART = JVM bytecode)** and the **server (JVM)** run Kotlin,
and **both run Ktor** (the phone already runs a loopback Ktor server for its WebView
today). So the shared surface is not just "domain logic" — it includes the **entire
web UI rendering stack**. The phone runs the shared Ktor + views **locally against
its SQLite replica** (offline-capable); the server runs the *same* code for remote
clients. That is what makes "the web UI is shared" and "capture works offline" both
true at once.

## 2. Module layout (Gradle multi-module / KMP)

- **`core`** (KMP `commonMain`) — op model, provenance, HLC clock, the merge rules
  (LWW-register per field, tombstones, tree-move), crypto interfaces, typed-output
  validation, sync-protocol types + `kotlinx.serialization`. **The correctness-
  critical, must-be-identical code — shared, single implementation.**
- **`data`** (KMP, **SQLDelight**) — schema + typed queries; platform drivers
  (Android SQLite driver / JVM JDBC driver). Shared schema and queries across phone
  and server.
- **`web`** (JVM, shared by phone + server) — Ktor routes + **kotlinx.html** views +
  **Datastar** SSE/fragment handlers + view models. One UI codebase; runs on the
  phone's loopback Ktor (offline) and the server's Ktor (remote clients).
- **`server`** (JVM application) — **operator runtime + agent orchestration + LLM
  client**, S3 client, litestream, the sync endpoints, auth (device tokens for
  native, sessions for browser), Let's Encrypt TLS. Server-only.
- **`androidApp`** (Android) — a **hybrid UI**:
  - **Native Compose** for the *thin* fast path: launch/home, quick-capture flows
    (voice/scan/text) + confirmation, navigation shell, settings/onboarding, a
    lightweight inbox-triage glance.
  - **WebView hosting the shared `web` module**, rendered by the phone's **loopback
    Ktor against the local SQLite replica** (offline-capable), for the rich content
    screens: task/project detail, reading, commenting, planning, decomposing, tree
    editing, context/tag management.
  - Plus the **capture hardware layer**: MediaRecorder (voice), ML Kit doc scanner,
    Glance quick-capture widget, accessibility volume-key gestures, share-target,
    AndroidKeyStore, runtime permissions/notifications.
  Android-only.

Shared = `core` + `data` + `web`. Platform-specific = `androidApp`'s native chunk
and `server`'s operator/agent/infra chunk.

## 3. What's shared vs platform-specific (the honest breakdown)

| Area | Where |
|---|---|
| Op model, merge, HLC, crypto algorithms, validation, sync protocol | **shared** (`core`) |
| DB schema + queries (SQLDelight) | **shared** (`data`) |
| Web UI: Ktor routes, kotlinx.html views, Datastar handlers | **shared** (`web`) |
| **Android**: launcher/lifecycle, WebView host, MediaRecorder, ML Kit scan, Glance widget, accessibility gestures, share-target, AndroidKeyStore, permissions | Android-only |
| **Server**: operator/agent runtime, LLM orchestration, S3, litestream, sync endpoints, auth, TLS | server-only |

So the not-shared part is **more than "the Android launcher"** — it's two real
chunks: (a) the **Android capture-hardware layer** (the phone's native reason to
exist), and (b) the **server operator/agent + infrastructure layer** (net-new,
central-only). The middle — including the web UI — is genuinely shared.

## 4. Web UI: shared, with small platform hooks

The tree/task/context/tag UI is fully shared. The only surface-specific bits are the
**capture entry points**: on the phone they invoke native capture (MediaRecorder /
ML Kit via a JS↔native bridge, as today); in the browser they use web APIs
(`getUserMedia` / file input / drag-drop). Everything else — rendering, navigation,
Datastar-driven live updates from the op stream — is one codebase.

## 5. What retires under "no backwards compatibility"

- **Room → SQLDelight** on the phone (shared `data` module).
- **Vanilla-JS web UI → kotlinx.html + Datastar** (shared `web` module); the JS
  footprint shrinks to Datastar's runtime + small native-capture bridges.
- **Google Drive backup → S3 sync via the daemon** (see the sync ADR).
- **M1c/M1d phone-as-LAN-server, QR/TLS pinning, mDNS, Tauri reverse proxy →**
  desktop/browser are plain authenticated HTTPS clients of the server. Most of that
  code retires; keep only an optional LAN fallback if wanted.

## 6. Open items

- **Language final-confirm:** Kotlin/JVM + Ktor + KMP `core` (this doc assumes it).
- **Depth of DB sharing:** `data` via SQLDelight across both (chosen here) vs. keep
  Room on phone — chosen: SQLDelight (no-backcompat lets us).
- **kotlinx.html vs a nicer HTML DSL / templating** — kotlinx.html is the default;
  revisit only if ergonomics disappoint.
- **Phone UI — RESOLVED (2026-07-08): hybrid.** Native **Compose** owns the *thin*
  fast path (launch, quick-capture, shell, settings, inbox glance); the shared
  **`web`** module (loopback Ktor + kotlinx.html + Datastar, against local SQLite)
  owns the rich content screens (detail, reading, commenting, planning,
  decomposing, tree/context/tag editing). Seam requirements:
  1. Reused web views render from the phone's **loopback Ktor / local SQLite**, not
     the remote server — so they work offline; mutations post to the local op log
     and sync later.
  2. **One design language across the seam** — shared tokens (colors, type incl.
     Geist/Inter, spacing) applied to *both* the Compose theme and the web CSS; the
     native→WebView handoff (node id, back-stack, theme) must feel invisible.
  3. Keep the Compose surface **deliberately thin** — if a screen lives in `web`,
     don't also build it in Compose, or the sharing win erodes.

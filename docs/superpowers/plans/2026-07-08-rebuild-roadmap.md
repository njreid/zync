# zync — Rebuild Roadmap (v0.2 → target architecture)

> **Status: 🟡 DRAFT for review (2026-07-08).** Sequences the move from shipped
> **v0.2** (phone-as-authority, Drive, vanilla-JS, LAN pairing) to the target: a
> central always-on server + op-log sync + all-Kotlin/KMP, per
> `2026-07-08-backup-sync-architecture.md`, `2026-07-08-kotlin-kmp-target-architecture.md`,
> and `2026-07-08-oplog-merge-operator-model.md`. **Supersedes the withdrawn
> `2026-07-07-m3-durable-capture-backup.md` draft** and reuses the M3+ numbers for
> the rebuild. Each milestone below gets its own detailed task-plan when it starts
> (repo convention); this doc is the roadmap.

## Sequencing principles

1. **De-risk the core first.** The op-log/merge is the only novel correctness risk;
   it's pure logic and fully unit-testable — build and prove it before anything
   depends on it.
2. **Vertical slice early.** Get one end-to-end thread working ASAP (capture on
   phone offline → sync → visible on the server) to validate the whole shape.
3. **Keep the phone usable throughout.** Capture must never regress; carry a bridge
   UI until the new one lands.
4. **Retire at the right moment**, not before — don't run two systems longer than
   necessary, don't rip out the old one before the new path works.
5. **No backwards-compat** with v0.2 data/format — the end state is clean; only the
   *transition order* matters.

## Dependency graph

```
M3 core ──▶ M4 server ──▶ M5 phone-replica ──▶ M6 web module ──▶ M7 hybrid UI + thin clients
              │                                     │
              └──────────────▶ M8 operators ◀───────┘ ──▶ M9 agents + hardening
```

---

### M3 — Shared core (`zync-core`, KMP): op log, merge, HLC
**Goal:** the provably-correct, language-shared foundation. **Pure `commonMain`
Kotlin — no UI, server, or networking.**
- Op types, HLC (gen/observe/compare), LWW registers, tombstones, tag registers,
  the Kleppmann tree-move algorithm, idempotent/commutative `apply`, serialization,
  operator-manifest types + typed-output validation.
- **Tests are the deliverable's teeth:** the six worked conflicts from the op/merge
  spec become conformance vectors; property tests for convergence
  (shuffle op delivery order → identical state).
- **Deliverable:** a tested `core` module. **Retires:** nothing yet.
- **Detailed task-plan:** `2026-07-08-m3-shared-core.md`.

### M4 — Server foundation: Ktor + SQLite + sync
**Goal:** a deployable, correct, durable server. **Depends on M3.**
- `data` (SQLDelight schema from the spec); `server` app: `seq` assignment,
  push/pull/bootstrap endpoints, device Ed25519 auth + browser sessions, Let's
  Encrypt TLS, **litestream→S3**, S3 blob store (`putIfAbsent`/get), rate limiting.
- Tested with a fake client replaying conformance vectors → server converges;
  restore-from-litestream drill.
- **Deployment (decided):** single EC2 + Docker Compose (app + litestream + Caddy) +
  GitHub Actions → GHCR → SSM, secrets in SSM Parameter Store. **Materialize** the
  compose/Caddyfile/deploy-workflow here — see `../specs/2026-07-08-deployment.md`.
- **Deliverable:** an EC2-deployable server that ingests ops, merges via `core`,
  persists to SQLite, backs up to S3. Minimal debug UI only.
- **Detailed task-plan:** `2026-07-08-m4-server-foundation.md`. **Security baseline:**
  `../specs/2026-07-08-threat-model.md` (auth/TLS/rate-limits/secrets/backup).

### M5 — Phone as a replica (first vertical slice)
**Goal:** the phone becomes an offline op-log replica that syncs. **Depends on M4.**
- Phone data layer **Room → SQLDelight** (`data`); route **all domain mutations
  through the op log** (`core`); sync client (push/pull on reconnect); local blob
  store + upload on sync; capture writes local ops offline.
- **Bridge:** keep a stopgap UI (the current web UI, re-pointed at the local
  op-log-backed state) so the phone stays usable this milestone.
- **Deliverable:** end-to-end thread — capture offline on phone → reconnect → op
  appears on the server. **Retires: Google Drive backup** (server sync + litestream
  is now the durability mechanism; M2 Task 4 direction ends here).

### M6 — Shared web module: kotlinx.html + Datastar
**Goal:** one content UI, shared by server and phone. **Depends on M5.**
- `web` module: Ktor routes + kotlinx.html views + Datastar/SSE for tree/task/
  project/context/tag, reading, commenting, planning, decomposing. Served by the
  **server** (desktop/browser) and the **phone's loopback Ktor** (offline, local
  SQLite).
- **Deliverable:** the shared content UI on both surfaces. **Retires: the vanilla-JS
  web UI** (`app/src/main/assets/web/`) and its Playwright suite (rewritten against
  the new UI).

### M7 — Phone hybrid UI + thin clients
**Goal:** the target client experience. **Depends on M6.**
- Native **Compose** for launcher/capture/shell/settings; **WebView** hosts the
  shared `web` module via loopback for content. Shared design tokens (Geist/Inter)
  across Compose + web.
- **Desktop/browser become thin online-only server clients** (plain HTTPS).
- **Deliverable:** full client on the target architecture. **Retires: the M1c/M1d
  LAN stack** — phone-as-LAN-server, QR pairing, TLS pinning, mDNS, and the Tauri
  reverse-proxy desktop (kept only as an optional LAN fallback if wanted).

### M8 — Operators runtime (central)
**Goal:** reactive operators on the op stream. **Depends on M4 (needs the server op
stream); best after M6 so results are visible.**
- Server-side runtime: manifest loading, read-scope/trigger evaluation, LLM client,
  typed-output validation + retries, idempotency/re-entrancy, field-ownership
  enforcement, fuel/cascade control + cycle detection.
- Ship one reference operator (**auto-clarify inbox**).
- **Deliverable:** operators reacting and emitting provenance-tagged ops.

### M9 — Agents (human-gated) + hardening
**Goal:** the full compute vision + production polish. **Depends on M8.**
- Agent task creation (from operator recommendations, human-approved), agent
  runtime, **proposed-objects-back** flow + accept/reject UI.
- Cross-cutting hardening: server monitoring/alerting, op-log compaction + snapshot
  cadence, retention, quota, backup/restore drills, threat-model review of the
  internet-facing box.
- **Deliverable:** operators + agents end-to-end, production-hardened.

---

## Risks & mitigations
- **Merge correctness (highest):** the phone and server must agree exactly. Mitigated
  by the **shared `core`** (one implementation) + conformance/property tests in M3.
- **Internet-facing server (new attack surface):** it now holds plaintext (trusted-
  server model). Mitigated by device auth, TLS, least-privilege S3, at-rest
  encryption, monitoring — front-loaded in M4, reviewed in M9. Full analysis +
  accepted residuals in `../specs/2026-07-08-threat-model.md` (incl. operator
  **prompt-injection** defense = scope/fuel/no-destructive-power + human-gated agents).
- **Big-bang risk:** mitigated by the M5 vertical slice and keeping a bridge UI until
  M6/M7; core+server (M3/M4) are built alongside the running v0.2 app.
- **Scope creep into Compose:** keep the native surface thin (M7) — content lives in
  the shared `web` module, not rebuilt natively.
- **Operator loops/cost:** fuel budgets + provenance idempotency + cycle detection in
  M8; agents human-gated in M9.

## Not doing (explicit)
- **No v0.2 data import — start fresh** (confirmed 2026-07-08: no existing tasks to
  migrate). M5 seeds an empty op-log; no Room→op-log importer is built.
- No CRDT framework beyond the LWW/tombstone/tree-move subset (append-dominant
  workload; see the op/merge spec).
- No multi-user/multi-tenant — single user, single trusted server.
- No offline desktop/browser — online-only thin clients; the phone is the only
  offline replica.

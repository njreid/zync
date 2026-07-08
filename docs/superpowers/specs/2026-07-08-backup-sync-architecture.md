# zync — Backup & Sync Architecture (ADR)

> **Status: 🟢 DIRECTION SET (2026-07-08) — see §12 for resolved decisions.**
> Supersedes the Google-Drive backup direction in M2 Task 4. Written 2026-07-08 in
> response to "the Google Drive approach is wrong — what about an EC2 host running a
> Go daemon (OT master) storing objects in S3?"; forks resolved after the
> agents-vs-operators and offline-capture discussion. §1–§11 record how we got here;
> **§12 is the current decision.**

## 1. Context

Today zync's core invariant is **phone-as-single-source-of-truth**: the Android
app owns the Room DB + Ktor server, and desktop/browser are thin remote terminals
reaching it over pinned TLS (M1c/M1d). Google Drive (M2 Task 4) is **disaster
recovery only** — a periodic client-encrypted snapshot to `appDataFolder`, never
part of the live data path. zync's value prop: *local-first, no account, no cloud
sees plaintext.*

The Drive approach has real limits: it's snapshot-only (coarse restore), needs a
Google account, is clunky for incremental/attachment sync, and — crucially — does
**nothing** for the "phone must be online/awake for other devices to work" gap.

## 2. The reframe: this is not a backup change

The proposal (a server that ingests operations) makes a **server or shared log
authoritative**, not the phone. That touches pairing, trust, the phone's role, and
the security posture — far more than swapping Drive→S3. We should decide the
**authority + sync model** deliberately; "backup" is then a consequence of it.

Two distinct problems are being conflated:
- **Durability** — don't lose data if the phone dies. (Drive/S3 both solve this.)
- **Availability + multi-device** — reach/edit data when the phone is
  off/asleep/away; merge edits made on several devices. (Drive does *not* solve
  this; a server can.)

## 3. Why "OT master" is the wrong primitive here

- **OT breaks end-to-end encryption.** Operational Transformation requires the
  server to *understand* operations to transform/reorder them → **plaintext task
  data on an EC2 box**. That is a hard regression from zync's "cloud never sees
  plaintext." For a personal GTD graph, that's a real privacy loss.
- **OT is overkill for one user.** OT is built for many concurrent editors on
  shared text (Google Docs). zync is one user across a few devices; conflicts are
  rare and low-stakes. OT's transform functions are famously hard to get right and
  would be a permanent complexity tax.
- **Better fit: an append-only op log with CRDT-style resolution** — Lamport/vector
  clocks, last-writer-wins per field, and a dedicated *move* CRDT for the node tree
  (Kleppmann's tree-move avoids cycles/orphans). CRDTs require the server to
  understand **nothing**, so they compose cleanly with E2E.

## 4. FORK A (decides everything): may the server see plaintext?

- **No (recommended, keeps zync's ethos):** the server is a **dumb store of opaque
  encrypted objects**; all merge/ordering logic is client-side (CRDT or
  phone-authority). OT is off the table.
- **Yes (only if you fully trust the box):** more options open (server-side
  indexing, search, OT) but you've given up E2E. Not recommended.

**This ADR assumes "No" (E2E preserved) unless the owner overrides.** But see the
2026-07-08 addendum (§11): if content-aware LLM **operators** run server-side, the
server must see plaintext, which forces FORK A to "Yes." Decide **FORK B (operator
execution locus, §11)** first — it largely determines this one.

## 5. S3 is right for blobs, wrong for the live log

S3 is excellent for **content-addressed, client-encrypted objects**: attachment
blobs and immutable op-log *segments* / snapshots. It has no atomic append or
ordering, so it's a poor backing store for a live operation *sequence*. If total
ordering is needed, the **Go daemon owns it** (a small Postgres/DynamoDB row or an
embedded WAL assigns monotonic sequence numbers over opaque ciphertext); S3 holds
the immutable segments + blobs.

## 6. Options considered

**Option 1 — Server as a dumb encrypted relay/mirror (RECOMMENDED, near-term).**
Phone stays authoritative. It pushes its client-encrypted op log + content-addressed
attachment blobs to the Go daemon → S3. Desktop/browser pull from the server when
the phone is unreachable (read-mostly; writes still route through the phone as
today, or queue).
- ✅ Solves durability **and** phone-offline read access. Minimal change to the
  authority model. E2E intact. Reuses the M3 incremental-backup design (content-
  addressed encrypted blobs + manifest), just pointed at S3 via the daemon.
- ➖ Multi-device *writing* while the phone is offline still needs Option 2.

**Option 2 — Server as sync coordinator with a CRDT (future, true multi-master).**
Every device holds a replica; edits are CRDT ops synced through the daemon (which
assigns sequence numbers over opaque ciphertext but never decrypts). No single
authority; offline edits on any device merge deterministically.
- ✅ Full offline multi-device editing, E2E intact.
- ➖ Real complexity: CRDT for the tree (move handling), causal delivery, snapshot
  compaction. Bigger client rewrite.

**Option 3 — Server as OT master (REJECTED).** Plaintext-visible, high complexity,
wrong tool for a single user (see §3).

**Decision (proposed): pursue Option 1 now; design the op log so Option 2 is a
later evolution, not a rewrite. Reject OT.**

## 7. Proposed protocol sketch (Option 1, forward-compatible with 2)

- **Domain ops** (event-source the existing `NodeRepository` mutations): `create`,
  `rename`, `move`, `complete`/`reopen`/`trash`, `defer`, `convert`, `setContexts`,
  context create/rename, attachment add/delete. Each op:
  `{opId, deviceId, lamport, ts, type, payload}`.
- **Encryption:** ops batched into segments, each encrypted client-side with
  AES-256-GCM under a **stable per-user data key** (fresh IV per segment). Same for
  blobs. Server stores ciphertext only.
- **Key management (reuses zync pairing!):** the user data key is generated on the
  first device, keystore-wrapped, and transferred to new devices via the existing
  **QR pairing** channel (device-to-device), with an optional exportable recovery
  key. This *also* fixes the M2 "fresh install can't decrypt backup" problem — the
  key travels device-to-device, not only via a re-typed passphrase.
- **S3 layout:** `users/<uid>/oplog/<seq>.enc` (immutable segments),
  `users/<uid>/blobs/<sha256>.enc` (dedup attachments), `users/<uid>/snap/<lamport>.enc`
  (compacted state snapshots so new devices don't replay the whole log).
- **Go daemon endpoints (authenticated, opaque payloads):** `POST /ops` (append →
  assign seq), `GET /ops?since=`, `PUT /blobs/<hash>` (put-if-absent),
  `GET /blobs/<hash>`, `GET/PUT /snap`, `GET /head`.
- **Auth/trust:** devices authenticate with **Ed25519 device identities** — reuse
  zync's existing pairing keypairs; the daemon holds each user's allowed device
  pubkeys. Public host → **real Let's Encrypt TLS** (not the phone's self-signed
  cert). Per-user isolation, rate limiting, quota.

## 8. Consequences

- **Security posture:** a server compromise leaks only **ciphertext** (E2E). But the
  server *does* see **metadata**: op counts, timing, object sizes, blob hashes,
  device ids. Note/accept this (pad sizes if it matters). S3 bucket stays private +
  SSE as defense-in-depth; the daemon is minimal and stateless-ish.
- **Ops burden (the real trade):** you now run/patch/secure an internet-facing box
  (vs. "runs on your phone, nothing to operate"). A single small VPS you control is
  still "self-hosted," just not phone-hosted. Cost ≈ a few USD/month (t4g.nano +
  S3). Name this explicitly — it's the main thing you're giving up.
- **Phone's role:** in Option 1 the phone stays authoritative and the Ktor server
  is unchanged; the daemon is an additional sync target. Desktop could later talk to
  the daemon directly instead of proxying the phone.
- **Reuse:** M3 Task 4 (incremental content-addressed encrypted blobs + manifest)
  transfers almost directly — S3-via-daemon instead of Drive `appDataFolder`. So
  that work is not wasted; it's re-pointed.

## 11. Addendum (2026-07-08) — writers: agents & operators

Prompted by: "CRDT is attractive because it may not be only one human — multiple
**agents** may contribute; or is our tree-node-watching **LLM-powered operator**
model better? Operators have looping/conditionals — like agents but simpler to
reason about."

**Separate two orthogonal axes; don't conflate them.**
- **State/replication:** single ordered authority vs. many replicas that merge
  (CRDT).
- **Compute/writers:** who emits ops — humans, open-ended agents, or scoped
  reactive operators.

**Multiple automated writers does NOT imply CRDT.** CRDT earns its (real) cost in
exactly one case: multiple replicas accepting writes **while disconnected**,
reconciling later. Agents/operators that are generally online and **funnel writes
through one ordered log** are a single replica with one serialization point → no
CRDT. Writer-count argues for a good *ordered log + concurrency control + reactive
scheduler*, not CRDT. Keep CRDT for the offline multi-device **human** edge, added
later at the edge — don't let it infect the core.

**The op log is the unifying substrate.** Event sourcing (state = fold(ops)) gives
backup, sync, provenance, replay, *and* the operator model from one primitive.
Every writer appends ops carrying **provenance** (human / operator-id / agent-id) —
provenance is what makes loop-breaking, idempotency, undo, and trust tractable.

**Operators = scoped, fueled, provenance-idempotent log consumers.** With a
declared **read scope** (subtree watched) and **write scope**, plus bounded
loops/conditionals, an operator is analyzable where an open-ended agent isn't
("triggers / stored procedures on the op stream" or spreadsheet recalc, not a free
agent). The endorsement comes with the LLM-in-the-loop discipline:
1. **Termination:** build the operator dependency graph from declared scopes;
   detect cycles; per-cascade **fuel budgets**; an operator may not re-trigger on
   its own output.
2. **Fixpoint/idempotency:** the tree must settle. Since LLM calls are
   nondeterministic, gate re-firing on **provenance/version** ("already handled
   node X @ vN") or it never converges (and bleeds cost).
3. **Cost/consequence:** every firing is a model call — debounce, budget, and gate
   consequential ops behind human confirmation.
The ordered log makes (1)-(2) tractable: operators consume at a cursor and append
to the same timeline — naturally serialized and replayable, no CRDT merge.

**FORK B (new, more consequential than CRDT-vs-not) — where do operators run?**
Content-aware LLM operators need **plaintext**.
- **Central (daemon/EC2):** the host holds the data key and sees plaintext; the
  ADR's "dumb encrypted relay" becomes a **trusted compute node**. Coherent for a
  personal system, but "cloud never sees plaintext" is consciously traded away (and
  this re-opens server-side options incl. OT).
- **Trusted end-devices only (phone/laptop):** E2E to the server holds, server
  stays dumb — but operators fire only when that device is online, and compute is
  device-bound.
You cannot have both server-side content-aware operators **and** an E2E-blind
server. This locus choice — not writer-count — sets the trust boundary and largely
determines FORK A.

**Revised recommendation.** Make the **ordered, provenance-carrying op log** the
core (it serves backup, sync, and operators alike). Pursue the **operator model**
over free agents for automated contributors, with fuel + provenance-idempotency +
human gates. **Defer CRDT** to the offline-human edge. Decide **FORK B (operator
locus)** before FORK A, since it dictates whether the server can be E2E-blind.

## 9. Open questions (owner)

1. **FORK A — E2E?** Confirm the server must never see plaintext (assumed yes).
2. **Primary driver?** durable backup / phone-offline *read* access / full offline
   multi-device *editing* / get off phone-as-server entirely. (Backup+read →
   Option 1; multi-device editing → Option 2.)
3. **Ops appetite:** willing to run + secure a small VPS + S3 bucket? If not, a
   managed object store (Backblaze B2 / Cloudflare R2 / S3) with a thin serverless
   auth shim is a lighter variant of the same shape.
4. **Key recovery:** device-to-device via QR only, or also an exportable recovery
   key / passphrase for the all-devices-lost case?

## 10. Recommendation

Keep E2E. **Reject OT.** Build the server as a **dumb, authenticated, encrypted
op-log + blob store** backed by S3 (Option 1), with device auth reusing zync's
Ed25519 pairing and the data key delivered device-to-device via QR. Design the op
log so a CRDT-based multi-master (Option 2) is a later evolution. This solves
durability + phone-offline access, preserves zync's privacy ethos, and re-points
(rather than discards) the M3 incremental-backup work. If accepted, this replaces
M2 Task 4's Drive direction and reshapes M3 Tasks 4–5 into "sync to the zync
daemon" instead of "backup to Drive."

## 12. Resolved decisions (2026-07-08) — supersedes §10

The forks are settled after the operators + offline-capture discussion:

- **FORK B → operators run CENTRAL** on a trusted, always-on EC2 host. Therefore
  **FORK A → the server sees plaintext**: E2E-to-server is **consciously dropped**.
  The model is **trusted server + trusted devices + TLS**, not zero-knowledge cloud.
  A server compromise discloses everything → the box must be hardened, monitored,
  least-privilege, encrypted-at-rest (server-held keys), strong device auth.
- **Operators** are deliberately simple: watch a declared scope → produce **typed
  output**, with **a few retries on type-validation failure**. No open-ended tool
  use. Rules: write only to **operator-owned fields / new child/annotation objects**,
  **never clobber human-authored fields**; **version-keyed idempotency**
  (“handled X@vN”); must be **re-entrant** to late-arriving offline history.
- **Agents** are a separate, higher-cost tier: **human-gated**, possibly
  long-running; an operator may *recommend* an agent task but the human triggers it;
  agent results return as **proposed objects** (provenance=agent), never silent
  mutation of human state.
- **HARD REQUIREMENT — capture works offline, sync on reconnect.** This selects a
  **multi-replica** model (ADR Option 2), *not* the Option 1 relay: edge devices
  accept writes while partitioned and reconcile later. Because the offline workload
  is **append-dominant** (capture = create) and the only offline writer is the
  human (operators/agents are central), the merge stays **lightweight, not a heavy
  CRDT and not OT**: edge-generated unique IDs (ULID/UUIDv7), a hybrid logical clock
  (HLC), **LWW-register per field**, **tombstones** for deletes, and a **tree-move
  rule** (Kleppmann) for concurrent reparents.

**Resulting shape.** The **merged, provenance-tagged op log is the source of
truth** — neither a device nor the server is "the authority." The EC2 daemon is a
**privileged always-on replica** that holds the canonical merged log, stores
content-addressed encrypted attachment blobs in **S3**, runs **operators** against
merged state, and hosts **human-gated agents**. Every edge (which ones → see below)
is a **replica**: local store + pending-op log + merge on sync.

**Risks carried forward (from the 2026-07-08 red-team):** operator-vs-human write
discipline (field ownership + provenance); operator re-entrancy on late-arriving
history; operators are eventual, never on the offline capture hot path; the
security model is trusted-not-zero-knowledge.

**Edge scope — RESOLVED (2026-07-08): phone is the only offline replica;
desktop/browser are online-only thin clients of the server.** Desktop/browser
issue ops directly to the server (append file, create task/project, add tag, …),
which serializes them immediately; they hold no durable state and no merge logic.
This collapses the sync problem to a **single phone↔server pair** — the simplest
non-trivial offline model (reconciliation ≈ "phone is the server's offline
replica": on reconnect push pending ops, server merges via LWW/tombstone/move,
phone fast-forwards).

Consequences of this scoping:
- **Desktop sheds the M1c/M1d pinning/mDNS/proxy stack.** Pointed at the server
  (real Let's Encrypt cert, known URL) the desktop is an ordinary authenticated
  HTTPS client — no self-signed pinning, no mDNS discovery, no local reverse proxy.
  A significant *reduction* of the trickiest shipped code.
- **The phone-as-LAN-server role (M1c/M1d) is largely superseded** — kept only if a
  "server-unreachable, phone+laptop on same Wi-Fi" LAN fallback is wanted; otherwise
  retire it.
- **Server is the availability SPOF for desktop/browser** (offline → they don't
  work; only the phone keeps working offline). Accepted.
- **Browser needs a server session/login** (can't QR-pair like a native app) — a
  mild "account to your own server" notion, a small departure from "no account".

**Next:** a companion spec — op schema (with provenance + HLC), the
LWW/tombstone/tree-move merge rules, the operator manifest + lifecycle
(scope/trigger/typed-output/retry/idempotency), and the agent handoff — then
reshape M3 Tasks 4–5 around "sync to the zync daemon."

# zync — Merge Conformance Vectors

> **Status: 🟢 (2026-07-08).** Canonical, language-agnostic test fixtures for the
> merge core (op/merge spec `2026-07-08-oplog-merge-operator-model.md`). **Any**
> implementation — the Kotlin `core` (M3 Task 6) or a future reimplementation — must
> pass all of these. They are also the concrete examples behind spec §9.

## Notation
- **HLC** = `(ms, ctr, dev)`; total order = ms → ctr → dev. Lower sorts first.
- **Register**: `field = value @hlc`. **Actor** ∈ `human | op | agent`.
- **Apply rule** (recap): SetField/tag = higher-HLC-wins; Tombstone = terminal
  (entity not-alive regardless of HLC); Move = HLC-ordered, cycle-skip.
- Each vector: **init** → **ops** (delivered to *both* replicas, in *any* order and,
  where noted, *shuffled*) → **expected** converged, projected state. Convergence
  means identical state on every replica after all ops are seen.

---

### V1 — LWW field race (later HLC wins)
- **init:** `T.title = "buy milk" @(1,0,srv)`
- **ops:** `H1 human SetField(T,title,"Buy oat milk") @(10,0,phone)` ·
  `H2 human SetField(T,title,"Buy almond milk") @(12,0,desk)`
- **expected:** `T.title = "Buy almond milk"` (12 > 10).
- **variant:** swap so phone = `(13,0,phone)` → `T.title = "Buy oat milk"`.

### V2 — Move + independent edit (both apply)
- **init:** `T.parent = Inbox @(1,0,srv)`, `T.status = ACTIVE @(1,0,srv)`
- **ops:** `Move(T → P1) @(10,0,phone)` · `SetField(T,status,DONE) @(12,0,srv)`
- **expected:** `T.parent = P1`, `T.status = DONE`.

### V3 — Move cycle (one winner, no cycle, order-independent)
- **init:** `A.parent = Root`, `B.parent = Root`
- **ops:** `M1 Move(A → B) @(11,0,phone)` · `M2 Move(B → A) @(12,0,desk)`
- **integration:** HLC order → M1(11): `A.parent=B` (ok); M2(12): `B→A` would make B a
  descendant of A (A already under B) → **cycle → skip**.
- **expected:** `A.parent = B`, `B.parent = Root`. Must hold even when M1 is delivered
  *after* M2 (late move → undo M2, apply M1, redo M2 → still skipped).

### V4 — Operator field ownership (by construction)
- **init:** `T.title = "X0" @(1,0,srv)`, `T.summary = ∅`
- **ops:** `human SetField(T,title,"X") @(12,0,phone)` ·
  `op SetField(T,summary,"Y") @(13,0,srv)` (`summary` is operator-owned)
- **expected:** `T.title = "X"`, `T.summary = "Y"` (different registers).
- **guard assertion:** emitting `op SetField(T,title,…)` (a **human-owned** field) is
  **rejected at emission** (write-scope violation). This is the *only* thing
  protecting human fields — **not** HLC order (the operator's HLC is deliberately
  later here to prove LWW would wrongly let it win).

### V5 — Out-of-order create delivery (additive; converges)
- **ops:** phone offline creates `C1 N1 @(10,0,phone)`, `C2 N2 @(11,0,phone)`,
  `C3 N3 @(12,0,phone)`; server has `C9 N9 @(20,0,srv)`.
- **deliver:** C1–C3 to the server in **shuffled** order, interleaved with C9.
- **expected:** `N1, N2, N3, N9` all alive. (Creates are additive — no conflict;
  order-independent.) *Operator re-firing on late creates is an M8 runtime test, not a
  merge vector.*

### V6 — Trash vs edit (independent registers, nothing lost)
- **init:** `T.notes = ∅`, `T.status = ACTIVE`
- **ops:** `human SetField(T,notes,"remember oat") @(10,0,phone)` ·
  `human SetField(T,status,DROPPED) @(12,0,srv)`
- **expected:** `T.notes = "remember oat"`, `T.status = DROPPED` (reversible trash).

### V7 — Tombstone wins (terminal, beats a later edit)
- **init:** `T.title = "X0" @(1,0,srv)`
- **ops:** `Tombstone(T) @(10,0,A)` · `human SetField(T,title,"X") @(12,0,B)`
- **expected:** `T` is **not alive** (tombstoned) even though the edit's HLC (12) > the
  tombstone's (10). Chosen policy: hard tombstone is terminal. (A `title` register may
  physically hold "X"; existence projection reports T dead.)

### V8 — Tag LWW boolean
- **init:** `tag(T, ctx)` absent
- **ops:** `AddTag(T,ctx) @(10,0,A)` · `RemoveTag(T,ctx) @(12,0,B)`
- **expected:** `tag(T,ctx)` **absent** (remove wins, 12 > 10).
- **variant:** reverse so add = `(13,0,A)` → `tag(T,ctx)` **present**.

---

## Property (not a fixed vector): convergence under shuffle
For any generated op set (including moves, tombstones, tags) with a **seeded** RNG:
deliver the same set to two replicas in **independently shuffled** orders → their
**projected states are byte-identical**. This is the general guarantee the eight
vectors above sample specific cases of. Idempotency: re-delivering any op (same
`opId`) never changes the result.

## Usage
- **M3 Task 6** encodes V1–V8 as fixed conformance tests + the shuffle property test.
- Keep this file as the **source of truth for the fixtures**; if a rule changes in the
  op/merge spec, update the affected vector here in the same change.
- A future non-Kotlin reimplementation must pass this exact set (the Automerge lesson:
  shared vectors, ideally a shared core).

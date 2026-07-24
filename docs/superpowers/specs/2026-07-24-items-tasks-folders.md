# Items, Tasks & Folders — data-model simplification

Status: proposed · 2026-07-24 · supersedes the `kind`-based node model for GTD content.

## Motivation

Today every node stores an explicit `kind` (task/project/…), and "a project is a task
that has children" overloads one type as both container and leaf. Promote/demote logic
(`convertToProject`, filing) is where the confusing bugs live. We remove the stored type
entirely: **type is derived from *where* a node lives and *whether it has children*.**

## The model

Two shapes, one location. The op-log is **unchanged** (SetField/Move/AddTag/Tombstone);
this is a read-model reinterpretation + UI vocabulary + one seed node.

Three locations, each a subtree:

- **Inbox** — top-level nodes (no parent) that are not the Projects/Reference roots or under them.
- **Projects** — descendants of a new well-known `PROJECTS_ROOT`.
- **Reference** — descendants of the existing `REFERENCE_ROOT`.

Derived type of a node X (`hasChildren` = has ≥1 live child):

| Location | hasChildren | Type (user word) | Actionable? |
|----------|-------------|------------------|-------------|
| Inbox | no | **Item** | yes (complete/delete/move) |
| Inbox | yes | — (not allowed; see rules) | — |
| Projects | no | **Task** | yes |
| Projects | yes | **Project** (folder) | **no** — container only |
| Reference | no | Reference item | no |
| Reference | yes | Reference folder | no |

"Item" and "Task" are the same shape (a childless leaf) in different trees. Recursion is
**confined to Folders** — Items and Tasks never contain anything.

## Rules (locked decisions)

1. **GTD-pure projects.** A Project is a container only. It has no independent status and
   never appears in Next/Today — those show its first open **Task**. Single-action Tasks
   (leaves directly under `PROJECTS_ROOT`) appear in Next as themselves.
2. **Subdivision promotes to a project.** Giving any leaf its first child makes it a Folder
   (there is no lightweight in-task checklist). Converting an Inbox Item to a project =
   move it under `PROJECTS_ROOT`; adding its first child makes it a Project.
3. **Project completion is derived.** A Project is *done* when every descendant Task is
   `DONE`, deleted (tombstoned), or `VOIDED` (status DROPPED). A Project with **zero open
   Tasks but also none at all** is *stalled* (GTD "project with no next action") — flagged,
   not done.
4. **Depth.** Projects nest at most **2 folder levels** (`PROJECTS_ROOT → Project → sub-Project`,
   tasks as leaves within). Reference nesting is **uncapped**. Moves that would exceed the
   Projects cap are rejected.
5. **Location determines identity.** Move a Task to the Inbox → it's an Item again; move an
   Item into Projects → it's a Task. Nothing is stored; everything re-derives.

## Views

- **Inbox**: flat list of Items.
- **Next**: single-action Tasks + the first open Task of each Project (never a Project row).
- **Today**: Items/Tasks with due ≤ today.
- **Projects**: browsable Projects tree (folders + tasks), with per-project progress and a
  "stalled" flag for projects lacking an open next action.
- **Reference**: browsable Reference tree.
- Contexts/free-tags still filter Items/Tasks; unchanged.

## Implementation plan (read-model refactor)

1. Add `WellKnownNodes.PROJECTS_ROOT`; seed it on the server + phone (flag-guarded, like the
   `@dev` context seed).
2. `ContentReadModel`: replace all `kind == "project"/"task"` checks with derived helpers
   `location(id)` (Inbox|Projects|Reference) and `hasChildren(id)`. `projects()`,
   `nextActions()`, `inbox()`, `reference()`, and the File-picker candidate logic all switch
   to these.
3. Commands: drop `convertToProject`; "make project" = move under `PROJECTS_ROOT`. Adding a
   child is the only thing that turns a leaf into a folder (nothing to write).
4. Depth guard: extend `moveWouldExceedDepth` for the Projects 2-level cap; leave Reference
   uncapped.
5. One-time migration (idempotent, flagged): reparent existing `kind=project` nodes (and
   their subtrees) under `PROJECTS_ROOT`; everything else top-level becomes an Inbox Item.
   Emitted as Move ops — no op-log schema change.
6. UI vocabulary: "Item / Task / Project / Reference"; File picker already targets the
   Projects/Reference trees, so it mostly stays.

## Open questions (small)

- **Completed project fate**: mark done + collapse in place, or auto-archive to Reference?
  (Default: mark done, collapsible; no auto-move.)
- **Stalled surfacing**: badge in Projects only, or also nudge in Next? (Default: Projects badge.)
- Whether Inbox should allow a leaf to gain a child directly (auto-move it to Projects) or
  require an explicit "make project" move first. (Default: adding a child auto-moves to Projects.)

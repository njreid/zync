# Reference tree, Task→Reference links, and Archive

Status: proposed · 2026-07-25 · extends [items-tasks-folders](2026-07-24-items-tasks-folders.md).

Reference becomes a proper knowledge base (folders + markdown items + media), Tasks can point
at reference URLs, and Projects retire to a new **Archive** area instead of Reference. Type stays
derived from tree location, with **one deliberate exception**: reference folders are explicit.

## Reference model

A **Reference item** is filed knowledge, not a task. Same underlying node; behavior derives from
living under `REFERENCE_ROOT`:

- **Has**: title, a **markdown body** (`notes`, rendered — not plain), free **tags**, and **media
  attachments** (blobs with mime types).
- **Has not** (in the Reference UI): due date, waiting-for/person, snooze. Those fields are
  **ignored, not cleared** — if the item is later moved into Projects they light up again.

**Reference folders are explicit** (the one asymmetry with derived Projects). A folder carries a
stored marker (`folder = true`) so an **empty** folder is still a folder and "add child" is
well-defined. `typeOf` under Reference therefore keys on the marker, not on has-children:

- under `REFERENCE_ROOT` + folder marker → `REFERENCE_FOLDER`
- under `REFERENCE_ROOT`, no marker → `REFERENCE_ITEM`

(Projects stay purely derived: a non-reference node with children is a Project.)

### Reference UI (its own component, not `itemLi`)

The tree shows **names only** — no swipe/File/Snooze/Edit buttons. Interactions:

- **Long-press → context menu.**
  - **Folder**: Rename · Move (reparent to an existing or newly-created folder) · Add child
    (folder or item) · Delete (**recursive**).
  - **Item**: Rename · Move · Delete.
- **Folder drill-down** with a breadcrumb (per-folder route).
- **Default view of an item = the rendered markdown preview**, media inline.

### Markdown rendering (server-side, sanitized, CSP-safe)

- `![alt](photo1.png)` → embed the item's **attachment named `photo1.png`** (relative = this
  item's media, matched by filename).
- `[text](/Personal/Family/info)` → a **link into another reference node by tree path** from the
  Reference root (navigates within the tree).
- So: **relative URLs = this item's attachments; `/`-rooted URLs = reference-tree paths.**

### Reading view

Reference items carrying the **`read` free tag** appear in **Reading** — a flat list. Membership
is exactly "has the `read` tag" (not a separate reading-state field). This establishes a general
pattern: **some tag values carry semantic power** (drive a view/behavior); `read` is the first.

## Task → Reference links

A Task may reference **0..many URLs** — just a **list of URLs on the task** (reference-tree paths
like `/Personal/Family/info`, or external). Stored as a **mergeable set** (per-URL boolean
register, like free tags) so concurrent adds on two devices both survive; shown as a list, added
from the task side. **No back-references** for now (a reference item does not list linking tasks).

## Archive

A new well-known `ARCHIVE_ROOT`, sibling to Projects/Reference. **Projects are archived by moving
them under Archive** (done tasks do **not** go to Reference — that old DONE→Reference proposal flow
is removed).

Anything under `ARCHIVE_ROOT` is **inert**:

- Excluded from **Next / Today / context views / notifications** (its tasks don't surface anywhere
  active).
- **Still searchable.**
- Shown in the **Archive view**.
- **Reversible**: move a project back under Projects and it (and its tasks) go live again.

## Type / location summary

`locationOf(id)` → INBOX | PROJECTS | REFERENCE | ARCHIVE (by `isUnder` the roots).

| Location | Shape | Type | Active surfaces? |
|----------|-------|------|------------------|
| Inbox | leaf | Item | inbox only |
| Projects | leaf | Task | Next/Today/context |
| Projects | children | Project | Projects |
| Reference | folder-marked | Reference folder | Reference/Reading |
| Reference | not marked | Reference item | Reference/Reading |
| Archive | (any) | archived | **none** (searchable) |

## Filing semantics

- Inbox item → **Projects** = Task (due/person active).
- Inbox item → **Reference** = Reference item (due/person ignored but preserved). Reference holds
  only folders + items, never tasks.
- Project → **Archive** = inert (reversible).

## Implementation stages (op-log unchanged; read-model + UI + one folder marker)

1. **Roots + markers**: `ARCHIVE_ROOT`; a `folder` marker for reference folders; commands
   `createFolder`, `archiveProject`, `unarchive`, `addReferenceLink`/`removeReferenceLink`.
2. **Read model**: `locationOf` (add ARCHIVE); exclude Archive from Next/Today/context/notifications
   (keep in search); reference folder/item derivation via the marker; Reading = `read`-tagged
   reference items; task reference-URL list.
3. **Markdown**: sanitized md→HTML with attachment-by-filename embeds + `/`-path reference links.
4. **Reference UI**: names-only tree, folder drill-down + breadcrumb, long-press context menus,
   item markdown preview; search-hit breadcrumb + click-to-locate.
5. **Archive + Reading views**; remove the DONE→Reference proposal flow.

## Open (small)
- Long-press on web: a JS long-press handler → Datastar-driven context menu (CSP-safe).
- Reference "Move" to a *new* folder = create-folder-then-reparent in one gesture.
- Attachment filename collisions within one item: last-match wins (rare; flag if it bites).

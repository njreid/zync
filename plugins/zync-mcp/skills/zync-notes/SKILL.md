---
name: zync-notes
description: Use when the user asks to review, triage, tidy, tag, organize, or clean up their Zync inbox, tasks, projects, or Reference tree — or asks Claude to "help with my notes/references" — via the connected `zync` MCP server. Covers the read-then-propose workflow and the tool catalog.
---

# Working with Zync over MCP

Zync is the user's personal GTD-style store: an **inbox** of untriaged captures, **tasks**/
**projects**, and a **Reference** tree of longer-lived material (notes, files, links). This
skill's `zync` MCP server gives you read access to all of it and *propose-only* write access —
every mutation you make becomes a suggestion the user reviews and accepts or dismisses in the
app. You can never silently overwrite anything they've done.

## The core guarantee — always propose

Every write tool submits with `mode:"propose"`, no matter what. Concretely:

- `create_task` / `create_project` mint a new node flagged `proposed=true`.
- `set_field`, `complete`, `trash`, `move`, `add_tag`, `attach` mint a **suggestion node**
  targeting the real one — the live data doesn't change until the user accepts it.
- `comment`, `add_free_tag`, `remove_free_tag` **commit immediately** — they're additive and
  can't clobber anything a human wrote, so they're the one place your writes land right away.
  Use `comment` freely to leave a note explaining a batch of proposals you just made.

Because of this, after you propose changes: **say what you proposed and that it's awaiting
review** — don't tell the user something is "done" when it's still a pending suggestion. If it's
useful, call `list_proposals` and summarize what's queued up.

## Tool catalog

**Read (always available):**

| Tool | Args | Use it to |
|---|---|---|
| `list_inbox` | — | see what's untriaged |
| `list_children` | `parent?` (ULID or `inbox`/`reference`, omit for root) | browse a subtree |
| `get_node` | `id` | inspect one node's full fields, comments, attachments |
| `search` | `query`, `limit?` | find nodes by keyword (hybrid keyword+semantic if the server has embeddings configured) |
| `list_comments` | `id` | read the discussion/annotations on a node |
| `list_proposals` | — | see what's pending human review |
| `list_reference` | `folder?` | browse the Reference tree |

**Write (every mutation becomes a proposal, or commits if additive — see above):**

`create_task`, `create_project`, `set_field`, `complete`, `trash`, `comment`, `add_free_tag`,
`remove_free_tag`, `move`, `add_tag`, `attach`.

`attach` references a blob uploaded separately via `PUT /api/blobs` — you generally won't call
this one directly unless the user has already uploaded something and given you its blob key.

Read tools always show up in `tools/list`; write tools are gated by the bot's granted verbs — if
one you need isn't listed, tell the user which verb to grant (`server bot add ... --verbs
<list>`) rather than guessing around the gap.

## A good triage/tidy workflow

1. **Survey before touching anything.** `list_inbox` and/or `list_reference` to see the shape of
   what exists. Don't propose changes to nodes you haven't looked at with `get_node`.
2. **Look for the obvious wins**: stale items with no updates that look abandoned (candidates for
   `trash` or `complete`), inbox captures that clearly belong in an existing Reference folder or
   under an existing project (candidates for `move`), items missing a title/notes worth filling in
   (`set_field`), and near-duplicates `search` turns up (surface these to the user rather than
   silently trashing one — dedup is a judgment call).
3. **Batch your proposals, then explain them.** After a pass, tell the user roughly what you
   proposed and why, grouped by kind (e.g. "3 moves into Reference/Recipes, 2 items marked
   complete, 1 duplicate flagged"). Use `comment` on a node if a one-line rationale will help them
   decide later.
4. **Don't over-tag.** `add_tag` targets an existing context/tag node — look it up (`search` or
   browse) rather than inventing one. `add_free_tag` is for ad hoc labels and is fine to use more
   loosely since it's additive and easy to remove.
5. **Respect field ownership.** If a `set_field` call errors as not-permitted, the bot's
   capability grant doesn't include that field — don't try to work around it (e.g. via `comment`
   abuse); just tell the user.

## Errors are tool errors, not failures to retry blindly

A bad ULID, a disallowed field, or a rate limit shows up as `isError:true` with an explanation in
the result text — the connection stays usable. Read the message and adjust (fix the id, ask the
user, or slow down) rather than hammering the same call again.

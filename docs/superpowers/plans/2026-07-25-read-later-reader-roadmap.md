# Read Later reader and retrieval roadmap

## Product boundary

Newz remains the discovery and capture surface. A save is exported to Zync's
bot-owned **Read Later** project, where Zync Reference becomes the durable,
offline-capable reading and retrieval surface. This deliberately does not send
saves directly into the general Reference tree: Read Later is a visible inbox
for intentional reading, and a user may file a completed article elsewhere in
Reference.

## Priority 0 — usable long-form reading

- Render imported article Markdown as safe semantic HTML in `/node/{id}/read`.
- Present source URL, summary, title, readable typography, and an obvious return
  to the saved item.
- Preserve existing plain-note behaviour for non-article Reference items.
- Add shared-web tests for headings, lists, links, code, raw-HTML escaping, and
  outbound source links.

## Priority 1 — make Read Later a reading queue

- [x] Detect imported Newz articles from their stable source metadata, and provide
  an `Unread`, `Reading`, and `Finished` state that is separate from task completion.
- Add an article row with source, saved date, read-time estimate, and reading
  progress; opening an article resumes its position.
- Add a lightweight `Mark finished` action that offers, but never forces,
  filing into the wider Reference tree.

## Priority 2 — retrieval and organisation

- Scope the existing FTS search to a `Read Later` filter and include imported
  Markdown in the index, not only title/summary/notes metadata.
- Add filters for source, tag, state, and date saved, plus manual collections.
- Add durable highlights and personal notes that remain distinct from the
  imported article body.

## Priority 3 — reading continuity

- Sync reading progress, state, highlights, and notes through the op-log so
  server and phone agree offline.
- Add a restrained `Continue reading` and `Saved recently` section; no
  engagement-driven nudges.
- Add export/deep-link affordances back to the canonical original.

## First increment

Implement Priority 0 without changing Newz's export schema: article Markdown
already arrives in Zync's `notes` field. The reader must render Markdown safely
and preserve ordinary Reference notes as readable prose.

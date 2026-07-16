# Native home screen + capture screen — decisions & spec (2026-07-16)

> Status: SPEC with approved mocks (`../mocks/2026-07-16-home-screen-mock.html`,
> `../mocks/2026-07-16-capture-mock.html`). Supersedes the launcher spec's L1/L4
> surface layout (the bar and context model stand; the main screen goes fully
> native). **Target device class: Pixel 9+** (decided — gates the on-device model).

## Decisions (2026-07-16)

| Question | Decision |
|---|---|
| On-device LLM | **Gemini Nano via ML Kit GenAI/AICore** (Pixel 9+); rules-based extraction fallback (dates, @words, names) so capture never blocks |
| Weather | **Open-Meteo** (free, keyless) + coarse location — Pixel has no public on-device weather API |
| Work calendar | Full work profile; **work agenda is pushed via the backend server** (no cross-profile access). Personal calendars read locally via CalendarProvider |
| Speech | `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE` (on-device, streaming partials) |
| Doc scan | Existing ML Kit document scanner (NOT Drive API — correction to earlier framing) |
| Capture routing | Tasks with a tree-node ("in") chip **skip the Inbox** and file directly under that node |

## Home screen (all native; the WebView appears on tile tap)

1. **Display boxes** (scrollable stat-tile row): Context picker (`@home ▾` dropdown),
   **Inbox** (unsorted count; tap → WebView at `/`), Today (due), Next (actions in
   context), Waiting. Selected tile = accent border; the tile selection drives what
   the WebView screen shows when opened.
2. **Hero**: clock (~72px, the view's one hero figure), date + weather (Open-Meteo)
   on one muted line. Taps: clock → system clock, date → calendar, weather → weather.
3. **Agenda** (the main surface): merged personal (CalendarProvider) + work
   (server-pushed) events. Identity = validated categorical pair on #13171f —
   Work `#3987e5`, Home `#199e70` (edge bar) + text chip (never color-alone);
   past events dimmed; accent "now" line.
   **Gap-fill**: between now and the next event, up to three doable tasks from the
   current context in a dashed block ("18 min until X — from @home"), tap-to-complete.
   Later: duration-aware suggestions.
4. Action bar as shipped (icons 27dp).

Dependencies this pulls in: **due dates** (Today tile), person field, and the
tag/move/organize UI gaps — one workstream. Work-agenda push needs a server
ingestion path (events as synced entities) — spec that with the backend piece.

## Capture screen (native, from the Capture slot)

- Opens **listening immediately** (on-device transcription, live partials dimmed);
  mode pills: Voice · Type · Photo (camera intent) · Scan (ML Kit).
- **Suggestion card** ("Suggested — on-device model"): improved title + dismissible
  chips — `context`, `due`, `person`, and **`in` (tree node)**. "+ add" opens
  pickers (context list / date / people / tree).
- **Routing rule**: with an `in` chip the primary button becomes "Save to <node>"
  and the task skips the Inbox; removing it reverts to "Save to Inbox".
  "Save raw" always available (verbatim, no enrichment).
- Privacy line: audio + transcript stay on device until op-log sync.
- Voice/scan/photo all still produce blob attachments exactly as today.

## Open follow-ups

- Server work-agenda ingestion (source on the work side + event entity/kind).
- Nano prompt + JSON contract for extraction; person/due-date fields in core
  vocabulary; tree/tag/due-date editing in :web detail view.
- Duration-aware gap suggestions (post-L5/operators).

# zync — Design Spec

**Date:** 2026-07-01 · **Status:** Draft for review · **Target device:** Pixel 9 (minSdk 34)

## 1. Purpose

zync is a personal Android app supporting ADHD executive function through GTD (Getting Things Done). It removes friction from the two hardest steps — *capture* and *clarify* — by automating them:

- Plug in a USB voice recorder → recordings auto-download, transcribe on-device, and land as tasks in the GTD Inbox.
- Scan physical documents → multi-page PDFs with OCR text land in the Inbox.
- Prompt-defined AI "operators" run a bounded agent loop over tasks to clean up, break down, and organize captured items.
- A recursive GTD task system (folders / projects / tasks / contexts) in embedded SQLite organizes everything.

## 2. Stack

- Kotlin, Jetpack Compose, single module, minSdk 34, manual DI (no framework).
- Room over SQLite for all persistence.
- WorkManager for transcription jobs and agent runs.
- VCS: jj (jujutsu, git backend).

### 2.1 Development workflow — Android CLI

Development uses the official **Android CLI** (developer.android.com/tools/agents/android-cli), the agent-oriented entry point to Android tooling:

- `android create` scaffolds the project from the `empty-activity-agp-9` template (M1 setup step).
- `android init` / `android skills add --agent=CLAUDE` installs the official Android skills so agent sessions follow current Android best practices.
- `android studio version-lookup` resolves current versions for AGP, Kotlin, Compose BOM, and libraries — no guessed dependency versions.
- `android emulator list|start|stop` manages a virtual device for development; USB/hardware flows still verified on the physical Pixel 9.
- `android run --apks=…` deploys builds; **Journeys** drives UI-level agent testing of the Inbox/clarify flows.
- `android update` run periodically to stay current.

**Setup prerequisite:** Android CLI is not yet installed on the dev machine — download from developer.android.com/tools/agents, then run `android update && android init`.

## 3. Data model (Room/SQLite)

### 3.1 Nodes — one recursive table

`node`: `id`, `kind` (`folder` | `project` | `task`), `parent_id` (nullable FK → node), `title`, `notes`, `status` (`active` | `done` | `dropped`; tasks/projects only), `defer_until` (nullable date), `created_at`, `completed_at`, `sort_order`, `ai_badge` (last agent_run id that touched it, nullable).

Seeded, undeletable rows: **Inbox** folder, **Someday** folder.

Nesting rules enforced in the repository layer (single choke-point, not schema):
- folder → root or folder
- project → folder
- task → project, task, or folder

Key operations are single-row updates: complete (`status`), move (`parent_id`), convert task→project (`kind` + re-parent). Completing a project marks it done; children remain queryable.

### 3.2 Contexts

`context`: `id`, `name`. Join table `node_context(node_id, context_id)`. A context view runs a recursive CTE from all roots and shows every matching active task regardless of depth ("filter over recursive tasks").

### 3.3 Attachments

`attachment`: `id`, `node_id`, `type` (`audio` | `transcript` | `pdf` | `ocr_text`), `file_path` into `Documents/Zync/`.

### 3.4 Import ledger

`import_ledger`: recorder file identity (`name`, `size`, `mtime`), import timestamp, status. Guarantees idempotent sync and safe resume after unplug.

### 3.5 Agent tables

- `operator`: `id`, `name`, `prompt`, `trigger_rules` (JSON: event `add|edit|move|geofence_enter|geofence_exit` + folder/project matcher or place reference, or manual-only), `apply_mode` (`auto` | `propose`), `enabled`, `max_iterations` (≤ 25).
- `place`: `id`, `name`, `lat`, `lng`, `radius_m` — user-defined locations for geofence triggers; optionally linked to a `context` (e.g. place "Supermarket" ↔ context "Errands").
- `agent_run`: `id`, `operator_id`, `trigger` info, `status` (`running|done|halted_limit|failed|reverted`), started/finished, token usage.
- `agent_mutation`: `id`, `run_id`, `seq`, `node_id`, `field`, `before` JSON, `after` JSON.

## 4. Storage & permissions

- Inbox files live in shared storage: `Documents/Zync/Inbox/<item>/` (audio + transcript.md, or scan.pdf + ocr.txt) — visible to file managers, Syncthing, USB transfer.
- **All Files Access** (`MANAGE_EXTERNAL_STORAGE`): personal sideloaded app; covers reading the mounted USB volume (`/storage/XXXX-XXXX`) and writing Documents without SAF friction.
- POST_NOTIFICATIONS, FOREGROUND_SERVICE_DATA_SYNC, USB host.
- `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION` — requested only when the user creates their first geofence-triggered operator (background location needs the two-step settings grant).

## 5. USB sync pipeline (M2)

The recorder presents as **USB Mass Storage**. Event-driven, no persistent service:

1. **Triggers** (manifest-declared, zero idle cost): activity intent-filter on `ACTION_USB_DEVICE_ATTACHED` with `device-filter.xml` matching the recorder's VID/PID; plus `ACTION_MEDIA_MOUNTED` receiver for volume-mount.
2. Trigger starts a short-lived **foreground service** (`dataSync` type) with progress notification.
3. Enumerate audio files (`wav/mp3/m4a/wma`) on the volume; diff against `import_ledger` (name+size+mtime).
4. Copy each new file to `Documents/Zync/Inbox/`, verify size.
5. **Delete from recorder immediately after verified copy** (audio is safe locally even if transcription later fails).
6. Create a **task in the Inbox folder** per recording (title = recording timestamp, editable) with the audio attached; enqueue transcription; ledger updated.
7. Service posts summary notification and exits. Unplug mid-sync → clean stop; ledger makes resume idempotent.

## 6. Transcription (M2)

- **Engine:** Moonshine Tiny (Useful Sensors) via `onnxruntime-android`; model files bundled in assets. English-only. Chosen over Android's built-in `SpeechRecognizer`, which is mic-only and cannot take files.
- **Decode:** `MediaExtractor` + `MediaCodec` → 16 kHz mono float PCM from any input format.
- **Segmentation:** energy-based VAD; ≤ 30 s chunks split on silence; transcripts stitched in order.
- **Execution:** WorkManager job per file (retries with backoff, survives app death). Task shows "transcribing…" chip; on completion the transcript goes into the task's `notes` and `transcript.md` attachment.
- Behind a `Transcriber` interface so the engine is swappable in one class.

## 7. Document scanning (M3)

- FAB on Inbox → **ML Kit Document Scanner** (Play-services UI — the same scanner behind Google Drive's scan feature): multi-page capture, edge detection, returns PDF + per-page JPEGs. No camera code or permission in-app.
- **ML Kit Text Recognition v2** OCRs the page JPEGs → extracted text.
- Result: Inbox task with `scan.pdf` + `ocr.txt` attachments; OCR text in `notes`. Symmetric with the audio pipeline.

## 8. UI (M1 + capture integration)

- **Main screen = Inbox folder**: newest-first task list — icon (mic/doc/text), title, date, duration/page count, two-line preview, status chips (transcribing…, ✨ AI-touched), scan FAB, quick-add text field.
- **Clarify actions** on each Inbox task (swipe/menu), mirroring GTD: **Do** (done) · **Move to project** (tree picker) · **Make a project** (kind flip + folder choice) · **Someday** · **Trash**.
- **Navigation** (drawer or bottom bar): Inbox · Someday · folder/project tree · Contexts · Run history · Settings.
- **Project view**: recursive task tree, collapsible subtasks, add-task inline.
- **Context view**: flat filtered list of active tasks across the whole tree.
- **Task detail**: editable title/notes, contexts, defer date, attachments (audio player bar; PDF opens via intent), AI-run badge with revert.
- Plug-in auto-launch lands on Inbox with a live sync banner.

## 9. Agent loop — operators (M4)

Modeled on nanocode/glaforge's minimal agent: a plain bounded `while` loop + typed tools.

- **LLM layer — hybrid** behind a `ChatBackend` interface:
  - *Cloud* (agent loop): OpenAI-compatible / Anthropic / Gemini endpoint, key + model in Settings. Real function calling.
  - *On-device* (single-shot helpers): Gemini Nano via ML Kit GenAI — title suggestions, summaries; also exposed to operators as a free `local_llm` tool.
- **Tools**, all funneled through one `AgentToolExecutor` over the node repository:
  - Read (subtree + ancestors + project + contexts): `get_task`, `list_children`, `get_ancestors`, `list_contexts`.
  - Write (the run's working set + descendants only — the triggering task for task-event runs, the scope-matched tasks for geofence runs): `update_task`, `create_subtask`, `create_sibling`, `set_contexts`, `move_task` (within the subtree's project). Deletes only of nodes created in the same run.
- **Triggering:** operators declare trigger rules (event + scope matcher) or manual-only; manual "Run operator…" on any task. Rapid edits debounced into one firing. **AI-originated mutations never fire triggers** (runs are tagged; dispatcher skips them) — cascades structurally impossible.
- **Geofence triggers:** operators may bind to `place` enter/exit events via the Play services Geofencing API (`GeofencingClient` + broadcast receiver — OS-managed, no polling, minimal battery). Since a geofence event has no triggering task, the operator's scope matcher (folder/project/context) selects the working set — e.g. entering "Supermarket" runs an operator over active tasks tagged "Errands" (surface as a notification, reorder, or propose a shopping checklist). Geofence-triggered runs count against the same global run limits; a per-place cooldown (default 30 min) prevents boundary-jitter refiring.
- **Hard limits (code-enforced):** ≤ 12 iterations/run default (per-operator override, ceiling 25) · ≤ 30 mutations/run · 60 s wall-clock/run · ≤ 10 runs/hour globally. Limit hit → halt, journal, notify.
- **Journal & undo:** every run logged (`agent_run` + ordered `agent_mutation` before/after).
  - `auto` apply-mode: changes land live, tasks badged ✨, one-tap **Revert run** replays mutations backward; if a human edited a field since, that mutation is skipped and flagged.
  - `propose` apply-mode: mutations render as a diff card on the task — Approve applies, Reject discards.
  - Run-history screen: all runs, status, token usage, revert.
- **Execution:** WorkManager jobs, network-constrained, serialized per subtree (no races with sync capture).
- **Built-in starter operator:** "Clarify capture" — on task added to Inbox: clean up transcript title, extract action items as subtasks, suggest contexts (propose mode by default).

## 10. ADHD executive-function layer (M5)

- **Focus view:** shows exactly one next action (filtered by context, defer date), with Do / Defer / Skip. No lists visible.
- **Tiny-first-step operator (built-in):** rewrites a vague task with a concrete ≤ 2-minute starter subtask.
- **Quick capture everywhere:** share-sheet target (any app → Inbox task), home-screen widget, quick-settings tile.
- **Defer/snooze:** "not today" hides a task until `defer_until`; resurfaces with a notification.
- **Guided weekly review:** wizard — empty Inbox → stale check → Someday scan → project-by-project next-action check.
- **Stale nudges:** gentle notification when Inbox items sit > N days (configurable).

## 10a. Backup (local-first)

- `Documents/Zync/` is the single self-contained data root: all attachments, plus DB snapshots. Attachment paths in the DB are stored **relative to this root**, so the folder is portable.
- A WorkManager job (daily + after each USB sync) runs SQLite `VACUUM INTO` — an atomic, consistent snapshot safe during use — writing `Documents/Zync/backup/zync-<date>.db`. Retention: last 14 dailies + monthly tail.
- Alongside it, a human-readable `backup/nodes.jsonl` export (nodes, contexts, attachments metadata) as a schema-independent escape hatch.
- Replication is delegated to any folder syncer (Syncthing → desktop/NAS). No cloud dependency, no server code.
- Restore: copy a snapshot over the app DB (import action in Settings), folder supplies the files.
- This is backup, not multi-device sync — one writing device. Multi-device is M8.

## 11. Later milestones (sketched, not designed)

- **M8 — Multi-device sync:** CRDT-based SQLite sync (e.g. cr-sqlite) or sync service (e.g. PowerSync). Hard problems: merging tree moves, agent-journal semantics across devices. The JSONL export keeps data portable until then.

- **M6 — Calendar sync:** Android `CalendarProvider` (device Google accounts, no OAuth). Time-specific tasks appear as calendar entries; a Today view merges calendar events with next actions. GTD's "sacred calendar" rule: only genuinely day-specific items.
- **M7 — Messages/WhatsApp capture:** WhatsApp has no public API. Paths: share-sheet (free from M5) and a `NotificationListenerService` capturing starred/flagged messages into Inbox. Revisit when reached.

## 12. Error handling

- Copy failure → file stays on recorder; retried next attach (ledger).
- Transcription failure → WorkManager backoff; task flagged with manual retry.
- Agent run failure/limit → halted, journaled, notified; partial mutations revertable.
- Unplug mid-sync → clean service stop; idempotent resume.

## 13. Milestones

| M | Scope |
|---|-------|
| M1 | Project setup via Android CLI (`android create`), then GTD core: node/context/attachment schema, repository + nesting rules, Inbox/folders/projects/contexts UI, clarify actions |
| M2 | USB sync + Moonshine transcription → Inbox capture |
| M3 | ML Kit doc scanner + OCR capture |
| M4 | Agent loop: operators, tools, limits, journal/undo, run history |
| M5 | ADHD layer: focus view, tiny-first-step, quick capture, defer/review/nudges |
| M5.5 | Backup: VACUUM INTO snapshots + JSONL export + restore action |
| M6 | Calendar sync (sketch) |
| M7 | Messages/WhatsApp capture (sketch) |
| M8 | Multi-device sync (sketch) |

## 14. Testing

- Unit: nesting-rule enforcement, recursive context CTE, task→project conversion, ledger diffing, VAD segmentation, agent limit enforcement, mutation revert (incl. conflict-skip), geofence trigger dispatch + cooldown.
- Instrumented: Moonshine inference on a fixture WAV; Room migrations.
- Agent loop tested against a scripted fake `ChatBackend` (deterministic tool-call sequences).
- UI journeys: Android CLI **Journeys** tests on an emulator for Inbox capture → clarify → project flows.
- Manual on Pixel 9: USB attach flow, scanner flow.

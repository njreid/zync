# Scanned-doc OCR (Drive API) + async summary — spec (2026-07-16)

> Status: SPEC (planning only). Extends the capture flow in
> `2026-07-16-native-home-and-capture.md`: a scan keeps landing instantly as an
> attachment; full OCR and a summary arrive asynchronously afterwards.

## Shape

```
scan (ML Kit) ──► attachment blob + ops        (instant, offline — unchanged)
      └─► OcrWorker (phone, async) ──► Drive API OCR ──► ocr text blob + ops
                                                └─► summarize operator (server, M8)
                                                        └─► summary field op
```

Two async legs, each owned by the side best placed for it:
- **OCR on the phone** — the Google account lives there (no server-held Google
  credentials), and WorkManager gives connectivity-gated retry for free.
- **Summary on the server** — the M8 operator runtime IS this machinery (trigger,
  idempotency, fuel, provenance), and long documents exceed Nano's context anyway.

## 1. Data model (core vocabulary)

On the attachment node:
- `ocrStatus`: `PENDING | RUNNING | DONE | FAILED` — device-owned field.
- `ocrBlobHash`: content-addressed **text blob** with the full OCR text (large —
  hundreds of KB for long docs — so it rides the existing blob pipeline, NOT a
  register value; blob-before-op push ordering already guarantees the server never
  sees the hash without the bytes).
- `summary`: operator-owned field (small; a register value), written with
  `actor=Operator("summarize")` provenance.

## 2. Phone: OcrWorker (WorkManager, connectivity-gated)

1. Enqueued by scan capture (and retroactively for scans missing `ocrStatus`).
2. Auth: Google account via Credential Manager / AccountManager with the
   **`drive.file` scope** (app-created files only — narrowest possible).
   First scan prompts the account picker once.
3. Pipeline: `files.create` the scan with target
   `mimeType=application/vnd.google-apps.document` (Drive performs OCR on
   conversion; set `ocrLanguage`) → `files.export text/plain` →
   **`files.delete` the transient Doc immediately** (nothing accumulates in Drive)
   → store OCR text in `LocalBlobStore` → emit `ocrBlobHash` + `ocrStatus=DONE`.
4. Failure: transient (network/5xx/auth-refresh) → WorkManager backoff;
   permanent (unsupported content, quota, revoked consent) → `ocrStatus=FAILED`
   (visible in the UI, re-triggerable).

## 3. Server: `summarize` operator (first real M8 operator after auto-clarify)

- **Trigger**: `ocrBlobHash` set on an attachment (ingest hook).
- **Read scope**: the attachment node + the OCR text blob (S3 fetch — the operator
  runtime gains a blob-read capability; size-capped, e.g. first ~100KB of text).
- **Output**: typed `{ summary: string }` (validated + bounded retries) →
  `summary` field op, operator-owned, provenance-tagged. A title improvement, if
  any, is a **proposed** object (human-owned field rule).
- Idempotent per `operator_run` (attachment id + ocr blob hash as input version);
  fuel-capped like every operator. Disabled without `ANTHROPIC_API_KEY`, as usual.

## 4. UI

- Attachment rows: subtle status — "OCR pending…" / "FAILED · retry" chip.
- Detail/reading view: **summary block** (labeled as operator-written) above the
  attachment; full OCR text readable + findable later (search will index it).
- Capture screen scan mode: footnote "full text + summary arrive after sync."

## 5. Privacy & consent

- Document content transits Google (Drive conversion) — one-time consent screen at
  first scan explains this; the transient Doc is deleted in the same worker run.
- OCR text + summary sync to the zync server like all content (trusted-server
  model). `drive.file` scope keeps zync out of the rest of the user's Drive.

## 6. Testing

- OcrWorker against a fake Drive transport (create/export/delete recorded; delete
  asserted even on export failure); status transitions; retroactive enqueue.
- Operator end-to-end against the fake LLM (mirrors AutoClarifyEndToEndTest) with
  a fake blob reader; idempotency on re-ingest; size-cap behavior.
- Ordering: OCR blob uploads before its ops (existing ReplicaSynchronizer tests
  extend naturally).

## Sequencing

Fits after the capture screen lands (build order #3), sharing its scan surface;
the operator half can land independently once M8 operators are enabled in prod.
Nano-on-device summaries for short docs are a possible later offline enhancement.

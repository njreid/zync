package dev.njr.zync.server.operator

import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.operator.FieldType
import dev.njr.zync.core.operator.Fuel
import dev.njr.zync.core.operator.OperatorManifest
import dev.njr.zync.core.operator.OutputSchema
import dev.njr.zync.core.operator.ReadScopeHandle
import dev.njr.zync.core.operator.TriggerKind
import dev.njr.zync.core.operator.WriteScope
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.streams.asSequence

/**
 * Manifest loading: the built-in reference operator plus optional JSON
 * manifests from `ZYNC_OPERATORS_DIR` (one `OperatorManifest` per `*.json`
 * file — declarative only; a loaded manifest must reference a read scope the
 * runtime already knows).
 */
object OperatorManifests {
    /**
     * The M8 reference operator: when a task lands in the inbox (root, no
     * tags, still ACTIVE), draft a one-line clarification. It owns only the
     * operator fields `summary`/`suggestedContext` — never human fields — and
     * fires once per task ([TriggerKind.EntityEntersScope]).
     */
    fun autoClarifyInbox(): OperatorManifest = OperatorManifest(
        id = "auto-clarify-inbox",
        name = "Auto-clarify inbox",
        readScope = ReadScopeHandle(ReadScopes.INBOX_TASK_REF),
        writeScope = WriteScope(fields = setOf("summary", "suggestedContext")),
        trigger = TriggerKind.EntityEntersScope,
        output = OutputSchema(
            fields = mapOf(
                "summary" to FieldType.String,
                "suggestedContext" to FieldType.String,
            ),
            required = setOf("summary"),
        ),
        retries = 2,
        fuel = Fuel(maxOpsPerFiring = 2, maxOpsPerCascade = 16),
    )

    /**
     * The first real content operator (spec 2026-07-16-scan-ocr-summary §3):
     * when a scanned/photo document's OCR text lands (`ocrBlobHash` set), read
     * the OCR text and write a one-paragraph `summary`. Owns only the operator
     * field `summary`; re-fires on a re-scan ([TriggerKind.EntityChangesInScope]).
     */
    fun summarize(): OperatorManifest = OperatorManifest(
        id = "summarize",
        name = "Summarize document",
        readScope = ReadScopeHandle(ReadScopes.SCANNED_DOC_REF),
        writeScope = WriteScope(fields = setOf(Fields.SUMMARY)),
        trigger = TriggerKind.EntityChangesInScope,
        output = OutputSchema(
            fields = mapOf(Fields.SUMMARY to FieldType.String),
            required = setOf(Fields.SUMMARY),
        ),
        retries = 2,
        fuel = Fuel(maxOpsPerFiring = 1, maxOpsPerCascade = 16),
    )

    /**
     * Suggest up to 3 file locations for an inbox item (GTD triage §6). Deterministic
     * keyword retrieval (via a [SuggestFileCompletionSource]); no LLM. Re-fires on text
     * edits. Owns only `fileSuggestions`; accepting a chip is the human Move.
     */
    fun suggestFileLocations(): OperatorManifest = OperatorManifest(
        id = "suggest-file",
        name = "Suggest file locations",
        readScope = ReadScopeHandle(ReadScopes.INBOX_TRIAGE_REF),
        writeScope = WriteScope(fields = setOf(Fields.FILE_SUGGESTIONS)),
        trigger = TriggerKind.EntityChangesInScope,
        output = OutputSchema(
            fields = mapOf(Fields.FILE_SUGGESTIONS to FieldType.Array),
            required = setOf(Fields.FILE_SUGGESTIONS), // empty array is valid (nothing cleared the floor)
        ),
        retries = 0,
        fuel = Fuel(maxOpsPerFiring = 1, maxOpsPerCascade = 8),
    )

    /**
     * When a task is marked DONE, propose a Reference-area filing parent (GTD triage §7,
     * RESOLVED Q5 = operator proposes, human accepts). Owns only `proposedFileParent`.
     */
    fun autoFileDone(): OperatorManifest = OperatorManifest(
        id = "auto-file-done",
        name = "File completed task to Reference",
        readScope = ReadScopeHandle(ReadScopes.DONE_TASK_REF),
        writeScope = WriteScope(fields = setOf(Fields.PROPOSED_FILE_PARENT)),
        trigger = TriggerKind.EntityChangesInScope,
        output = OutputSchema(
            fields = mapOf(Fields.PROPOSED_FILE_PARENT to FieldType.String),
            required = emptySet(), // absent = below floor, no proposal
        ),
        retries = 0,
        fuel = Fuel(maxOpsPerFiring = 1, maxOpsPerCascade = 8),
    )

    /** The retrieval operators that need no LLM — registered even without ANTHROPIC_API_KEY. */
    fun retrievalOnly(): List<OperatorManifest> = listOf(suggestFileLocations(), autoFileDone())

    /** Parse every `*.json` file in [dir] as an [OperatorManifest]. */
    fun load(dir: Path, json: Json = Json): List<OperatorManifest> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.asSequence()
                .filter { it.extension == "json" }
                .sorted()
                .map { json.decodeFromString(OperatorManifest.serializer(), Files.readString(it)) }
                .toList()
        }
    }

    /** Built-ins plus any manifests configured via `ZYNC_OPERATORS_DIR`. */
    fun fromEnv(env: (String) -> String? = System::getenv, json: Json = Json): List<OperatorManifest> {
        val extra = env("ZYNC_OPERATORS_DIR")?.let { load(Path.of(it), json) }.orEmpty()
        return listOf(autoClarifyInbox(), summarize()) + retrievalOnly() + extra
    }
}

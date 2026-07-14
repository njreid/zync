package dev.njr.zync.server.operator

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
        return listOf(autoClarifyInbox()) + extra
    }
}

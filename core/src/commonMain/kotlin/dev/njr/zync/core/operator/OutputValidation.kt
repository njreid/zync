package dev.njr.zync.core.operator

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** The primitive/container kinds an [OutputSchema] field may require. */
@Serializable
enum class FieldType { String, Number, Boolean, Object, Array }

/**
 * A slim, typed schema for an operator's LLM result — a flat set of fields with
 * declared types and a required subset. Enough to reject malformed LLM output
 * before it becomes ops; full JSON Schema is out of scope for `core`.
 */
@Serializable
data class OutputSchema(
    val fields: Map<String, FieldType>,
    val required: Set<String> = fields.keys,
) {
    fun validate(result: JsonElement): ValidationResult {
        if (result !is JsonObject) return ValidationResult.Invalid(listOf("expected a JSON object"))
        val errors = mutableListOf<String>()
        for (field in required) {
            if (field !in result) errors += "missing required field '$field'"
        }
        for ((field, type) in fields) {
            val element = result[field] ?: continue // absence already flagged by `required`
            if (!typeMatches(type, element)) errors += "field '$field' expected $type"
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }
}

private fun typeMatches(type: FieldType, element: JsonElement): Boolean = when (type) {
    FieldType.String -> element is JsonPrimitive && element.isString
    FieldType.Number -> element is JsonPrimitive && !element.isString && element.doubleOrNull != null
    FieldType.Boolean -> element is JsonPrimitive && !element.isString && element.booleanOrNull != null
    FieldType.Object -> element is JsonObject
    FieldType.Array -> element is JsonArray
}

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val errors: List<String>) : ValidationResult
}

/** Outcome of running an operator's attempts against its schema within its retry budget. */
sealed interface OperatorOutcome {
    data class Accepted(val value: JsonElement, val attemptsUsed: Int) : OperatorOutcome
    data class Rejected(val attemptsUsed: Int, val lastErrors: List<String>) : OperatorOutcome
}

/**
 * Pure retry-count semantics (spec §7: "retries: N attempts on schema-validation
 * failure — the ONLY control flow"). Validates [attempts] in order against this
 * schema, accepting the first that passes; a total of `retries + 1` attempts is
 * allowed (the initial call plus N retries). No I/O — the caller supplies the LLM
 * results; this only decides accept/reject and how many attempts were consumed.
 */
fun OutputSchema.evaluate(attempts: List<JsonElement>, retries: Int): OperatorOutcome {
    val maxAttempts = retries + 1
    var used = 0
    var lastErrors = emptyList<String>()
    for (attempt in attempts) {
        if (used >= maxAttempts) break
        used++
        when (val result = validate(attempt)) {
            is ValidationResult.Valid -> return OperatorOutcome.Accepted(attempt, used)
            is ValidationResult.Invalid -> lastErrors = result.errors
        }
    }
    return OperatorOutcome.Rejected(used, lastErrors)
}

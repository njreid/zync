package dev.njr.zync.core.operator

import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OperatorTest {
    private val schema = OutputSchema(
        fields = mapOf("summary" to FieldType.String, "confidence" to FieldType.Number, "urgent" to FieldType.Boolean),
        required = setOf("summary"),
    )

    private fun obj(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(build)

    @Test
    fun validOutputPasses() {
        val result = schema.validate(obj { put("summary", JsonPrimitive("done")); put("confidence", JsonPrimitive(0.9)) })
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun missingRequiredFieldFails() {
        val result = schema.validate(obj { put("confidence", JsonPrimitive(0.9)) })
        val invalid = assertIs<ValidationResult.Invalid>(result)
        assertTrue(invalid.errors.any { "summary" in it })
    }

    @Test
    fun wrongTypeFails() {
        val result = schema.validate(obj { put("summary", JsonPrimitive("ok")); put("confidence", JsonPrimitive("high")) })
        val invalid = assertIs<ValidationResult.Invalid>(result)
        assertTrue(invalid.errors.any { "confidence" in it })
    }

    @Test
    fun nonObjectFails() {
        assertIs<ValidationResult.Invalid>(schema.validate(JsonPrimitive("not an object")))
    }

    @Test
    fun evaluateAcceptsFirstValidWithinBudget() {
        val bad = obj { put("confidence", JsonPrimitive(1)) } // missing summary
        val good = obj { put("summary", JsonPrimitive("ok")) }
        val outcome = schema.evaluate(listOf(bad, good), retries = 2)
        val accepted = assertIs<OperatorOutcome.Accepted>(outcome)
        assertEquals(2, accepted.attemptsUsed)
    }

    @Test
    fun evaluateRejectsWhenBudgetExhausted() {
        val bad = obj { put("confidence", JsonPrimitive(1)) }
        val outcome = schema.evaluate(listOf(bad, bad, bad, bad), retries = 1) // only 2 attempts allowed
        val rejected = assertIs<OperatorOutcome.Rejected>(outcome)
        assertEquals(2, rejected.attemptsUsed)
        assertTrue(rejected.lastErrors.isNotEmpty())
    }

    @Test
    fun writeScopeEnforcesFieldOwnership() {
        // V4 guard: an inbox-clarify operator owns `summary`, never the human `title`.
        val scope = WriteScope(fields = setOf("summary"))
        assertTrue(scope.permitsField("summary"))
        assertFalse(scope.permitsField("title"))

        val hlc = Hlc(13, 0, "srv")
        val t = id(1)
        val ownEmit = Op.SetField(id(2), t, EntityType.Node, hlc, Actor.Operator("clarify"), "srv", 13, "summary", JsonPrimitive("Y"))
        val humanFieldEmit = Op.SetField(id(3), t, EntityType.Node, hlc, Actor.Operator("clarify"), "srv", 13, "title", JsonPrimitive("hijack"))
        assertTrue(scope.permits(ownEmit))
        assertFalse(scope.permits(humanFieldEmit))
    }

    @Test
    fun manifestRoundTrips() {
        val manifest = OperatorManifest(
            id = "inbox-clarify",
            name = "Inbox Clarifier",
            readScope = ReadScopeHandle("kind=task AND parent=INBOX AND tags=empty"),
            writeScope = WriteScope(fields = setOf("summary", "suggestedContext"), childTypes = setOf(EntityType.Node)),
            trigger = TriggerKind.EntityEntersScope,
            output = schema,
            retries = 2,
            fuel = Fuel(maxOpsPerFiring = 3, maxOpsPerCascade = 20),
        )
        val json = Json.encodeToString(OperatorManifest.serializer(), manifest)
        assertEquals(manifest, Json.decodeFromString(OperatorManifest.serializer(), json))
    }
}

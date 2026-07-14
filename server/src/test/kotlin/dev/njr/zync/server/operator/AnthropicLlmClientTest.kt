package dev.njr.zync.server.operator

import dev.njr.zync.core.operator.FieldType
import dev.njr.zync.core.operator.OutputSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The pure halves of the Anthropic client: request building + response mapping. */
class AnthropicLlmClientTest {
    private val schema = OutputSchema(
        fields = mapOf("summary" to FieldType.String, "score" to FieldType.Number),
        required = setOf("summary"),
    )
    private val request = LlmRequest("clarify", system = "sys", user = "usr", schema = schema)

    @Test
    fun request_body_has_the_messages_api_shape() {
        val body = AnthropicLlmClient.requestBody(request, model = "claude-sonnet-5")

        assertEquals(JsonPrimitive("claude-sonnet-5"), body["model"])
        assertEquals(JsonPrimitive(4096), body["max_tokens"])
        assertEquals(JsonPrimitive("sys"), body["system"])
        val message = (body["messages"] as JsonArray).single().jsonObject
        assertEquals(JsonPrimitive("user"), message["role"])
        assertEquals(JsonPrimitive("usr"), message["content"])

        val format = body["output_config"]!!.jsonObject["format"]!!.jsonObject
        assertEquals(JsonPrimitive("json_schema"), format["type"])
        val jsonSchema = format["schema"]!!.jsonObject
        assertEquals(JsonPrimitive("object"), jsonSchema["type"])
        assertEquals(JsonPrimitive(false), jsonSchema["additionalProperties"])
        val properties = jsonSchema["properties"]!!.jsonObject
        assertEquals(JsonPrimitive("string"), properties["summary"]!!.jsonObject["type"])
        assertEquals(JsonPrimitive("number"), properties["score"]!!.jsonObject["type"])
        assertEquals(listOf("summary"), (jsonSchema["required"] as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test
    fun text_content_block_becomes_text_reply() {
        val reply = client().parseResponse(
            200,
            """{"content":[{"type":"text","text":"{\"summary\":\"ok\"}"}],"stop_reason":"end_turn"}""",
        )
        assertEquals(LlmReply.Text("""{"summary":"ok"}"""), reply)
    }

    @Test
    fun thinking_blocks_are_skipped() {
        val reply = client().parseResponse(
            200,
            """{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"{}"}],"stop_reason":"end_turn"}""",
        )
        assertEquals(LlmReply.Text("{}"), reply)
    }

    @Test
    fun refusal_stop_reason_maps_to_refusal() {
        val reply = client().parseResponse(
            200,
            """{"content":[],"stop_reason":"refusal","stop_details":{"type":"refusal","explanation":"nope"}}""",
        )
        assertEquals(LlmReply.Refusal("nope"), reply)
    }

    @Test
    fun http_errors_map_to_unavailable() {
        val overloaded = client().parseResponse(529, """{"type":"error","error":{"type":"overloaded_error"}}""")
        assertIs<LlmReply.Unavailable>(overloaded)
        assertTrue(overloaded.message.contains("529"))

        assertIs<LlmReply.Unavailable>(client().parseResponse(200, "not json"))
        assertIs<LlmReply.Unavailable>(client().parseResponse(200, """{"content":[]}"""))
    }

    @Test
    fun from_env_reads_key_and_model_and_disables_without_a_key() {
        assertEquals(null, AnthropicLlmClient.fromEnv { null })
        assertEquals(null, AnthropicLlmClient.fromEnv { key -> if (key == "ANTHROPIC_API_KEY") "" else null })
        assertTrue(AnthropicLlmClient.fromEnv { key -> if (key == "ANTHROPIC_API_KEY") "sk-test" else null } != null)
    }

    private fun client() = AnthropicLlmClient(apiKey = "sk-test")
}

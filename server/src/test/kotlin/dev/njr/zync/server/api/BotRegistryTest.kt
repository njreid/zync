package dev.njr.zync.server.api

import dev.njr.zync.core.api.BotCapabilities
import dev.njr.zync.core.api.OpEnvelope
import dev.njr.zync.core.api.OpIntent
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.id
import dev.njr.zync.server.sync.SyncService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bot registry (step 4): `bot add` CLI, token auth, and capability enforcement. */
class BotRegistryTest {
    @Test
    fun addedBotAuthenticatesWithItsCapabilities() {
        val db = JvmZyncDatabase.inMemory()
        val lines = mutableListOf<String>()
        BotCommand.run(db, listOf("add", "Scraper", "--propose", "--verbs", "create,comment"), now = 1L, out = { lines += it })
        val token = lines.first { it.startsWith("token: ") }.removePrefix("token: ")

        val bot = SqlBotRegistry(db).authenticate(token)!!
        assertEquals("propose", bot.capabilities.mode)
        assertEquals(setOf("create", "comment"), bot.capabilities.verbs)
        assertNull(SqlBotRegistry(db).authenticate("not-the-token"))
    }

    @Test
    fun revokedBotIsRejected() {
        val db = JvmZyncDatabase.inMemory()
        val lines = mutableListOf<String>()
        BotCommand.run(db, listOf("add", "Temp"), now = 1L, out = { lines += it })
        val token = lines.first { it.startsWith("token: ") }.removePrefix("token: ")
        val id = lines.first { it.startsWith("registered bot") }.substringAfter("id=").substringBefore(",")
        assertTrue(SqlBotRegistry(db).authenticate(token) != null)
        db.botQueries.revokeBot(id)
        assertNull(SqlBotRegistry(db).authenticate(token))
    }

    @Test
    fun capabilityRejectsDisallowedVerbAndField() {
        val service = SyncService(JvmZyncDatabase.inMemory())
        val api = ExternalOpApi(service)
        // Only allowed to create, and only set the notes field.
        val bot = BotIdentity("scoped", BotCapabilities(verbs = setOf("create", "setField"), fields = setOf("notes")))

        val badVerb = api.submit(bot, OpEnvelope(intents = listOf(OpIntent(op = "comment", target = id(1).toString(), text = "x"))))
        assertEquals("error", badVerb.results.single().status)

        val badField = api.submit(bot, OpEnvelope(intents = listOf(OpIntent(op = "setField", target = id(1).toString(), field = "dueDate", value = kotlinx.serialization.json.JsonPrimitive(1)))))
        assertEquals("error", badField.results.single().status)

        // An allowed verb+field commits.
        val ok = api.submit(bot, OpEnvelope(intents = listOf(OpIntent(op = "create", title = "ok"))))
        assertEquals("committed", ok.results.single().status)
    }
}

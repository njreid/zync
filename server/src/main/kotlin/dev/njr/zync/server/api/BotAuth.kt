package dev.njr.zync.server.api

import dev.njr.zync.core.api.BotCapabilities
import dev.njr.zync.data.db.ZyncDatabase
import dev.njr.zync.server.auth.constantTimeEquals
import dev.njr.zync.server.sha256Hex
import kotlinx.serialization.json.Json

/** An authenticated external bot (external-op-api spec §2) with its capability grant. */
data class BotIdentity(val id: String, val capabilities: BotCapabilities) {
    val commits: Boolean get() = capabilities.mode == "commit"
}

/** Resolves a bearer token to a bot identity, or null if unknown/invalid/revoked. */
fun interface BotAuth {
    fun authenticate(token: String): BotIdentity?
}

/**
 * Single-token env fallback (spec §2): `ZYNC_BOT_TOKEN` grants one commit-capable bot with
 * full capabilities (id from `ZYNC_BOT_ID`, default "bot"). Unset ⇒ no bots. Composed with
 * [SqlBotRegistry] via [BotAuth.plus] so both can be active.
 */
class EnvBotAuth(private val token: String?, private val botId: String = "bot") : BotAuth {
    override fun authenticate(token: String): BotIdentity? {
        val expected = this.token?.takeIf { it.isNotBlank() } ?: return null
        if (!constantTimeEquals(expected, token)) return null
        return BotIdentity(botId, BotCapabilities())
    }

    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): EnvBotAuth =
            EnvBotAuth(env("ZYNC_BOT_TOKEN"), env("ZYNC_BOT_ID") ?: "bot")
    }
}

/**
 * The persisted bot registry (spec §2, step 4): looks a bearer token up by its sha256 hash
 * and returns the bot's parsed capabilities. Revoked bots are rejected.
 */
class SqlBotRegistry(private val db: ZyncDatabase, private val json: Json = Json) : BotAuth {
    override fun authenticate(token: String): BotIdentity? {
        val row = db.botQueries.botBySecretHash(sha256Hex(token.encodeToByteArray())).executeAsOneOrNull() ?: return null
        if (row.revoked != 0L) return null
        val caps = runCatching { json.decodeFromString(BotCapabilities.serializer(), row.capabilities) }
            .getOrElse { return null }
        return BotIdentity(row.id, caps)
    }
}

/** Try this auth first, then [next] — so the env token and the registry can both be active. */
operator fun BotAuth.plus(next: BotAuth): BotAuth = BotAuth { token -> authenticate(token) ?: next.authenticate(token) }

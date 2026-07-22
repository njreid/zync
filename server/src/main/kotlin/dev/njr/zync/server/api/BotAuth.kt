package dev.njr.zync.server.api

import dev.njr.zync.server.auth.constantTimeEquals

/** An authenticated external bot (external-op-api spec §2). [commit] = may write live (vs propose-only). */
data class BotIdentity(val id: String, val commit: Boolean = true)

/** Resolves a bearer token to a bot identity, or null if unknown/invalid. */
fun interface BotAuth {
    fun authenticate(token: String): BotIdentity?
}

/**
 * Single-token env fallback (spec §2): `ZYNC_BOT_TOKEN` grants one commit-capable bot
 * (id from `ZYNC_BOT_ID`, default "bot"). Unset ⇒ no bots (the op API is closed). The bot
 * registry (step 4) generalizes this to many scoped identities.
 */
class EnvBotAuth(private val token: String?, private val botId: String = "bot") : BotAuth {
    override fun authenticate(token: String): BotIdentity? {
        val expected = this.token?.takeIf { it.isNotBlank() } ?: return null
        if (!constantTimeEquals(expected, token)) return null
        return BotIdentity(botId, commit = true)
    }

    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): EnvBotAuth =
            EnvBotAuth(env("ZYNC_BOT_TOKEN"), env("ZYNC_BOT_ID") ?: "bot")
    }
}

package dev.njr.zync.server.api

import dev.njr.zync.core.api.BotCapabilities
import dev.njr.zync.data.db.ZyncDatabase
import dev.njr.zync.server.sha256Hex
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64

/**
 * `server bot add <name> [--propose] [--verbs a,b,c] [--fields x,y]` — register an external
 * bot (external-op-api §2). Mints a bearer token (shown ONCE), stores only its hash + the
 * capability grant, and prints the token for the operator to hand to the bot.
 */
object BotCommand {
    fun run(db: ZyncDatabase, args: List<String>, now: Long, json: Json = Json, out: (String) -> Unit = ::println) {
        if (args.getOrNull(0) != "add" || args.getOrNull(1) == null) {
            out("usage: server bot add <name> [--propose] [--verbs a,b,c] [--fields x,y]")
            return
        }
        val name = args[1]
        val rest = args.drop(2)
        val caps = BotCapabilities(
            verbs = flag(rest, "--verbs")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: BotCapabilities.ALL_VERBS,
            mode = if ("--propose" in rest) "propose" else "commit",
            fields = flag(rest, "--fields")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet(),
        )
        val token = randomToken()
        val id = slug(name) + "-" + randomToken().take(6).lowercase()
        db.botQueries.upsertBot(
            id = id,
            name = name,
            secret_hash = sha256Hex(token.encodeToByteArray()),
            capabilities = json.encodeToString(BotCapabilities.serializer(), caps),
            created_wall = now,
        )
        out("registered bot '$name' (id=$id, mode=${caps.mode})")
        out("token: $token")
        out("(store this now — only its hash is kept; it cannot be recovered)")
    }

    private fun flag(args: List<String>, name: String): String? {
        val i = args.indexOf(name)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    private fun slug(s: String) = s.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-').ifEmpty { "bot" }

    private fun randomToken(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

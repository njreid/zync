package dev.njr.zync.net

/**
 * The zync Android app's Cloudflare Access Service Token (see
 * docs/superpowers/plans/2026-09-04-shared-host-cloudflare-zero-trust.md Task 5) — proves
 * "this is the zync app" to Cloudflare's edge on the non-interactive paths (/sync,
 * /pair, /agenda). It is NOT per-user identity; [dev.njr.zync.replica.DeviceSigner]'s
 * Ed25519 signature remains the actual device/user identity underneath it.
 */
class CfAccessCredentials private constructor(clientId: String, clientSecret: String) {
    val headers: Map<String, String> = mapOf(
        "CF-Access-Client-Id" to clientId,
        "CF-Access-Client-Secret" to clientSecret,
    )

    companion object {
        fun from(clientId: String, clientSecret: String): CfAccessCredentials? {
            if (clientId.isBlank() || clientSecret.isBlank()) return null
            return CfAccessCredentials(clientId, clientSecret)
        }
    }
}

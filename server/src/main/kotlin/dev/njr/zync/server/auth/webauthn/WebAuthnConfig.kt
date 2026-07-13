package dev.njr.zync.server.auth.webauthn

/**
 * WebAuthn relying-party configuration. Single-user: one stable [userHandle]. The [rpId]
 * is the registrable domain (e.g. `zync.example.com`, or `localhost` in dev); [origin] is
 * the full scheme+host the browser reports (e.g. `https://zync.example.com`). Both are
 * verified against every ceremony, so they must match the deployment exactly.
 */
data class WebAuthnConfig(
    val rpId: String,
    val rpName: String,
    val origin: String,
    val userHandle: ByteArray,
    val userName: String,
    val userDisplayName: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is WebAuthnConfig && rpId == other.rpId && origin == other.origin &&
            userHandle.contentEquals(other.userHandle) && userName == other.userName)

    override fun hashCode(): Int = rpId.hashCode() * 31 + origin.hashCode()
}

package dev.njr.zync.server.auth.webauthn

import dev.njr.zync.data.db.ZyncDatabase

/**
 * Persistent store of registered passkeys, backed by the `webauthn_credential` table.
 * The COSE public key is held as webauthn4j's serialized attested-credential-data; the
 * sign counter is bumped on each accepted assertion.
 */
class WebauthnCredentialStore(private val db: ZyncDatabase) {

    data class Record(
        val credentialId: String,
        val userHandle: String,
        val attestedCredentialData: String, // base64
        val signCount: Long,
    )

    fun save(record: Record, createdAt: Long) {
        db.webauthnCredentialQueries.insertCredential(
            record.credentialId, record.userHandle, record.attestedCredentialData, record.signCount, createdAt,
        )
    }

    fun get(credentialId: String): Record? =
        db.webauthnCredentialQueries.getCredential(credentialId).executeAsOneOrNull()?.let {
            Record(it.credential_id, it.user_handle, it.attested_credential_data, it.sign_count)
        }

    fun all(): List<Record> =
        db.webauthnCredentialQueries.allCredentials().executeAsList().map {
            Record(it.credential_id, it.user_handle, it.attested_credential_data, it.sign_count)
        }

    fun updateSignCount(credentialId: String, signCount: Long) {
        db.webauthnCredentialQueries.updateSignCount(signCount, credentialId)
    }

    fun count(): Long = db.webauthnCredentialQueries.countCredentials().executeAsOne()
}

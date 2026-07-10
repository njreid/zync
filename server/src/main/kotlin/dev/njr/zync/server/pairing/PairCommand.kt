package dev.njr.zync.server.pairing

import dev.njr.zync.data.db.ZyncDatabase
import dev.njr.zync.server.auth.SqlDeviceRegistry

/**
 * The `zync pair` operator command: mints a one-time code (DB-backed, shared with the
 * running server), and prints a QR the phone scans. The QR carries the server address
 * + public key (to find and pin the server) + the code + expiry.
 */
object PairCommand {
    fun run(db: ZyncDatabase, identity: ServerIdentity, publicAddress: String, now: Long, out: (String) -> Unit = ::println) {
        val manager = PairingManager(db, SqlDeviceRegistry(db))
        val code = manager.open(now)
        val expiresAt = now + PairingManager.DEFAULT_TTL
        val uri = pairingUri(publicAddress, identity.publicKeyBase64, code, expiresAt)

        out("")
        out(Qr.ascii(uri))
        out("Scan with the zync app, or enter manually:")
        out("  server:  $publicAddress")
        out("  code:    $code")
        out("  expires: in ${PairingManager.DEFAULT_TTL / 60000} min")
        out("")
    }

    /** Compact pairing payload encoded in the QR (values URL-encoded — base64 may contain +//=). */
    fun pairingUri(address: String, serverPublicKeyBase64: String, code: String, expiresAt: Long): String {
        fun enc(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8)
        return "zync://pair?h=${enc(address)}&k=${enc(serverPublicKeyBase64)}&c=${enc(code)}&e=$expiresAt"
    }
}

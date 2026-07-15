package dev.njr.zync.server.pairing

import dev.njr.zync.core.pairing.PairRequest
import dev.njr.zync.core.pairing.PairResponse
import dev.njr.zync.core.pairing.pairingConfirmationMessage
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Bundles the pairing endpoint's dependencies for wiring into the app. [publicAddress]
 * (ZYNC_PUBLIC_ADDR) enables the browser pairing page; null hides it.
 */
class PairingEndpoint(val manager: PairingManager, val identity: ServerIdentity, val publicAddress: String? = null)

/**
 * `POST /pair` — UNAUTHENTICATED but gated by the one-time [PairRequest.code] (shown
 * only in the operator's `zync pair` QR). Registers the device's key and returns a
 * server-signed confirmation.
 */
@OptIn(ExperimentalEncodingApi::class)
fun Route.pairingRoutes(
    manager: PairingManager,
    identity: ServerIdentity,
    now: () -> Long = System::currentTimeMillis,
    publicAddress: String? = null,
) {
    // Browser pairing page. Deliberately NOT under /pair (that prefix is exempt from
    // the web session gate for the device POST) — /settings/* is session-gated, so
    // only an authenticated browser can mint a pairing code here.
    if (publicAddress != null) {
        get("/settings/pairing") {
            val at = now()
            val code = manager.open(at)
            val uri = PairCommand.pairingUri(publicAddress, identity.publicKeyBase64, code, at + PairingManager.DEFAULT_TTL)
            call.respondText(pairingPageHtml(uri), io.ktor.http.ContentType.Text.Html)
        }
    }

    post("/pair") {
        val request = call.receive<PairRequest>()
        val publicKey = try {
            Base64.decode(request.devicePublicKey)
        } catch (_: Exception) {
            call.respondText("malformed device public key", status = HttpStatusCode.BadRequest)
            return@post
        }
        when (val result = manager.redeem(request.code, publicKey, request.replicaId, now())) {
            is PairingResult.Paired -> {
                val confirmation = identity.sign(pairingConfirmationMessage(result.deviceId, request.devicePublicKey))
                call.respond(PairResponse(result.deviceId, identity.publicKeyBase64, Base64.encode(confirmation)))
            }
            is PairingResult.Rejected ->
                call.respondText(result.reason, status = HttpStatusCode.Unauthorized)
        }
    }
}

/** The session-gated pairing page: scan the QR with the phone camera, or tap the link on-device. */
internal fun pairingPageHtml(pairingUri: String): String = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>zync — pair a phone</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 26rem; margin: 3rem auto; padding: 0 1rem; }
    svg { width: 100%; max-width: 20rem; display: block; margin: 1rem 0; }
    code { word-break: break-all; font-size: .8rem; display: block; margin: 1rem 0; color: #555; }
    .muted { color: #666; }
  </style>
</head>
<body>
  <h1>Pair a phone</h1>
  <p>Scan with the phone's camera, or open this page on the phone and tap the link.</p>
  ${Qr.svg(pairingUri)}
  <p><a href="$pairingUri">Pair this device</a></p>
  <code>$pairingUri</code>
  <p class="muted">The code is single-use and expires in 2 minutes — reload for a fresh one.</p>
</body>
</html>
""".trimIndent()

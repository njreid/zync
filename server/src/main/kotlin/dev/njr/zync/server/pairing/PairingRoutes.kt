package dev.njr.zync.server.pairing

import dev.njr.zync.core.pairing.PairRequest
import dev.njr.zync.core.pairing.PairResponse
import dev.njr.zync.core.pairing.pairingConfirmationMessage
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Bundles the pairing endpoint's dependencies for wiring into the app. */
class PairingEndpoint(val manager: PairingManager, val identity: ServerIdentity)

/**
 * `POST /pair` — UNAUTHENTICATED but gated by the one-time [PairRequest.code] (shown
 * only in the operator's `zync pair` QR). Registers the device's key and returns a
 * server-signed confirmation.
 */
@OptIn(ExperimentalEncodingApi::class)
fun Route.pairingRoutes(manager: PairingManager, identity: ServerIdentity, now: () -> Long = System::currentTimeMillis) {
    post("/pair") {
        val request = call.receive<PairRequest>()
        val publicKey = try {
            Base64.decode(request.devicePublicKey)
        } catch (_: Exception) {
            call.respondText("malformed device public key", status = HttpStatusCode.BadRequest)
            return@post
        }
        when (val result = manager.redeem(request.code, publicKey, now())) {
            is PairingResult.Paired -> {
                val confirmation = identity.sign(pairingConfirmationMessage(result.deviceId, request.devicePublicKey))
                call.respond(PairResponse(result.deviceId, identity.publicKeyBase64, Base64.encode(confirmation)))
            }
            is PairingResult.Rejected ->
                call.respondText(result.reason, status = HttpStatusCode.Unauthorized)
        }
    }
}

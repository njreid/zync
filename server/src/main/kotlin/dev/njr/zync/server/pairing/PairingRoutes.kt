package dev.njr.zync.server.pairing

import dev.njr.zync.core.pairing.PairRequest
import dev.njr.zync.core.pairing.PairResponse
import dev.njr.zync.core.pairing.pairingConfirmationMessage
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.queryString
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
            // The QR carries an https handoff URL, NOT the zync:// URI: camera apps
            // only act on http(s) QR codes, so scanning opens /pair/open in the phone
            // browser, whose button fires the custom scheme with a user gesture.
            val openUrl = "$publicAddress/pair/open?${uri.substringAfter('?')}"
            call.respondText(pairingPageHtml(uri, openUrl), io.ktor.http.ContentType.Text.Html)
        }

        // Camera-scan landing page (session-EXEMPT under /pair — the one-time code in
        // the query IS the credential, exactly as in the QR; it is single-use and
        // expires in 2 minutes). Renders the app-handoff button.
        get("/pair/open") {
            val qs = call.request.queryString()
            val params = io.ktor.http.parseQueryString(qs)
            if (listOf("h", "k", "c", "e").any { params[it].isNullOrBlank() }) {
                call.respondText("missing pairing parameters", status = HttpStatusCode.BadRequest)
                return@get
            }
            call.respondText(pairOpenPageHtml("zync://pair?$qs"), io.ktor.http.ContentType.Text.Html)
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
internal fun pairingPageHtml(pairingUri: String, openUrl: String): String = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>zync — pair a phone</title>
  <style>
    :root { color-scheme: dark; }
    body { font-family: system-ui, sans-serif; max-width: 26rem; margin: 3rem auto; padding: 0 1rem;
           background: #13171f; color: #c2c7d0; }
    a { color: #79c0ff; }
    svg { width: 100%; max-width: 20rem; display: block; margin: 1rem 0; border-radius: .4rem; }
    code { word-break: break-all; font-size: .8rem; display: block; margin: 1rem 0; color: #8a91a0; }
    .muted { color: #8a91a0; }
    a.button { display: block; text-align: center; font-size: 1.1rem; padding: .9rem 2rem; margin: 1rem 0;
               background: #0172ad; color: #fff; border-radius: .5rem; text-decoration: none; }
  </style>
</head>
<body>
  <h1>Pair a phone</h1>
  <a class="button" href="$pairingUri">Pair this phone</a>
  <p class="muted">Reading this on another device? Scan with the phone's camera instead — it opens
     a page with an "Open zync" button.</p>
  ${Qr.svg(openUrl)}
  <code>$openUrl</code>
  <p class="muted">The code is single-use and expires in 2 minutes — reload for a fresh one.</p>
</body>
</html>
""".trimIndent()

/** The camera-scan landing page: hands the invite off to the app via the custom scheme. */
internal fun pairOpenPageHtml(pairingUri: String): String = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>zync — open the app</title>
  <style>
    :root { color-scheme: dark; }
    body { font-family: system-ui, sans-serif; max-width: 26rem; margin: 4rem auto; padding: 0 1rem;
           text-align: center; background: #13171f; color: #c2c7d0; }
    a.button { display: inline-block; font-size: 1.2rem; padding: 1rem 2rem; background: #0172ad; color: #fff;
               border-radius: .5rem; text-decoration: none; margin: 1.5rem 0; }
    .muted { color: #8a91a0; font-size: .9rem; }
  </style>
</head>
<body>
  <h1>zync</h1>
  <p>Tap to pair this phone with the server.</p>
  <a class="button" href="$pairingUri">Open zync</a>
  <p class="muted">Nothing happens? Install the zync app first, then reload this page
     (the code expires after 2 minutes — get a fresh QR from Settings → Pairing).</p>
  <script>
    // Best-effort auto-handoff; browsers may require the tap, which is the button above.
    setTimeout(() => { location.href = ${'"'}$pairingUri${'"'}; }, 300);
  </script>
</body>
</html>
""".trimIndent()

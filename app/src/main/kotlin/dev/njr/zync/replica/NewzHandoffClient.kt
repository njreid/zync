package dev.njr.zync.replica

import dev.njr.zync.core.integrations.NewzHandoffResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json

/**
 * Mints a one-time newz handoff URL using the device's signed-request identity
 * (spec: ../newz/zync-integration-spec.md). Called immediately before opening the
 * newz WebView; the returned URL contains a single-use token that expires in ≤60s
 * and must never be logged, bridged to JS, or handed to another app.
 */
suspend fun mintNewzHandoff(
    http: HttpClient,
    paired: PairedServer,
    now: () -> Long,
    nonce: () -> String,
    json: Json = Json { ignoreUnknownKeys = true },
): NewzHandoffResponse {
    val signer = Ed25519DeviceSigner(paired.deviceId, paired.deviceSeed)
    val path = "/integrations/newz/handoff"
    val response = http.post("${paired.address}$path") {
        signedHeaders(signer, "POST", path, now(), nonce()).forEach { (k, v) -> header(k, v) }
    }
    response.requireOk("newz handoff")
    val body = json.decodeFromString(NewzHandoffResponse.serializer(), response.bodyAsText())
    // The paired server is authenticated and chooses this short-lived destination.
    // Newz may use its own host, but must always be HTTPS and the fixed handoff path.
    val destination = runCatching { java.net.URI(body.handoffUrl) }.getOrNull()
    require(destination?.scheme == "https" && destination.path == "/newz/handoff" && !destination.query.isNullOrEmpty()) {
        "unexpected handoff destination"
    }
    return body
}

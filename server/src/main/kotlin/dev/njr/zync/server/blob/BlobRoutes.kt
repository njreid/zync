package dev.njr.zync.server.blob

import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.auth.requireAuth
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class BlobKeyResponse(val key: String)

/**
 * Blob upload/download, server-mediated (clients never touch S3). Auth-guarded.
 * Upload computes the content-addressed key from the body; download validates the
 * key shape before hitting the store (traversal-safe).
 */
fun Route.blobRoutes(service: BlobService, auth: ServerAuth) {
    post("/blob") {
        if (!call.requireAuth(auth.authenticator)) return@post
        val bytes = call.receive<ByteArray>()
        try {
            call.respond(BlobKeyResponse(service.store(bytes)))
        } catch (e: BlobTooLargeException) {
            call.respondText(e.message ?: "payload too large", status = HttpStatusCode.PayloadTooLarge)
        }
    }
    get("/blob/{key}") {
        if (!call.requireAuth(auth.authenticator)) return@get
        val bytes = service.fetch(call.parameters["key"].orEmpty())
        if (bytes == null) call.respondText("not found", status = HttpStatusCode.NotFound)
        else call.respondBytes(bytes)
    }
}

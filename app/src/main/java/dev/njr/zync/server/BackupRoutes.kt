package dev.njr.zync.server

import dev.njr.zync.backup.BackupController
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class BackupSettingsDto(
    val enabled: Boolean,
    val hasPassphrase: Boolean,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
    val lastError: String?,
    val lastBackupName: String?,
    val restorePending: Boolean,
)

@Serializable
data class BackupConfigBody(
    val enabled: Boolean,
    val passphrase: String? = null,
)

fun Route.backupRoutes(backupController: BackupController) {
    route("/backup") {
        get("/state") {
            if (!requireBackupLoopbackConnector()) return@get
            call.respond(backupController.state().toDto())
        }

        post("/config") {
            if (!requireBackupLoopbackConnector()) return@post
            val body = call.receive<BackupConfigBody>()
            try {
                backupController.configure(body.enabled, body.passphrase)
                call.respond(backupController.state().toDto())
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto(e.message ?: "invalid backup settings"))
            }
        }

        post("/now") {
            if (!requireBackupLoopbackConnector()) return@post
            call.respond(withContext(Dispatchers.IO) { backupController.backupNow().toDto() })
        }

        post("/restore-next-launch") {
            if (!requireBackupLoopbackConnector()) return@post
            try {
                call.respond(backupController.requestRestore().toDto())
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto(e.message ?: "invalid restore settings"))
            }
        }
    }
}

private suspend fun RoutingContext.requireBackupLoopbackConnector(): Boolean {
    val connector = AuthGuard.classify(call.request.local.scheme)
    if (connector == AuthGuard.Connector.LAN) {
        call.respond(HttpStatusCode.Forbidden)
        return false
    }
    return true
}

private fun dev.njr.zync.backup.BackupState.toDto(): BackupSettingsDto =
    BackupSettingsDto(
        enabled = enabled,
        hasPassphrase = hasPassphrase,
        lastSuccessAt = lastSuccessAt,
        lastFailureAt = lastFailureAt,
        lastError = lastError,
        lastBackupName = lastBackupName,
        restorePending = restorePending,
    )

package dev.njr.zync.replica

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream

/**
 * Production [DriveOcr]: OCR a document by round-tripping it through Google Drive
 * (spec 2026-07-16-scan-ocr-summary §2.3) — `files.create` converting to a Google
 * Doc (Drive OCRs on conversion), `files.export text/plain`, then `files.delete`
 * the transient Doc immediately (nothing accumulates in Drive). Auth is a
 * `drive.file`-scoped token from the Identity Authorization API — the narrowest
 * scope (app-created files only).
 *
 * DEVICE-VERIFIED: the Google account picker/consent and live Drive calls need a
 * real device with a signed-in account; unit tests use a fake transport. First-run
 * consent must be granted from an Activity (a worker cannot show it) — until then
 * this reports a permanent "needs consent" failure and the scan is marked FAILED
 * (re-triggerable once consent is granted).
 */
class GoogleDriveOcr(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val httpFactory: () -> HttpClient = { HttpClient(OkHttp) },
) : DriveOcr {

    override suspend fun ocr(bytes: ByteArray, sourceMime: String): String {
        val token = accessToken()
        val http = httpFactory()
        try {
            val docId = createDoc(http, token, bytes, sourceMime)
            try {
                return exportText(http, token, docId)
            } finally {
                // Best-effort delete — always attempted, even if export failed.
                runCatching { http.delete("$DRIVE/files/$docId") { bearer(token) } }
            }
        } finally {
            http.close()
        }
    }

    private suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        val result = try {
            Tasks.await(Identity.getAuthorizationClient(context).authorize(request))
        } catch (e: Exception) {
            throw DriveOcrException(permanent = false, "Drive authorization failed: ${e.message}", e)
        }
        if (result.hasResolution()) {
            throw DriveOcrException(permanent = true, "Drive access needs one-time consent")
        }
        result.accessToken ?: throw DriveOcrException(permanent = false, "no Drive access token")
    }

    private suspend fun createDoc(http: HttpClient, token: String, bytes: ByteArray, sourceMimeRaw: String): String {
        // Strip CR/LF so the mime can't inject extra multipart headers into the body.
        val sourceMime = sourceMimeRaw.filterNot { it == '\r' || it == '\n' }
        val boundary = "zync-ocr-boundary"
        val metadata = """{"name":"zync-ocr","mimeType":"application/vnd.google-apps.document"}"""
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n".toByteArray())
            write("--$boundary\r\nContent-Type: $sourceMime\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()

        val response = http.post("$UPLOAD/files?uploadType=multipart&fields=id&ocrLanguage=en") {
            bearer(token)
            contentType(ContentType("multipart", "related").withParameter("boundary", boundary))
            setBody(body)
        }
        failIfError(response, "files.create")
        val id = json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]?.jsonPrimitive?.content
        return id ?: throw DriveOcrException(permanent = false, "files.create returned no id")
    }

    private suspend fun exportText(http: HttpClient, token: String, docId: String): String {
        val response = http.get("$DRIVE/files/$docId/export?mimeType=text/plain") { bearer(token) }
        failIfError(response, "files.export")
        return response.bodyAsText()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) =
        header(HttpHeaders.Authorization, "Bearer $token")

    private suspend fun failIfError(response: HttpResponse, what: String) {
        if (response.status.isSuccess()) return
        val permanent = when (response.status) {
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> true
            HttpStatusCode.RequestTimeout, HttpStatusCode.TooManyRequests -> false
            else -> response.status.value in 400..499
        }
        throw DriveOcrException(permanent, "$what failed: HTTP ${response.status.value}")
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299

    private companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val DRIVE = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    }
}

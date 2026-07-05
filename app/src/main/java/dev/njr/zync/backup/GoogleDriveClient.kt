package dev.njr.zync.backup

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class GoogleDriveClient(
    private val context: Context,
    private val tokenProvider: suspend () -> String = { context.driveAppDataAccessToken() },
    private val baseUrl: String = "https://www.googleapis.com",
) : DriveClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun uploadBackup(name: String, bytes: ByteArray) {
        val boundary = "zync-${System.nanoTime()}"
        val metadata = """{"name":"$name","parents":["appDataFolder"]}"""
        val body = ByteArrayOutputStream().use { out ->
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            out.write(metadata.toByteArray(Charsets.UTF_8))
            out.write("\r\n--$boundary\r\n".toByteArray())
            out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
            out.write(bytes)
            out.write("\r\n--$boundary--\r\n".toByteArray())
            out.toByteArray()
        }

        request(
            url = "$baseUrl/upload/drive/v3/files?uploadType=multipart",
            method = "POST",
            contentType = "multipart/related; boundary=$boundary",
            body = body,
        )
        pruneOldBackups()
    }

    override suspend fun latestBackup(): ByteArray? {
        val file = listBackups(pageSize = 1).firstOrNull() ?: return null
        return request("$baseUrl/drive/v3/files/${file.id}?alt=media", "GET")
    }

    private suspend fun pruneOldBackups(keep: Int = KEEP_SNAPSHOTS) {
        val backups = listBackups(pageSize = keep + 20)
        for (file in backups.drop(keep)) {
            request("$baseUrl/drive/v3/files/${file.id}", "DELETE")
        }
    }

    private suspend fun listBackups(pageSize: Int): List<DriveFile> {
        val query = "'appDataFolder' in parents and name contains 'zync-' and name contains '.zyncbackup'"
        val url = "$baseUrl/drive/v3/files" +
            "?spaces=appDataFolder" +
            "&pageSize=$pageSize" +
            "&orderBy=modifiedTime%20desc" +
            "&fields=files(id,name,modifiedTime)" +
            "&q=${URLEncoder.encode(query, Charsets.UTF_8.name())}"
        val listing = request(url, "GET")
        return json.decodeFromString(DriveFileList.serializer(), listing.toString(Charsets.UTF_8)).files
    }

    private suspend fun request(
        url: String,
        method: String,
        contentType: String? = null,
        body: ByteArray? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer ${tokenProvider()}")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) error("Drive request failed ($code): ${bytes.toString(Charsets.UTF_8).take(240)}")
            bytes
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun Context.driveAppDataAccessToken(): String = withContext(Dispatchers.IO) {
    val account: Account = GoogleSignIn.getLastSignedInAccount(this@driveAppDataAccessToken)?.account
        ?: error("Google Drive is not connected")
    try {
        GoogleAuthUtil.getToken(this@driveAppDataAccessToken, account, "oauth2:$DRIVE_APPDATA_SCOPE")
    } catch (e: UserRecoverableAuthException) {
        throw IllegalStateException("Google Drive authorization needs user approval", e)
    } catch (e: GoogleAuthException) {
        throw IOException("Google authorization failed", e)
    }
}

const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

private const val KEEP_SNAPSHOTS = 5

@Serializable
private data class DriveFileList(val files: List<DriveFile> = emptyList())

@Serializable
private data class DriveFile(val id: String, val name: String = "", val modifiedTime: String = "")

package dev.njr.zync.backup

interface DriveClient {
    suspend fun uploadBackup(name: String, bytes: ByteArray)
    suspend fun latestBackup(): ByteArray?
}

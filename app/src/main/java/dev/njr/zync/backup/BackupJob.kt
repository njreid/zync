package dev.njr.zync.backup

import dev.njr.zync.data.ZyncDatabase
import java.io.File

/**
 * One end-to-end backup run: take a consistent [DbSnapshot] of the database,
 * gather every attachment row, and hand both to [SnapshotBackup] to write a new
 * encrypted, content-addressed snapshot. Framework-free (no WorkManager/Android
 * service types) so the whole flow is unit-testable against a real file-backed
 * Room DB + an in-memory store; the [BackupWorker] just calls [run].
 */
class BackupJob(
    private val db: ZyncDatabase,
    private val dbFile: File,
    private val attachmentsRoot: File,
    private val backup: SnapshotBackup,
    private val workDir: File,
) {
    /** Run a backup, returning the new snapshot id. */
    suspend fun run(): String {
        workDir.mkdirs()
        val snapshotFile = File(workDir, "zync-snapshot-${System.nanoTime()}.db")
        try {
            DbSnapshot.snapshot(db, dbFile, snapshotFile)
            val attachments = db.nodeDao().allAttachments()
            return backup.backUp(snapshotFile, attachmentsRoot, attachments)
        } finally {
            snapshotFile.delete()
        }
    }
}

package dev.njr.zync.backup

import dev.njr.zync.data.ZyncDatabase
import java.io.File

/**
 * Produces a consistent on-disk snapshot of the Room database for backup.
 *
 * Room runs in WAL mode, so recent writes may live in the `-wal` sidecar rather
 * than the main `.db` file. We `wal_checkpoint(TRUNCATE)` first to fold the WAL
 * back into the main file (and empty the sidecar), then copy that file — so the
 * copy is a complete, self-contained database, never a torn read of a live WAL.
 */
object DbSnapshot {

    /**
     * Checkpoint [db] and copy its file [dbFile] to [target], returning [target].
     */
    fun snapshot(db: ZyncDatabase, dbFile: File, target: File): File {
        db.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { it.moveToFirst() }
        target.parentFile?.mkdirs()
        dbFile.copyTo(target, overwrite = true)
        return target
    }
}

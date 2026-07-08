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
     * Fold the WAL back into the main database file on [db]'s own connection.
     *
     * `wal_checkpoint(TRUNCATE)` writes all committed WAL frames into the main
     * `.db` file and empties the `-wal` sidecar. Because it runs on Room's live
     * connection (not a second, uncoordinated one), the main file is left
     * complete and self-consistent; in WAL mode later writes go to a fresh
     * `-wal`, never the main file — so a copy of the main file taken right after
     * is a valid snapshot without the live sidecars.
     */
    fun checkpointForBackup(db: ZyncDatabase) {
        db.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { it.moveToFirst() }
    }

    /**
     * Checkpoint [db] and copy its file [dbFile] to [target], returning [target].
     */
    fun snapshot(db: ZyncDatabase, dbFile: File, target: File): File {
        checkpointForBackup(db)
        target.parentFile?.mkdirs()
        dbFile.copyTo(target, overwrite = true)
        return target
    }
}

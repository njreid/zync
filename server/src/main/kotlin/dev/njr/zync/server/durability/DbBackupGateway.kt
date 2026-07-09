package dev.njr.zync.server.durability

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.data.db.ZyncDatabase
import java.io.File

/**
 * Boot-time durability hook. In prod this is litestream: restore the SQLite file
 * from S3 if the box is fresh, then let `litestream replicate` continuously stream
 * changes back. Abstracted so the boot sequence is testable without the binary.
 */
interface DbBackupGateway {
    /** If the local DB is absent, restore it from the replica; return true if restored. */
    fun restoreIfAbsent(dbPath: String): Boolean

    /** Capture a snapshot of the current DB (pre-migration rollback point). */
    fun snapshot(dbPath: String)

    /** No durability (dev/tests without a replica). */
    object None : DbBackupGateway {
        override fun restoreIfAbsent(dbPath: String): Boolean = false
        override fun snapshot(dbPath: String) = Unit
    }
}

/**
 * Server boot: restore-from-replica-if-fresh → snapshot the restored DB (so a bad
 * migration can be rolled back to this point) → open + migrate. Returns the ready DB.
 */
object StartupSequence {
    fun open(dbPath: String, gateway: DbBackupGateway = DbBackupGateway.None): ZyncDatabase {
        gateway.restoreIfAbsent(dbPath)
        if (File(dbPath).exists()) gateway.snapshot(dbPath)
        return JvmZyncDatabase.open(JdbcSqliteDriver("jdbc:sqlite:$dbPath"))
    }
}

/**
 * Prod [DbBackupGateway] backed by the litestream CLI (PID-1 supervisor in the
 * container, per the deployment spec). `restoreIfAbsent` shells out to
 * `litestream restore`; continuous replication (and thus snapshots/generations) is
 * handled by the `litestream replicate` process wrapping the app.
 */
class LitestreamCli(private val replicaUrl: String) : DbBackupGateway {
    override fun restoreIfAbsent(dbPath: String): Boolean {
        if (File(dbPath).exists()) return false
        exec("litestream", "restore", "-if-replica-exists", "-o", dbPath, replicaUrl)
        return File(dbPath).exists()
    }

    override fun snapshot(dbPath: String) {
        // litestream replicates continuously and retains generations; the boot-time
        // rollback point is the replica's current generation. No one-shot command needed.
    }

    private fun exec(vararg command: String) {
        val exit = ProcessBuilder(*command).inheritIO().start().waitFor()
        check(exit == 0) { "command failed ($exit): ${command.joinToString(" ")}" }
    }
}

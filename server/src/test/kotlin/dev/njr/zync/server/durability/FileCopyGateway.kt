package dev.njr.zync.server.durability

import java.io.File

/**
 * Test stand-in for litestream: snapshots the SQLite file (+ WAL sidecars) into a
 * "remote" directory as numbered generations, and restores them. Exercises the same
 * property litestream relies on — a SQLite file snapshot restores to identical state —
 * plus prior-generation recovery, without the binary or MinIO.
 */
class FileCopyGateway(private val remoteRoot: File) : DbBackupGateway {
    private val sidecars = listOf("", "-wal", "-shm")
    var generation: Int = 0
        private set

    override fun restoreIfAbsent(dbPath: String): Boolean {
        if (File(dbPath).exists()) return false
        return restoreGeneration(generation, dbPath)
    }

    override fun snapshot(dbPath: String) {
        if (generation == 0 && !File(dbPath).exists()) return
        generation += 1
        val dir = genDir(generation).apply { mkdirs() }
        sidecars.forEach { suffix ->
            val src = File(dbPath + suffix)
            if (src.exists()) src.copyTo(File(dir, "db$suffix"), overwrite = true)
        }
    }

    /** Restore a specific generation (bad-migration recovery). Returns true if it existed. */
    fun restoreGeneration(gen: Int, dbPath: String): Boolean {
        if (gen <= 0 || !genDir(gen).exists()) return false
        sidecars.forEach { suffix -> File(dbPath + suffix).delete() }
        sidecars.forEach { suffix ->
            val src = File(genDir(gen), "db$suffix")
            if (src.exists()) src.copyTo(File(dbPath + suffix), overwrite = true)
        }
        return true
    }

    private fun genDir(gen: Int) = File(remoteRoot, "gen-$gen")
}

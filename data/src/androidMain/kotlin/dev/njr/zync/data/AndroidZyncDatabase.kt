package dev.njr.zync.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.njr.zync.data.db.ZyncDatabase

/**
 * Android (phone) factory for [ZyncDatabase]. `AndroidSqliteDriver` runs schema
 * create/migrate through its own callback, so this mirrors [JvmZyncDatabase] on the
 * server side. Pass `name = null` for an in-memory database (tests).
 */
object AndroidZyncDatabase {
    fun create(context: Context, name: String? = "zync.db"): ZyncDatabase {
        if (name != null) purgeLegacyRoomDatabase(context, name)
        return ZyncDatabase(AndroidSqliteDriver(ZyncDatabase.Schema, context, name))
    }

    /**
     * The retired v0.2 app's Room database shared this exact filename under the same
     * applicationId, so upgrading v0.2 → the rebuilt app hands SQLDelight a Room file:
     * wrong `user_version`, none of the op-log tables, and every query 500s the
     * loopback UI. The rebuild starts fresh by design (roadmap: no v0.2 import) —
     * delete any pre-oplog file before opening. A real op-log database (has `op_log`)
     * is never touched.
     */
    private fun purgeLegacyRoomDatabase(context: Context, name: String) {
        val file = context.getDatabasePath(name)
        if (!file.exists()) return
        val isOpLog = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'op_log'",
                    null,
                ).use { it.moveToFirst() }
            }
        }.getOrDefault(false) // unopenable/corrupt counts as legacy: it can't be used anyway
        if (!isOpLog) context.deleteDatabase(name)
    }
}

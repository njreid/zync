package dev.njr.zync.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.njr.zync.data.db.ZyncDatabase

/**
 * Android (phone) factory for [ZyncDatabase]. `AndroidSqliteDriver` runs schema
 * create/migrate through its own callback, so this mirrors [JvmZyncDatabase] on the
 * server side. Pass `name = null` for an in-memory database (tests).
 */
object AndroidZyncDatabase {
    fun create(context: Context, name: String? = "zync.db"): ZyncDatabase =
        ZyncDatabase(AndroidSqliteDriver(ZyncDatabase.Schema, context, name))
}

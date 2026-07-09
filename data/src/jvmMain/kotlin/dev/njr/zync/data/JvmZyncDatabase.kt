package dev.njr.zync.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.njr.zync.data.db.ZyncDatabase

/**
 * JVM (server) factory for [ZyncDatabase] with a minimal migration harness — the
 * discipline mirrors the app's Room migrations: the schema version is tracked in
 * SQLite's `PRAGMA user_version`, a fresh DB is created at the current version, and
 * an older DB is migrated forward. When the schema changes, bump
 * `ZyncDatabase.Schema.version`, add the `.sqm` migration, and add a v(N-1)→vN test.
 */
object JvmZyncDatabase {
    /** Ephemeral in-memory database (tests, throwaway server runs). */
    fun inMemory(): ZyncDatabase = open(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

    /** File-backed database at [path]; created on first open, migrated on later opens. */
    fun file(path: String): ZyncDatabase = open(JdbcSqliteDriver("jdbc:sqlite:$path"))

    fun open(driver: SqlDriver): ZyncDatabase {
        migrateIfNeeded(driver)
        return ZyncDatabase(driver)
    }

    private fun migrateIfNeeded(driver: SqlDriver) {
        val current = userVersion(driver)
        val target = ZyncDatabase.Schema.version
        when {
            current == 0L -> {
                ZyncDatabase.Schema.create(driver)
                setUserVersion(driver, target)
            }
            current < target -> {
                ZyncDatabase.Schema.migrate(driver, current, target)
                setUserVersion(driver, target)
            }
        }
    }

    private fun userVersion(driver: SqlDriver): Long =
        driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            val hasRow = cursor.next().value
            QueryResult.Value(if (hasRow) cursor.getLong(0) ?: 0L else 0L)
        }, 0).value

    private fun setUserVersion(driver: SqlDriver, version: Long) {
        driver.execute(null, "PRAGMA user_version = $version", 0)
    }
}

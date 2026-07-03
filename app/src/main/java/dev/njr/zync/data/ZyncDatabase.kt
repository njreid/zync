package dev.njr.zync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NodeEntity::class, ContextEntity::class, NodeContextCrossRef::class, AttachmentEntity::class, AllowedDeviceEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class ZyncDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
    abstract fun contextDao(): ContextDao
    abstract fun allowedDeviceDao(): AllowedDeviceDao

    companion object {
        const val INBOX_ID = 1L
        const val SOMEDAY_ID = 2L

        private val seedCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                db.execSQL(
                    "INSERT INTO node (id, kind, parentId, title, notes, status, deferUntil, createdAt, completedAt, sortOrder, builtin) " +
                    "VALUES ($INBOX_ID, 'FOLDER', NULL, 'Inbox', '', 'ACTIVE', NULL, $now, NULL, 0, 1), " +
                    "($SOMEDAY_ID, 'FOLDER', NULL, 'Someday', '', 'ACTIVE', NULL, $now, NULL, 1, 1)"
                )
            }
        }

        fun build(context: Context): ZyncDatabase =
            Room.databaseBuilder(context, ZyncDatabase::class.java, "zync.db")
                .addCallback(seedCallback)
                .addMigrations(Migration_1_2)
                .build()

        fun inMemory(context: Context): ZyncDatabase =
            Room.inMemoryDatabaseBuilder(context, ZyncDatabase::class.java)
                .addCallback(seedCallback)
                .allowMainThreadQueries()
                .build()
    }
}

val Migration_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `allowed_device` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, `pubkey` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, " +
            "`lastSeen` INTEGER, `revoked` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_allowed_device_pubkey` ON `allowed_device` (`pubkey`)"
        )
    }
}

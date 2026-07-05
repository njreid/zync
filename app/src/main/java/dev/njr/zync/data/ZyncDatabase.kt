package dev.njr.zync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NodeEntity::class, ContextEntity::class, NodeContextCrossRef::class, AttachmentEntity::class, AllowedDeviceEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class ZyncDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
    abstract fun contextDao(): ContextDao
    abstract fun attachmentDao(): AttachmentDao
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
                .addMigrations(Migration_1_2, Migration_2_3)
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

val Migration_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `attachment_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`nodeId` INTEGER NOT NULL, `type` TEXT NOT NULL, `relativePath` TEXT NOT NULL, " +
                "FOREIGN KEY(`nodeId`) REFERENCES `node`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "INSERT INTO `attachment_new` (`id`, `nodeId`, `type`, `relativePath`) " +
                "SELECT a.`id`, a.`nodeId`, a.`type`, a.`relativePath` " +
                "FROM `attachment` a INNER JOIN `node` n ON n.`id` = a.`nodeId`"
        )
        db.execSQL("DROP TABLE `attachment`")
        db.execSQL("ALTER TABLE `attachment_new` RENAME TO `attachment`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_attachment_nodeId` ON `attachment` (`nodeId`)"
        )
    }
}

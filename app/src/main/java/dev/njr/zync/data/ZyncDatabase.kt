package dev.njr.zync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The legacy Room database. Since M7 (op-log cutover) it holds ONLY the LAN-pairing
 * allow-list ([AllowedDeviceEntity]); all content (nodes/contexts/attachments) moved to
 * the op-log store ([dev.njr.zync.data.db.ZyncDatabase] via SQLDelight). This class and
 * its `allowed_device` table survive until the LAN stack is retired (M7 Task 4).
 */
@Database(
    entities = [AllowedDeviceEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class ZyncDatabase : RoomDatabase() {
    abstract fun allowedDeviceDao(): AllowedDeviceDao

    companion object {
        fun build(context: Context): ZyncDatabase =
            Room.databaseBuilder(context, ZyncDatabase::class.java, "zync.db")
                .addMigrations(Migration_1_2, Migration_2_3, Migration_3_4)
                .build()

        fun inMemory(context: Context): ZyncDatabase =
            Room.inMemoryDatabaseBuilder(context, ZyncDatabase::class.java)
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

/** M7 op-log cutover: content now lives in the op-log store; drop the retired Room content tables. */
val Migration_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `attachment`")
        db.execSQL("DROP TABLE IF EXISTS `node_context`")
        db.execSQL("DROP TABLE IF EXISTS `context`")
        db.execSQL("DROP TABLE IF EXISTS `node`")
    }
}

package dev.njr.zync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NodeEntity::class, ContextEntity::class, NodeContextCrossRef::class, AttachmentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ZyncDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
    abstract fun contextDao(): ContextDao

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
                .build()

        fun inMemory(context: Context): ZyncDatabase =
            Room.inMemoryDatabaseBuilder(context, ZyncDatabase::class.java)
                .addCallback(seedCallback)
                .allowMainThreadQueries()
                .build()
    }
}

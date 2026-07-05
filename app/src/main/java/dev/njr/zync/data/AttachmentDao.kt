package dev.njr.zync.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insert(attachment: AttachmentEntity): Long

    @Query("SELECT * FROM attachment WHERE id = :id")
    suspend fun getById(id: Long): AttachmentEntity?

    @Query("SELECT * FROM attachment WHERE nodeId = :nodeId ORDER BY id")
    suspend fun forNode(nodeId: Long): List<AttachmentEntity>

    @Delete
    suspend fun delete(attachment: AttachmentEntity)

    @Query("DELETE FROM attachment WHERE nodeId = :nodeId")
    suspend fun deleteForNode(nodeId: Long)
}

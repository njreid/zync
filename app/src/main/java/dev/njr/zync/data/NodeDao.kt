package dev.njr.zync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NodeDao {
    @Insert
    suspend fun insert(node: NodeEntity): Long

    @Update
    suspend fun update(node: NodeEntity)

    @Query("SELECT * FROM node WHERE id = :id")
    suspend fun getById(id: Long): NodeEntity?

    @Query("SELECT * FROM node WHERE parentId = :parentId AND status != 'DROPPED' ORDER BY sortOrder, createdAt DESC")
    suspend fun childrenOf(parentId: Long): List<NodeEntity>

    @Query("SELECT * FROM node WHERE parentId = :parentId AND status != 'DROPPED' ORDER BY sortOrder, createdAt DESC")
    fun observeChildren(parentId: Long): Flow<List<NodeEntity>>

    @Query("SELECT * FROM node WHERE parentId IS NULL AND status != 'DROPPED' ORDER BY builtin DESC, sortOrder, title")
    fun observeRoots(): Flow<List<NodeEntity>>

    @Insert
    suspend fun insertAttachment(attachment: AttachmentEntity): Long

    @Query("SELECT * FROM attachment WHERE nodeId = :nodeId")
    suspend fun attachmentsFor(nodeId: Long): List<AttachmentEntity>
}

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

    /** Every attachment across all nodes — used by the backup snapshotter. */
    @Query("SELECT * FROM attachment")
    suspend fun allAttachments(): List<AttachmentEntity>

    @Query(
        """
        WITH RECURSIVE tagged(id) AS (
            SELECT nodeId FROM node_context WHERE contextId = :contextId
            UNION
            SELECT n.id FROM node n JOIN tagged t ON n.parentId = t.id
        )
        SELECT * FROM node
        WHERE id IN (SELECT id FROM tagged)
          AND kind = 'TASK'
          AND status = 'ACTIVE'
          AND (deferUntil IS NULL OR deferUntil <= :now)
        ORDER BY createdAt DESC
        """
    )
    fun observeTasksInContext(contextId: Long, now: Long): Flow<List<NodeEntity>>

    @Query("SELECT * FROM node WHERE kind IN ('FOLDER','PROJECT') AND status = 'ACTIVE' ORDER BY kind, title")
    fun observeDestinations(): Flow<List<NodeEntity>>
}

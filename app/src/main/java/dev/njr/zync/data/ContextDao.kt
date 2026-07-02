package dev.njr.zync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContextDao {
    @Insert
    suspend fun insert(context: ContextEntity): Long

    @Insert
    suspend fun tag(ref: NodeContextCrossRef)

    @Query("DELETE FROM node_context WHERE nodeId = :nodeId AND contextId = :contextId")
    suspend fun untag(nodeId: Long, contextId: Long)

    @Query("SELECT * FROM context ORDER BY name")
    fun observeAll(): Flow<List<ContextEntity>>

    @Query("SELECT c.name FROM context c JOIN node_context nc ON nc.contextId = c.id WHERE nc.nodeId = :nodeId ORDER BY c.name")
    suspend fun contextNamesFor(nodeId: Long): List<String>

    @Query("SELECT c.* FROM context c JOIN node_context nc ON nc.contextId = c.id WHERE nc.nodeId = :nodeId ORDER BY c.name")
    fun observeContextsFor(nodeId: Long): Flow<List<ContextEntity>>
}

package dev.njr.zync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AllowedDeviceDao {
    @Insert
    suspend fun insert(device: AllowedDeviceEntity): Long

    @Query("SELECT * FROM allowed_device WHERE pubkey = :pubkey")
    suspend fun byPubkey(pubkey: String): AllowedDeviceEntity?

    @Query("SELECT * FROM allowed_device ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<AllowedDeviceEntity>>

    @Query("UPDATE allowed_device SET revoked = :revoked WHERE id = :id")
    suspend fun setRevoked(id: Long, revoked: Boolean)

    @Query("UPDATE allowed_device SET lastSeen = :lastSeen WHERE id = :id")
    suspend fun touch(id: Long, lastSeen: Long)
}

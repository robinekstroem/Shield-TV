package se.norrsken.tv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import se.norrsken.tv.data.db.entity.ChannelEntity

@Dao
interface ChannelDao {

    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0 AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY sortOrder, name
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun page(categoryId: Long?, limit: Int, offset: Int): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun byId(id: Long): ChannelEntity?

    @Query("SELECT COUNT(*) FROM channels WHERE hidden = 0")
    fun observeCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0 AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY sortOrder, name
        LIMIT :limit
        """
    )
    fun observeChannels(categoryId: Long?, limit: Int): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE hidden = 0 AND name LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<ChannelEntity>

    /**
     * Importen kan komma med tiotusen rader. En @Insert som tar en lista
     * körs som en enda transaktion — anropa den med hela listan, aldrig
     * i en loop, annars blir varje rad ett eget diskanrop.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun clearForPlaylist(playlistId: Long)
}

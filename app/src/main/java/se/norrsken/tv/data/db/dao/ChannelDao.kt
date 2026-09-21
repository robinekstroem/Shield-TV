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
          AND (categoryId IS NULL OR categoryId NOT IN (:hiddenIds))
        ORDER BY
            CASE WHEN :sort = 0 THEN sortOrder END ASC,
            CASE WHEN :sort = 1 THEN LOWER(name) END ASC,
            LOWER(name) ASC
        LIMIT :limit
        """
    )
    fun observeChannels(
        categoryId: Long?,
        sort: Int,
        hiddenIds: List<Long>,
        limit: Int,
    ): Flow<List<ChannelEntity>>

    /** [pattern] kommer från likePattern(), inte direkt från användaren. */
    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0
          AND name LIKE :pattern ESCAPE '\'
          AND (categoryId IS NULL OR categoryId NOT IN (:hiddenIds))
        ORDER BY LOWER(name)
        LIMIT :limit
        """
    )
    suspend fun search(pattern: String, hiddenIds: List<Long>, limit: Int): List<ChannelEntity>

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

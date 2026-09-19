package se.norrsken.tv.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import se.norrsken.tv.data.db.entity.CategoryEntity
import se.norrsken.tv.data.db.entity.ContentKind
import se.norrsken.tv.data.db.entity.PlaylistEntity

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY priority ASC, name ASC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE enabled = 1 ORDER BY priority ASC")
    suspend fun enabled(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    /** Alla källors kategorier av ett slag, sammanslagna. */
    @Query("SELECT * FROM categories WHERE kind = :kind AND hidden = 0 ORDER BY name")
    fun observeCategories(kind: ContentKind): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE playlistId = :playlistId AND kind = :kind ORDER BY sortOrder, name")
    fun observeCategories(playlistId: Long, kind: ContentKind): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>): List<Long>

    @Query("DELETE FROM categories WHERE playlistId = :playlistId")
    suspend fun clearCategories(playlistId: Long)
}

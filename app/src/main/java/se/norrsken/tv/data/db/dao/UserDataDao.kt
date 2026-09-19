package se.norrsken.tv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import se.norrsken.tv.data.db.entity.FavoriteEntity
import se.norrsken.tv.data.db.entity.MediaKind
import se.norrsken.tv.data.db.entity.ProfileEntity
import se.norrsken.tv.data.db.entity.WatchHistoryEntity

@Dao
interface UserDataDao {

    @Query("SELECT * FROM profiles ORDER BY isDefault DESC, name")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: ProfileEntity): Long

    @Query("SELECT * FROM favorites WHERE profileId = :profileId AND kind = :kind")
    fun observeFavorites(profileId: Long, kind: MediaKind): Flow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE profileId = :profileId AND kind = :kind AND refId = :refId")
    suspend fun removeFavorite(profileId: Long, kind: MediaKind, refId: Long)

    @Query(
        """
        SELECT * FROM watch_history
        WHERE profileId = :profileId AND completed = 0
        ORDER BY lastPlayedUtc DESC LIMIT :limit
        """
    )
    fun observeContinueWatching(profileId: Long, limit: Int): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND kind = :kind AND refId = :refId")
    suspend fun progressFor(profileId: Long, kind: MediaKind, refId: Long): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(entry: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE profileId = :profileId")
    suspend fun clearHistory(profileId: Long)
}

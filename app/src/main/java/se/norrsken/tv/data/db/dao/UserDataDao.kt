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

data class ContinueMovie(
    val id: Long,
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedUtc: Long,
)

data class ContinueEpisode(
    val id: Long,
    val title: String,
    val season: Int,
    val episode: Int,
    val thumbnailUrl: String?,
    val seriesTitle: String?,
    val seriesPosterUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedUtc: Long,
)

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

    /**
     * "Fortsätt titta" med titel och omslag i samma fråga.
     *
     * Ett uppslag per rad hade blivit tjugo frågor varje gång historiken
     * ändras — en sammanfogning gör det till en.
     */
    @Query(
        """
        SELECT m.id AS id, m.title AS title, m.posterUrl AS posterUrl,
               m.year AS year, h.positionMs AS positionMs,
               h.durationMs AS durationMs, h.lastPlayedUtc AS lastPlayedUtc
        FROM watch_history h
        INNER JOIN movies m ON m.id = h.refId
        WHERE h.profileId = :profileId AND h.kind = 'MOVIE' AND h.completed = 0
        ORDER BY h.lastPlayedUtc DESC LIMIT :limit
        """
    )
    fun observeContinueMovies(profileId: Long, limit: Int): Flow<List<ContinueMovie>>

    @Query(
        """
        SELECT e.id AS id, e.title AS title, e.season AS season,
               e.episode AS episode, e.thumbnailUrl AS thumbnailUrl,
               s.title AS seriesTitle, s.posterUrl AS seriesPosterUrl,
               h.positionMs AS positionMs, h.durationMs AS durationMs,
               h.lastPlayedUtc AS lastPlayedUtc
        FROM watch_history h
        INNER JOIN episodes e ON e.id = h.refId
        LEFT JOIN series s ON s.id = e.seriesId
        WHERE h.profileId = :profileId AND h.kind = 'EPISODE' AND h.completed = 0
        ORDER BY h.lastPlayedUtc DESC LIMIT :limit
        """
    )
    fun observeContinueEpisodes(profileId: Long, limit: Int): Flow<List<ContinueEpisode>>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND kind = :kind AND refId = :refId")
    suspend fun progressFor(profileId: Long, kind: MediaKind, refId: Long): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(entry: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE profileId = :profileId")
    suspend fun clearHistory(profileId: Long)
}

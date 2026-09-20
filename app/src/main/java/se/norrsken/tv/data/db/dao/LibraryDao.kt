package se.norrsken.tv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import se.norrsken.tv.data.db.entity.EpisodeEntity
import se.norrsken.tv.data.db.entity.MovieEntity
import se.norrsken.tv.data.db.entity.SeriesEntity

@Dao
interface LibraryDao {

    @Query("SELECT * FROM movies WHERE (:categoryId IS NULL OR categoryId = :categoryId) ORDER BY addedUtc DESC LIMIT :limit OFFSET :offset")
    suspend fun movies(categoryId: Long?, limit: Int, offset: Int): List<MovieEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY addedUtc DESC, title LIMIT :limit
        """
    )
    fun observeMovies(categoryId: Long?, limit: Int): Flow<List<MovieEntity>>

    @Query(
        """
        SELECT * FROM series
        WHERE (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY title LIMIT :limit
        """
    )
    fun observeSeries(categoryId: Long?, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT COUNT(*) FROM movies")
    fun observeMovieCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM series")
    fun observeSeriesCount(): Flow<Int>

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun movie(id: Long): MovieEntity?

    @Query("SELECT * FROM movies WHERE title LIKE '%' || :query || '%' ORDER BY title LIMIT :limit")
    suspend fun searchMovies(query: String, limit: Int): List<MovieEntity>

    @Query("SELECT * FROM series WHERE (:categoryId IS NULL OR categoryId = :categoryId) ORDER BY addedUtc DESC LIMIT :limit OFFSET :offset")
    suspend fun series(categoryId: Long?, limit: Int, offset: Int): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun seriesById(id: Long): SeriesEntity?

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun episode(id: Long): EpisodeEntity?

    @Query("SELECT COUNT(*) FROM episodes WHERE seriesId = :seriesId")
    suspend fun episodeCount(seriesId: Long): Int

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY season, episode")
    fun observeEpisodes(seriesId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId AND (season > :season OR (season = :season AND episode > :episode)) ORDER BY season, episode LIMIT 1")
    suspend fun nextEpisode(seriesId: Long, season: Int, episode: Int): EpisodeEntity?

    /** Hela biblioteket för en källa. Används för att rädda metadata över en synk. */
    @Query("SELECT * FROM movies WHERE playlistId = :playlistId")
    suspend fun moviesForPlaylist(playlistId: Long): List<MovieEntity>

    @Query("SELECT * FROM series WHERE playlistId = :playlistId")
    suspend fun seriesForPlaylist(playlistId: Long): List<SeriesEntity>

    @Query("DELETE FROM movies WHERE playlistId = :playlistId")
    suspend fun deleteMoviesForPlaylist(playlistId: Long)

    @Query("DELETE FROM episodes WHERE seriesId IN (SELECT id FROM series WHERE playlistId = :playlistId)")
    suspend fun deleteEpisodesForPlaylist(playlistId: Long)

    @Query("DELETE FROM series WHERE playlistId = :playlistId")
    suspend fun deleteSeriesForPlaylist(playlistId: Long)

    /**
     * Nollstaller "forsokt men hittade inget" sa titlarna provas igen.
     * Kors nar TMDB-nyckeln andras: utan den forblir allt som scrollats
     * forbi innan nyckeln fanns permanent utan omslag.
     */
    @Query("UPDATE movies SET matchState = 'UNMATCHED' WHERE matchState = 'AMBIGUOUS'")
    suspend fun resetAmbiguousMovies()

    @Query("UPDATE series SET matchState = 'UNMATCHED' WHERE matchState = 'AMBIGUOUS'")
    suspend fun resetAmbiguousSeries()

    @Update
    suspend fun updateMovie(movie: MovieEntity)

    @Update
    suspend fun updateSeries(series: SeriesEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovies(movies: List<MovieEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(series: List<SeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(episodes: List<EpisodeEntity>)
}

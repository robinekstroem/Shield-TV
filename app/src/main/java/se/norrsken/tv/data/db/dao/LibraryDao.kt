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

    @Query("SELECT * FROM movies ORDER BY addedUtc DESC, title LIMIT :limit")
    fun observeMovies(limit: Int): Flow<List<MovieEntity>>

    @Query("SELECT * FROM series ORDER BY title LIMIT :limit")
    fun observeSeries(limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun movie(id: Long): MovieEntity?

    @Query("SELECT * FROM movies WHERE title LIKE '%' || :query || '%' ORDER BY title LIMIT :limit")
    suspend fun searchMovies(query: String, limit: Int): List<MovieEntity>

    @Query("SELECT * FROM series WHERE (:categoryId IS NULL OR categoryId = :categoryId) ORDER BY addedUtc DESC LIMIT :limit OFFSET :offset")
    suspend fun series(categoryId: Long?, limit: Int, offset: Int): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun seriesById(id: Long): SeriesEntity?

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY season, episode")
    fun observeEpisodes(seriesId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId AND (season > :season OR (season = :season AND episode > :episode)) ORDER BY season, episode LIMIT 1")
    suspend fun nextEpisode(seriesId: Long, season: Int, episode: Int): EpisodeEntity?

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

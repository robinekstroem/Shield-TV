package se.norrsken.tv.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Hur säker metadatamatchningen är. Regeln från spec:en — hellre ingen
 * metadata än fel metadata — sitter i det här fältet: under [MATCHED] visas
 * leverantörens egna uppgifter, inte den externa tjänstens.
 */
enum class MatchState { UNMATCHED, AMBIGUOUS, MATCHED, MANUAL }

@Entity(
    tableName = "movies",
    indices = [Index("playlistId"), Index("categoryId"), Index("title")],
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val categoryId: Long?,
    /** Titeln som leverantören skrev den, med "4K" och annat skräp kvar. */
    val rawTitle: String,
    /** Rensad titel: "Gladiator (2000) 4K" → "Gladiator". */
    val title: String,
    val year: Int? = null,
    val streamUrl: String,
    val containerExt: String? = null,
    val providerId: String? = null,

    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val runtimeMinutes: Int? = null,
    val rating: Double? = null,
    val ratingSource: String? = null,
    val genres: String? = null,
    val director: String? = null,
    val cast: String? = null,
    val trailerUrl: String? = null,

    val metadataId: String? = null,
    val matchState: MatchState = MatchState.UNMATCHED,
    val addedUtc: Long = 0,
)

@Entity(
    tableName = "series",
    indices = [Index("playlistId"), Index("categoryId"), Index("title")],
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val categoryId: Long?,
    val rawTitle: String,
    val title: String,
    val year: Int? = null,
    val providerId: String? = null,

    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val rating: Double? = null,
    val genres: String? = null,
    val trailerUrl: String? = null,

    val metadataId: String? = null,
    val matchState: MatchState = MatchState.UNMATCHED,
    val addedUtc: Long = 0,
)

@Entity(
    tableName = "episodes",
    indices = [Index("seriesId", "season", "episode")],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long,
    val season: Int,
    val episode: Int,
    val title: String,
    val overview: String? = null,
    val streamUrl: String,
    val containerExt: String? = null,
    val thumbnailUrl: String? = null,
    val durationMinutes: Int? = null,
    val airedUtc: Long? = null,
)

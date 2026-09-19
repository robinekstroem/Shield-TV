package se.norrsken.tv.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lokal profil. Ingen PIN och ingen föräldrakontroll här — det ska byggas
 * ordentligt eller inte alls. Profilen finns för att favoriter och historik
 * ska kunna skiljas åt från början, så tabellerna slipper migreras senare.
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val isDefault: Boolean = false,
)

enum class MediaKind { CHANNEL, MOVIE, EPISODE }

@Entity(
    tableName = "favorites",
    indices = [Index("profileId", "kind", "refId", unique = true)],
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val kind: MediaKind,
    val refId: Long,
    val addedUtc: Long,
)

/**
 * Tittarhistorik. [positionMs] uppdateras med jämna mellanrum under
 * uppspelning, inte varje sekund — skrivningar mot disk mitt i en stream
 * märks på svaga boxar.
 */
@Entity(
    tableName = "watch_history",
    indices = [Index("profileId", "kind", "refId", unique = true), Index("lastPlayedUtc")],
)
data class WatchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val kind: MediaKind,
    val refId: Long,
    /** Sätts för avsnitt, så "fortsätt titta" kan hoppa till nästa avsnitt. */
    val seriesId: Long? = null,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean = false,
    val lastPlayedUtc: Long,
)

package se.norrsken.tv.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.norrsken.tv.data.db.dao.ContinueEpisode
import se.norrsken.tv.data.db.dao.ContinueMovie
import se.norrsken.tv.data.db.dao.UserDataDao
import se.norrsken.tv.data.db.entity.MediaKind
import se.norrsken.tv.data.db.entity.WatchHistoryEntity
import se.norrsken.tv.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/** Innan appen har profiler hör all historik till en och samma. */
private const val PROFILE = 0L

/** Kortare än så är inte värt att återuppta — man har knappt börjat. */
private const val MIN_RESUME_MS = 30_000L

/** Över den andelen räknas något som sett och försvinner ur listan. */
private const val COMPLETED_FRACTION = 0.95f

/**
 * Var man är i en film eller ett avsnitt.
 *
 * Skriver i en egen livscykel, inte spelarens: sparandet sker när man
 * lämnar uppspelningen, och då är skärmens ViewModel på väg att rivas. Ett
 * anrop i den hade avbrutits mitt i.
 */
@Singleton
class WatchHistoryRepository @Inject constructor(
    private val dao: UserDataDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)

    fun observeMovies(limit: Int): Flow<List<ContinueMovie>> =
        dao.observeContinueMovies(PROFILE, limit)

    fun observeEpisodes(limit: Int): Flow<List<ContinueEpisode>> =
        dao.observeContinueEpisodes(PROFILE, limit)

    /** Positionen att återuppta från, eller 0 om den ska börja om. */
    suspend fun resumePosition(kind: MediaKind, refId: Long): Long = withContext(io) {
        val entry = dao.progressFor(PROFILE, kind, refId) ?: return@withContext 0L
        if (entry.completed) 0L else entry.positionMs
    }

    fun save(kind: MediaKind, refId: Long, seriesId: Long?, positionMs: Long, durationMs: Long) {
        // Live har varken position eller längd, och en film som knappt
        // startat hör inte hemma i "fortsätt titta".
        if (durationMs <= 0 || positionMs < MIN_RESUME_MS) return
        scope.launch {
            dao.saveProgress(
                WatchHistoryEntity(
                    profileId = PROFILE,
                    kind = kind,
                    refId = refId,
                    seriesId = seriesId,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    completed = positionMs.toFloat() / durationMs >= COMPLETED_FRACTION,
                    lastPlayedUtc = System.currentTimeMillis(),
                )
            )
        }
    }
}

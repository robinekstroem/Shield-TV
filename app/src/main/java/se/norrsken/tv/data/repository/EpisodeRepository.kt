package se.norrsken.tv.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import se.norrsken.tv.data.db.dao.LibraryDao
import se.norrsken.tv.data.db.dao.PlaylistDao
import se.norrsken.tv.data.db.entity.EpisodeEntity
import se.norrsken.tv.data.db.entity.PlaylistType
import se.norrsken.tv.data.xtream.XtreamClient
import se.norrsken.tv.data.xtream.XtreamCredentialsFactory
import se.norrsken.tv.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Avsnitten hamtas forst nar anvandaren oppnar serien.
 *
 * Xtream har ett anrop per serie. Med 3000 serier i biblioteket skulle en
 * import-tidig hamtning betyda 3000 anrop, ta halvtimmar och fa paneler att
 * strypa kontot. Har hamtas de en gang per serie och sparas.
 */
@Singleton
class EpisodeRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val playlistDao: PlaylistDao,
    private val client: XtreamClient,
    private val credentialsFactory: XtreamCredentialsFactory,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Returnerar felmeddelande, eller null om allt gick bra. */
    suspend fun ensureEpisodes(seriesId: Long): String? = withContext(io) {
        if (libraryDao.episodeCount(seriesId) > 0) return@withContext null

        val series = libraryDao.seriesById(seriesId)
            ?: return@withContext "Serien finns inte kvar i biblioteket."
        val providerId = series.providerId?.toIntOrNull()
            ?: return@withContext "Serien saknar id hos leverantören."

        val playlist = playlistDao.byId(series.playlistId)
            ?: return@withContext "Kallan till serien ar borttagen."
        if (playlist.type != PlaylistType.XTREAM) {
            return@withContext "Avsnitt kan bara hämtas från ett Xtream-konto."
        }

        val credentials = credentialsFactory.forPlaylist(playlist)

        val episodes = try {
            client.episodes(credentials, providerId)
        } catch (e: Exception) {
            return@withContext e.message ?: "Avsnitten gick inte att hamta."
        }

        if (episodes.isEmpty()) return@withContext "Leverantoren listar inga avsnitt."

        libraryDao.insertEpisodes(
            episodes.map { episode ->
                EpisodeEntity(
                    seriesId = seriesId,
                    season = episode.season,
                    episode = episode.episode,
                    title = episode.title,
                    overview = episode.plot,
                    streamUrl = client.episodeUrl(
                        credentials,
                        episode.id,
                        episode.containerExtension,
                    ),
                    containerExt = episode.containerExtension,
                    thumbnailUrl = episode.thumbnail,
                    durationMinutes = episode.durationSeconds?.let { it / 60 },
                )
            }
        )
        null
    }
}

package se.norrsken.tv.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import se.norrsken.tv.data.db.dao.LibraryDao
import se.norrsken.tv.data.db.dao.PlaylistDao
import se.norrsken.tv.data.db.entity.PlaylistType
import se.norrsken.tv.data.xtream.XtreamClient
import se.norrsken.tv.data.xtream.XtreamCredentialsFactory
import se.norrsken.tv.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Filmens uppgifter från leverantören själv.
 *
 * Det här är den enda källan som inte kan peka på fel film. Uppslaget sker
 * på leverantörens eget id för strömmen — ingen titel jämförs, ingenting
 * gissas. En extern databas måste tolka "[HD] Gladiator II 4K MULTI" och
 * kan ta fel; det här kan den inte.
 *
 * Ett anrop per film, först när filmen öppnas. Med tiotusen filmer skulle
 * en import-tidig hämtning betyda tiotusen anrop.
 */
@Singleton
class VodDetailsRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val playlistDao: PlaylistDao,
    private val client: XtreamClient,
    private val credentialsFactory: XtreamCredentialsFactory,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Sant när filmen nu har beskrivning eller bild från leverantören. */
    suspend fun ensureDetails(movieId: Long): Boolean = withContext(io) {
        val movie = libraryDao.movie(movieId) ?: return@withContext false

        // Redan hämtad. Ett tomt svar från leverantören ger inget nytt
        // nästa gång heller, men beskrivningen är billig att kontrollera.
        if (movie.overview != null && movie.backdropUrl != null) return@withContext true

        val providerId = movie.providerId?.toIntOrNull() ?: return@withContext false
        val playlist = playlistDao.byId(movie.playlistId) ?: return@withContext false
        if (playlist.type != PlaylistType.XTREAM) return@withContext false
        val credentials = credentialsFactory.forPlaylist(playlist)

        val info = runCatching { client.vodInfo(credentials, providerId) }.getOrNull()
            ?: return@withContext false

        libraryDao.fillMovieDetails(
            id = movieId,
            overview = info.plot,
            poster = info.cover,
            backdrop = info.backdrop,
            genres = info.genre,
            director = info.director,
            actors = info.cast,
            rating = info.rating,
            runtimeMinutes = info.durationSeconds?.let { it / 60 }?.takeIf { it > 0 },
            trailer = info.trailer,
            year = info.releaseDate?.take(4)?.toIntOrNull()?.takeIf { it in 1880..2200 },
        )
        info.plot != null || info.backdrop != null
    }
}

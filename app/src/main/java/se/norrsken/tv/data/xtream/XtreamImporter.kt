package se.norrsken.tv.data.xtream

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import se.norrsken.tv.data.db.dao.ChannelDao
import se.norrsken.tv.data.db.dao.LibraryDao
import se.norrsken.tv.data.db.dao.PlaylistDao
import se.norrsken.tv.data.db.entity.CategoryEntity
import se.norrsken.tv.data.db.entity.ChannelEntity
import se.norrsken.tv.data.db.entity.ContentKind
import se.norrsken.tv.data.db.entity.MatchState
import se.norrsken.tv.data.db.entity.MovieEntity
import se.norrsken.tv.data.db.entity.PlaylistEntity
import se.norrsken.tv.data.db.entity.SeriesEntity
import se.norrsken.tv.data.playlist.ImportState
import se.norrsken.tv.data.playlist.XtreamCredentials
import se.norrsken.tv.data.playlist.normalizeChannelName
import se.norrsken.tv.data.remote.PlaylistException
import se.norrsken.tv.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XtreamImporter @Inject constructor(
    private val client: XtreamClient,
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val libraryDao: LibraryDao,
    private val credentialsFactory: XtreamCredentialsFactory,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun import(playlist: PlaylistEntity): Flow<ImportState> = channelFlow {
        val credentials = credentialsFactory.forPlaylist(playlist)

        try {
            send(ImportState.Fetching("kontouppgifter"))
            client.authenticate(credentials)

            playlistDao.clearCategories(playlist.id)
            channelDao.clearForPlaylist(playlist.id)

            val channels = importLive(playlist, credentials)
            val movies = importVod(playlist, credentials)
            val series = importSeries(playlist, credentials)

            playlistDao.markSynced(
                id = playlist.id,
                providerEpgUrl = client.xmltvUrl(credentials),
                syncedAt = System.currentTimeMillis(),
            )

            send(ImportState.Done(channels = channels, movies = movies, series = series))
        } catch (e: PlaylistException) {
            send(ImportState.Failed(e.message ?: "Importen misslyckades."))
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            send(ImportState.Failed("Importen avbröts: ${e.javaClass.simpleName}."))
        }
    }.flowOn(io)

    private suspend fun ProducerScope<ImportState>.importLive(
        playlist: PlaylistEntity,
        credentials: XtreamCredentials,
    ): Int {
        send(ImportState.Fetching("kanaler"))
        val categories = client.liveCategories(credentials)
        val streams = client.liveStreams(credentials)
        if (streams.isEmpty()) return 0

        send(ImportState.Parsing("kanaler", streams.size))
        val categoryIds = storeCategories(playlist.id, ContentKind.LIVE, categories)

        var saved = 0
        streams.chunked(CHUNK).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            channelDao.insertAll(
                chunk.mapIndexed { index, stream ->
                    ChannelEntity(
                        playlistId = playlist.id,
                        categoryId = stream.categoryId?.let(categoryIds::get),
                        name = stream.name,
                        normalizedName = normalizeChannelName(stream.name),
                        streamUrl = client.liveUrl(
                            credentials,
                            stream.streamId,
                            playlist.liveExtension,
                        ),
                        logoUrl = stream.icon,
                        // Panelens epg_channel_id motsvarar tvg-id och är det
                        // tablån matchar på i Fas 4.
                        tvgId = stream.epgChannelId,
                        groupTitle = stream.categoryId?.let { id ->
                            categories.firstOrNull { it.id == id }?.name
                        },
                        channelNumber = stream.number,
                        catchupDays = stream.archiveDays,
                        sortOrder = stream.number ?: (saved + index),
                    )
                }
            )
            saved += chunk.size
            send(ImportState.Saving("kanaler", saved, streams.size))
        }
        return saved
    }

    private suspend fun ProducerScope<ImportState>.importVod(
        playlist: PlaylistEntity,
        credentials: XtreamCredentials,
    ): Int {
        send(ImportState.Fetching("filmer"))
        val categories = client.vodCategories(credentials)
        val streams = client.vodStreams(credentials)
        if (streams.isEmpty()) return 0

        send(ImportState.Parsing("filmer", streams.size))
        val categoryIds = storeCategories(playlist.id, ContentKind.MOVIE, categories)

        // Raderna måste bort före insert. Primärnyckeln autogenereras, så en
        // REPLACE-insert skapar nya rader i stället för att ersätta — utan
        // det här dubblades hela filmbiblioteket vid varje uppdatering.
        //
        // Men först sparas det vi redan hämtat från TMDB, nycklat på
        // leverantörens id. Annars kastas varje omslag vid varje synk och
        // appen börjar om från noll mot metadatatjänsten.
        val keptMovies = libraryDao.moviesForPlaylist(playlist.id)
            .filter { it.providerId != null }
            .associateBy { it.providerId!! }
        libraryDao.deleteMoviesForPlaylist(playlist.id)

        var saved = 0
        streams.chunked(CHUNK).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            libraryDao.insertMovies(
                chunk.map { stream ->
                    val (title, year) = splitTitleAndYear(stream.name)
                    val kept = keptMovies[stream.streamId.toString()]
                    MovieEntity(
                        playlistId = playlist.id,
                        categoryId = stream.categoryId?.let(categoryIds::get),
                        rawTitle = stream.name,
                        title = title,
                        year = year,
                        streamUrl = client.movieUrl(
                            credentials,
                            stream.streamId,
                            stream.containerExtension,
                        ),
                        containerExt = stream.containerExtension,
                        providerId = stream.streamId.toString(),
                        posterUrl = stream.icon ?: kept?.posterUrl,
                        backdropUrl = kept?.backdropUrl,
                        overview = kept?.overview,
                        rating = stream.rating ?: kept?.rating,
                        ratingSource = when {
                            stream.rating != null -> "provider"
                            else -> kept?.ratingSource
                        },
                        metadataId = kept?.metadataId,
                        matchState = keptMatchState(kept?.matchState, kept?.title, title),
                        addedUtc = stream.addedUtc,
                    )
                }
            )
            saved += chunk.size
            send(ImportState.Saving("filmer", saved, streams.size))
        }
        return saved
    }

    private suspend fun ProducerScope<ImportState>.importSeries(
        playlist: PlaylistEntity,
        credentials: XtreamCredentials,
    ): Int {
        send(ImportState.Fetching("serier"))
        val categories = client.seriesCategories(credentials)
        val list = client.seriesList(credentials)
        if (list.isEmpty()) return 0

        send(ImportState.Parsing("serier", list.size))
        val categoryIds = storeCategories(playlist.id, ContentKind.SERIES, categories)

        val keptSeries = libraryDao.seriesForPlaylist(playlist.id)
            .filter { it.providerId != null }
            .associateBy { it.providerId!! }
        // Avsnitten först: de pekar på serie-id som är på väg att försvinna.
        libraryDao.deleteEpisodesForPlaylist(playlist.id)
        libraryDao.deleteSeriesForPlaylist(playlist.id)

        var saved = 0
        list.chunked(CHUNK).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            libraryDao.insertSeries(
                chunk.map { series ->
                    val (title, year) = splitTitleAndYear(series.name)
                    val kept = keptSeries[series.seriesId.toString()]
                    SeriesEntity(
                        playlistId = playlist.id,
                        categoryId = series.categoryId?.let(categoryIds::get),
                        rawTitle = series.name,
                        title = title,
                        year = year ?: series.releaseDate?.take(4)?.toIntOrNull(),
                        providerId = series.seriesId.toString(),
                        posterUrl = series.cover ?: kept?.posterUrl,
                        backdropUrl = series.backdrop ?: kept?.backdropUrl,
                        overview = series.plot ?: kept?.overview,
                        rating = series.rating ?: kept?.rating,
                        genres = series.genre,
                        trailerUrl = series.trailer,
                        metadataId = kept?.metadataId,
                        matchState = keptMatchState(kept?.matchState, kept?.title, title),
                    )
                }
            )
            saved += chunk.size
            send(ImportState.Saving("serier", saved, list.size))
        }
        // Avsnitten hämtas först när användaren öppnar serien. Ett anrop per
        // serie här skulle betyda tusentals anrop och en import som tar
        // halvtimmar — vissa paneler stryper kontot långt innan dess.
        return saved
    }

    private suspend fun storeCategories(
        playlistId: Long,
        kind: ContentKind,
        categories: List<XtreamCategory>,
    ): Map<String, Long> {
        if (categories.isEmpty()) return emptyMap()
        val ids = playlistDao.insertCategories(
            categories.mapIndexed { index, category ->
                CategoryEntity(
                    playlistId = playlistId,
                    kind = kind,
                    externalId = category.id,
                    name = category.name,
                    sortOrder = index,
                )
            }
        )
        return categories.map { it.id }.zip(ids).toMap()
    }

    private companion object {
        const val CHUNK = 500
    }
}

/**
 * Vilken matchningsstatus en titel ska ha efter en synk.
 *
 * Har rensningen gett en annan titel an forra gangen gjordes forra
 * matchningen pa fel strang — da ska den goras om. Ett manuellt val ar
 * anvandarens beslut och ror vi aldrig.
 */
internal fun keptMatchState(previous: MatchState?, previousTitle: String?, title: String): MatchState =
    when {
        previous == null -> MatchState.UNMATCHED
        previous == MatchState.MANUAL -> MatchState.MANUAL
        previousTitle != title -> MatchState.UNMATCHED
        else -> previous
    }

/**
 * "Gladiator (2000) 4K" → "Gladiator" + 2000.
 *
 * Årtalet är det som gör metadatamatchningen i Fas 6 säker: utan det blir
 * varje nyinspelning en möjlig träff på originalet.
 */
internal fun splitTitleAndYear(raw: String): Pair<String, Int?> {
    val year = Regex("""[(\[]?((?:19|20)\d{2})[)\]]?""")
        .findAll(raw)
        .lastOrNull()
        ?.groupValues?.get(1)
        ?.toIntOrNull()

    val cleaned = raw
        .replace(Regex("""[(\[]?(?:19|20)\d{2}[)\]]?"""), " ")
        // Hakparenteser i IPTV-titlar ar nastan alltid taggar: [HD-Sub],
        // [SE-AUDIO], [4K]. Att ta bort orden inuti och lamna skalen gav
        // "Moana [ -Sub] [ -AUDIO] [ ]" — och den titeln matchar aldrig
        // nagot hos TMDB, sa filmen fick heller aldrig nagot omslag.
        .replace(Regex("""\[[^\]]*\]"""), " ")
        .replace(Regex("""(?i)\b(4k|uhd|fhd|hd|sd|hevc|h265|x265|multi|vf|vostfr)\b"""), " ")
        .replace(Regex("""\(\s*\)"""), " ")
        .replace(Regex("""[._]+"""), " ")
        .replace(Regex(""" +"""), " ")
        .trim()
        .trim('-', '|', ':')
        .trim()

    return (cleaned.ifEmpty { raw.trim() }) to year
}

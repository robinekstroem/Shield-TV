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
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun import(playlist: PlaylistEntity): Flow<ImportState> = channelFlow {
        val credentials = XtreamCredentials(
            baseUrl = playlist.url,
            username = playlist.username.orEmpty(),
            password = playlist.secret.orEmpty(),
        )

        try {
            send(ImportState.Fetching("kontouppgifter"))
            client.authenticate(credentials)

            playlistDao.clearCategories(playlist.id)
            channelDao.clearForPlaylist(playlist.id)

            val channels = importLive(playlist, credentials)
            val movies = importVod(playlist, credentials)
            val series = importSeries(playlist, credentials)

            playlistDao.update(
                playlist.copy(
                    epgUrl = playlist.epgUrl ?: client.xmltvUrl(credentials),
                    lastSyncUtc = System.currentTimeMillis(),
                )
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

        var saved = 0
        streams.chunked(CHUNK).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            libraryDao.insertMovies(
                chunk.map { stream ->
                    val (title, year) = splitTitleAndYear(stream.name)
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
                        posterUrl = stream.icon,
                        rating = stream.rating,
                        ratingSource = if (stream.rating != null) "provider" else null,
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

        var saved = 0
        list.chunked(CHUNK).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            libraryDao.insertSeries(
                chunk.map { series ->
                    val (title, year) = splitTitleAndYear(series.name)
                    SeriesEntity(
                        playlistId = playlist.id,
                        categoryId = series.categoryId?.let(categoryIds::get),
                        rawTitle = series.name,
                        title = title,
                        year = year ?: series.releaseDate?.take(4)?.toIntOrNull(),
                        providerId = series.seriesId.toString(),
                        posterUrl = series.cover,
                        backdropUrl = series.backdrop,
                        overview = series.plot,
                        rating = series.rating,
                        genres = series.genre,
                        trailerUrl = series.trailer,
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
        .replace(Regex("""(?i)\b(4k|uhd|fhd|hd|sd|hevc|h265|x265|multi|vf|vostfr)\b"""), " ")
        .replace(Regex("""[._]+"""), " ")
        .replace(Regex(""" +"""), " ")
        .trim()
        .trim('-', '|', ':')
        .trim()

    return (cleaned.ifEmpty { raw.trim() }) to year
}

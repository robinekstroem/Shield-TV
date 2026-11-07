package se.norrsken.tv.desktop.data

import se.norrsken.tv.data.playlist.ImportState
import se.norrsken.tv.data.playlist.XtreamCredentials
import se.norrsken.tv.data.playlist.parseM3u
import se.norrsken.tv.data.remote.PlaylistDownloader
import se.norrsken.tv.data.xtream.XtreamClient

/**
 * Hämtar en källa och gör om den till [SourceContent].
 *
 * Själva arbetet — nedladdning med omdirigeringar och gzip, M3U-parsning
 * rad för rad, Xtream-API:ts slarviga typer — görs av exakt samma kod som i
 * Android-appen. Det här är bara översättningen till Windows-modellerna.
 *
 * Blockerande: anropas från Dispatchers.IO.
 */
class Importer(
    private val downloader: PlaylistDownloader,
    private val xtream: XtreamClient,
) {

    fun importM3u(
        sourceId: String,
        url: String,
        userAgent: String,
        report: (ImportState) -> Unit,
    ): SourceContent {
        report(ImportState.Fetching("spellistan"))
        val playlist = downloader.open(url, userAgent) { reader ->
            parseM3u(reader) { parsed -> report(ImportState.Parsing("kanaler", parsed)) }
        }

        val channels = playlist.entries.mapIndexed { index, entry ->
            LiveChannel(
                key = "$sourceId:c$index",
                sourceId = sourceId,
                name = entry.name,
                group = entry.group ?: UNCATEGORIZED,
                logo = entry.logoUrl,
                number = entry.channelNumber,
                epgId = entry.tvgId,
                url = entry.url,
                streamId = null,
            )
        }

        return SourceContent(
            channels = channels,
            movies = emptyList(),
            series = emptyList(),
            liveGroups = channels.map { it.group }.distinct(),
            movieGroups = emptyList(),
            seriesGroups = emptyList(),
            epgUrl = playlist.epgUrl,
        )
    }

    fun importXtream(
        sourceId: String,
        credentials: XtreamCredentials,
        report: (ImportState) -> Unit,
    ): SourceContent {
        // Kontrollen först: är uppgifterna fel finns ingen anledning att
        // hämta tiotusen kanaler.
        report(ImportState.Fetching("kontot"))
        xtream.authenticate(credentials)

        report(ImportState.Fetching("kanaler"))
        val (channels, liveGroups) = importXtreamLive(sourceId, credentials)
        report(ImportState.Parsing("kanaler", channels.size))

        report(ImportState.Fetching("filmer"))
        val vodCategories = xtream.vodCategories(credentials)
        val vodNames = vodCategories.associate { it.id to it.name }
        val movies = xtream.vodStreams(credentials).mapIndexed { index, vod ->
            Movie(
                key = "$sourceId:m$index",
                sourceId = sourceId,
                streamId = vod.streamId,
                name = vod.name,
                group = vod.categoryId?.let(vodNames::get) ?: UNCATEGORIZED,
                poster = vod.icon,
                rating = vod.rating,
                addedUtc = vod.addedUtc,
                extension = vod.containerExtension,
            )
        }
        report(ImportState.Parsing("filmer", movies.size))

        // Bara serielistan. Avsnitten hämtas när serien öppnas — ett anrop per
        // serie gånger tusentals serier får paneler att strypa kontot.
        report(ImportState.Fetching("serier"))
        val seriesCategories = xtream.seriesCategories(credentials)
        val seriesNames = seriesCategories.associate { it.id to it.name }
        val series = xtream.seriesList(credentials).mapIndexed { index, s ->
            Series(
                key = "$sourceId:s$index",
                sourceId = sourceId,
                seriesId = s.seriesId,
                name = s.name,
                group = s.categoryId?.let(seriesNames::get) ?: UNCATEGORIZED,
                cover = s.cover,
                backdrop = s.backdrop,
                plot = s.plot,
                genre = s.genre,
                releaseDate = s.releaseDate,
                rating = s.rating,
                trailer = s.trailer,
                addedUtc = s.lastModifiedUtc,
            )
        }
        report(ImportState.Parsing("serier", series.size))

        return SourceContent(
            channels = channels,
            movies = movies,
            series = series,
            liveGroups = liveGroups,
            movieGroups = vodCategories.map { it.name },
            seriesGroups = seriesCategories.map { it.name },
            // Tablån kommer i en senare fas. Adressen bär lösenordet och
            // sparas därför inte i onödan.
            epgUrl = null,
        )
    }

    /**
     * Bara kanalerna och deras kategorier. Vid varje start hämtas de om, så
     * att evenemangskanalerna har dagens namn; filmerna och serierna står kvar.
     */
    fun importXtreamLive(sourceId: String, credentials: XtreamCredentials): Pair<List<LiveChannel>, List<String>> {
        val liveCategories = xtream.liveCategories(credentials)
        val liveNames = liveCategories.associate { it.id to it.name }
        val channels = xtream.liveStreams(credentials).mapIndexed { index, stream ->
            LiveChannel(
                key = "$sourceId:c$index",
                sourceId = sourceId,
                name = stream.name,
                group = stream.categoryId?.let(liveNames::get) ?: UNCATEGORIZED,
                logo = stream.icon,
                number = stream.number,
                epgId = stream.epgChannelId,
                url = null,
                streamId = stream.streamId,
            )
        }
        return channels to liveCategories.map { it.name }
    }
}

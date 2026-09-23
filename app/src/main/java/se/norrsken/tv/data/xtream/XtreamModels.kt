package se.norrsken.tv.data.xtream

data class XtreamAccount(
    val status: String,
    val expiresUtc: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val isTrial: Boolean,
)

data class XtreamCategory(
    val id: String,
    val name: String,
)

data class XtreamLiveStream(
    val streamId: Int,
    val name: String,
    val icon: String?,
    /** Panelens EPG-id. Motsvarar tvg-id i en M3U-fil. */
    val epgChannelId: String?,
    val categoryId: String?,
    val number: Int?,
    val archiveDays: Int,
)

data class XtreamVodStream(
    val streamId: Int,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    /** mp4, mkv, avi … avgör både URL och vilken avkodare som behövs. */
    val containerExtension: String,
    val rating: Double?,
    val addedUtc: Long,
)

data class XtreamSeries(
    val seriesId: Int,
    val name: String,
    val cover: String?,
    val backdrop: String?,
    val plot: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: Double?,
    val categoryId: String?,
    val trailer: String?,
)

data class XtreamEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val containerExtension: String,
    val plot: String?,
    val thumbnail: String?,
    val durationSeconds: Int?,
)

/**
 * Leverantörens egen data om en film, hämtad på filmens eget id.
 *
 * Ingen titelmatchning inblandad — det här ÄR filmen, inte en gissning om
 * vilken film den kan vara. Kvaliteten varierar mellan leverantörer, men
 * den kan aldrig peka på fel titel.
 */
data class XtreamVodInfo(
    val plot: String?,
    val cover: String?,
    val backdrop: String?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val releaseDate: String?,
    val rating: Double?,
    val durationSeconds: Int?,
    val trailer: String?,
)

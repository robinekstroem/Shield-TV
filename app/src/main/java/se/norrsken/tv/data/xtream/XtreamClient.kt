// Delas med Windows-versionen (se windows/build.gradle.kts). Håll filen fri
// från android.*-importer, annars slutar Windows-bygget att kompilera.

package se.norrsken.tv.data.xtream

import org.json.JSONArray
import org.json.JSONObject
import se.norrsken.tv.data.playlist.XtreamCredentials
import se.norrsken.tv.data.remote.PlaylistDownloader
import se.norrsken.tv.data.remote.PlaylistException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Klient mot Xtream Codes-kompatibla paneler.
 *
 * Svaren parsas med org.json, inte kotlinx.serialization, och det är ett
 * medvetet val. Panelerna är ökänt slarviga med typer: stream_id kommer
 * ibland som tal och ibland som sträng, rating kan vara "", null, "7.4"
 * eller 7.4 i samma svar. En strikt parser kraschar på det; org.json:s
 * opt-metoder tvingar fram ett värde eller ger ett standardvärde.
 *
 * Det kostar mer kod. Alternativet är en app som fungerar hos vissa
 * leverantörer och inte hos andra, med ett fel användaren inte kan tolka.
 */
@Singleton
class XtreamClient @Inject constructor(
    private val downloader: PlaylistDownloader,
) {

    /**
     * Kontrollerar uppgifterna och ger tillbaka kontots status.
     * Körs alltid först — misslyckas den finns ingen anledning att hämta
     * tiotusen kanaler.
     */
    fun authenticate(credentials: XtreamCredentials): XtreamAccount {
        val root = obj(credentials, action = null)
        val info = root.optJSONObject("user_info")
            ?: throw PlaylistException(
                "Servern svarade men inte som en Xtream-panel. Kontrollera adressen."
            )

        if (info.optInt("auth", 0) != 1) {
            throw PlaylistException("Fel användarnamn eller lösenord.")
        }

        val status = info.optString("status", "").trim()
        when (status.lowercase()) {
            "expired" -> throw PlaylistException("Abonnemanget har gått ut.")
            "banned", "disabled" -> throw PlaylistException("Kontot är spärrat hos leverantören.")
        }

        return XtreamAccount(
            status = status.ifEmpty { "Active" },
            expiresUtc = info.optString("exp_date").toLongOrNull()?.times(1000),
            maxConnections = info.optString("max_connections").toIntOrNull(),
            activeConnections = info.optString("active_cons").toIntOrNull(),
            isTrial = info.optString("is_trial") == "1",
        )
    }

    fun liveCategories(c: XtreamCredentials): List<XtreamCategory> =
        array(c, "get_live_categories").mapObjects(::parseCategory)

    fun liveStreams(c: XtreamCredentials): List<XtreamLiveStream> =
        array(c, "get_live_streams").mapObjects(::parseLiveStream)

    fun vodCategories(c: XtreamCredentials): List<XtreamCategory> =
        array(c, "get_vod_categories").mapObjects(::parseCategory)

    fun vodStreams(c: XtreamCredentials): List<XtreamVodStream> =
        array(c, "get_vod_streams").mapObjects(::parseVodStream)

    fun seriesCategories(c: XtreamCredentials): List<XtreamCategory> =
        array(c, "get_series_categories").mapObjects(::parseCategory)

    fun seriesList(c: XtreamCredentials): List<XtreamSeries> =
        array(c, "get_series").mapObjects(::parseSeries)

    /**
     * Leverantörens data om en film. Ett anrop per film, på begäran — av
     * samma skäl som avsnitten: tusentals filmer skulle bli tusentals anrop.
     */
    fun vodInfo(c: XtreamCredentials, streamId: Int): XtreamVodInfo? {
        val root = obj(c, "get_vod_info", "vod_id" to streamId.toString())
        val info = root.optJSONObject("info") ?: return null
        val movie = root.optJSONObject("movie_data")

        // Backdropen kommer som lista; första bilden är den leverantören
        // valt först.
        val backdrop = info.optJSONArray("backdrop_path")
            ?.takeIf { it.length() > 0 }
            ?.optString(0)
            ?.ifEmpty { null }

        return XtreamVodInfo(
            plot = info.optString("plot").ifEmpty { info.optString("description").ifEmpty { null } },
            cover = info.optString("movie_image").ifEmpty { null },
            backdrop = backdrop,
            genre = info.optString("genre").ifEmpty { null },
            director = info.optString("director").ifEmpty { null },
            cast = info.optString("cast").ifEmpty { null },
            releaseDate = info.optString("releasedate").ifEmpty {
                info.optString("release_date").ifEmpty { null }
            },
            rating = info.optString("rating").toDoubleOrNull(),
            // Vissa skickar sekunder, andra "1:58:00".
            durationSeconds = info.optInt("duration_secs").takeIf { it > 0 }
                ?: parseDuration(info.optString("duration")),
            trailer = info.optString("youtube_trailer").ifEmpty { null }
                ?: movie?.optString("youtube_trailer")?.ifEmpty { null },
        )
    }

    /** "1:58:00" eller "118" → sekunder. */
    private fun parseDuration(value: String): Int? {
        if (value.isEmpty()) return null
        val parts = value.split(':').mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0] * 60
            else -> null
        }.takeIf { it != null && it > 0 }
    }

    /**
     * Avsnitten för en serie. Hämtas per serie, på begäran — aldrig i
     * importen. Med 3000 serier i biblioteket skulle det bli 3000 anrop,
     * vilket tar tiotals minuter och får vissa paneler att strypa kontot.
     */
    fun episodes(c: XtreamCredentials, seriesId: Int): List<XtreamEpisode> {
        val root = obj(c, "get_series_info", "series_id" to seriesId.toString())
        val seasons = root.optJSONObject("episodes") ?: return emptyList()

        val result = mutableListOf<XtreamEpisode>()
        seasons.keys().forEach { seasonKey ->
            val list = seasons.optJSONArray(seasonKey) ?: return@forEach
            val season = seasonKey.toIntOrNull() ?: 0
            list.mapObjects { it }.forEach { item ->
                val info = item.optJSONObject("info")
                result += XtreamEpisode(
                    id = item.optString("id"),
                    season = item.optInt("season", season),
                    episode = item.optInt("episode_num", 0),
                    title = item.optString("title").ifEmpty { "Avsnitt" },
                    containerExtension = item.optString("container_extension")
                        .ifEmpty { "mp4" },
                    plot = info?.optString("plot")?.ifEmpty { null },
                    thumbnail = info?.optString("movie_image")?.ifEmpty { null },
                    durationSeconds = info?.optString("duration_secs")?.toIntOrNull(),
                )
            }
        }
        return result.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    // --- Ström-URL:er -------------------------------------------------------

    fun liveUrl(c: XtreamCredentials, streamId: Int, extension: String): String =
        "${c.baseUrl}/live/${enc(c.username)}/${enc(c.password)}/$streamId.$extension"

    fun movieUrl(c: XtreamCredentials, streamId: Int, extension: String): String =
        "${c.baseUrl}/movie/${enc(c.username)}/${enc(c.password)}/$streamId.$extension"

    fun episodeUrl(c: XtreamCredentials, episodeId: String, extension: String): String =
        "${c.baseUrl}/series/${enc(c.username)}/${enc(c.password)}/$episodeId.$extension"

    /** XMLTV-tablån för kontot. Används i Fas 4. */
    fun xmltvUrl(c: XtreamCredentials): String =
        "${c.baseUrl}/xmltv.php?username=${enc(c.username)}&password=${enc(c.password)}"

    // --- Internt ------------------------------------------------------------

    private fun endpoint(
        c: XtreamCredentials,
        action: String?,
        vararg extra: Pair<String, String>,
    ): String = buildString {
        append(c.baseUrl)
        append("/player_api.php?username=")
        append(enc(c.username))
        append("&password=")
        append(enc(c.password))
        if (action != null) {
            append("&action=")
            append(action)
        }
        extra.forEach { (key, value) ->
            append('&').append(key).append('=').append(enc(value))
        }
    }

    private fun obj(
        c: XtreamCredentials,
        action: String?,
        vararg extra: Pair<String, String>,
    ): JSONObject {
        val text = downloader.readText(endpoint(c, action, *extra), c.userAgent)
        return runCatching { JSONObject(text) }.getOrElse {
            throw PlaylistException("Servern svarade med något som inte är giltig JSON.")
        }
    }

    private fun array(c: XtreamCredentials, action: String): JSONArray {
        val text = downloader.readText(endpoint(c, action), c.userAgent)
        return runCatching { JSONArray(text) }.getOrElse {
            // Paneler svarar ibland med {"user_info":...} eller ren text när
            // en sektion saknas i abonnemanget. Tom lista, inte krasch.
            JSONArray()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
    val result = ArrayList<T>(length())
    for (index in 0 until length()) {
        optJSONObject(index)?.let { result += transform(it) }
    }
    return result
}

private fun parseCategory(o: JSONObject) = XtreamCategory(
    id = o.optString("category_id"),
    name = o.optString("category_name").ifEmpty { "Okategoriserat" },
)

private fun parseLiveStream(o: JSONObject) = XtreamLiveStream(
    streamId = o.optInt("stream_id"),
    name = o.optString("name").ifEmpty { "Namnlös kanal" },
    icon = o.optString("stream_icon").ifEmpty { null },
    epgChannelId = o.optString("epg_channel_id").ifEmpty { null },
    categoryId = o.optString("category_id").ifEmpty { null },
    number = o.optString("num").toIntOrNull(),
    archiveDays = o.optString("tv_archive_duration").toIntOrNull() ?: 0,
)

private fun parseVodStream(o: JSONObject) = XtreamVodStream(
    streamId = o.optInt("stream_id"),
    name = o.optString("name").ifEmpty { "Namnlös titel" },
    icon = o.optString("stream_icon").ifEmpty { null },
    categoryId = o.optString("category_id").ifEmpty { null },
    containerExtension = o.optString("container_extension").ifEmpty { "mp4" },
    rating = o.optString("rating").toDoubleOrNull(),
    addedUtc = o.optString("added").toLongOrNull()?.times(1000) ?: 0L,
)

private fun parseSeries(o: JSONObject) = XtreamSeries(
    seriesId = o.optInt("series_id"),
    name = o.optString("name").ifEmpty { "Namnlös serie" },
    cover = o.optString("cover").ifEmpty { null },
    backdrop = o.optJSONArray("backdrop_path")?.optString(0)?.ifEmpty { null },
    plot = o.optString("plot").ifEmpty { null },
    genre = o.optString("genre").ifEmpty { null },
    releaseDate = o.optString("releaseDate").ifEmpty { null },
    rating = o.optString("rating").toDoubleOrNull(),
    categoryId = o.optString("category_id").ifEmpty { null },
    trailer = o.optString("youtube_trailer").ifEmpty { null },
    lastModifiedUtc = o.optString("last_modified").toLongOrNull()?.times(1000) ?: 0L,
)

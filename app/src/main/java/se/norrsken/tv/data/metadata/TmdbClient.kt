package se.norrsken.tv.data.metadata

import org.json.JSONObject
import se.norrsken.tv.data.remote.PlaylistDownloader
import se.norrsken.tv.data.remote.PlaylistException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** Kastas när nyckeln avvisas. Skiljer sig från vanliga nätfel — det går inte över. */
class MetadataAuthException(message: String) : Exception(message)

/**
 * Sökning mot TMDB.
 *
 * Metadata hämtas enligt TMDB:s villkor och kräver att appen anger källan.
 * Attributionen står i Inställningar.
 */
@Singleton
class TmdbClient @Inject constructor(
    private val downloader: PlaylistDownloader,
) {

    fun search(
        apiKey: String,
        kind: MetadataKind,
        title: String,
        year: Int?,
        language: String,
    ): List<MetadataCandidate> {
        val primary = request(apiKey, kind, title, year, language)

        // TMDB returnerar bara den språkspecifika affischen. Saknas en svensk
        // affisch kommer poster_path tillbaka tom trots att filmen finns.
        // Då frågar vi om på engelska i stället för att visa en tom ruta.
        if (primary.any { it.posterUrl != null } || language.startsWith("en")) {
            return primary
        }
        val fallback = request(apiKey, kind, title, year, "en-US")
        return if (fallback.any { it.posterUrl != null }) fallback else primary
    }

    private fun request(
        apiKey: String,
        kind: MetadataKind,
        title: String,
        year: Int?,
        language: String,
    ): List<MetadataCandidate> {
        val path = if (kind == MetadataKind.MOVIE) "movie" else "tv"
        val yearParam = when {
            year == null -> ""
            kind == MetadataKind.MOVIE -> "&year=$year"
            else -> "&first_air_date_year=$year"
        }

        val url = "$BASE/search/$path?api_key=${enc(apiKey)}" +
            "&query=${enc(title)}" +
            "&language=${enc(language)}" +
            "&include_adult=false" +
            yearParam

        val text = try {
            downloader.readText(url, userAgent = "Norrsken/0.1")
        } catch (e: PlaylistException) {
            val message = e.message.orEmpty()
            if (message.contains("nekar åtkomst")) {
                throw MetadataAuthException("TMDB avvisade nyckeln.")
            }
            throw e
        }

        val results = runCatching { JSONObject(text).optJSONArray("results") }.getOrNull()
            ?: return emptyList()

        val candidates = ArrayList<MetadataCandidate>(results.length())
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val name = item.optString(if (kind == MetadataKind.MOVIE) "title" else "name")
            if (name.isEmpty()) continue

            val date = item.optString(
                if (kind == MetadataKind.MOVIE) "release_date" else "first_air_date"
            )

            candidates += MetadataCandidate(
                providerId = item.optInt("id").toString(),
                title = name,
                originalTitle = item.optString(
                    if (kind == MetadataKind.MOVIE) "original_title" else "original_name"
                ).ifEmpty { null },
                year = date.take(4).toIntOrNull(),
                overview = item.optString("overview").ifEmpty { null },
                posterUrl = item.optString("poster_path").ifEmpty { null }
                    ?.let { "$IMAGE_BASE$POSTER_SIZE$it" },
                backdropUrl = item.optString("backdrop_path").ifEmpty { null }
                    ?.let { "$IMAGE_BASE$BACKDROP_SIZE$it" },
                rating = item.optDouble("vote_average").takeIf { it > 0.0 },
                popularity = item.optDouble("popularity", 0.0),
            )
        }
        return candidates
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val BASE = "https://api.themoviedb.org/3"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/"
        /** w342 räcker för ett rutnät. Större bilder gör bara scrollen tyngre. */
        const val POSTER_SIZE = "w342"
        const val BACKDROP_SIZE = "w1280"
    }
}

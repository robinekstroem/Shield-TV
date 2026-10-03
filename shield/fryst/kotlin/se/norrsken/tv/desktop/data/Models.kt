package se.norrsken.tv.desktop.data

/** Grupp för allt leverantören inte satt någon kategori på. */
const val UNCATEGORIZED = "Okategoriserat"

enum class SourceType(val label: String) {
    M3U("M3U"),
    XTREAM("Xtream"),
}

/**
 * En källa användaren lagt till. Motsvarar PlaylistEntity i Android-appen.
 *
 * Inloggningsuppgifter ligger aldrig i klartext på disk: [secret] är
 * krypterad med Windows DPAPI, se [SecretBox].
 */
data class Source(
    val id: String,
    val name: String,
    val type: SourceType,
    /** M3U: adressen med inloggningen maskerad, bara för visning. Xtream: serverns bas-URL. */
    val displayUrl: String,
    val username: String? = null,
    /** Krypterat: Xtream-lösenordet, eller hela M3U-adressen. */
    val secret: String? = null,
    /**
     * Filändelse för Xtream-live, "ts" eller "m3u8". TS som standard här:
     * VLC spelar rå TS stabilare än HLS — tvärtom mot ExoPlayer på Android,
     * där m3u8 är standard.
     */
    val liveExtension: String = "ts",
    val lastSyncUtc: Long? = null,
)

data class LiveChannel(
    val key: String,
    val sourceId: String,
    val name: String,
    val group: String,
    val logo: String?,
    val number: Int?,
    val epgId: String?,
    /** M3U: hela adressen. Xtream: null — byggs vid uppspelning ur [streamId]. */
    val url: String?,
    val streamId: Int?,
) {
    val searchKey: String = name.lowercase()
}

data class Movie(
    val key: String,
    val sourceId: String,
    val streamId: Int,
    val name: String,
    val group: String,
    val poster: String?,
    val rating: Double?,
    val addedUtc: Long,
    val extension: String,
) {
    val searchKey: String = name.lowercase()

    /** Årtalet ur namnet, där leverantören satt ett: "Dune: Part Two (2024)". */
    val year: Int? = titleYear(name)
}

private val trailingYear = Regex("""(?:^|[\s(\[\-–|])((?:19|20)\d{2})[)\]]?\s*$""")

internal fun titleYear(name: String): Int? {
    val year = trailingYear.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    // "Blade Runner 2049" är ingen framtidsfilm.
    return year.takeIf { it <= java.time.Year.now().value + 1 }
}

data class Series(
    val key: String,
    val sourceId: String,
    val seriesId: Int,
    val name: String,
    val group: String,
    val cover: String?,
    val backdrop: String?,
    val plot: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: Double?,
    /** YouTube-id eller adress, som leverantören anger den. */
    val trailer: String? = null,
    /** När leverantören senast ändrade serien. 0: okänt. */
    val addedUtc: Long = 0L,
) {
    val searchKey: String = name.lowercase()

    val year: String?
        get() = releaseDate?.take(4)?.takeIf { it.length == 4 && it.all { c -> c.isDigit() } }
}

/** Allt en källa gav vid senaste importen, i leverantörens ordning. */
class SourceContent(
    val channels: List<LiveChannel>,
    val movies: List<Movie>,
    val series: List<Series>,
    val liveGroups: List<String>,
    val movieGroups: List<String>,
    val seriesGroups: List<String>,
    val epgUrl: String?,
)

/**
 * Alla källor ihopslagna — det skärmarna ser.
 *
 * Vanlig klass, inte data class, med flit: Compose jämför nycklar och
 * state med equals, och en data class med 20 000 kanaler skulle då jämföras
 * element för element vid varje tangenttryckning i sökfältet.
 */
class Library(
    val channels: List<LiveChannel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val liveGroups: List<String> = emptyList(),
    val movieGroups: List<String> = emptyList(),
    val seriesGroups: List<String> = emptyList(),
    val liveCounts: Map<String, Int> = emptyMap(),
    val movieCounts: Map<String, Int> = emptyMap(),
    val seriesCounts: Map<String, Int> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()

    companion object {
        fun merge(contents: List<SourceContent>): Library {
            val channels = contents.flatMap { it.channels }
            val movies = contents.flatMap { it.movies }
            val series = contents.flatMap { it.series }
            val liveCounts = channels.groupingBy { it.group }.eachCount()
            val movieCounts = movies.groupingBy { it.group }.eachCount()
            val seriesCounts = series.groupingBy { it.group }.eachCount()
            return Library(
                channels = channels,
                movies = movies,
                series = series,
                liveGroups = orderedGroups(contents.flatMap { it.liveGroups }, liveCounts),
                movieGroups = orderedGroups(contents.flatMap { it.movieGroups }, movieCounts),
                seriesGroups = orderedGroups(contents.flatMap { it.seriesGroups }, seriesCounts),
                liveCounts = liveCounts,
                movieCounts = movieCounts,
                seriesCounts = seriesCounts,
            )
        }

        /**
         * Leverantörens ordning först. Grupper som bara finns på objekten —
         * t.ex. ett category_id som inte stod i kategorilistan — hamnar sist.
         * Tomma grupper visas inte: en kategori utan innehåll är en återvändsgränd.
         */
        private fun orderedGroups(declared: List<String>, counts: Map<String, Int>): List<String> {
            val result = LinkedHashSet<String>()
            declared.forEach { if ((counts[it] ?: 0) > 0) result += it }
            result += counts.keys
            return result.toList()
        }
    }
}

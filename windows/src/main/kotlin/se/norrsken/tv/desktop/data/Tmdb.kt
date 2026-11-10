package se.norrsken.tv.desktop.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

class TmdbKeyException : IOException("TMDB godkände inte nyckeln.")
class TmdbBusyException : IOException("TMDB ber appen vänta en stund.")

/** Det TMDB vet om en film eller serie. id 0: sökt men inte hittad. */
data class TmdbFacts(
    val id: Int,
    /** Svenska genrenamn, kommaseparerade: "Drama, Komedi". */
    val genres: String = "",
    val rating: Double? = null,
    val votes: Int = 0,
    val popularity: Double = 0.0,
    val overview: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val year: Int? = null,
    val checkedUtc: Long = 0L,
) {
    val found: Boolean get() = id > 0
}

/**
 * Betyget så som IMDb räknar sin topplista: få röster drar betyget mot
 * medel. En okänd film med 10 av 10 från tre röster hamnar då inte före
 * en känd med 8,5 från tiotusentals.
 */
fun weightedScore(rating: Double?, votes: Int): Double? {
    if (rating == null) return null
    val weight = 300.0
    val mean = 6.4
    return (votes / (votes + weight)) * rating + (weight / (votes + weight)) * mean
}

/**
 * Leverantörernas namn är fulla av taggar: "Hugo och Josefin [SE] [Multi-Sub] [1967]",
 * "SE - Dune: Part Two (2024) 4K". TMDB vill ha titel och år var för sig.
 */
object TitleCleaner {
    private val quality = Regex("""(?i)\b(?:4k|uhd|fhd|hdr|hevc|h\.?26[45]|x26[45]|2160p|1080p|720p|multi[- ]?subs?|dubbed|dubbad)\b""")
    // ] och } som tecken skrivs \] och \}: Androids reguljära uttryck kräver det.
    private val bracketed = Regex("""\[[^\]]*\]|\{[^}]*\}|\((?:19|20)\d{2}\)""")
    private val prefix = Regex("""^\s*[A-Z]{2,4}\s*[-|:]\s+""")
    // Julkalendrar: "22/12: It's a Wonderful Life", "1.12 - Elf". Inte "9/11" eller "12/12/12".
    private val calendarDay = Regex("""^\s*\d{1,2}\s*[/.]\s*\d{1,2}\s*[:.\-–|]+\s*""")
    private val bareYear = Regex("""[\s\-–|]+(?:19|20)\d{2}\s*$""")
    private val spaces = Regex("""\s{2,}""")

    fun searchTitle(name: String): Pair<String, Int?> {
        val withoutQuality = quality.replace(name, " ")
        val year = titleYear(withoutQuality.trim())
        var title = bracketed.replace(withoutQuality, " ")
        title = calendarDay.replace(title, "")
        title = prefix.replace(title, "")
        // Årtalet i slutet tas bara bort om det faktiskt var ett år: "Blade Runner 2049" behåller sitt.
        if (year != null) title = bareYear.replace(title.trimEnd(), "")
        title = spaces.replace(title, " ").trim().trim('-', '|', ':', '–').trim()
        return title to year
    }

    fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
}

/**
 * TMDB:s öppna API, med användarens egen gratisnyckel. Både den korta
 * API-nyckeln och den långa läsnyckeln fungerar.
 */
class TmdbClient(private val key: String) {
    private val bearer = key.startsWith("eyJ")

    fun check() {
        get("configuration", emptyMap())
    }

    /** Genrernas id och svenska namn. kind: "movie" eller "tv". */
    fun genres(kind: String): Map<Int, String> {
        val array = get("genre/$kind/list", mapOf("language" to "sv-SE")).optJSONArray("genres") ?: return emptyMap()
        return (0 until array.length())
            .mapNotNull { index -> array.optJSONObject(index) }
            .associate { it.optInt("id") to it.optString("name", "") }
    }

    /** Bästa träffen för en titel, eller en post med id 0 om ingen stämmer. */
    fun search(kind: String, title: String, year: Int?, genreNames: Map<Int, String>): TmdbFacts {
        val hit = pick(kind, query(kind, title, year), title, year)
            ?: if (year != null) pick(kind, query(kind, title, null), title, year) else null
        return hit?.let { toFacts(kind, it, genreNames) } ?: TmdbFacts(id = 0, checkedUtc = System.currentTimeMillis())
    }

    private fun query(kind: String, title: String, year: Int?): List<JSONObject> {
        val params = linkedMapOf("query" to title, "language" to "sv-SE", "include_adult" to "false")
        if (year != null) params[if (kind == "movie") "year" else "first_air_date_year"] = year.toString()
        val results = get("search/$kind", params).optJSONArray("results") ?: return emptyList()
        return (0 until minOf(results.length(), 10)).mapNotNull { results.optJSONObject(it) }
    }

    /** Samma år och titel först, sedan året ±1, sedan samma titel; annars bara om träffen är ensam. */
    private fun pick(kind: String, candidates: List<JSONObject>, title: String, year: Int?): JSONObject? {
        if (candidates.isEmpty()) return null
        val wanted = TitleCleaner.normalize(title)
        fun yearOf(o: JSONObject): Int? = o.optString(if (kind == "movie") "release_date" else "first_air_date", "").take(4).toIntOrNull()
        fun sameTitle(o: JSONObject): Boolean =
            TitleCleaner.normalize(o.optString(if (kind == "movie") "title" else "name", "")) == wanted ||
                TitleCleaner.normalize(o.optString(if (kind == "movie") "original_title" else "original_name", "")) == wanted
        return candidates.firstOrNull { year != null && yearOf(it) == year && sameTitle(it) }
            ?: candidates.firstOrNull { o -> year != null && yearOf(o)?.let { abs(it - year) <= 1 } == true }
            ?: candidates.firstOrNull { sameTitle(it) }
            ?: candidates.singleOrNull()
    }

    private fun toFacts(kind: String, o: JSONObject, genreNames: Map<Int, String>): TmdbFacts {
        val ids = o.optJSONArray("genre_ids")
        val genres = ids?.let { array -> (0 until array.length()).mapNotNull { genreNames[array.optInt(it)] } }.orEmpty()
        return TmdbFacts(
            id = o.optInt("id"),
            genres = genres.joinToString(", "),
            rating = o.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            votes = o.optInt("vote_count", 0),
            popularity = o.optDouble("popularity", 0.0),
            overview = o.optString("overview", "").ifBlank { null },
            poster = o.optString("poster_path", "").ifBlank { null }?.let { IMAGES + "w342" + it },
            backdrop = o.optString("backdrop_path", "").ifBlank { null }?.let { IMAGES + "w1280" + it },
            year = o.optString(if (kind == "movie") "release_date" else "first_air_date", "").take(4).toIntOrNull(),
            checkedUtc = System.currentTimeMillis(),
        )
    }

    /**
     * Titeln som bild — filmens eller seriens logga — helst på svenska, annars
     * engelska eller utan text. Bara PNG: SVG går inte att visa. Null: ingen finns.
     */
    fun logo(kind: String, id: Int): String? {
        val logos = get("$kind/$id/images", mapOf("include_image_language" to "sv,en,null")).optJSONArray("logos") ?: return null
        fun language(o: JSONObject): Int = when (o.optString("iso_639_1", "")) {
            "sv" -> 0
            "en" -> 1
            "", "null" -> 2
            else -> 3
        }
        val best = (0 until logos.length())
            .mapNotNull { logos.optJSONObject(it) }
            .filter { it.optString("file_path", "").endsWith(".png", ignoreCase = true) }
            .sortedWith(compareBy<JSONObject> { language(it) }.thenByDescending { it.optDouble("vote_average", 0.0) })
            .firstOrNull() ?: return null
        return IMAGES + "w500" + best.optString("file_path")
    }

    /** Det som trendar i dag hos TMDB, filmer och serier blandat, mest omtalat först. */
    fun trending(): List<TrendingTitle> {
        val result = ArrayList<TrendingTitle>()
        for (page in 1..2) {
            val array = get("trending/all/day", mapOf("language" to "sv-SE", "page" to page.toString())).optJSONArray("results") ?: break
            for (index in 0 until array.length()) {
                val o = array.optJSONObject(index) ?: continue
                val kind = o.optString("media_type", "")
                if (kind != "movie" && kind != "tv") continue
                val movie = kind == "movie"
                result += TrendingTitle(
                    kind = kind,
                    id = o.optInt("id"),
                    title = o.optString(if (movie) "title" else "name", ""),
                    original = o.optString(if (movie) "original_title" else "original_name", ""),
                    year = o.optString(if (movie) "release_date" else "first_air_date", "").take(4).toIntOrNull(),
                )
            }
        }
        return result
    }

    private fun get(path: String, params: Map<String, String>): JSONObject {
        val query = buildString {
            for ((name, value) in params) {
                if (isNotEmpty()) append('&')
                append(name).append('=').append(URLEncoder.encode(value, "UTF-8"))
            }
            if (!bearer) {
                if (isNotEmpty()) append('&')
                append("api_key=").append(URLEncoder.encode(key, "UTF-8"))
            }
        }
        val connection = URL("https://api.themoviedb.org/3/$path?$query").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        if (bearer) connection.setRequestProperty("Authorization", "Bearer $key")
        when (val code = connection.responseCode) {
            401 -> throw TmdbKeyException()
            429 -> throw TmdbBusyException()
            !in 200..299 -> throw IOException("TMDB svarade $code")
        }
        return connection.inputStream.use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
    }

    private companion object {
        const val IMAGES = "https://image.tmdb.org/t/p/"
    }
}

/** En titel på TMDB:s trendlista. kind: "movie" eller "tv". */
data class TrendingTitle(
    val kind: String,
    val id: Int,
    val title: String,
    val original: String,
    val year: Int?,
)

/**
 * Loggor och dagens trendlista från TMDB, sparade mellan körningar så att
 * startsidan har dem direkt. Loggorna per "movie:id" eller "tv:id"; tom
 * text betyder att titeln saknar logga.
 */
class TmdbExtrasStore {
    private val file: File get() = File(AppDirs.cache, "tmdb-extra.json")

    class Saved(val logos: Map<String, String>, val trending: List<TrendingTitle>, val trendingAt: Long)

    fun load(): Saved = runCatching {
        val current = file
        if (!current.isFile) return Saved(emptyMap(), emptyList(), 0L)
        val o = JSONObject(current.readText(Charsets.UTF_8))
        val logos = HashMap<String, String>()
        o.optJSONObject("logos")?.let { saved -> for (key in saved.keys()) logos[key] = saved.optString(key, "") }
        val list = o.optJSONArray("trending")
        val trending = if (list == null) {
            emptyList()
        } else {
            (0 until list.length()).mapNotNull { index ->
                val e = list.optJSONObject(index) ?: return@mapNotNull null
                TrendingTitle(
                    kind = e.optString("k", "movie"),
                    id = e.optInt("i"),
                    title = e.optString("t", ""),
                    original = e.optString("o", ""),
                    year = if (e.has("y")) e.optInt("y") else null,
                )
            }
        }
        Saved(logos, trending, o.optLong("at", 0L))
    }.getOrDefault(Saved(emptyMap(), emptyList(), 0L))

    fun save(saved: Saved) {
        runCatching {
            val logos = JSONObject()
            for ((key, url) in saved.logos) logos.put(key, url)
            val list = JSONArray()
            for (t in saved.trending) {
                val e = JSONObject().put("k", t.kind).put("i", t.id).put("t", t.title).put("o", t.original)
                t.year?.let { e.put("y", it) }
                list.put(e)
            }
            val o = JSONObject().put("logos", logos).put("trending", list).put("at", saved.trendingAt)
            writeFileSafely(file, o.toString().toByteArray(Charsets.UTF_8))
        }
    }
}

/** Det som hämtats från TMDB, per rensad titel och år. Missar prövas igen efter en månad. */
class TmdbStore {
    private val file: File get() = File(AppDirs.cache, "tmdb.json")

    fun load(): Map<String, TmdbFacts> = runCatching {
        val current = file
        if (!current.isFile) return emptyMap()
        val o = JSONObject(current.readText(Charsets.UTF_8))
        val stale = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val result = HashMap<String, TmdbFacts>(o.length() * 2)
        for (key in o.keys()) {
            val e = o.optJSONObject(key) ?: continue
            val facts = TmdbFacts(
                id = e.optInt("i", 0),
                genres = e.optString("g", ""),
                rating = if (e.has("r")) e.optDouble("r") else null,
                votes = e.optInt("v", 0),
                popularity = e.optDouble("p", 0.0),
                overview = e.optString("o", "").ifEmpty { null },
                poster = e.optString("ps", "").ifEmpty { null },
                backdrop = e.optString("b", "").ifEmpty { null },
                year = if (e.has("y")) e.optInt("y") else null,
                checkedUtc = e.optLong("t", 0L),
            )
            if (!facts.found && facts.checkedUtc < stale) continue
            result[key] = facts
        }
        result
    }.getOrDefault(emptyMap())

    fun save(facts: Map<String, TmdbFacts>) {
        runCatching {
            val o = JSONObject()
            for ((key, f) in facts) {
                val e = JSONObject().put("i", f.id).put("t", f.checkedUtc)
                if (f.found) {
                    // Genrerna är en vanlig text ("Drama, Komedi"). Förr stod JSONArray(...) här,
                    // som försöker tolka texten som JSON och kastar: då sparades ingenting alls.
                    e.put("g", f.genres).put("v", f.votes).put("p", f.popularity)
                    f.rating?.let { e.put("r", it) }
                    f.overview?.let { e.put("o", it) }
                    f.poster?.let { e.put("ps", it) }
                    f.backdrop?.let { e.put("b", it) }
                    f.year?.let { e.put("y", it) }
                }
                o.put(key, e)
            }
            writeFileSafely(file, o.toString().toByteArray(Charsets.UTF_8))
        }
    }

    companion object {
        fun keyOf(kind: String, title: String, year: Int?): String = "$kind|${TitleCleaner.normalize(title)}|${year ?: ""}"
    }
}

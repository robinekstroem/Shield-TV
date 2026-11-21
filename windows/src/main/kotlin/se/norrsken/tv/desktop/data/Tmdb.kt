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
    /** Vilken version av sökningen som gav svaret. Inte hittade söks om när sökningen blivit bättre. */
    val matcher: Int = 0,
    /**
     * Namn och år stämmer så väl att TMDB:s omslag visas före leverantörens.
     * En lösare träff — bara årtalet, eller samma namn men ett annat år —
     * ger fakta och betyg, men leverantörens omslag går före.
     */
    val sure: Boolean = false,
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
    private val quality = Regex(
        """(?i)\b(?:4k|uhd|fhd|hdr10\+?|hdr|dolby ?vision|hevc|h\.?26[45]|x26[45]|2160p|1080p|720p|480p|blu-?ray|bdrip|brrip|web-?dl|webrip|hdrip|dvdrip|hdtv|remux|10bit|aac|ac3|dts|atmos|multi[- ]?subs?|dual[- ]audio|dubbed|dubbad)\b""",
    )
    // Språk utanför hakparentes: "Frost (Svenskt tal)", "Dune Swe Sub".
    private val language = Regex(
        """(?i)\b(?:svenskt tal|sv\.? ?tal|svensk text|sv\.? ?text|swe(?:dish)? (?:subs?|subbed|dub|dubbed|tal|text)|eng(?:lish)? subs?)\b""",
    )
    // Det som blir kvar av "(Svenskt tal)": tomma parenteser.
    private val emptyBrackets = Regex("""\(\s*\)|\[\s*\]""")
    // Utgåvor av samma film, sist i namnet: "Avatar Extended Edition". "Uncut Gems" får vara.
    private val edition = Regex(
        """(?i)\s*\b(?:extended(?: (?:cut|edition|version))?|director'?s cut|uncut(?: version)?|unrated(?: version| cut)?|theatrical (?:cut|version)|remastered|special edition|collector'?s edition|ultimate edition|(?:\d{1,3}(?:th|st|nd|rd)? )?anniversary edition|imax edition)\s*$""",
    )
    // Språkkod sist: "Dune - SE", "Frost | NORDIC".
    private val trailingTag = Regex("""\s*[-|]\s*(?:SE|SV|SWE|NO|DK|FI|EN|ENG|NORDIC|MULTI|SUBS?|DUB)\s*$""")
    // Land i parentes: "The Office (US)".
    private val countryParen = Regex("""\s*\((?:US|UK|GB|SE|NO|DK|FI|AU|CA|NZ|IE)\)\s*""")
    // Säsong i seriens namn: "Breaking Bad S01", "Bron Säsong 2".
    private val season = Regex("""(?i)\b(?:s\d{1,2}(?: ?e\d{1,3})?(?:\s*-\s*s\d{1,2})?|season \d{1,2}|säsong \d{1,2}|säs\.? ?\d{1,2})\b""")
    // ] och } som tecken skrivs \] och \}: Androids reguljära uttryck kräver det.
    private val bracketed = Regex("""\[[^\]]*\]|\{[^}]*\}|\((?:19|20)\d{2}\)""")
    private val prefix = Regex("""^\s*[A-Z]{2,4}\s*[-|:]\s+""")
    // Julkalendrar: "22/12: It's a Wonderful Life", "1.12 - Elf". Inte "9/11" eller "12/12/12".
    private val calendarDay = Regex("""^\s*\d{1,2}\s*[/.]\s*\d{1,2}\s*[:.\-–|]+\s*""")
    private val bareYear = Regex("""[\s\-–|]+(?:19|20)\d{2}\s*$""")
    private val spaces = Regex("""\s{2,}""")

    fun searchTitle(name: String): Pair<String, Int?> {
        val withoutQuality = emptyBrackets.replace(language.replace(quality.replace(name, " "), " "), " ")
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

    private val word = Regex("""[\p{L}\p{N}]+""")
    private val romans = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9")

    /**
     * Talen i en titel, romerska som vanliga: "Frozen II" och "Frost 2" har
     * samma, "Toy Story 2" och "Toy Story 3" inte. Uppföljare med nästan
     * samma namn blandas då inte ihop.
     */
    fun numbersOf(title: String): List<String> = word.findAll(title.lowercase()).mapNotNull { match ->
        val w = match.value
        if (w.all { it.isDigit() }) w.trimStart('0').ifEmpty { "0" } else romans[w]
    }.toList()

    /**
     * Titeln så som TMDB söks: utan utgåva ("Extended Edition"), utan
     * språkkod sist ("- SE"), utan land ("(US)") och, för serier, utan
     * säsong ("S01"). Bara för sökningen: titeln som versionerna grupperas
     * efter behåller dem.
     */
    fun queryTitle(kind: String, title: String): String {
        var t = edition.replace(title, "")
        t = trailingTag.replace(t, "")
        t = countryParen.replace(t, " ")
        if (kind == "tv") t = season.replace(t, " ")
        t = spaces.replace(t, " ").trim().trim('-', '|', ':', '–').trim()
        return t.ifBlank { title }
    }

    /**
     * Titeln pekar ut en del eller en särskild utgåva — "Bron S02", "Avatar
     * Extended Edition" — och är alltså inte samma sak som huvudtiteln, även
     * om TMDB hittar samma film eller serie för båda.
     */
    fun isPart(title: String): Boolean = season.containsMatchIn(title) || edition.containsMatchIn(title)

    /**
     * Hur lika två normaliserade titlar stavas, 0–1: andelen gemensamma
     * bokstavspar. "harrypotterochdevisessten" och "harrypotterdevisessten"
     * ligger nära, två olika filmer långt ifrån.
     */
    fun similarity(a: String, b: String): Double {
        if (a == b) return if (a.isEmpty()) 0.0 else 1.0
        if (a.length < 2 || b.length < 2) return 0.0
        val pairs = HashMap<String, Int>()
        for (i in 0 until a.length - 1) pairs.merge(a.substring(i, i + 2), 1, Int::plus)
        var shared = 0
        for (i in 0 until b.length - 1) {
            val pair = b.substring(i, i + 2)
            val left = pairs[pair] ?: 0
            if (left > 0) {
                shared++
                pairs[pair] = left - 1
            }
        }
        return 2.0 * shared / (a.length - 1 + b.length - 1)
    }
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

    /**
     * Bästa träffen för en titel, eller en post med id 0 om ingen stämmer.
     * Hittas inget söks titelns delar för sig — huvudtiteln före ett
     * kolon eller streck, eller det efter — men då krävs att namnet verkligen
     * stämmer, så att en kort titel inte ger en annan film.
     */
    fun search(kind: String, cleaned: String, year: Int?, genreNames: Map<Int, String>): TmdbFacts {
        val title = TitleCleaner.queryTitle(kind, cleaned)
        val hit = pick(kind, query(kind, title, year), title, year, strict = false)
            ?: (if (year != null) pick(kind, query(kind, title, null), title, year, strict = false) else null)
            ?: variants(title).firstNotNullOfOrNull { alternative ->
                pick(kind, query(kind, alternative, year), alternative, year, strict = true)
                    ?: if (year != null) pick(kind, query(kind, alternative, null), alternative, year, strict = true) else null
            }
        return hit?.let { toFacts(kind, it.found, genreNames, sure = it.sure && !it.part) }
            ?: TmdbFacts(id = 0, checkedUtc = System.currentTimeMillis(), matcher = MATCHER)
    }

    /** [part]: hittad på en del av titeln. Då räknas träffen aldrig som säker. */
    private class Match(val found: JSONObject, val sure: Boolean, val part: Boolean)

    /** Andra sätt att skriva titeln: delarna kring ett kolon eller streck, "&" som "and" och "och". */
    private fun variants(title: String): List<String> {
        val list = ArrayList<String>(3)
        Regex("""\s+[-–]\s+|:\s+""").find(title)?.let { separator ->
            title.substring(0, separator.range.first).trim().takeIf { it.length >= 4 }?.let { list += it }
            title.substring(separator.range.last + 1).trim().takeIf { it.length >= 4 }?.let { list += it }
        }
        if ('&' in title) {
            list += title.replace("&", "and")
            list += title.replace("&", "och")
        }
        return list.distinct().filter { it != title }
    }

    private fun query(kind: String, title: String, year: Int?): List<JSONObject> {
        val params = linkedMapOf("query" to title, "language" to "sv-SE", "include_adult" to "false")
        if (year != null) params[if (kind == "movie") "year" else "first_air_date_year"] = year.toString()
        val results = get("search/$kind", params).optJSONArray("results") ?: return emptyList()
        return (0 until minOf(results.length(), 10)).mapNotNull { results.optJSONObject(it) }
    }

    /**
     * Samma år och titel först, sedan året ±1, sedan samma titel; sedan den
     * titel som stavas mest likt (skiljetecken, "och", en undertitel), och
     * sist bara om träffen är ensam. [strict]: bara när namnet stämmer.
     */
    private fun pick(kind: String, candidates: List<JSONObject>, title: String, year: Int?, strict: Boolean): Match? {
        if (candidates.isEmpty()) return null
        val wanted = TitleCleaner.normalize(title)
        if (wanted.isEmpty()) return null
        val wantedNumbers = TitleCleaner.numbersOf(title)
        fun yearOf(o: JSONObject): Int? = o.optString(if (kind == "movie") "release_date" else "first_air_date", "").take(4).toIntOrNull()
        fun rawNames(o: JSONObject): List<String> = listOf(
            o.optString(if (kind == "movie") "title" else "name", ""),
            o.optString(if (kind == "movie") "original_title" else "original_name", ""),
        )
        fun names(o: JSONObject): List<String> = rawNames(o).map { TitleCleaner.normalize(it) }
        fun alike(o: JSONObject): Double = names(o).maxOf { TitleCleaner.similarity(wanted, it) }
        fun sameTitle(o: JSONObject): Boolean = wanted in names(o)
        fun sameNumbers(o: JSONObject): Boolean = rawNames(o).any { TitleCleaner.numbersOf(it) == wantedNumbers }
        fun closeYear(o: JSONObject): Boolean = year == null || yearOf(o)?.let { abs(it - year) <= 1 } != false
        fun match(o: JSONObject, sure: Boolean) = Match(o, sure, part = strict)

        candidates.firstOrNull { year != null && yearOf(it) == year && sameTitle(it) }?.let { return match(it, sure = true) }
        if (!strict) {
            // Sökt med årtalet: TMDB:s första träff det året, även när den heter något annat här.
            candidates.firstOrNull { o -> year != null && yearOf(o)?.let { abs(it - year) <= 1 } == true }
                ?.let { return match(it, sure = alike(it) >= 0.6 && sameNumbers(it)) }
        }
        // Leverantörens årtal kan vara fel: samma titel räcker, utom när bara en del av titeln söktes.
        candidates.firstOrNull { sameTitle(it) && (!strict || closeYear(it)) }?.let { o ->
            // Utan årtal och med flera filmer med samma namn (nyinspelningar) vet man inte vilken.
            val alone = year != null || candidates.count { sameTitle(it) } == 1
            return match(o, sure = closeYear(o) && alone)
        }
        // Mest lika stavning, med året och TMDB:s egen ordning som avgörande. Talen måste
        // stämma: "Toy Story 3" blir aldrig "Toy Story 2".
        var best: JSONObject? = null
        var bestScore = 0.0
        candidates.forEachIndexed { index, o ->
            if (!sameNumbers(o)) return@forEachIndexed
            val similar = alike(o)
            val found = yearOf(o)
            val yearScore = if (year == null || found == null) {
                0.0
            } else {
                when (abs(found - year)) {
                    0 -> 0.15
                    1 -> 0.08
                    else -> -0.4
                }
            }
            val score = similar + yearScore + 0.04 * (candidates.size - index) / candidates.size
            if (similar >= (if (strict) 0.86 else 0.72) && score > bestScore) {
                best = o
                bestScore = score
            }
        }
        val chosen = best
        if (chosen != null && bestScore >= (if (strict) 0.9 else 0.78)) return match(chosen, sure = true)
        if (strict) return null
        return candidates.singleOrNull()?.takeIf { closeYear(it) }?.let { match(it, sure = alike(it) >= 0.6 && sameNumbers(it)) }
    }

    private fun toFacts(kind: String, o: JSONObject, genreNames: Map<Int, String>, sure: Boolean): TmdbFacts {
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
            matcher = MATCHER,
            sure = sure,
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

    companion object {
        private const val IMAGES = "https://image.tmdb.org/t/p/"

        /** Sökningens version: höjs när den hittar mer, så att det som inte hittades söks om. */
        const val MATCHER = 2

        /** TMDB:s omslag i större storlek, för en stor yta. Null: inte ett TMDB-omslag i vanlig storlek. */
        fun largePoster(url: String): String? {
            val normal = IMAGES + "w342/"
            return if (url.startsWith(normal)) IMAGES + "w780/" + url.substring(normal.length) else null
        }
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
                matcher = e.optInt("m", 0),
                sure = e.optInt("s", 0) == 1,
            )
            // Inte hittad: söks om efter en månad, eller direkt när sökningen blivit bättre.
            if (!facts.found && (facts.checkedUtc < stale || facts.matcher < TmdbClient.MATCHER)) continue
            result[key] = facts
        }
        result
    }.getOrDefault(emptyMap())

    fun save(facts: Map<String, TmdbFacts>) {
        runCatching {
            val o = JSONObject()
            for ((key, f) in facts) {
                val e = JSONObject().put("i", f.id).put("t", f.checkedUtc).put("m", f.matcher)
                if (f.found) {
                    // Genrerna är en vanlig text ("Drama, Komedi"). Förr stod JSONArray(...) här,
                    // som försöker tolka texten som JSON och kastar: då sparades ingenting alls.
                    e.put("g", f.genres).put("v", f.votes).put("p", f.popularity)
                    f.rating?.let { e.put("r", it) }
                    f.overview?.let { e.put("o", it) }
                    f.poster?.let { e.put("ps", it) }
                    f.backdrop?.let { e.put("b", it) }
                    f.year?.let { e.put("y", it) }
                    if (f.sure) e.put("s", 1)
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

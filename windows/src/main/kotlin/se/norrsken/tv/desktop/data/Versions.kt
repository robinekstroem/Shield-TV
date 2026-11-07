package se.norrsken.tv.desktop.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Samma film eller serie i flera versioner.
 *
 * Leverantörer lägger ofta samma film i flera kategorier: med svensk text,
 * i 4K, med polsk speakerröst, med arabisk text. Här blir de en titel, och
 * versionerna väljs inne på filmens sida.
 *
 * Två poster räknas som samma film när TMDB har kopplat dem till samma film,
 * eller när titeln utan taggar och årtalet stämmer. En version utan årtal
 * räknas till titeln bara när det finns exakt ett år att välja mellan, så
 * att "Halloween" från 1978 och 2018 aldrig blandas ihop.
 *
 * Byggs i bakgrunden och ändras aldrig efteråt; ett nytt bibliotek ger ett
 * nytt index.
 */
class VersionIndex private constructor(
    private val movieGroupOf: Map<String, Int>,
    private val movieGroups: List<List<Movie>>,
    private val seriesGroupOf: Map<String, Int>,
    private val seriesGroups: List<List<Series>>,
    /** Per version: platsen i gruppen. 0 visas och spelas i första hand. */
    private val rank: Map<String, Int>,
    /** Per version: titeln utan taggar, densamma för hela gruppen. */
    private val titles: Map<String, String>,
    /** Per version: gruppens årtal. */
    private val years: Map<String, Int>,
    /** Per version: vad som skiljer den från de andra, på svenska. */
    private val labels: Map<String, String>,
    /** Versioner i 4K. */
    private val uhd: Set<String>,
    /** Versioner som inte visas: på andra språk, eller i dolda kategorier. */
    private val hidden: Set<String>,
) {
    val isEmpty: Boolean get() = movieGroupOf.isEmpty() && seriesGroupOf.isEmpty()

    /** Döljs av språkvalet eller en dold kategori. */
    fun isHidden(movie: Movie): Boolean = (M + movie.key) in hidden

    fun isHidden(series: Series): Boolean = (S + series.key) in hidden

    /** Antal filmer när varje film räknas en gång. */
    val movieCount: Int get() = movieGroups.size

    /** Antal serier när varje serie räknas en gång. */
    val seriesCount: Int get() = seriesGroups.size

    /** Alla versioner av filmen, den som passar bäst först. Ensam film: bara den. */
    fun versionsOf(movie: Movie): List<Movie> = movieGroupOf[movie.key]?.let { movieGroups[it] } ?: listOf(movie)

    fun versionsOf(series: Series): List<Series> = seriesGroupOf[series.key]?.let { seriesGroups[it] } ?: listOf(series)

    /** Samma värde för alla versioner av en film; olika filmer får olika värden. */
    fun groupKeyOf(movie: Movie): String = movieGroupOf[movie.key]?.let { "m$it" } ?: "x" + movie.key

    fun groupKeyOf(series: Series): String = seriesGroupOf[series.key]?.let { "s$it" } ?: "x" + series.key

    fun titleOf(movie: Movie): String = titles[M + movie.key] ?: movie.name

    fun titleOf(series: Series): String = titles[S + series.key] ?: series.name

    fun yearOf(movie: Movie): Int? = years[M + movie.key] ?: movie.year

    fun yearOf(series: Series): Int? = years[S + series.key] ?: series.year?.toIntOrNull()

    /** "Svensk text · 4K". Tomt för en film som bara finns i en version. */
    fun labelOf(movie: Movie): String = labels[M + movie.key].orEmpty()

    fun labelOf(series: Series): String = labels[S + series.key].orEmpty()

    /** Finns filmen i 4K i någon version. */
    fun has4k(movie: Movie): Boolean = versionsOf(movie).any { (M + it.key) in uhd }

    fun has4k(series: Series): Boolean = versionsOf(series).any { (S + it.key) in uhd }

    /**
     * En post per film, i listans ordning: där den första versionen stod.
     *
     * [preferDefault]: visa filmens bästa version även om den inte själv
     * fanns i listan (genrer, topplistor, alla filmer). Annars visas den
     * bästa av versionerna som fanns i listan, så att en sökning på "4K"
     * eller en kategori med svensk text visar just den versionen.
     */
    fun collapseMovies(list: List<Movie>, preferDefault: Boolean): List<Movie> =
        collapse(list, preferDefault, movieGroupOf, movieGroups, M) { it.key }

    fun collapseSeries(list: List<Series>, preferDefault: Boolean): List<Series> =
        collapse(list, preferDefault, seriesGroupOf, seriesGroups, S) { it.key }

    private fun <T> collapse(
        list: List<T>,
        preferDefault: Boolean,
        groupOf: Map<String, Int>,
        groups: List<List<T>>,
        prefix: String,
        key: (T) -> String,
    ): List<T> {
        if (groupOf.isEmpty() && hidden.isEmpty()) return list
        // Bästa versionen av varje film som finns i listan.
        val chosen = HashMap<Int, T>(list.size)
        for (item in list) {
            val group = groupOf[key(item)] ?: continue
            val current = chosen[group]
            if (current == null || rankOf(prefix, key(item)) < rankOf(prefix, key(current))) chosen[group] = item
        }
        val emitted = HashSet<Int>(chosen.size)
        val result = ArrayList<T>(chosen.size + 8)
        for (item in list) {
            val group = groupOf[key(item)]
            if (group == null) {
                // Dold av språkvalet eller kategorin: visas inte.
                if ((prefix + key(item)) in hidden) continue
                // Kom till efter att indexet byggdes: visas som den är.
                result += item
                continue
            }
            if (!emitted.add(group)) continue
            result += if (preferDefault) groups[group].first() else chosen.getValue(group)
        }
        return result
    }

    private fun rankOf(prefix: String, key: String): Int = rank[prefix + key] ?: Int.MAX_VALUE

    companion object {
        private const val M = "m|"
        private const val S = "s|"

        val EMPTY = VersionIndex(emptyMap(), emptyList(), emptyMap(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptySet(), emptySet())

        /** Rensad titel och taggar per namn: samma namn kommer igen vid varje ombyggnad. */
        private val parsed = ConcurrentHashMap<String, Parsed>()

        /**
         * Bygger indexet. Tungt för stora bibliotek: anropas i bakgrunden.
         * [sourceNames]: källornas namn, för att skilja likadana versioner från
         * olika leverantörer åt.
         */
        fun build(
            library: Library,
            tmdb: Map<String, TmdbFacts>,
            sourceNames: Map<String, String>,
            filter: LibraryFilter = LibraryFilter.NONE,
        ): VersionIndex {
            val rank = HashMap<String, Int>((library.movies.size + library.series.size) * 2)
            val titles = HashMap<String, String>(rank.size)
            val years = HashMap<String, Int>(rank.size)
            val labels = HashMap<String, String>(rank.size)
            val uhd = HashSet<String>()
            val hidden = HashSet<String>()
            // Kategoriernas språk räknas en gång per kategori, inte per film.
            val foreignGroups = HashMap<String, Boolean>()
            fun foreignGroup(group: String): Boolean = foreignGroups.getOrPut(group) { VersionTags.isForeignGroup(group) }
            fun <T> excluded(entry: Entry<T>, kind: Char): Boolean {
                if (filter.hiddenGroups.isNotEmpty() && LibraryFilter.groupKey(kind, entry.group) in filter.hiddenGroups) return true
                if (!filter.hideForeign) return false
                val tags = entry.parsed.tags
                return tags.foreign && !tags.friendly || !tags.friendly && foreignGroup(entry.group)
            }

            val movieEntries = library.movies.map { movie ->
                val p = parse(movie.name)
                val tmdbId = tmdb[TmdbStore.keyOf("movie", p.title, p.cleanerYear)]?.takeIf { it.found }?.id
                Entry(movie, movie.key, movie.sourceId, movie.group, p, p.year, tmdbId, movie.poster != null, movie.addedUtc)
            }
            val seriesEntries = library.series.map { series ->
                val p = parse(series.name)
                val seriesYear = series.year?.toIntOrNull()
                val tmdbId = tmdb[TmdbStore.keyOf("tv", p.title, p.cleanerYear ?: seriesYear)]?.takeIf { it.found }?.id
                Entry(series, series.key, series.sourceId, series.group, p, seriesYear ?: p.year, tmdbId, series.cover != null, series.addedUtc)
            }

            fun <T> finish(entries: List<Entry<T>>, prefix: String): Pair<Map<String, Int>, List<List<T>>> {
                val groupOf = HashMap<String, Int>(entries.size * 2)
                val groups = ArrayList<List<T>>()
                for (cluster in cluster(entries)) {
                    val ordered = cluster.sortedWith(
                        compareByDescending<Entry<T>> { it.parsed.tags.score }
                            .thenByDescending { it.hasArt }
                            .thenByDescending { it.added },
                    )
                    val id = groups.size
                    groups += ordered.map { it.item }
                    val top = ordered.first()
                    val title = top.parsed.title.ifBlank { top.parsed.name }
                    val year = ordered.firstNotNullOfOrNull { it.year }
                    val unique = uniqueLabels(ordered, sourceNames)
                    ordered.forEachIndexed { index, entry ->
                        groupOf[entry.key] = id
                        rank[prefix + entry.key] = index
                        titles[prefix + entry.key] = title
                        if (year != null) years[prefix + entry.key] = year
                        if (ordered.size > 1) labels[prefix + entry.key] = unique[index]
                        if (entry.parsed.tags.is4k) uhd += prefix + entry.key
                    }
                }
                return groupOf to groups
            }

            // Det som döljs grupperas inte: det syns varken i listorna eller bland versionerna.
            val (visibleMovies, hiddenMovies) = movieEntries.partition { !excluded(it, LibraryFilter.MOVIE) }
            val (visibleSeries, hiddenSeries) = seriesEntries.partition { !excluded(it, LibraryFilter.SERIES) }
            hiddenMovies.forEach { hidden += M + it.key }
            hiddenSeries.forEach { hidden += S + it.key }
            val (movieGroupOf, movieGroups) = finish(visibleMovies, M)
            val (seriesGroupOf, seriesGroups) = finish(visibleSeries, S)
            return VersionIndex(movieGroupOf, movieGroups, seriesGroupOf, seriesGroups, rank, titles, years, labels, uhd, hidden)
        }

        private fun parse(name: String): Parsed = parsed.getOrPut(name) {
            val (title, cleanerYear) = TitleCleaner.searchTitle(name)
            val normalized = TitleCleaner.normalize(title)
            Parsed(
                name = name,
                title = title,
                // Tom titel (namnet var bara taggar): aldrig ihop med något annat.
                normalized = normalized.ifEmpty { "#" + name },
                cleanerYear = cleanerYear,
                year = bracketYear(name) ?: cleanerYear,
                tags = VersionTags.parse(name),
            )
        }

        /** Årtalet i en hakparentes var som helst i namnet: "Greyhound [Multi-Subs] [2020] [4K]". */
        private fun bracketYear(name: String): Int? {
            val year = bracketedYear.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            return year.takeIf { it <= java.time.Year.now().value + 1 }
        }

        // ] och ) som tecken skrivs \] och \): Androids reguljära uttryck kräver det.
        private val bracketedYear = Regex("""[\[(]((?:19|20)\d{2})[\])]""")

        /** Samma film: samma TMDB-id, eller samma titel och år. */
        private fun <T> cluster(entries: List<Entry<T>>): List<List<Entry<T>>> {
            if (entries.isEmpty()) return emptyList()
            val ids = HashMap<String, Int>(entries.size * 3)
            val sets = UnionFind(entries.size * 2 + 1)
            fun id(key: String): Int = ids.getOrPut(key) { ids.size }
            val node = IntArray(entries.size)
            // Titel → nycklar med årtal, och nyckeln utan.
            val withYear = HashMap<String, MutableList<Int>>()
            val withoutYear = HashMap<String, Int>()
            entries.forEachIndexed { index, entry ->
                val titleKey = "t|" + entry.parsed.normalized + "|" + (entry.year?.toString() ?: "")
                val fresh = !ids.containsKey(titleKey)
                val node0 = id(titleKey)
                node[index] = node0
                if (fresh) {
                    if (entry.year != null) {
                        withYear.getOrPut(entry.parsed.normalized) { ArrayList(2) } += node0
                    } else {
                        withoutYear[entry.parsed.normalized] = node0
                    }
                }
                entry.tmdbId?.let { sets.union(node0, id("d|$it")) }
            }
            // Utan årtal: till titeln när det bara finns ett år att välja.
            for ((normalized, bare) in withoutYear) {
                val roots = withYear[normalized]?.map { sets.find(it) }?.distinct() ?: continue
                if (roots.size == 1) sets.union(roots[0], bare)
            }
            val clusters = LinkedHashMap<Int, MutableList<Entry<T>>>()
            entries.forEachIndexed { index, entry ->
                clusters.getOrPut(sets.find(node[index])) { ArrayList(2) } += entry
            }
            return clusters.values.toList()
        }

        /**
         * Versionernas namn, så att inga två är lika: likadana versioner från
         * olika källor får källans namn, annars leverantörens kategori, annars
         * ett nummer.
         */
        private fun <T> uniqueLabels(ordered: List<Entry<T>>, sourceNames: Map<String, String>): List<String> {
            val base = ordered.map { it.parsed.tags.label.ifEmpty { "Original" } }
            val counts = base.groupingBy { it }.eachCount()
            val result = ArrayList<String>(ordered.size)
            val used = HashSet<String>()
            ordered.forEachIndexed { index, entry ->
                var label = base[index]
                if ((counts[label] ?: 0) > 1) {
                    val sameLabel = ordered.filterIndexed { i, _ -> base[i] == label }
                    val extra = when {
                        sameLabel.map { it.sourceId }.distinct().size > 1 -> sourceNames[entry.sourceId]
                        sameLabel.map { it.group }.distinct().size > 1 -> entry.group.takeIf { it.isNotBlank() }
                        else -> null
                    }
                    if (extra != null) label = "$label · $extra"
                }
                var candidate = label
                var number = 2
                while (!used.add(candidate)) {
                    candidate = "$label · $number"
                    number++
                }
                result += candidate
            }
            return result
        }
    }

    private class Parsed(
        val name: String,
        val title: String,
        val normalized: String,
        val cleanerYear: Int?,
        val year: Int?,
        val tags: VersionTags.Tags,
    )

    private class Entry<T>(
        val item: T,
        val key: String,
        val sourceId: String,
        val group: String,
        val parsed: Parsed,
        val year: Int?,
        val tmdbId: Int?,
        val hasArt: Boolean,
        val added: Long,
    )

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var node = x
            while (parent[node] != root) {
                val next = parent[node]
                parent[node] = root
                node = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) parent[rootB] = rootA
        }
    }
}

/**
 * Vad som ska döljas i biblioteket. Inget tas bort: ändras valet syns allt
 * igen vid nästa ombyggnad av versionerna.
 */
class LibraryFilter(
    /** Dölj versioner och kategorier som bara finns på andra språk än svenska, nordiska och engelska. */
    val hideForeign: Boolean,
    /** Dolda kategorier, som [groupKey]. */
    val hiddenGroups: Set<String>,
) {
    companion object {
        const val LIVE = 'l'
        const val MOVIE = 'm'
        const val SERIES = 's'
        val NONE = LibraryFilter(hideForeign = false, hiddenGroups = emptySet())

        /** "m|Action", "s|Svenska serier", "l|SE: Sport". */
        fun groupKey(kind: Char, group: String): String = "$kind|$group"
    }
}

/**
 * Vad som skiljer en version från en annan, ur taggarna i namnet:
 * [SWE-SUB], [PL] [LEKTOR], [Multi-Sub], [4K], [PRE] och liknande.
 */
internal object VersionTags {

    class Tags(
        /** På svenska: "Svensk text · 4K". Tomt när namnet inte säger något. */
        val label: String,
        /** Hur väl versionen passar en svensk tittare. Högst visas och spelas först. */
        val score: Int,
        val is4k: Boolean,
        /** Namnet nämner svenska, nordiska, engelska eller flera undertexter. */
        val friendly: Boolean = false,
        /** Namnet nämner ett annat språk: polsk speakerröst, arabisk text, turkiskt tal. */
        val foreign: Boolean = false,
    )

    /**
     * Kategorier på andra språk, efter koden eller ordet i namnet: "PL | Filmy",
     * "|AR| أفلام", "TURKISH MOVIES". En kategori som också nämner svenska,
     * nordiska eller engelska räknas inte dit.
     */
    fun isForeignGroup(group: String): Boolean {
        // Å finns bara i de nordiska språken.
        if ('å' in group || 'Å' in group) return false
        var other = false
        for (raw in group.split(groupSplitter)) {
            if (raw.length < 2) continue
            val token = raw.uppercase()
            // Korta koder räknas bara i versaler: "DE" är tyska, men "De bästa" är svenska.
            if (raw.length <= 3 && raw != token) continue
            val language = languages[token] ?: countries[token] ?: continue
            if (language.kind != Kind.OTHER) return false
            other = true
        }
        return other
    }

    /**
     * Vilket land eller språk ett kanalnamn eller en kategori pekar på, efter
     * koderna och orden i den: 0 svenskt, 1 norskt eller danskt, 2 finskt,
     * 3 engelskt, 5 annat. Null när namnet inte säger något.
     */
    fun regionRank(text: String): Int? {
        var best: Int? = null
        for (raw in text.split(groupSplitter)) {
            if (raw.length < 2) continue
            val token = raw.uppercase()
            // Korta koder räknas bara i versaler: "SE" är Sverige, "Se" är ett ord.
            if (raw.length <= 3 && raw != token) continue
            val language = languages[token] ?: countries[token] ?: continue
            val rank = when (language.kind) {
                Kind.SWEDISH -> 0
                Kind.NORDIC -> 1
                Kind.FINNISH -> 2
                Kind.ENGLISH -> 3
                Kind.OTHER -> 5
            }
            if (best == null || rank < best) best = rank
        }
        return best
    }

    private class Language(val noun: String, val adjective: String, val kind: Kind)

    private enum class Kind { SWEDISH, NORDIC, FINNISH, ENGLISH, OTHER }

    private val languages: Map<String, Language> = buildMap {
        fun add(names: String, noun: String, adjective: String, kind: Kind = Kind.OTHER) {
            names.split(' ').forEach { put(it, Language(noun, adjective, kind)) }
        }
        add("SE SV SWE SWEDISH SVE SVENSK SVENSKA", "Svenska", "Svensk", Kind.SWEDISH)
        add("NO NOR NB NORWEGIAN NORSK", "Norska", "Norsk", Kind.NORDIC)
        add("DK DA DAN DANISH DANSK", "Danska", "Dansk", Kind.NORDIC)
        add("FI FIN FINNISH", "Finska", "Finsk", Kind.FINNISH)
        add("EN ENG ENGLISH UK US", "Engelska", "Engelsk", Kind.ENGLISH)
        add("PL POL POLISH", "Polska", "Polsk")
        add("AR ARA ARB ARABIC", "Arabiska", "Arabisk")
        add("DE GER DEU GERMAN", "Tyska", "Tysk")
        add("FR FRE FRA FRENCH VF VOSTFR", "Franska", "Fransk")
        add("ES SPA ESP SPANISH LAT LATINO", "Spanska", "Spansk")
        add("IT ITA ITALIAN", "Italienska", "Italiensk")
        add("PT POR BR PORTUGUESE", "Portugisiska", "Portugisisk")
        add("NL DUT NLD DUTCH", "Nederländska", "Nederländsk")
        add("TR TUR TURKISH", "Turkiska", "Turkisk")
        add("RU RUS RUSSIAN", "Ryska", "Rysk")
        add("FA PER FAR IR PERSIAN", "Persiska", "Persisk")
        add("KU KUR KURDISH", "Kurdiska", "Kurdisk")
        add("AL ALB SQ ALBANIAN", "Albanska", "Albansk")
        add("GR EL GRE GREEK", "Grekiska", "Grekisk")
        add("HU HUN HUNGARIAN", "Ungerska", "Ungersk")
        add("RO RUM RON ROMANIAN", "Rumänska", "Rumänsk")
        add("CZ CS CZE CZECH", "Tjeckiska", "Tjeckisk")
        add("HR CRO CROATIAN", "Kroatiska", "Kroatisk")
        add("SR SRB SERBIAN", "Serbiska", "Serbisk")
        add("BG BUL BULGARIAN", "Bulgariska", "Bulgarisk")
        add("UA UKR UKRAINIAN", "Ukrainska", "Ukrainsk")
        add("HI HIN HINDI", "Hindi", "Hindi")
        add("ZH CHI CHINESE", "Kinesiska", "Kinesisk")
        add("JA JP JAP JAPANESE", "Japanska", "Japansk")
        add("KO KOR KOREAN", "Koreanska", "Koreansk")
        add("TH THA THAI", "Thailändska", "Thailändsk")
        add("SO SOM SOMALI", "Somaliska", "Somalisk")
        add("IS ICE ISL ICELANDIC", "Isländska", "Isländsk")
    }

    /** Länder och språk som kategorinamn ofta skrivs med, utöver språkkoderna ovan. */
    private val countries: Map<String, Language> = buildMap {
        fun add(names: String, kind: Kind = Kind.OTHER) {
            names.split(' ').forEach { put(it, Language(it, it, kind)) }
        }
        add("SWEDEN SVERIGE NORDIC NORDEN SCANDINAVIA SKANDINAVIEN", Kind.SWEDISH)
        add("NORWAY NORGE DENMARK DANMARK ICELAND", Kind.NORDIC)
        add("FINLAND SUOMI", Kind.FINNISH)
        add("ENGLAND BRITISH AMERICAN USA CANADA AUSTRALIA IRELAND", Kind.ENGLISH)
        add(
            "POLSKA POLAND ARAB ARABIA TURKEY TURKIYE TÜRK TÜRKÇE GERMANY DEUTSCH DEUTSCHLAND FRANCE FRANÇAIS " +
                "SPAIN ESPAÑA ESPANOL ESPAÑOL ITALIA ITALY PORTUGAL BRASIL BRAZIL NEDERLAND HOLLAND RUSSIA " +
                "IRAN AFGHAN AFGHANISTAN KURDISTAN ALBANIA SHQIP GREECE HELLAS ROMANIA BULGARIA UKRAINE INDIA " +
                "INDIAN PAKISTAN BANGLA BANGLADESH AFRICA AFRICAN AFRIKA LATINO LATAM MEXICO ARGENTINA EXYU BALKAN " +
                "SERBIA CROATIA BOSNIA KOREA JAPAN CHINA PHILIPPINES PINOY THAILAND VIETNAM MALAYSIA INDONESIA " +
                "SOMALIA ETHIOPIA ERITREA PUNJABI TAMIL TELUGU URDU BOLLYWOOD",
        )
    }

    private val groupSplitter = Regex("""[^\p{L}\p{N}]+""")

    private val subtitleWords = setOf("SUB", "SUBS", "SUBBED", "SUBTITLE", "SUBTITLES", "SUBTITLED", "TEXT", "TEXTAD", "TEXTED", "TXT", "UNDERTEXT")
    private val dubWords = setOf("DUB", "DUBB", "DUBBED", "DUBBAD", "DUBBING", "SYNC", "SYNKAD")
    private val voiceWords = setOf("LEKTOR", "LEKT", "VO", "VOICEOVER")
    private val multiWords = setOf("MULTI", "MULTISUB", "MULTISUBS")
    private val qualityWords = setOf("4K", "UHD", "2160P", "2160", "FHD", "1080P", "1080", "HD", "720P", "720", "SD", "480P", "HDR", "HDR10", "DV", "DOVI", "3D")

    private val others: Map<String, String> = mapOf(
        "PRE" to "Premiär",
        "PREMIERE" to "Premiär",
        "PREMIÄR" to "Premiär",
        "NF" to "Netflix",
        "NETFLIX" to "Netflix",
        "AMZN" to "Prime Video",
        "PRIME" to "Prime Video",
        "DSNP" to "Disney+",
        "DISNEY" to "Disney+",
        "HMAX" to "Max",
        "MAX" to "Max",
        "HBO" to "Max",
        "ATVP" to "Apple TV+",
        "VIAPLAY" to "Viaplay",
        "CMORE" to "C More",
        "SKY" to "Sky",
        "ATMOS" to "Atmos",
        "IMAX" to "IMAX",
        "EXTENDED" to "Förlängd version",
        "UNCUT" to "Oklippt",
        "REMASTERED" to "Remastrad",
        "DC" to "Regissörens version",
        "CAM" to "Biokopia",
        "HDCAM" to "Biokopia",
        "TS" to "Biokopia",
        "HDTS" to "Biokopia",
        "TELESYNC" to "Biokopia",
    )

    // ] ) och } som tecken skrivs med \ framför: Androids reguljära uttryck kräver det.
    private val segment = Regex("""\[([^\]]*)\]|\(([^)]*)\)|\{([^}]*)\}""")
    private val splitter = Regex("""[\s_/|,.+\-]+""")
    private val prefixCode = Regex("""^\s*([A-Za-z0-9]{2,5})\s*[-|:]\s+""")
    private val looseQuality = Regex("""(?i)(?:^|[\s_.\-])(4k|uhd|2160p|1080p|720p|fhd|hdr10|hdr)(?=$|[\s_.\-])""")
    private val year = Regex("""(?:19|20)\d{2}""")

    fun parse(name: String): Tags {
        val segments = ArrayList<List<String>>()
        for (match in segment.findAll(name)) {
            val content = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: continue
            val tokens = content.split(splitter).map { it.trim().uppercase() }.filter { it.isNotEmpty() && !year.matches(it) }
            if (tokens.isNotEmpty()) segments += tokens
        }
        prefixCode.find(name)?.groupValues?.get(1)?.uppercase()?.let { code ->
            if (code in languages || code in qualityWords || code in others) segments += listOf(code)
        }
        for (match in looseQuality.findAll(name)) segments += listOf(match.groupValues[1].uppercase())
        return interpret(segments)
    }

    private fun interpret(segments: List<List<String>>): Tags {
        val languageParts = LinkedHashSet<String>()
        val otherParts = LinkedHashSet<String>()
        var quality: String? = null
        var qualityRank = 0
        var hdr: String? = null
        var score = 0
        var saysLanguage = false
        var is4k = false
        // För språkvalet i biblioteket: nämns ett språk man förstår, eller bara andra?
        var friendly = false
        var foreign = false
        fun understands(kind: Kind?) {
            when (kind) {
                null -> Unit
                Kind.OTHER -> foreign = true
                else -> friendly = true
            }
        }

        // [PL] [LEKTOR]: språket står i ett eget segment.
        val alone = segments.filter { it.size == 1 }.mapNotNull { languages[it[0]] }
        val voiceElsewhere = segments.any { tokens -> tokens.any { it in voiceWords || it in dubWords } }

        for (tokens in segments) {
            val set = tokens.toSet()
            val langs = tokens.mapNotNull { languages[it] }.distinct()
            val subs = set.any { it in subtitleWords }
            val multi = set.any { it in multiWords }
            val voice = set.any { it in voiceWords }
            val dub = set.any { it in dubWords }
            val exYu = ("EX" in set && "YU" in set) || "EXYU" in set
            var understood = false

            when {
                multi && (subs || "MULTISUB" in set || "MULTISUBS" in set) -> {
                    languageParts += "Flera undertexter"
                    score += 40
                    saysLanguage = true
                    understood = true
                    friendly = true
                }
                multi -> {
                    languageParts += "Flera språk"
                    score += 30
                    saysLanguage = true
                    understood = true
                    friendly = true
                }
                subs -> {
                    val language = langs.firstOrNull()
                    languageParts += language?.let { it.adjective + " text" } ?: "Undertexter"
                    score += when (language?.kind) {
                        Kind.SWEDISH -> 60
                        Kind.NORDIC -> 30
                        Kind.FINNISH -> 12
                        Kind.ENGLISH -> 15
                        null -> 10
                        Kind.OTHER -> -25
                    }
                    understands(language?.kind)
                    saysLanguage = true
                    understood = true
                }
                voice -> {
                    val language = langs.firstOrNull() ?: alone.firstOrNull()
                    languageParts += language?.let { it.adjective + " speakerröst" } ?: "Speakerröst"
                    score -= 45
                    // Speakerröst (lektor) är polsk sed: utan språk räknas den som främmande.
                    understands(language?.kind ?: Kind.OTHER)
                    saysLanguage = true
                    understood = true
                }
                dub -> {
                    val language = langs.firstOrNull() ?: alone.firstOrNull()
                    languageParts += language?.let { it.adjective + " tal" } ?: "Dubbad"
                    score += when (language?.kind) {
                        Kind.SWEDISH -> 45
                        Kind.ENGLISH -> 20
                        Kind.NORDIC -> -10
                        else -> -45
                    }
                    understands(language?.kind)
                    saysLanguage = true
                    understood = true
                }
                exYu -> {
                    val english = langs.any { it.kind == Kind.ENGLISH }
                    languageParts += if (english) "Ex-YU, engelska" else "Ex-YU"
                    score -= 25
                    understands(if (english) Kind.ENGLISH else Kind.OTHER)
                    saysLanguage = true
                    understood = true
                }
                langs.size == 1 && tokens.size == 1 -> {
                    understands(langs.first().kind)
                    // Språket ensamt. Står det också [LEKTOR] eller [DUB] har det redan sagts där.
                    if (!voiceElsewhere) {
                        val language = langs.first()
                        languageParts += language.noun
                        score += when (language.kind) {
                            Kind.SWEDISH -> 55
                            Kind.NORDIC -> 20
                            Kind.FINNISH -> 0
                            Kind.ENGLISH -> 25
                            Kind.OTHER -> -35
                        }
                        saysLanguage = true
                    }
                    understood = true
                }
            }

            for (token in tokens) {
                when (token) {
                    "4K", "UHD", "2160P", "2160" -> {
                        is4k = true
                        if (qualityRank < 4) {
                            quality = "4K"
                            qualityRank = 4
                        }
                        understood = true
                    }
                    "FHD", "1080P", "1080" -> {
                        if (qualityRank < 3) {
                            quality = "Full HD"
                            qualityRank = 3
                        }
                        understood = true
                    }
                    "HD", "720P", "720" -> {
                        if (qualityRank < 2) {
                            quality = "HD"
                            qualityRank = 2
                        }
                        understood = true
                    }
                    "SD", "480P" -> {
                        if (qualityRank < 1) {
                            quality = "SD"
                            qualityRank = 1
                        }
                        understood = true
                    }
                    "HDR", "HDR10" -> {
                        if (hdr == null) hdr = "HDR"
                        understood = true
                    }
                    "DV", "DOVI" -> {
                        hdr = "Dolby Vision"
                        understood = true
                    }
                    "3D" -> {
                        otherParts += "3D"
                        understood = true
                    }
                    "HEVC", "H265", "X265", "H264", "X264", "AVC" -> understood = true
                    else -> others[token]?.let { label ->
                        otherParts += label
                        if (label == "Biokopia") score -= 30
                        understood = true
                    }
                }
            }

            // Något leverantören skrivit som appen inte känner igen: visas som det står.
            if (!understood) {
                val raw = tokens.joinToString(" ")
                if (raw.length in 2..14) otherParts += raw
            }
        }

        if (!saysLanguage) score += 20
        score += when (qualityRank) {
            3 -> 6
            2 -> 4
            4 -> 3
            1 -> -5
            else -> 0
        }
        val label = (languageParts + otherParts + listOfNotNull(quality, hdr)).joinToString(" · ")
        return Tags(label, score, is4k, friendly, foreign)
    }
}

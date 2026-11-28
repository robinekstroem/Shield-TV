package se.norrsken.tv.desktop.data

import kotlin.math.abs

/**
 * Julen i Filmer och Serier: Julfilmer — julfilmerna i biblioteket, bäst
 * betyg först — och Julkalendrar, alla julkalendrar som finns. Nycklarna
 * börjar med \u0001, som de egna kategoriernas, så att de aldrig krockar med
 * en kategori från leverantören.
 */
object JulKeys {
    const val FILMS = "\u0001julfilmer"
    const val CALENDARS = "\u0001julkalendrar"

    fun isJul(group: String?): Boolean = group == FILMS || group == CALENDARS

    /** Namnet som visas för julens kategorier, eller null för en annan. */
    fun titleOf(group: String?): String? = when (group) {
        FILMS -> "Julfilmer"
        CALENDARS -> "Julkalendrar"
        else -> null
    }
}

/** Nyckelorden hos TMDB som gör en film till julfilm. */
val CHRISTMAS_WORDS: Set<String> = setOf("christmas", "christmas eve", "christmas party", "santa claus")

/** Nyckelorden hos TMDB för julkalendrar, på engelska och skandinaviska. */
val CALENDAR_WORDS: Set<String> = setOf("advent calendar", "christmas calendar", "julkalender", "julekalender")

/** En julkalender: året den gick och namnen den kan ha i biblioteket. */
private class JulCalendar(val year: Int, vararg val names: String)

/** SVT:s julkalendrar, 1960–2025. */
private val SVT_CALENDARS = listOf(
    JulCalendar(1960, "Titteliture"),
    JulCalendar(1961, "Julbåten Juliana"),
    JulCalendar(1962, "Tomtefamiljen i Storskogen"),
    JulCalendar(1963, "Den tänkande brevbäraren"),
    JulCalendar(1964, "Lill-Stina på reportage i Storskogen"),
    JulCalendar(1965, "Farbror Pekkas handelsbod"),
    JulCalendar(1966, "En småstad vid seklets början"),
    JulCalendar(1967, "Gumman som blev liten som en tesked"),
    JulCalendar(1968, "Klart spår till Tomteboda"),
    JulCalendar(1969, "Herkules Jonssons storverk"),
    JulCalendar(1970, "Regnbågslandet"),
    JulCalendar(1971, "Broster, Broster!", "Broster Broster"),
    JulCalendar(1972, "Barnen i Höjden"),
    JulCalendar(1973, "Mumindalen"),
    JulCalendar(1974, "Rulle på Rullseröd"),
    JulCalendar(1975, "Långtradarchaufförens berättelser"),
    JulCalendar(1976, "Gumman som blev liten som en tesked"),
    JulCalendar(1977, "Fem myror är fler än fyra elefanter"),
    JulCalendar(1978, "Julius Julskötare"),
    JulCalendar(1979, "Trolltider"),
    JulCalendar(1980, "Det blir jul på Möllegården"),
    JulCalendar(1981, "Stjärnhuset"),
    JulCalendar(1982, "Albert & Herberts julkalender", "Albert och Herberts julkalender"),
    JulCalendar(1983, "Lille Luj och Änglaljus i strumpornas hus"),
    JulCalendar(1984, "Julstrul med Staffan & Bengt", "Julstrul med Staffan och Bengt"),
    JulCalendar(1985, "Trolltider med trollkalender"),
    JulCalendar(1986, "Julpussar och stjärnsmällar"),
    JulCalendar(1987, "Marias barn"),
    JulCalendar(1988, "Liv i luckan med julkalendern"),
    JulCalendar(1989, "T. Sventon praktiserande privatdetektiv", "T Sventon"),
    JulCalendar(1990, "Kurt Olssons julkalender"),
    JulCalendar(1991, "Sunes jul"),
    JulCalendar(1992, "Klasses julkalender"),
    JulCalendar(1993, "Tomtemaskinen"),
    JulCalendar(1994, "Håll huvet kallt"),
    JulCalendar(1995, "Jul i Kapernaum"),
    JulCalendar(1996, "Mysteriet på Greveholm"),
    JulCalendar(1997, "Pelle Svanslös"),
    JulCalendar(1998, "När karusellerna sover"),
    JulCalendar(1999, "Julens hjältar"),
    JulCalendar(2000, "Ronny & Julia", "Ronny och Julia"),
    JulCalendar(2001, "Kaspar i Nudådalen"),
    JulCalendar(2002, "Dieselråttor & sjömansmöss", "Dieselråttor och sjömansmöss"),
    JulCalendar(2003, "Håkan Bråkan"),
    JulCalendar(2004, "Allrams höjdarpaket"),
    JulCalendar(2005, "En decemberdröm"),
    JulCalendar(2006, "LasseMajas detektivbyrå", "Lasse-Majas detektivbyrå"),
    JulCalendar(2007, "En riktig jul"),
    JulCalendar(2008, "Skägget i brevlådan"),
    JulCalendar(2009, "Superhjältejul"),
    JulCalendar(2010, "Hotell Gyllene knorren"),
    JulCalendar(2011, "Tjuvarnas jul"),
    JulCalendar(2012, "Mysteriet på Greveholm: Grevens återkomst", "Grevens återkomst"),
    JulCalendar(2013, "Barna Hedenhös uppfinner julen"),
    JulCalendar(2014, "Piratskattens hemlighet"),
    JulCalendar(2015, "Tusen år till julafton"),
    JulCalendar(2016, "Selmas saga"),
    JulCalendar(2017, "Jakten på tidskristallen"),
    JulCalendar(2018, "Storm på Lugna gatan"),
    JulCalendar(2019, "Panik i tomteverkstan"),
    JulCalendar(2020, "Mirakel"),
    JulCalendar(2021, "En hederlig jul med Knyckertz"),
    JulCalendar(2022, "Kronprinsen som försvann"),
    JulCalendar(2023, "Trolltider – legenden om Bergatrollet", "Legenden om Bergatrollet"),
    JulCalendar(2024, "Snödrömmar"),
    JulCalendar(2025, "Tidstjuven"),
)

/**
 * Vilka filmer i biblioteket som är julfilmer, med betyget att sortera efter,
 * och vilka serier som är julkalendrar, med året de gick. Byggs i bakgrunden
 * ur TMDB:s listor, SVT:s julkalendrar och leverantörens kategorinamn.
 */
class JulIndex private constructor(
    /** Filmens nyckel → betyget räknat som IMDb:s topplista, eller null. */
    val films: Map<String, Double?>,
    /** Seriens nyckel → året den gick, eller null. */
    val calendars: Map<String, Int?>,
    /** Hur många julfilmer, varje film en gång hur många versioner den än har. */
    val filmCount: Int,
    /** Hur många julkalendrar, varje serie en gång. */
    val calendarCount: Int,
) {
    companion object {
        val EMPTY = JulIndex(emptyMap(), emptyMap(), 0, 0)

        /**
         * Filmerna: TMDB:s julfilmer ([christmasFilms]) efter TMDB-id, annars
         * efter namn och år. Serierna: SVT:s julkalendrar, TMDB:s
         * ([christmasShows]) och allt i en kategori som heter något med julkalender.
         */
        fun build(
            library: Library,
            versions: VersionIndex,
            christmasFilms: List<TmdbListTitle>,
            christmasShows: List<TmdbListTitle>,
            movieTmdbId: (Movie) -> Int?,
            seriesTmdbId: (Series) -> Int?,
        ): JulIndex {
            val films = HashMap<String, Double?>()
            val filmGroups = HashSet<String>()
            if (christmasFilms.isNotEmpty()) {
                val byId = christmasFilms.associateBy { it.id }
                val byName = titlesByName(christmasFilms)
                for (movie in library.movies) {
                    if (versions.isHidden(movie)) continue
                    val hit = movieTmdbId(movie)?.let { byId[it] }
                        ?: pick(byName[collectionName(versions.titleOf(movie))], versions.yearOf(movie))
                        ?: continue
                    films[movie.key] = weightedScore(hit.rating, hit.votes)
                    filmGroups += versions.groupKeyOf(movie)
                }
            }

            val showIds = christmasShows.associateBy { it.id }
            val showNames = titlesByName(christmasShows)
            val known = HashMap<String, MutableList<Int>>()
            for (calendar in SVT_CALENDARS) {
                for (raw in calendar.names) {
                    val name = collectionName(raw)
                    if (name.isNotEmpty()) known.getOrPut(name) { ArrayList(1) } += calendar.year
                }
            }
            val calendars = HashMap<String, Int?>()
            val calendarGroups = HashSet<String>()
            for (series in library.series) {
                if (versions.isHidden(series)) continue
                val year = versions.yearOf(series)
                val name = collectionName(versions.titleOf(series))
                val inCategory = series.group.contains("julkalend", ignoreCase = true) ||
                    series.group.contains("julekalend", ignoreCase = true) ||
                    series.name.contains("julkalender", ignoreCase = true)
                val fromTmdb = seriesTmdbId(series)?.let { showIds[it] } ?: pick(showNames[name], year)
                val fromSvt = known[name]?.let { years -> svtYear(years, year, name) }
                if (!inCategory && fromTmdb == null && fromSvt == null) continue
                calendars[series.key] = year ?: fromSvt ?: fromTmdb?.year
                calendarGroups += versions.groupKeyOf(series)
            }
            return JulIndex(films, calendars, filmGroups.size, calendarGroups.size)
        }

        private fun titlesByName(list: List<TmdbListTitle>): Map<String, List<TmdbListTitle>> {
            val map = HashMap<String, MutableList<TmdbListTitle>>()
            for (title in list) {
                for (raw in setOf(title.title, title.original)) {
                    val name = collectionName(raw)
                    if (name.isNotEmpty()) map.getOrPut(name) { ArrayList(1) } += title
                }
            }
            return map
        }

        /** Träffen vars år stämmer (ett års marginal). Utan år bara om namnet är entydigt. */
        private fun pick(candidates: List<TmdbListTitle>?, year: Int?): TmdbListTitle? {
            if (candidates.isNullOrEmpty()) return null
            if (year == null) return candidates.singleOrNull()
            return candidates.firstOrNull { it.year != null && abs(it.year - year) <= 1 }
        }

        /**
         * Året för en av SVT:s julkalendrar med det här namnet. Stämmer inte
         * året räcker ett långt namn (tre ord eller fler): de korta finns ofta
         * som andra serier också.
         */
        private fun svtYear(years: List<Int>, year: Int?, name: String): Int? {
            if (year == null) return years.first()
            years.firstOrNull { abs(it - year) <= 1 }?.let { return it }
            return if (name.count { it == ' ' } >= 2) years.first() else null
        }
    }
}

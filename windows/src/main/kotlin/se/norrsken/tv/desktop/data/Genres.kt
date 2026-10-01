package se.norrsken.tv.desktop.data

/**
 * Genrer som appen känner igen i leverantörens kategorinamn och i seriernas
 * genretext. Leverantören skickar ingen genre för filmer, bara kategorin, så
 * det är där den får hittas: "SE | Barnfilmer", "EN - Action", "Skräck".
 *
 * Mönstren täcker svenska, engelska och grannspråken, och sammansättningar
 * som "Skräckfilm" (\w* efter ordets början). Korta engelska ord matchas
 * bara som hela ord: "war" ska inte hitta "Star Wars".
 */
enum class Genre(val label: String, pattern: String) {
    ACTION("Action", """action\w*"""),
    ADVENTURE("Äventyr", """äventyr\w*|adventure\w*|eventyr\w*"""),
    COMEDY("Komedi", """komedi\w*|comed\w*|humor\w*"""),
    DRAMA("Drama", """drama\w*"""),
    THRILLER("Thriller", """thriller\w*|spänning\w*"""),
    CRIME("Kriminal", """crime|krim\w*|deckare\w*|kriminal\w*"""),
    HORROR("Skräck", """horror|skräck\w*|skrekk\w*|gyser\w*"""),
    SCIFI("Science fiction", """sci-?fi|science[- ]fiction"""),
    FANTASY("Fantasy", """fantasy"""),
    ANIMATION("Animerat", """animat\w*|animer\w*|tecknad\w*|tecknat|anime|cartoon\w*"""),
    FAMILY("Barn & familj", """famil\w*|kids|barn\w*|children|børn\w*"""),
    DOCUMENTARY("Dokumentär", """dokument\w*|documentar\w*|docu\w*"""),
    ROMANCE("Romantik", """romanti\w*|romance|romcom|kärlek\w*"""),
    WAR("Krig", """war|krig\w*"""),
    HISTORY("Historia", """histor\w*"""),
    MUSIC("Musik", """music\w*|musik\w*|konsert\w*|concert\w*"""),
    MYSTERY("Mysterium", """myster\w*"""),
    WESTERN("Western", """western\w*"""),
    SPORT("Sport", """sport\w*"""),
    CHRISTMAS("Jul", """christmas|jul|julfilm\w*|xmas"""),
    STANDUP("Stand-up", """stand[- ]?up"""),
    REALITY("Reality", """reality"""),
    ;

    /** Skiftlägesokänsligt, och Unicode-medvetet så att å, ä och ö räknas som bokstäver. */
    internal val regex = Regex("""(?iuU)\b(?:$pattern)\b""")
}

/** Vilka genrer ett kategorinamn eller en genretext pekar på. */
fun genresIn(text: String?): Set<Genre> =
    if (text.isNullOrBlank()) emptySet() else Genre.entries.filterTo(LinkedHashSet()) { it.regex.containsMatchIn(text) }

/**
 * Biblioteket sorterat per genre. Mönstren prövas en gång per unikt
 * kategorinamn och genretext, inte en gång per titel — kategorierna är
 * några hundra, titlarna tiotusentals.
 */
class GenreIndex(
    val movies: Map<Genre, List<Movie>>,
    val series: Map<Genre, List<Series>>,
) {
    companion object {
        val EMPTY = GenreIndex(emptyMap(), emptyMap())

        fun build(
            library: Library,
            facts: Map<String, MovieFacts> = emptyMap(),
            movieExtra: (Movie) -> String? = { null },
            seriesExtra: (Series) -> String? = { null },
        ): GenreIndex {
            val known = HashMap<String, Set<Genre>>()
            fun genresOf(text: String?): Set<Genre> =
                if (text.isNullOrBlank()) emptySet() else known.getOrPut(text) { genresIn(text) }

            val movies = HashMap<Genre, MutableList<Movie>>()
            for (movie in library.movies) {
                val own = facts[MovieFactsStore.keyOf(movie.sourceId, movie.streamId)]?.genre
                for (genre in genresOf(movie.group) + genresOf(own) + genresOf(movieExtra(movie))) movies.getOrPut(genre) { ArrayList() } += movie
            }
            val series = HashMap<Genre, MutableList<Series>>()
            for (item in library.series) {
                for (genre in genresOf(item.group) + genresOf(item.genre) + genresOf(seriesExtra(item))) series.getOrPut(genre) { ArrayList() } += item
            }
            return GenreIndex(movies, series)
        }
    }
}

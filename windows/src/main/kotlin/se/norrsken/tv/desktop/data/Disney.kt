package se.norrsken.tv.desktop.data

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Disney under Filmer: tre samlingar som appen fyller själv ur hela
 * biblioteket, i alla kategorier, så att också svensktalade versioner kommer
 * med. Ordningen är filmhistoriens: kortfilmerna först, sedan de tecknade
 * klassikerna, sist Pixar.
 */
enum class DisneyShelf(
    /** Kategorins nyckel i listan. Kan inte krocka med leverantörens kategorinamn. */
    val key: String,
    val title: String,
) {
    SHORTS("\u0001disney:kortfilmer", "Kalle Anka & vänner"),
    CLASSICS("\u0001disney:klassiker", "Disneyklassiker"),
    PIXAR("\u0001disney:pixar", "Pixar"),
    ;

    companion object {
        /** Alla tre samlingarna tillsammans: "Disney" i kategorilistan. */
        const val ALL_KEY = "\u0001disney"

        fun of(key: String?): DisneyShelf? = entries.firstOrNull { it.key == key }

        fun isDisney(key: String?): Boolean = key == ALL_KEY || of(key) != null
    }
}

/**
 * Vilka filmer och serier som hör till vilken samling. Byggs i bakgrunden när
 * biblioteket, versionerna eller de egna valen ändras.
 */
class DisneyIndex private constructor(
    /** "m:" + filmens nyckel eller "s:" + seriens → samlingen. */
    private val shelves: Map<String, DisneyShelf>,
    /** Klassikerns eller Pixarfilmens nummer i ordningen. */
    private val numbers: Map<String, Int>,
    /** Kortfilmernas figur: "Kalle Anka", "Musse Pigg". */
    private val characters: Map<String, String>,
    /** Antal titlar per samling, varje film eller serie en gång. */
    val counts: Map<DisneyShelf, Int>,
    /** Figurerna som finns bland kortfilmerna, i fast ordning. */
    val characterNames: List<String>,
) {
    val isEmpty: Boolean get() = shelves.isEmpty()

    val total: Int get() = counts.values.sum()

    fun shelfOf(movie: Movie): DisneyShelf? = shelves[M + movie.key]

    fun shelfOf(series: Series): DisneyShelf? = shelves[S + series.key]

    fun numberOf(movie: Movie): Int? = numbers[M + movie.key]

    fun characterOf(movie: Movie): String? = characters[M + movie.key]

    fun characterOf(series: Series): String? = characters[S + series.key]

    companion object {
        val EMPTY = DisneyIndex(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList())

        /** Antal titlar i samlingen som finns, oavsett vad leverantören har. */
        val CLASSICS_TOTAL: Int get() = CLASSICS.size
        val PIXAR_TOTAL: Int get() = PIXAR.size

        /** Valet "inte Disney" bland de egna valen. */
        const val NONE = "NONE"

        /** Nyckeln för ett eget val: titeln och året, samma för alla versioner av filmen. */
        fun choiceKey(title: String, year: Int?): String = clean(title) + "|" + (year?.toString() ?: "")

        /** Visas valet Disney på filmsidan: filmen är med, eller kategorin eller titeln låter som Disney. */
        fun isCandidate(group: String, title: String): Boolean {
            val text = " " + fold(group) + " " + fold(title) + " "
            return CANDIDATE_WORDS.any { " $it" in text }
        }

        fun build(library: Library, versions: VersionIndex, choices: Map<String, String>): DisneyIndex {
            val shelves = HashMap<String, DisneyShelf>()
            val numbers = HashMap<String, Int>()
            val characters = HashMap<String, String>()
            val groups = HashMap<DisneyShelf, HashSet<String>>()

            for (movie in library.movies) {
                if (versions.isHidden(movie)) continue
                val title = versions.titleOf(movie)
                val year = versions.yearOf(movie)
                val name = clean(title)
                val plain = fold(title)
                val hint = disneyHint(movie.group)
                val canon = canonFor(name, year, hint)
                val choice = choices[choiceKey(title, year)]
                val shelf: DisneyShelf?
                var character: String? = null
                if (choice != null) {
                    // Egna val går före det appen själv kommer fram till.
                    shelf = DisneyShelf.entries.firstOrNull { it.name == choice }
                    if (shelf == DisneyShelf.SHORTS) character = characterOf(plain, null, true) ?: OTHERS
                } else {
                    shelf = canon?.first ?: characterOf(plain, year, hint)?.let {
                        character = it
                        DisneyShelf.SHORTS
                    }
                }
                if (shelf == null) continue
                val key = M + movie.key
                shelves[key] = shelf
                if (canon != null && canon.first == shelf) numbers[key] = canon.second.number
                character?.let { characters[key] = it }
                groups.getOrPut(shelf) { HashSet() }.add(versions.groupKeyOf(movie))
            }

            // Kortfilmerna ligger ibland som serier: en samling med en kortfilm per avsnitt.
            for (series in library.series) {
                if (versions.isHidden(series)) continue
                val plain = fold(versions.titleOf(series))
                val character = characterOf(plain, versions.yearOf(series), disneyHint(series.group)) ?: continue
                val key = S + series.key
                shelves[key] = DisneyShelf.SHORTS
                characters[key] = character
                groups.getOrPut(DisneyShelf.SHORTS) { HashSet() }.add(versions.groupKeyOf(series))
            }

            val present = characters.values.toSet()
            return DisneyIndex(
                shelves = shelves,
                numbers = numbers,
                characters = characters,
                counts = groups.mapValues { it.value.size },
                characterNames = CHARACTER_ORDER.filter { it in present },
            )
        }

        /** Klassikern eller Pixarfilmen med det här namnet och ett år som stämmer. */
        private fun canonFor(name: String, year: Int?, hint: Boolean): Pair<DisneyShelf, CanonTitle>? =
            CANON_BY_NAME[name]?.firstOrNull { (_, title) -> fits(title, year, hint) }

        /**
         * Året ska stämma, med ett års marginal (två för de gamla, som kom
         * till Sverige senare). Utan år räcker namnet, utom för de titlar som
         * har nyinspelningar eller vanliga namn; de kräver en Disneykategori.
         */
        private fun fits(title: CanonTitle, year: Int?, hint: Boolean): Boolean {
            if (year != null) return abs(year - title.year) <= (if (title.year < 1960) 2 else 1)
            return !title.ambiguous || hint
        }

        /**
         * Figuren i en gammal kortfilm, eller null. De nya serierna och
         * filmerna med samma figurer (Musse Piggs klubbhus, DuckTales) hör
         * inte hit, och inte heller något från efter 1990. Vanliga ord som
         * "Pluto" kräver ett år eller en Disneykategori.
         */
        private fun characterOf(name: String, year: Int?, hint: Boolean): String? {
            val padded = " $name "
            // Julaftonens Kalle Anka visas varje år; leverantören kan ha årets årtal på den.
            if (CHRISTMAS.any { it in padded }) return "Kalle Anka"
            if (MODERN.any { it in padded }) return null
            if (year != null && year > SHORTS_LAST_YEAR) return null
            STRONG.firstOrNull { (word, _) -> " $word " in padded }?.let { return it.second }
            val weak = WEAK.firstOrNull { (word, _) -> " $word " in padded } ?: return null
            return if (year != null || hint) weak.second else null
        }

        private fun disneyHint(group: String): Boolean {
            val g = fold(group)
            return "disney" in g || "pixar" in g
        }

        /**
         * Titeln att jämföra med listorna: små bokstäver utan accenter och
         * skiljetecken, utan "Disney's" först, och utan "the", "and" och "och",
         * så att "Jungle Book" är "The Jungle Book" och "&" är "and".
         */
        private fun clean(title: String): String {
            var name = fold(title)
            for (prefix in PREFIXES) {
                if (name.startsWith("$prefix ")) {
                    name = name.removePrefix("$prefix ").trim()
                    break
                }
            }
            return name.split(' ').filter { it.isNotEmpty() && it !in STOP_WORDS }.joinToString(" ")
        }

        private val CANON_BY_NAME: Map<String, List<Pair<DisneyShelf, CanonTitle>>> by lazy {
            val map = HashMap<String, MutableList<Pair<DisneyShelf, CanonTitle>>>()
            for (title in CLASSICS) for (n in title.names) map.getOrPut(clean(n)) { ArrayList() }.add(DisneyShelf.CLASSICS to title)
            for (title in PIXAR) for (n in title.names) map.getOrPut(clean(n)) { ArrayList() }.add(DisneyShelf.PIXAR to title)
            map
        }
    }
}

private const val M = "m:"
private const val S = "s:"

/** Kortfilmer och korta filmer med figurerna; därefter kommer de nya serierna. */
private const val SHORTS_LAST_YEAR = 1990

private const val OTHERS = "Övriga"

private class CanonTitle(
    val number: Int,
    val year: Int,
    /** Engelska och svenska namn, och andra stavningar. */
    val names: List<String>,
    /** Finns som nyinspelning eller har ett vanligt namn: kräver år eller en Disneykategori. */
    val ambiguous: Boolean = false,
)

private fun t(number: Int, year: Int, vararg names: String, ambiguous: Boolean = false) =
    CanonTitle(number, year, names.toList(), ambiguous)

/** Disneys tecknade klassiker, i Disneys egen numrering. */
private val CLASSICS = listOf(
    t(1, 1937, "Snow White and the Seven Dwarfs", "Snövit och de sju dvärgarna"),
    t(2, 1940, "Pinocchio", ambiguous = true),
    t(3, 1940, "Fantasia"),
    t(4, 1941, "Dumbo", ambiguous = true),
    t(5, 1942, "Bambi"),
    t(6, 1942, "Saludos Amigos"),
    t(7, 1944, "The Three Caballeros", "Tre caballeros"),
    t(8, 1946, "Make Mine Music"),
    t(9, 1947, "Fun and Fancy Free", "Pank och fågelfri"),
    t(10, 1948, "Melody Time"),
    t(11, 1949, "The Adventures of Ichabod and Mr. Toad", "Ichabod and Mr. Toad"),
    t(12, 1950, "Cinderella", "Askungen", ambiguous = true),
    t(13, 1951, "Alice in Wonderland", "Alice i Underlandet", ambiguous = true),
    t(14, 1953, "Peter Pan", ambiguous = true),
    t(15, 1955, "Lady and the Tramp", "Lady och Lufsen", ambiguous = true),
    t(16, 1959, "Sleeping Beauty", "Törnrosa", ambiguous = true),
    t(17, 1961, "One Hundred and One Dalmatians", "101 Dalmatians", "Pongo och de 101 dalmatinerna", ambiguous = true),
    t(18, 1963, "The Sword in the Stone", "Svärdet i stenen"),
    t(19, 1967, "The Jungle Book", "Djungelboken", ambiguous = true),
    t(20, 1970, "The Aristocats", "Aristocats"),
    t(21, 1973, "Robin Hood", ambiguous = true),
    t(22, 1977, "The Many Adventures of Winnie the Pooh", "Nalle Puhs äventyr"),
    t(23, 1977, "The Rescuers", "Räddningspatrullen"),
    t(24, 1981, "The Fox and the Hound", "Micke och Molle"),
    t(25, 1985, "The Black Cauldron", "Taran och den magiska kitteln"),
    t(26, 1986, "The Great Mouse Detective", "Basil – mästerdetektiven"),
    t(27, 1988, "Oliver & Company", "Oliver and Company", "Oliver & gänget"),
    t(28, 1989, "The Little Mermaid", "Den lilla sjöjungfrun", ambiguous = true),
    t(29, 1990, "The Rescuers Down Under", "Räddningspatrullen i Australien"),
    t(30, 1991, "Beauty and the Beast", "Skönheten och odjuret", ambiguous = true),
    t(31, 1992, "Aladdin", ambiguous = true),
    t(32, 1994, "The Lion King", "Lejonkungen", ambiguous = true),
    t(33, 1995, "Pocahontas", ambiguous = true),
    t(34, 1996, "The Hunchback of Notre Dame", "Ringaren i Notre Dame", ambiguous = true),
    t(35, 1997, "Hercules", "Herkules", ambiguous = true),
    t(36, 1998, "Mulan", ambiguous = true),
    t(37, 1999, "Tarzan", ambiguous = true),
    t(38, 1999, "Fantasia 2000"),
    t(39, 2000, "Dinosaur", "Dinosaurier", ambiguous = true),
    t(40, 2000, "The Emperor's New Groove", "Kejsarens nya stil"),
    t(41, 2001, "Atlantis: The Lost Empire", "Atlantis – En försvunnen värld"),
    t(42, 2002, "Lilo & Stitch", "Lilo and Stitch", ambiguous = true),
    t(43, 2002, "Treasure Planet", "Skattkammarplaneten"),
    t(44, 2003, "Brother Bear", "Björnbröder"),
    t(45, 2004, "Home on the Range", ambiguous = true),
    t(46, 2005, "Chicken Little"),
    t(47, 2007, "Meet the Robinsons", "Möt Robinsons"),
    t(48, 2008, "Bolt", ambiguous = true),
    t(49, 2009, "The Princess and the Frog", "Prinsessan och grodan"),
    t(50, 2010, "Tangled", "Trassel"),
    t(51, 2011, "Winnie the Pooh", "Nalle Puh", ambiguous = true),
    t(52, 2012, "Wreck-It Ralph", "Röjar-Ralf"),
    t(53, 2013, "Frozen", "Frost", ambiguous = true),
    t(54, 2014, "Big Hero 6"),
    t(55, 2016, "Zootopia", "Zootropolis"),
    t(56, 2016, "Moana", "Vaiana", ambiguous = true),
    t(57, 2018, "Ralph Breaks the Internet", "Röjar-Ralf kraschar internet", "Wreck-It Ralph 2"),
    t(58, 2019, "Frozen II", "Frozen 2", "Frost 2"),
    t(59, 2021, "Raya and the Last Dragon", "Raya och den sista draken"),
    t(60, 2021, "Encanto"),
    t(61, 2022, "Strange World", ambiguous = true),
    t(62, 2023, "Wish", "Önskan", ambiguous = true),
    t(63, 2024, "Moana 2", "Vaiana 2"),
    t(64, 2025, "Zootopia 2", "Zootropolis 2"),
)

/** Pixars långfilmer, i ordning. */
private val PIXAR = listOf(
    t(1, 1995, "Toy Story"),
    t(2, 1998, "A Bug's Life", "Ett småkryps liv"),
    t(3, 1999, "Toy Story 2"),
    t(4, 2001, "Monsters, Inc.", "Monsterbolaget"),
    t(5, 2003, "Finding Nemo", "Hitta Nemo"),
    t(6, 2004, "The Incredibles", "Superhjältarna"),
    t(7, 2006, "Cars", "Bilar", ambiguous = true),
    t(8, 2007, "Ratatouille", "Råttatouille"),
    t(9, 2008, "WALL-E", "WALL·E"),
    t(10, 2009, "Up", "Upp", ambiguous = true),
    t(11, 2010, "Toy Story 3"),
    t(12, 2011, "Cars 2", "Bilar 2"),
    t(13, 2012, "Brave", "Modig", ambiguous = true),
    t(14, 2013, "Monsters University"),
    t(15, 2015, "Inside Out", "Insidan ut", ambiguous = true),
    t(16, 2015, "The Good Dinosaur", "Den gode dinosaurien"),
    t(17, 2016, "Finding Dory", "Hitta Doris"),
    t(18, 2017, "Cars 3", "Bilar 3"),
    t(19, 2017, "Coco", ambiguous = true),
    t(20, 2018, "Incredibles 2", "The Incredibles 2", "Superhjältarna 2"),
    t(21, 2019, "Toy Story 4"),
    t(22, 2020, "Onward", "Framåt", ambiguous = true),
    t(23, 2020, "Soul", ambiguous = true),
    t(24, 2021, "Luca", ambiguous = true),
    t(25, 2022, "Turning Red"),
    t(26, 2022, "Lightyear"),
    t(27, 2023, "Elemental", ambiguous = true),
    t(28, 2024, "Inside Out 2", "Insidan ut 2"),
    t(29, 2025, "Elio", ambiguous = true),
    t(30, 2026, "Hoppers", ambiguous = true),
    t(31, 2026, "Toy Story 5"),
)

/** Ord som säger vem det är, på svenska och engelska. "Mickey's" blir "mickey s". */
private val STRONG: List<Pair<String, String>> = listOf(
    "kalle anka" to "Kalle Anka",
    "kalle ankas" to "Kalle Anka",
    "donald duck" to "Kalle Anka",
    "donald s" to "Kalle Anka",
    "kajsa anka" to "Kalle Anka",
    "daisy duck" to "Kalle Anka",
    "farbror joakim" to "Kalle Anka",
    "scrooge mcduck" to "Kalle Anka",
    "knatte fnatte" to "Kalle Anka",
    "huey dewey" to "Kalle Anka",
    "ludwig von drake" to "Kalle Anka",
    "ludwig von anka" to "Kalle Anka",
    "from all of us to all of you" to "Kalle Anka",
    "musse pigg" to "Musse Pigg",
    "musse piggs" to "Musse Pigg",
    "mickey mouse" to "Musse Pigg",
    "mickey s" to "Musse Pigg",
    "mimmi pigg" to "Musse Pigg",
    "minnie mouse" to "Musse Pigg",
    "minnie s" to "Musse Pigg",
    "steamboat willie" to "Musse Pigg",
    "plane crazy" to "Musse Pigg",
    "the band concert" to "Musse Pigg",
    "langben" to "Långben",
    "langbens" to "Långben",
    "piff och puff" to "Piff och Puff",
    "chip n dale" to "Piff och Puff",
    "chip an dale" to "Piff och Puff",
    "chip and dale" to "Piff och Puff",
    "silly symphony" to "Silly Symphonies",
    "silly symphonies" to "Silly Symphonies",
    "oswald the lucky rabbit" to "Övriga",
    "lambert the sheepish lion" to "Övriga",
    "humphrey the bear" to "Övriga",
    "tjuren ferdinand" to "Övriga",
    "ferdinand the bull" to "Övriga",
)

/** Ord som också betyder annat: räknas bara med ett gammalt år eller i en Disneykategori. */
private val WEAK: List<Pair<String, String>> = listOf(
    "goofy" to "Långben",
    "goofy s" to "Långben",
    "pluto" to "Pluto",
    "pluto s" to "Pluto",
    "figaro" to "Övriga",
    "oswald" to "Övriga",
    "three little pigs" to "Silly Symphonies",
    "tre sma grisarna" to "Silly Symphonies",
    "the tortoise and the hare" to "Silly Symphonies",
    "the old mill" to "Silly Symphonies",
    "flowers and trees" to "Silly Symphonies",
    "the ugly duckling" to "Silly Symphonies",
)

/** "Kalle Anka och hans vänner önskar god jul", med vilket årtal som helst. */
private val CHRISTMAS = listOf(" onskar god jul ", " from all of us to all of you ", " kalle ankas julafton ", " kalle anka pa julafton ")

/** De nya serierna och filmerna med samma figurer. */
private val MODERN = listOf(
    " clubhouse ", " klubbhus ", " roadster ", " mixed up ", " funhouse ", " rescue rangers ", " ducktales ",
    " duck tales ", " goof troop ", " goofy movie ", " house of mouse ", " mickey mouse works ", " wonderful world of mickey ",
    " once upon a christmas ", " twice upon a christmas ", " magical christmas ", " house of villains ", " quack pack ",
    " darkwing ", " legend of the three caballeros ", " mickey 17 ",
)

private val CHARACTER_ORDER = listOf("Kalle Anka", "Musse Pigg", "Långben", "Pluto", "Piff och Puff", "Silly Symphonies", OTHERS)

private val STOP_WORDS = setOf("the", "and", "och")

/** "Disney's", "Walt Disney's", "Disney Pixar's" före titeln. */
private val PREFIXES = listOf(
    "walt disney s", "walt disneys", "walt disney", "disney pixar s", "disney pixars", "disney pixar", "disney s", "disneys", "pixar s", "pixars",
)

/** Kategorier och titlar där valet Disney visas på filmsidan. */
private val CANDIDATE_WORDS = listOf(
    "disney", "pixar", "walt", "kids", "barn", "family", "familj", "animat", "tecknat", "cartoon", "klassiker", "classic",
)

/** Samma vikning som Disney använder, för samlingarna. */
internal fun foldTitle(value: String): String = fold(value)

/** Små bokstäver utan accenter, bara bokstäver och siffror med ett mellanslag emellan. */
private fun fold(value: String): String {
    val out = StringBuilder(value.length)
    var gap = false
    for (raw in value) {
        val c = raw.lowercaseChar()
        val piece = when {
            c in 'a'..'z' || c in '0'..'9' -> null
            c.code < 128 -> ""
            c.category == CharCategory.NON_SPACING_MARK -> continue
            else -> foldChar(c)
        }
        if (piece != null && piece.isEmpty()) {
            gap = true
            continue
        }
        if (gap && out.isNotEmpty()) out.append(' ')
        gap = false
        if (piece == null) out.append(c) else out.append(piece)
    }
    return out.toString()
}

private val folded = ConcurrentHashMap<Char, String>()

private fun foldChar(c: Char): String = folded.getOrPut(c) {
    when (c) {
        'ø' -> "o"
        'æ' -> "ae"
        'œ' -> "oe"
        'ß' -> "ss"
        'ł' -> "l"
        // WALL·E: punkten mitt i ordet är ett skiljetecken.
        '·' -> ""
        else -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD).filter { it in 'a'..'z' || it in '0'..'9' }
    }
}

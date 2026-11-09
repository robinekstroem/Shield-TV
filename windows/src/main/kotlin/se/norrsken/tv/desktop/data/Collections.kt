package se.norrsken.tv.desktop.data

import kotlin.math.abs

/**
 * Samlingarna: filmserier och universum — Marvel, Star Wars, Harry Potter,
 * James Bond och de andra — med filmerna i rätt ordning. Appen letar upp
 * dem i biblioteket efter titel och år, på engelska och svenska, så att man
 * ser hela serien: det man har, och det som saknas.
 *
 * Disneys tre samlingar (Kalle Anka & vänner, klassikerna, Pixar) har egna
 * regler och ligger i [DisneyIndex]; de visas bredvid de här i galleriet.
 */
class Franchise internal constructor(
    val id: String,
    val title: String,
    val tagline: String,
    val from: Long,
    val to: Long,
    val accent: Long,
    val parts: List<FranchisePart>,
    byYear: Boolean,
    storyTitles: List<String>?,
) {
    /** Alla filmer i utgivningsordning, eller del för del ([byYear] falskt). */
    val films: List<FranchiseFilm> = parts.flatMap { it.films }.let { all -> if (byYear) all.sortedBy { it.year } else all }

    /**
     * Berättelsens ordning, när den skiljer sig från utgivningen. Bara om
     * listan täcker alla filmerna — annars hellre ingen än en som tappar någon.
     */
    val story: List<FranchiseFilm>? = storyTitles
        ?.mapNotNull { name -> films.firstOrNull { it.title == name } }
        ?.takeIf { it.size == films.size && it.toSet().size == films.size }

    val key: String get() = CollectionKeys.of(id)

    internal val positions: Map<FranchiseFilm, Int> = films.withIndex().associate { it.value to it.index }

    fun positionOf(film: FranchiseFilm): Int = positions[film] ?: -1
}

class FranchisePart internal constructor(val title: String, val films: List<FranchiseFilm>)

/** En film i en samling. Namn med "~" först kräver året: det finns andra filmer som heter så. */
class FranchiseFilm internal constructor(val year: Int, internal val names: List<String>) {
    val title: String = names.first().removePrefix("~")
}

/** Nycklarna i Filmers kategorilista: galleriet, och en samling. */
object CollectionKeys {
    const val GALLERY = "\u0001samlingar"
    private const val PREFIX = "\u0001samling:"

    fun of(id: String): String = PREFIX + id

    fun isGallery(group: String?): Boolean = group == GALLERY

    fun franchiseOf(group: String?): Franchise? =
        if (group != null && group.startsWith(PREFIX)) FRANCHISES_BY_ID[group.removePrefix(PREFIX)] else null

    /** Galleriet, en samling eller Disney: allt som hör till Samlingar. */
    fun isCollections(group: String?): Boolean = isGallery(group) || franchiseOf(group) != null || DisneyShelf.isDisney(group)

    /** Inne i en samling eller i Disney: Tillbaka går till galleriet. */
    fun isInside(group: String?): Boolean = franchiseOf(group) != null || DisneyShelf.isDisney(group)
}

/** Filmerna man har ur varje samling: samlingens id → filmens plats i [Franchise.films] → filmen. */
class CollectionIndex private constructor(private val owned: Map<String, Map<Int, Movie>>) {

    fun ownedIn(franchise: Franchise): Map<Int, Movie> = owned[franchise.id].orEmpty()

    fun count(franchise: Franchise): Int = owned[franchise.id]?.size ?: 0

    /** Samlingarna man har något ur, i galleriets ordning. */
    fun present(): List<Franchise> = FRANCHISES.filter { count(it) > 0 }

    val isEmpty: Boolean get() = owned.isEmpty()

    companion object {
        val EMPTY = CollectionIndex(emptyMap())

        /** Går igenom filmerna en gång. Gömda versioner hoppas över: en film per titel räcker. */
        fun build(library: Library, versions: VersionIndex): CollectionIndex {
            val owned = HashMap<String, HashMap<Int, Movie>>()
            for (movie in library.movies) {
                if (versions.isHidden(movie)) continue
                val candidates = NAMES[collectionName(versions.titleOf(movie))] ?: continue
                val year = versions.yearOf(movie)
                for (candidate in candidates) {
                    if (!fits(candidate, year)) continue
                    val franchise = candidate.franchise
                    val position = franchise.positionOf(candidate.film)
                    if (position < 0) continue
                    owned.getOrPut(franchise.id) { HashMap() }.putIfAbsent(position, movie)
                }
            }
            return CollectionIndex(owned)
        }

        /** Året ska stämma, med ett års marginal (två för de gamla). Utan år räcker namnet, utom för "~"-namnen. */
        private fun fits(candidate: Candidate, year: Int?): Boolean {
            val film = candidate.film
            if (year != null) return abs(year - film.year) <= (if (film.year < 1970) 2 else 1)
            return !candidate.ambiguous
        }

        private class Candidate(val franchise: Franchise, val film: FranchiseFilm, val ambiguous: Boolean)

        private val NAMES: Map<String, List<Candidate>> by lazy {
            val map = HashMap<String, MutableList<Candidate>>()
            for (franchise in FRANCHISES) {
                for (film in franchise.films) {
                    for (raw in film.names) {
                        val ambiguous = raw.startsWith("~")
                        val name = collectionName(raw.removePrefix("~"))
                        if (name.isEmpty()) continue
                        val list = map.getOrPut(name) { ArrayList() }
                        if (list.none { it.franchise === franchise && it.film === film }) list += Candidate(franchise, film, ambiguous)
                    }
                }
            }
            map
        }
    }
}

/**
 * Titeln att jämföra: små bokstäver utan accenter och skiljetecken, utan
 * "Marvel's" och liknande först, utan "the", "a", "and", "och" och
 * "Episode", med romerska siffror som vanliga ("Rocky IV" = "Rocky 4") och
 * apostrofer borta utan mellanrum ("Philosopher's" = "Philosophers").
 */
internal fun collectionName(title: String): String {
    val text = title.replace("'", "").replace("’", "").replace("`", "").replace("´", "")
    var name = foldTitle(text)
    for (prefix in PREFIXES) {
        if (name.startsWith("$prefix ")) {
            name = name.removePrefix("$prefix ").trim()
            break
        }
    }
    val out = ArrayList<String>()
    for (word in name.split(' ')) {
        if (word.isEmpty() || word in STOP_WORDS) continue
        val previous = out.lastOrNull()
        out += when {
            word == "volume" -> "vol"
            (previous == "part" || previous == "del") && word in SPELLED -> SPELLED.getValue(word)
            else -> ROMAN[word] ?: JOINED[word] ?: word
        }
    }
    return out.joinToString(" ")
}

private val STOP_WORDS = setOf("the", "a", "an", "and", "och", "episode")
private val ROMAN = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9")
private val SPELLED = mapOf("one" to "1", "two" to "2", "three" to "3", "ett" to "1", "tva" to "2", "tre" to "3")
private val JOINED = mapOf("spiderman" to "spider man", "antman" to "ant man", "xmen" to "x men", "ironman" to "iron man")

/** Före titeln i en del listor: "Marvel's", "Disney's", "James Bond 007 -". */
private val PREFIXES = listOf(
    "marvel studios", "marvel s", "marvels", "walt disney s", "disney s", "disneys", "disney pixar s", "dreamworks animation s",
    "dreamworks", "studio ghibli", "ghibli", "james bond 007", "james bond", "007",
)

private fun film(year: Int, vararg names: String) = FranchiseFilm(year, names.toList())

private fun part(title: String, vararg films: FranchiseFilm) = FranchisePart(title, films.toList())

private fun franchise(
    id: String,
    title: String,
    tagline: String,
    from: Long,
    to: Long,
    accent: Long,
    vararg parts: FranchisePart,
    byYear: Boolean = true,
    story: List<String>? = null,
) = Franchise(id, title, tagline, from, to, accent, parts.toList(), byYear, story)

// --- Samlingarna, i galleriets ordning ------------------------------------------------

private val MARVEL = franchise(
    "marvel", "Marvel", "Marvel Cinematic Universe", 0xFF7A0E14, 0xFF1A0507, 0xFFFF4D4D,
    part(
        "Fas 1",
        film(2008, "Iron Man"),
        film(2008, "The Incredible Hulk", "Incredible Hulk"),
        film(2010, "Iron Man 2"),
        film(2011, "~Thor"),
        film(2011, "Captain America: The First Avenger", "Captain America 1"),
        film(2012, "~The Avengers", "Marvel's The Avengers", "Avengers Assemble", "~Avengers"),
    ),
    part(
        "Fas 2",
        film(2013, "Iron Man 3"),
        film(2013, "Thor: The Dark World", "Thor 2"),
        film(2014, "Captain America: The Winter Soldier", "Captain America 2"),
        film(2014, "Guardians of the Galaxy"),
        film(2015, "Avengers: Age of Ultron", "Avengers 2"),
        film(2015, "Ant-Man"),
    ),
    part(
        "Fas 3",
        film(2016, "Captain America: Civil War", "Captain America 3"),
        film(2016, "~Doctor Strange", "~Dr. Strange"),
        film(2017, "Guardians of the Galaxy Vol. 2", "Guardians of the Galaxy 2"),
        film(2017, "Spider-Man: Homecoming"),
        film(2017, "Thor: Ragnarok", "Thor 3"),
        film(2018, "Black Panther"),
        film(2018, "Avengers: Infinity War", "Avengers 3"),
        film(2018, "Ant-Man and the Wasp", "Ant-Man 2"),
        film(2019, "Captain Marvel"),
        film(2019, "Avengers: Endgame", "Avengers 4"),
        film(2019, "Spider-Man: Far From Home"),
    ),
    part(
        "Fas 4",
        film(2021, "~Black Widow"),
        film(2021, "Shang-Chi and the Legend of the Ten Rings", "Shang-Chi"),
        film(2021, "Eternals"),
        film(2021, "Spider-Man: No Way Home"),
        film(2022, "Doctor Strange in the Multiverse of Madness", "Doctor Strange 2"),
        film(2022, "Thor: Love and Thunder", "Thor 4"),
        film(2022, "Black Panther: Wakanda Forever", "Black Panther 2"),
    ),
    part(
        "Fas 5",
        film(2023, "Ant-Man and the Wasp: Quantumania", "Ant-Man 3"),
        film(2023, "Guardians of the Galaxy Vol. 3", "Guardians of the Galaxy 3"),
        film(2023, "The Marvels"),
        film(2024, "Deadpool & Wolverine", "Deadpool 3"),
        film(2025, "Captain America: Brave New World", "Captain America 4"),
        film(2025, "Thunderbolts*", "Thunderbolts", "The New Avengers"),
    ),
    part(
        "Fas 6",
        film(2025, "The Fantastic Four: First Steps", "Fantastic Four: First Steps"),
        film(2026, "Spider-Man: Brand New Day", "Spider-Man 4"),
    ),
    story = listOf(
        "Captain America: The First Avenger", "Captain Marvel", "Iron Man", "Iron Man 2", "The Incredible Hulk", "Thor",
        "The Avengers", "Thor: The Dark World", "Iron Man 3", "Captain America: The Winter Soldier", "Guardians of the Galaxy",
        "Guardians of the Galaxy Vol. 2", "Avengers: Age of Ultron", "Ant-Man", "Captain America: Civil War", "Black Widow",
        "Black Panther", "Spider-Man: Homecoming", "Doctor Strange", "Thor: Ragnarok", "Ant-Man and the Wasp",
        "Avengers: Infinity War", "Avengers: Endgame", "Spider-Man: Far From Home", "Shang-Chi and the Legend of the Ten Rings",
        "Eternals", "Spider-Man: No Way Home", "Doctor Strange in the Multiverse of Madness", "Thor: Love and Thunder",
        "Black Panther: Wakanda Forever", "Ant-Man and the Wasp: Quantumania", "Guardians of the Galaxy Vol. 3", "The Marvels",
        "Deadpool & Wolverine", "Captain America: Brave New World", "Thunderbolts*", "The Fantastic Four: First Steps",
        "Spider-Man: Brand New Day",
    ),
)

private val STAR_WARS = franchise(
    "starwars", "Star Wars", "En galax långt, långt borta", 0xFF05060C, 0xFF16203A, 0xFFFFE81F,
    part(
        "Skywalker-sagan",
        film(1999, "The Phantom Menace", "Star Wars: Episode I - The Phantom Menace", "Star Wars: The Phantom Menace", "Star Wars 1", "Star Wars: Det mörka hotet", "Det mörka hotet"),
        film(2002, "Attack of the Clones", "Star Wars: Episode II - Attack of the Clones", "Star Wars: Attack of the Clones", "Star Wars 2", "Star Wars: Klonerna anfaller", "Klonerna anfaller"),
        film(2005, "Revenge of the Sith", "Star Wars: Episode III - Revenge of the Sith", "Star Wars: Revenge of the Sith", "Star Wars 3", "Star Wars: Mörkrets hämnd", "Mörkrets hämnd"),
        film(1977, "Star Wars", "Star Wars: Episode IV - A New Hope", "Star Wars: A New Hope", "A New Hope", "Star Wars 4", "Stjärnornas krig"),
        film(1980, "The Empire Strikes Back", "Star Wars: Episode V - The Empire Strikes Back", "Star Wars: The Empire Strikes Back", "Star Wars 5", "Rymdimperiet slår tillbaka", "Star Wars: Rymdimperiet slår tillbaka"),
        film(1983, "Return of the Jedi", "Star Wars: Episode VI - Return of the Jedi", "Star Wars: Return of the Jedi", "Star Wars 6", "Jedins återkomst", "Star Wars: Jedins återkomst"),
        film(2015, "The Force Awakens", "Star Wars: Episode VII - The Force Awakens", "Star Wars: The Force Awakens", "Star Wars 7"),
        film(2017, "The Last Jedi", "Star Wars: Episode VIII - The Last Jedi", "Star Wars: The Last Jedi", "Star Wars 8"),
        film(2019, "The Rise of Skywalker", "Star Wars: Episode IX - The Rise of Skywalker", "Star Wars: The Rise of Skywalker", "Star Wars 9"),
    ),
    part(
        "Fristående",
        film(2008, "~Star Wars: The Clone Wars", "~The Clone Wars"),
        film(2016, "Rogue One: A Star Wars Story", "Rogue One"),
        film(2018, "Solo: A Star Wars Story", "~Solo"),
        film(2026, "The Mandalorian and Grogu", "Mandalorian and Grogu"),
    ),
    story = listOf(
        "The Phantom Menace", "Attack of the Clones", "Star Wars: The Clone Wars", "Revenge of the Sith", "Solo: A Star Wars Story",
        "Rogue One: A Star Wars Story", "Star Wars", "The Empire Strikes Back", "Return of the Jedi", "The Mandalorian and Grogu",
        "The Force Awakens", "The Last Jedi", "The Rise of Skywalker",
    ),
)

private val HARRY_POTTER = franchise(
    "harrypotter", "Harry Potter", "Trollkarlsvärlden, från Hogwarts till Grindelwald", 0xFF3A0C12, 0xFF14102A, 0xFFE8B44C,
    part(
        "Harry Potter",
        film(2001, "Harry Potter and the Philosopher's Stone", "Harry Potter and the Sorcerer's Stone", "Harry Potter och de vises sten", "Harry Potter 1"),
        film(2002, "Harry Potter and the Chamber of Secrets", "Harry Potter och hemligheternas kammare", "Harry Potter 2"),
        film(2004, "Harry Potter and the Prisoner of Azkaban", "Harry Potter och fången från Azkaban", "Harry Potter 3"),
        film(2005, "Harry Potter and the Goblet of Fire", "Harry Potter och den flammande bägaren", "Harry Potter 4"),
        film(2007, "Harry Potter and the Order of the Phoenix", "Harry Potter och Fenixorden", "Harry Potter 5"),
        film(2009, "Harry Potter and the Half-Blood Prince", "Harry Potter och halvblodsprinsen", "Harry Potter 6"),
        film(2010, "Harry Potter and the Deathly Hallows: Part 1", "Harry Potter och dödsrelikerna del 1", "Harry Potter 7"),
        film(2011, "Harry Potter and the Deathly Hallows: Part 2", "Harry Potter och dödsrelikerna del 2", "Harry Potter 8"),
    ),
    part(
        "Fantastiska vidunder",
        film(2016, "Fantastic Beasts and Where to Find Them", "Fantastic Beasts", "Fantastiska vidunder och var man hittar dem", "Fantastiska vidunder"),
        film(2018, "Fantastic Beasts: The Crimes of Grindelwald", "Fantastiska vidunder: Grindelwalds brott", "Fantastic Beasts 2"),
        film(2022, "Fantastic Beasts: The Secrets of Dumbledore", "Fantastiska vidunder: Dumbledores hemligheter", "Fantastic Beasts 3"),
    ),
    byYear = false,
    story = listOf(
        "Fantastic Beasts and Where to Find Them", "Fantastic Beasts: The Crimes of Grindelwald", "Fantastic Beasts: The Secrets of Dumbledore",
        "Harry Potter and the Philosopher's Stone", "Harry Potter and the Chamber of Secrets", "Harry Potter and the Prisoner of Azkaban",
        "Harry Potter and the Goblet of Fire", "Harry Potter and the Order of the Phoenix", "Harry Potter and the Half-Blood Prince",
        "Harry Potter and the Deathly Hallows: Part 1", "Harry Potter and the Deathly Hallows: Part 2",
    ),
)

private val MIDDLE_EARTH = franchise(
    "midgard", "Sagan om ringen", "Midgård: ringen, Hobbit och Rohirrim", 0xFF0F1A12, 0xFF2A2214, 0xFFD9B45A,
    part(
        "Sagan om ringen",
        film(2001, "The Lord of the Rings: The Fellowship of the Ring", "The Fellowship of the Ring", "Sagan om ringen", "Lord of the Rings 1"),
        film(2002, "The Lord of the Rings: The Two Towers", "The Two Towers", "Sagan om de två tornen", "Lord of the Rings 2"),
        film(2003, "The Lord of the Rings: The Return of the King", "The Return of the King", "Sagan om konungens återkomst", "Lord of the Rings 3"),
    ),
    part(
        "Hobbit",
        film(2012, "The Hobbit: An Unexpected Journey", "Hobbit: En oväntad resa", "The Hobbit 1"),
        film(2013, "The Hobbit: The Desolation of Smaug", "Hobbit: Smaugs ödemark", "The Hobbit 2"),
        film(2014, "The Hobbit: The Battle of the Five Armies", "Hobbit: Femhäraslaget", "The Hobbit 3"),
    ),
    part(
        "Rohirrims krig",
        film(2024, "The Lord of the Rings: The War of the Rohirrim", "The War of the Rohirrim", "Sagan om ringen: Rohirrims krig"),
    ),
    story = listOf(
        "The Lord of the Rings: The War of the Rohirrim", "The Hobbit: An Unexpected Journey", "The Hobbit: The Desolation of Smaug",
        "The Hobbit: The Battle of the Five Armies", "The Lord of the Rings: The Fellowship of the Ring",
        "The Lord of the Rings: The Two Towers", "The Lord of the Rings: The Return of the King",
    ),
)

private val BOND = franchise(
    "bond", "James Bond", "Alla 007-filmerna", 0xFF0C0C0C, 0xFF29221A, 0xFFD4AF37,
    part(
        "Sean Connery",
        film(1962, "Dr. No", "Doctor No", "Agent 007 med rätt att döda"),
        film(1963, "From Russia with Love", "Agent 007 ser rött"),
        film(1964, "Goldfinger"),
        film(1965, "Thunderball", "Åskbollen"),
        film(1967, "You Only Live Twice", "Man lever bara två gånger"),
        film(1971, "Diamonds Are Forever", "Diamantfeber"),
    ),
    part(
        "George Lazenby",
        film(1969, "On Her Majesty's Secret Service", "I hennes majestäts hemliga tjänst"),
    ),
    part(
        "Roger Moore",
        film(1973, "Live and Let Die", "Leva och låta dö"),
        film(1974, "The Man with the Golden Gun", "Mannen med den gyllene pistolen"),
        film(1977, "The Spy Who Loved Me", "Älskade spion"),
        film(1979, "Moonraker"),
        film(1981, "For Your Eyes Only", "Ur dödlig synvinkel"),
        film(1983, "Octopussy"),
        film(1985, "A View to a Kill", "Levande måltavla"),
    ),
    part(
        "Timothy Dalton",
        film(1987, "The Living Daylights", "Iskallt uppdrag"),
        film(1989, "Licence to Kill", "License to Kill", "Tid för hämnd"),
    ),
    part(
        "Pierce Brosnan",
        film(1995, "GoldenEye"),
        film(1997, "Tomorrow Never Dies"),
        film(1999, "The World Is Not Enough", "Världen räcker inte till"),
        film(2002, "Die Another Day"),
    ),
    part(
        "Daniel Craig",
        film(2006, "~Casino Royale"),
        film(2008, "Quantum of Solace"),
        film(2012, "Skyfall"),
        film(2015, "~Spectre"),
        film(2021, "No Time to Die"),
    ),
)

private val SPIDER_MAN = franchise(
    "spiderman", "Spider-Man", "Alla Spindelmannen-filmerna, och Venom", 0xFFB3122E, 0xFF0D2B6B, 0xFF8EC5FF,
    part(
        "Sam Raimi",
        film(2002, "~Spider-Man", "Spider-Man 1"),
        film(2004, "Spider-Man 2"),
        film(2007, "Spider-Man 3"),
    ),
    part(
        "The Amazing Spider-Man",
        film(2012, "The Amazing Spider-Man", "Amazing Spider-Man 1"),
        film(2014, "The Amazing Spider-Man 2"),
    ),
    part(
        "Tom Holland",
        film(2017, "Spider-Man: Homecoming"),
        film(2019, "Spider-Man: Far From Home"),
        film(2021, "Spider-Man: No Way Home"),
        film(2026, "Spider-Man: Brand New Day", "Spider-Man 4"),
    ),
    part(
        "Spider-Verse",
        film(2018, "Spider-Man: Into the Spider-Verse", "Into the Spider-Verse"),
        film(2023, "Spider-Man: Across the Spider-Verse", "Across the Spider-Verse"),
    ),
    part(
        "Venom och Sonys universum",
        film(2018, "~Venom"),
        film(2021, "Venom: Let There Be Carnage", "Venom 2"),
        film(2022, "Morbius"),
        film(2024, "Madame Web"),
        film(2024, "Kraven the Hunter"),
        film(2024, "Venom: The Last Dance", "Venom 3"),
    ),
    byYear = false,
)

private val X_MEN = franchise(
    "xmen", "X-Men", "Mutanterna, Wolverine och Deadpool", 0xFF0B2A5B, 0xFF14171F, 0xFFFFC72C,
    part(
        "X-Men",
        film(2000, "X-Men"),
        film(2003, "X2", "X2: X-Men United", "X-Men 2"),
        film(2006, "X-Men: The Last Stand", "X-Men 3"),
        film(2009, "X-Men Origins: Wolverine"),
        film(2011, "X-Men: First Class"),
        film(2013, "The Wolverine"),
        film(2014, "X-Men: Days of Future Past"),
        film(2016, "Deadpool"),
        film(2016, "X-Men: Apocalypse"),
        film(2017, "~Logan"),
        film(2018, "Deadpool 2"),
        film(2019, "Dark Phoenix", "X-Men: Dark Phoenix"),
        film(2020, "The New Mutants", "New Mutants"),
    ),
)

private val DC = franchise(
    "dc", "DC", "Superman, Wonder Woman, Justice League", 0xFF0A2A66, 0xFF061226, 0xFF4DA3FF,
    part(
        "DC Extended Universe",
        film(2013, "Man of Steel"),
        film(2016, "Batman v Superman: Dawn of Justice", "Batman vs Superman", "Batman v Superman"),
        film(2016, "~Suicide Squad"),
        film(2017, "~Wonder Woman"),
        film(2017, "Justice League"),
        film(2018, "Aquaman"),
        film(2019, "Shazam!", "Shazam"),
        film(2020, "Birds of Prey", "Birds of Prey and the Fantabulous Emancipation of One Harley Quinn"),
        film(2020, "Wonder Woman 1984", "WW84"),
        film(2021, "Zack Snyder's Justice League", "Justice League Snyder Cut"),
        film(2021, "~The Suicide Squad"),
        film(2022, "Black Adam"),
        film(2023, "Shazam! Fury of the Gods", "Shazam 2"),
        film(2023, "~The Flash"),
        film(2023, "Blue Beetle"),
        film(2023, "Aquaman and the Lost Kingdom", "Aquaman 2"),
    ),
    part(
        "DC-universumet",
        film(2025, "~Superman"),
        film(2026, "~Supergirl", "Supergirl: Woman of Tomorrow"),
    ),
    part(
        "Joker",
        film(2019, "~Joker"),
        film(2024, "Joker: Folie à Deux", "Joker 2"),
    ),
    byYear = false,
)

private val BATMAN = franchise(
    "batman", "Batman", "Gotham, från Burton till Nolan", 0xFF141414, 0xFF2A2A30, 0xFFFFD000,
    part(
        "Burton och Schumacher",
        film(1989, "~Batman"),
        film(1992, "Batman Returns"),
        film(1995, "Batman Forever"),
        film(1997, "Batman & Robin"),
    ),
    part(
        "The Dark Knight",
        film(2005, "Batman Begins"),
        film(2008, "The Dark Knight"),
        film(2012, "The Dark Knight Rises"),
    ),
    part(
        "The Batman",
        film(2022, "~The Batman"),
    ),
)

private val FAST_FURIOUS = franchise(
    "fastfurious", "Fast & Furious", "Hela familjen, från Los Angeles till Rom", 0xFF161616, 0xFF3B1800, 0xFFFF8A00,
    part(
        "Fast & Furious",
        film(2001, "~The Fast and the Furious", "Fast and Furious 1"),
        film(2003, "2 Fast 2 Furious", "Fast and Furious 2"),
        film(2006, "The Fast and the Furious: Tokyo Drift", "Tokyo Drift", "Fast and Furious 3"),
        film(2009, "~Fast & Furious", "Fast & Furious 4"),
        film(2011, "Fast Five", "Fast & Furious 5"),
        film(2013, "Fast & Furious 6", "Furious 6"),
        film(2015, "Furious 7", "Fast & Furious 7"),
        film(2017, "The Fate of the Furious", "Fast & Furious 8", "Fast 8"),
        film(2019, "Fast & Furious Presents: Hobbs & Shaw", "Hobbs & Shaw"),
        film(2021, "F9", "F9: The Fast Saga", "Fast & Furious 9", "Fast 9"),
        film(2023, "Fast X", "Fast & Furious 10"),
    ),
)

private val JURASSIC = franchise(
    "jurassic", "Jurassic Park", "Jurassic Park och Jurassic World", 0xFF0E2014, 0xFF2E1F0B, 0xFFF2A93B,
    part(
        "Jurassic Park",
        film(1993, "Jurassic Park"),
        film(1997, "The Lost World: Jurassic Park", "Jurassic Park 2", "The Lost World"),
        film(2001, "Jurassic Park III"),
    ),
    part(
        "Jurassic World",
        film(2015, "Jurassic World"),
        film(2018, "Jurassic World: Fallen Kingdom", "Jurassic World 2"),
        film(2022, "Jurassic World Dominion", "Jurassic World 3"),
        film(2025, "Jurassic World Rebirth", "Jurassic World 4"),
    ),
)

private val MISSION_IMPOSSIBLE = franchise(
    "mission", "Mission: Impossible", "Ethan Hunt och IMF", 0xFF0D0D10, 0xFF2E0A0A, 0xFFE63946,
    part(
        "Mission: Impossible",
        film(1996, "~Mission: Impossible", "Mission: Impossible 1"),
        film(2000, "Mission: Impossible 2", "M:I-2"),
        film(2006, "Mission: Impossible III"),
        film(2011, "Mission: Impossible - Ghost Protocol", "Ghost Protocol", "Mission: Impossible 4"),
        film(2015, "Mission: Impossible - Rogue Nation", "Rogue Nation", "Mission: Impossible 5"),
        film(2018, "Mission: Impossible - Fallout", "Mission: Impossible 6"),
        film(2023, "Mission: Impossible - Dead Reckoning Part One", "Mission: Impossible - Dead Reckoning", "Mission: Impossible 7"),
        film(2025, "Mission: Impossible - The Final Reckoning", "Mission: Impossible - Dead Reckoning Part Two", "Mission: Impossible 8"),
    ),
)

private val PIRATES = franchise(
    "pirates", "Pirates of the Caribbean", "Jack Sparrow och Svarta Pärlan", 0xFF071B26, 0xFF1F160C, 0xFFC9A55B,
    part(
        "Pirates of the Caribbean",
        film(2003, "Pirates of the Caribbean: The Curse of the Black Pearl", "Pirates of the Caribbean: Svarta Pärlans förbannelse", "Pirates of the Caribbean 1", "The Curse of the Black Pearl"),
        film(2006, "Pirates of the Caribbean: Dead Man's Chest", "Pirates of the Caribbean: Död mans kista", "Pirates of the Caribbean 2", "Dead Man's Chest"),
        film(2007, "Pirates of the Caribbean: At World's End", "Pirates of the Caribbean: Vid världens ände", "Pirates of the Caribbean 3", "At World's End"),
        film(2011, "Pirates of the Caribbean: On Stranger Tides", "Pirates of the Caribbean: I främmande farvatten", "Pirates of the Caribbean 4", "On Stranger Tides"),
        film(2017, "Pirates of the Caribbean: Dead Men Tell No Tales", "Pirates of the Caribbean: Salazar's Revenge", "Pirates of the Caribbean: Salazars hämnd", "Pirates of the Caribbean 5"),
    ),
)

private val INDIANA_JONES = franchise(
    "indianajones", "Indiana Jones", "Äventyren med hatten och piskan", 0xFF2A1A0D, 0xFF120C06, 0xFFE0A458,
    part(
        "Indiana Jones",
        film(1981, "Raiders of the Lost Ark", "Indiana Jones and the Raiders of the Lost Ark", "Jakten på den försvunna skatten", "Indiana Jones 1"),
        film(1984, "Indiana Jones and the Temple of Doom", "Indiana Jones och de fördömdas tempel", "Indiana Jones 2"),
        film(1989, "Indiana Jones and the Last Crusade", "Indiana Jones och det sista korståget", "Indiana Jones 3"),
        film(2008, "Indiana Jones and the Kingdom of the Crystal Skull", "Indiana Jones och kristalldödskallens rike", "Indiana Jones 4"),
        film(2023, "Indiana Jones and the Dial of Destiny", "Indiana Jones och ödets urtavla", "Indiana Jones 5"),
    ),
)

private val JOHN_WICK = franchise(
    "johnwick", "John Wick", "Continental, kapitel för kapitel", 0xFF0A0A0A, 0xFF1E1408, 0xFFE8C170,
    part(
        "John Wick",
        film(2014, "John Wick", "John Wick 1"),
        film(2017, "John Wick: Chapter 2", "John Wick 2"),
        film(2019, "John Wick: Chapter 3 - Parabellum", "John Wick: Parabellum", "John Wick 3"),
        film(2023, "John Wick: Chapter 4", "John Wick 4"),
        film(2025, "From the World of John Wick: Ballerina", "~Ballerina"),
    ),
)

private val DREAMWORKS = franchise(
    "dreamworks", "DreamWorks", "Shrek, Madagascar, Kung Fu Panda och Draktränaren", 0xFF0B2E6B, 0xFF2D7DD2, 0xFFFFE17A,
    part(
        "Shrek",
        film(2001, "Shrek"),
        film(2004, "Shrek 2"),
        film(2007, "Shrek the Third", "Shrek 3", "Shrek den tredje"),
        film(2010, "Shrek Forever After", "Shrek 4"),
        film(2011, "~Puss in Boots", "Mästerkatten i stövlar"),
        film(2022, "Puss in Boots: The Last Wish", "Mästerkatten: Den sista önskan"),
    ),
    part(
        "Madagascar",
        film(2005, "~Madagascar"),
        film(2008, "Madagascar: Escape 2 Africa", "Madagascar 2"),
        film(2012, "Madagascar 3: Europe's Most Wanted", "Madagascar 3"),
        film(2014, "Penguins of Madagascar", "Pingvinerna från Madagascar", "Pingvinerna från Madagaskar"),
    ),
    part(
        "Kung Fu Panda",
        film(2008, "Kung Fu Panda"),
        film(2011, "Kung Fu Panda 2"),
        film(2016, "Kung Fu Panda 3"),
        film(2024, "Kung Fu Panda 4"),
    ),
    part(
        "Draktränaren",
        film(2010, "~How to Train Your Dragon", "~Draktränaren"),
        film(2014, "How to Train Your Dragon 2", "Draktränaren 2"),
        film(2019, "How to Train Your Dragon: The Hidden World", "How to Train Your Dragon 3", "Draktränaren 3", "Draktränaren 3: Den dolda världen"),
        film(2025, "~How to Train Your Dragon", "~Draktränaren"),
    ),
    byYear = false,
)

private val GHIBLI = franchise(
    "ghibli", "Studio Ghibli", "Miyazaki, Takahata och de andra", 0xFF2C6E9B, 0xFF8FC3E3, 0xFFFFFFFF,
    part(
        "Studio Ghibli",
        film(1984, "Nausicaä of the Valley of the Wind", "Nausicaä från Vindarnas dal"),
        film(1986, "Castle in the Sky", "Laputa: Castle in the Sky", "Laputa – slottet i himlen"),
        film(1988, "My Neighbor Totoro", "My Neighbour Totoro", "Min granne Totoro"),
        film(1988, "Grave of the Fireflies", "Eldflugornas grav"),
        film(1989, "Kiki's Delivery Service", "Kikis expressbud"),
        film(1991, "Only Yesterday"),
        film(1992, "Porco Rosso"),
        film(1993, "Ocean Waves"),
        film(1994, "Pom Poko"),
        film(1995, "Whisper of the Heart"),
        film(1997, "Princess Mononoke", "Prinsessan Mononoke"),
        film(1999, "My Neighbors the Yamadas"),
        film(2001, "Spirited Away"),
        film(2002, "The Cat Returns"),
        film(2004, "Howl's Moving Castle", "Det levande slottet"),
        film(2006, "Tales from Earthsea"),
        film(2008, "Ponyo", "Ponyo on the Cliff by the Sea", "Ponyo på klippan vid havet"),
        film(2010, "The Secret World of Arrietty", "Arrietty", "Lånaren Arrietty"),
        film(2011, "From Up on Poppy Hill"),
        film(2013, "The Wind Rises"),
        film(2013, "The Tale of the Princess Kaguya", "Sagan om prinsessan Kaguya"),
        film(2014, "When Marnie Was There"),
        film(2016, "The Red Turtle"),
        film(2020, "Earwig and the Witch"),
        film(2023, "The Boy and the Heron", "Pojken och hägern"),
    ),
)

private val ICE_AGE = franchise(
    "iceage", "Ice Age", "Manny, Sid, Diego och Scrat", 0xFF2F5F80, 0xFF8EC3DE, 0xFFFFFFFF,
    part(
        "Ice Age",
        film(2002, "~Ice Age", "Ice Age 1"),
        film(2006, "Ice Age: The Meltdown", "Ice Age 2"),
        film(2009, "Ice Age: Dawn of the Dinosaurs", "Ice Age 3"),
        film(2012, "Ice Age: Continental Drift", "Ice Age 4"),
        film(2016, "Ice Age: Collision Course", "Ice Age 5"),
        film(2022, "The Ice Age Adventures of Buck Wild", "Ice Age: Buck Wild"),
    ),
)

private val DESPICABLE_ME = franchise(
    "dummamig", "Dumma mig", "Gru och minionerna", 0xFF2E5AAC, 0xFFE0A800, 0xFFFFE45C,
    part(
        "Dumma mig",
        film(2010, "Despicable Me", "Dumma mig"),
        film(2013, "Despicable Me 2", "Dumma mig 2"),
        film(2015, "Minions", "Minioner"),
        film(2017, "Despicable Me 3", "Dumma mig 3"),
        film(2022, "Minions: The Rise of Gru", "Minions 2", "Minioner 2", "Minioner: Grus uppgång"),
        film(2024, "Despicable Me 4", "Dumma mig 4"),
    ),
)

private val HUNGER_GAMES = franchise(
    "hungergames", "Hunger Games", "Panem och Hungerspelen", 0xFF2A0C05, 0xFF0E0E0E, 0xFFF26B1D,
    part(
        "Hunger Games",
        film(2012, "The Hunger Games", "Hunger Games 1"),
        film(2013, "The Hunger Games: Catching Fire", "Catching Fire", "Hunger Games 2"),
        film(2014, "The Hunger Games: Mockingjay - Part 1", "Mockingjay Part 1", "Hunger Games 3"),
        film(2015, "The Hunger Games: Mockingjay - Part 2", "Mockingjay Part 2", "Hunger Games 4"),
        film(2023, "The Hunger Games: The Ballad of Songbirds & Snakes", "The Ballad of Songbirds & Snakes"),
    ),
    story = listOf(
        "The Hunger Games: The Ballad of Songbirds & Snakes", "The Hunger Games", "The Hunger Games: Catching Fire",
        "The Hunger Games: Mockingjay - Part 1", "The Hunger Games: Mockingjay - Part 2",
    ),
)

private val TWILIGHT = franchise(
    "twilight", "Twilight", "Bella, Edward och Jacob", 0xFF0E1418, 0xFF2A2F33, 0xFFB71C1C,
    part(
        "Twilight",
        film(2008, "~Twilight", "Twilight 1"),
        film(2009, "The Twilight Saga: New Moon", "New Moon", "Twilight 2"),
        film(2010, "The Twilight Saga: Eclipse", "~Eclipse", "Twilight 3"),
        film(2011, "The Twilight Saga: Breaking Dawn - Part 1", "Breaking Dawn Part 1"),
        film(2012, "The Twilight Saga: Breaking Dawn - Part 2", "Breaking Dawn Part 2"),
    ),
)

private val TRANSFORMERS = franchise(
    "transformers", "Transformers", "Autobots mot Decepticons", 0xFF1B1F27, 0xFF5A1010, 0xFF6FA8FF,
    part(
        "Transformers",
        film(2007, "~Transformers", "Transformers 1"),
        film(2009, "Transformers: Revenge of the Fallen", "Transformers 2"),
        film(2011, "Transformers: Dark of the Moon", "Transformers 3"),
        film(2014, "Transformers: Age of Extinction", "Transformers 4"),
        film(2017, "Transformers: The Last Knight", "Transformers 5"),
        film(2018, "Bumblebee"),
        film(2023, "Transformers: Rise of the Beasts"),
        film(2024, "Transformers One"),
    ),
    story = listOf(
        "Transformers One", "Bumblebee", "Transformers: Rise of the Beasts", "Transformers", "Transformers: Revenge of the Fallen",
        "Transformers: Dark of the Moon", "Transformers: Age of Extinction", "Transformers: The Last Knight",
    ),
)

private val ALIEN = franchise(
    "alien", "Alien", "I rymden kan ingen höra dig skrika", 0xFF020604, 0xFF0E1F14, 0xFF7CFF8A,
    part(
        "Alien",
        film(1979, "~Alien"),
        film(1986, "Aliens"),
        film(1992, "Alien 3"),
        film(1997, "Alien: Resurrection"),
    ),
    part(
        "Prometheus",
        film(2012, "Prometheus"),
        film(2017, "Alien: Covenant"),
    ),
    part(
        "Romulus",
        film(2024, "Alien: Romulus"),
    ),
    story = listOf("Prometheus", "Alien: Covenant", "Alien", "Alien: Romulus", "Aliens", "Alien 3", "Alien: Resurrection"),
)

private val MATRIX = franchise(
    "matrix", "Matrix", "Följ den vita kaninen", 0xFF000A02, 0xFF032B10, 0xFF39FF6A,
    part(
        "Matrix",
        film(1999, "The Matrix", "Matrix 1"),
        film(2003, "The Matrix Reloaded", "Matrix 2"),
        film(2003, "The Matrix Revolutions", "Matrix 3"),
        film(2021, "The Matrix Resurrections", "Matrix 4"),
    ),
)

private val BACK_TO_THE_FUTURE = franchise(
    "framtiden", "Tillbaka till framtiden", "Marty, Doc och DeLorean", 0xFF3A1060, 0xFFB33A1C, 0xFFFFC23D,
    part(
        "Tillbaka till framtiden",
        film(1985, "Back to the Future", "Tillbaka till framtiden"),
        film(1989, "Back to the Future Part II", "Back to the Future 2", "Tillbaka till framtiden del II", "Tillbaka till framtiden 2"),
        film(1990, "Back to the Future Part III", "Back to the Future 3", "Tillbaka till framtiden del III", "Tillbaka till framtiden 3"),
    ),
)

private val TERMINATOR = franchise(
    "terminator", "Terminator", "I'll be back", 0xFF0A0F1A, 0xFF2A0A0A, 0xFFFF3B30,
    part(
        "Terminator",
        film(1984, "The Terminator", "Terminator 1"),
        film(1991, "Terminator 2: Judgment Day", "Terminator 2"),
        film(2003, "Terminator 3: Rise of the Machines", "Terminator 3"),
        film(2009, "Terminator Salvation"),
        film(2015, "Terminator Genisys"),
        film(2019, "Terminator: Dark Fate"),
    ),
)

private val ROCKY = franchise(
    "rocky", "Rocky & Creed", "Från Philadelphia till världstiteln", 0xFF3B0A0A, 0xFF0A1E3B, 0xFFF5D76E,
    part(
        "Rocky",
        film(1976, "~Rocky", "Rocky 1"),
        film(1979, "Rocky II"),
        film(1982, "Rocky III"),
        film(1985, "Rocky IV"),
        film(1990, "Rocky V"),
        film(2006, "Rocky Balboa"),
    ),
    part(
        "Creed",
        film(2015, "~Creed", "Creed 1"),
        film(2018, "Creed II"),
        film(2023, "Creed III"),
    ),
)

private val CONJURING = franchise(
    "conjuring", "The Conjuring", "Hela skräckuniversumet", 0xFF0B0B0B, 0xFF2A2420, 0xFFC8B89A,
    part(
        "The Conjuring",
        film(2013, "~The Conjuring", "The Conjuring 1"),
        film(2014, "~Annabelle"),
        film(2016, "The Conjuring 2"),
        film(2017, "Annabelle: Creation"),
        film(2018, "~The Nun"),
        film(2019, "The Curse of La Llorona"),
        film(2019, "Annabelle Comes Home"),
        film(2021, "The Conjuring: The Devil Made Me Do It", "The Conjuring 3"),
        film(2023, "The Nun II"),
        film(2025, "The Conjuring: Last Rites", "The Conjuring 4"),
    ),
)

private val MONSTERVERSE = franchise(
    "monsterverse", "Godzilla & Kong", "MonsterVerse", 0xFF06141C, 0xFF1E3A2F, 0xFF66E0FF,
    part(
        "MonsterVerse",
        film(2014, "~Godzilla"),
        film(2017, "Kong: Skull Island"),
        film(2019, "Godzilla: King of the Monsters", "Godzilla 2"),
        film(2021, "Godzilla vs. Kong"),
        film(2024, "Godzilla x Kong: The New Empire", "Godzilla x Kong"),
    ),
)

private val APES = franchise(
    "apornas", "Apornas planet", "Från Charlton Heston till Caesar", 0xFF2A1E12, 0xFF0D1A1C, 0xFFE2B36B,
    part(
        "Originalen",
        film(1968, "~Planet of the Apes", "~Apornas planet"),
        film(1970, "Beneath the Planet of the Apes"),
        film(1971, "Escape from the Planet of the Apes"),
        film(1972, "Conquest of the Planet of the Apes"),
        film(1973, "Battle for the Planet of the Apes"),
    ),
    part(
        "Nyinspelningen",
        film(2001, "~Planet of the Apes", "~Apornas planet"),
    ),
    part(
        "Caesar",
        film(2011, "Rise of the Planet of the Apes"),
        film(2014, "Dawn of the Planet of the Apes"),
        film(2017, "War for the Planet of the Apes"),
        film(2024, "Kingdom of the Planet of the Apes"),
    ),
)

private val MAD_MAX = franchise(
    "madmax", "Mad Max", "Ökenvägarna", 0xFF3A1A06, 0xFFB5651D, 0xFFFFD08A,
    part(
        "Mad Max",
        film(1979, "~Mad Max", "Mad Max 1"),
        film(1981, "Mad Max 2", "Mad Max 2: The Road Warrior", "The Road Warrior"),
        film(1985, "Mad Max Beyond Thunderdome", "Mad Max 3"),
        film(2015, "Mad Max: Fury Road", "Fury Road"),
        film(2024, "Furiosa: A Mad Max Saga", "Furiosa"),
    ),
)

private val DIE_HARD = franchise(
    "diehard", "Die Hard", "Yippee-ki-yay", 0xFF0D0D0D, 0xFF2B1F14, 0xFFFF6B35,
    part(
        "Die Hard",
        film(1988, "~Die Hard", "Die Hard 1"),
        film(1990, "Die Hard 2", "Die Hard 2: Die Harder"),
        film(1995, "Die Hard with a Vengeance", "Die Hard 3"),
        film(2007, "Live Free or Die Hard", "Die Hard 4.0", "Die Hard 4"),
        film(2013, "A Good Day to Die Hard", "Die Hard 5"),
    ),
)

private val NARNIA = franchise(
    "narnia", "Narnia", "Berättelsen om Narnia", 0xFF0E2238, 0xFF6B8FB3, 0xFFF2D27A,
    part(
        "Narnia",
        film(2005, "The Chronicles of Narnia: The Lion, the Witch and the Wardrobe", "The Lion, the Witch and the Wardrobe", "Narnia: Häxan och lejonet", "Häxan och lejonet"),
        film(2008, "The Chronicles of Narnia: Prince Caspian", "Prince Caspian", "Narnia: Prins Caspian", "Prins Caspian"),
        film(2010, "The Chronicles of Narnia: The Voyage of the Dawn Treader", "The Voyage of the Dawn Treader", "Narnia: Kung Caspian och skeppet Gryningen"),
    ),
)

/** Alla samlingar, i galleriets ordning. Disneys tre läggs in efter Star Wars. */
val FRANCHISES: List<Franchise> = listOf(
    MARVEL, STAR_WARS, HARRY_POTTER, MIDDLE_EARTH, BOND, SPIDER_MAN, X_MEN, DC, BATMAN, FAST_FURIOUS, JURASSIC,
    MISSION_IMPOSSIBLE, PIRATES, INDIANA_JONES, JOHN_WICK, DREAMWORKS, GHIBLI, ICE_AGE, DESPICABLE_ME, HUNGER_GAMES,
    TWILIGHT, TRANSFORMERS, ALIEN, MATRIX, BACK_TO_THE_FUTURE, TERMINATOR, ROCKY, CONJURING, MONSTERVERSE, APES, MAD_MAX,
    DIE_HARD, NARNIA,
)

private val FRANCHISES_BY_ID: Map<String, Franchise> = FRANCHISES.associateBy { it.id }

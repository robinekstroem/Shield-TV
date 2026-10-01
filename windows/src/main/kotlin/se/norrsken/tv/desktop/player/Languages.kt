package se.norrsken.tv.desktop.player

/** Språknamnet på svenska, från VLC:s engelska namn eller en språkkod. */
fun languageName(raw: String): String =
    languages[raw.trim().lowercase()] ?: raw.trim().replaceFirstChar { it.uppercase() }

private val languages = mapOf(
    "swedish" to "Svenska", "swe" to "Svenska", "sve" to "Svenska", "sv" to "Svenska",
    "english" to "Engelska", "eng" to "Engelska", "en" to "Engelska",
    "norwegian" to "Norska", "norwegian bokmal" to "Norska", "norwegian bokmål" to "Norska", "nor" to "Norska",
    "danish" to "Danska", "dan" to "Danska", "finnish" to "Finska", "fin" to "Finska",
    "german" to "Tyska", "french" to "Franska", "spanish" to "Spanska", "spanish; castilian" to "Spanska",
    "italian" to "Italienska", "dutch" to "Nederländska", "dutch; flemish" to "Nederländska",
    "portuguese" to "Portugisiska", "polish" to "Polska", "russian" to "Ryska", "ukrainian" to "Ukrainska",
    "turkish" to "Turkiska", "arabic" to "Arabiska", "persian" to "Persiska", "kurdish" to "Kurdiska",
    "greek" to "Grekiska", "greek, modern (1453-)" to "Grekiska", "serbian" to "Serbiska",
    "croatian" to "Kroatiska", "bosnian" to "Bosniska", "albanian" to "Albanska", "somali" to "Somaliska",
    "tigrinya" to "Tigrinja", "hindi" to "Hindi", "japanese" to "Japanska", "korean" to "Koreanska",
    "chinese" to "Kinesiska", "thai" to "Thailändska", "icelandic" to "Isländska", "estonian" to "Estniska",
    "hungarian" to "Ungerska", "czech" to "Tjeckiska", "romanian" to "Rumänska", "hebrew" to "Hebreiska",
    "undetermined" to "Okänt språk",
)

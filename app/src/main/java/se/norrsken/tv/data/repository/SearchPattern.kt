package se.norrsken.tv.data.repository

/**
 * Gör om det användaren skrivit till ett LIKE-mönster för SQLite.
 *
 * Två fällor:
 *
 * SQLite:s LIKE är bara skiftlägesokänslig för A–Z. "äventyr" hittar inte
 * "Äventyr", och för svenska titlar är det vanligt. Därför blir varje
 * bokstav utanför ASCII ett `_` — jokertecknet för exakt ett tecken — som
 * matchar både stor och liten variant. Det kan ge någon extra träff
 * ("_ventyr" matchar även "Eventyr"), men i en sökning är en träff för
 * mycket ofarlig. En som saknas är det inte.
 *
 * `%` och `_` i det användaren själv skrivit måste escapas, annars blir
 * "100%" ett jokertecken i stället för en procentsats.
 */
fun likePattern(query: String): String {
    val body = buildString {
        for (c in query.trim()) {
            when {
                c == '\\' || c == '%' || c == '_' -> append('\\').append(c)
                c.code > 127 && c.isLetter() -> append('_')
                else -> append(c)
            }
        }
    }
    return "%$body%"
}

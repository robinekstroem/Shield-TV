// Delas med Windows-versionen (se windows/build.gradle.kts). Håll filen fri
// från android.*-importer, annars slutar Windows-bygget att kompilera.

package se.norrsken.tv.data.security

/**
 * Maskerar inloggningsuppgifter i en URL innan den visas eller kopieras.
 *
 * IPTV-adresser bar uppgifterna i klartext, pa tva satt:
 *
 *   http://server:8080/get.php?username=robin&password=hemligt
 *   http://server:8080/live/robin/hemligt/12345.ts
 *
 * Bada maste maskeras. Den andra glommas latt eftersom det inte ser ut som
 * en inloggning — det ser ut som en sokvag.
 *
 * Rent Kotlin utan android.net.Uri, sa funktionen gar att enhetstesta.
 */

private val SENSITIVE_PARAMS = setOf(
    "username", "user", "password", "pass", "token", "api_key", "apikey",
    "auth", "access_token", "mac", "sn",
)

private val CREDENTIAL_PATHS = setOf("live", "movie", "series", "timeshift", "streaming")

const val MASK = "***"

fun sanitizeUrl(url: String?): String {
    val value = url.orEmpty()
    if (value.isEmpty()) return ""

    val queryIndex = value.indexOf('?')
    val path = if (queryIndex >= 0) value.substring(0, queryIndex) else value
    val query = if (queryIndex >= 0) value.substring(queryIndex + 1) else null

    return buildString {
        append(maskPathCredentials(path))
        if (query != null) {
            append('?')
            append(maskQuery(query))
        }
    }
}

private fun maskQuery(query: String): String =
    query.split('&').joinToString("&") { pair ->
        val key = pair.substringBefore('=')
        if (key.lowercase() in SENSITIVE_PARAMS) "$key=$MASK" else pair
    }

/**
 * Xtream lagger anvandarnamn och losenord som de tva segmenten direkt efter
 * /live, /movie eller /series. Maskera bara dar — inte varje segment, for da
 * blir adressen oanvandbar for felsokning.
 */
private fun maskPathCredentials(path: String): String {
    val segments = path.split('/').toMutableList()
    for (index in segments.indices) {
        if (segments[index].lowercase() in CREDENTIAL_PATHS) {
            if (index + 1 < segments.size) segments[index + 1] = MASK
            if (index + 2 < segments.size) segments[index + 2] = MASK
            break
        }
    }
    return segments.joinToString("/")
}

package se.norrsken.tv.data.playlist

import java.net.URI

/**
 * Xtream-uppgifter utlästa ur en adress.
 */
data class XtreamCredentials(
    /** Utan avslutande snedstreck: "http://host:8080". */
    val baseUrl: String,
    val username: String,
    val password: String,
    /**
     * Vilken User-Agent anrop mot den har leverantoren ska skicka. Ligger
     * pa uppgifterna och inte som en global konstant, eftersom paneler
     * filtrerar olika — en som svarar 403 pa en strang slapper igenom en annan.
     */
    val userAgent: String = se.norrsken.tv.data.remote.PlaylistDownloader.DEFAULT_USER_AGENT,
)

/**
 * Den vanligaste M3U-länken från en leverantör ser ut så här:
 *
 *     http://host:8080/get.php?username=abc&password=123&type=m3u_plus&output=ts
 *
 * Det är i själva verket ett Xtream-konto. Har man de uppgifterna är API:et
 * alltid bättre än M3U-filen: kategorier, filmer med omslag, serier med
 * säsonger och EPG per kanal — sånt som helt saknas i en platt spellista.
 *
 * Därför plockas uppgifterna ut automatiskt när användaren klistrar in en
 * get.php-länk, i stället för att be dem skriva in allt en gång till.
 */
fun extractXtreamCredentials(url: String): XtreamCredentials? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null

    val path = uri.path.orEmpty().lowercase()
    if (!path.endsWith("get.php") && !path.endsWith("player_api.php")) return null

    val query = parseQuery(uri.rawQuery)
    val username = query["username"]?.takeIf { it.isNotBlank() } ?: return null
    val password = query["password"]?.takeIf { it.isNotBlank() } ?: return null

    val host = uri.host ?: return null
    val port = if (uri.port > 0) ":${uri.port}" else ""

    return XtreamCredentials(
        baseUrl = "$scheme://$host$port",
        username = username,
        password = password,
    )
}

/**
 * Egen query-tolkning i stallet for android.net.Uri: funktionen blir da ren
 * Kotlin och gar att enhetstesta utan emulator.
 */
private fun parseQuery(raw: String?): Map<String, String> =
    raw.orEmpty()
        .split('&')
        .filter { it.isNotEmpty() }
        .associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            key to runCatching {
                java.net.URLDecoder.decode(value, "UTF-8")
            }.getOrDefault(value)
        }

/** Normaliserar det användaren skrivit i serverfältet till en bas-URL. */
fun normalizeBaseUrl(input: String): String {
    var value = input.trim().removeSuffix("/")
    if (!value.startsWith("http://") && !value.startsWith("https://")) {
        value = "http://$value"
    }
    return value
}

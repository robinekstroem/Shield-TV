package se.norrsken.tv.ipad

/** En kanal att titta på. Nyckeln är unik i hela listan. */
internal data class Channel(val key: String, val name: String, val group: String, val logo: String?, val url: String)

/** En källa: en M3U-länk eller ett Xtream-konto. */
internal data class Source(
    val id: String,
    val name: String,
    val xtream: Boolean,
    val url: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
)

internal object M3u {
    // namn="värde" i en #EXTINF-rad.
    private val attribute = Regex("([\\w-]+)=\"([^\"]*)\"")

    /** Kanalerna i en M3U-lista. Filmer och serier i listan hoppas över: det här är live-tv. */
    fun parse(text: String, sourceId: String): List<Channel> {
        val result = ArrayList<Channel>()
        var name: String? = null
        var group = "Övrigt"
        var logo: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val attributes = attribute.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                // Namnet står efter kommat som följer på attributen.
                val comma = line.indexOf(',', line.lastIndexOf('"').coerceAtLeast(0))
                name = (if (comma >= 0) line.substring(comma + 1).trim() else "").ifEmpty { attributes["tvg-name"] ?: "Kanal" }
                group = attributes["group-title"]?.ifBlank { null } ?: "Övrigt"
                logo = attributes["tvg-logo"]?.ifBlank { null }
                continue
            }
            if (line.isEmpty() || line.startsWith("#")) continue
            val current = name ?: continue
            if ("/movie/" !in line && "/series/" !in line) {
                result += Channel("$sourceId|${result.size}", current, group, logo, line)
            }
            name = null
        }
        return result
    }
}

internal object Xtream {
    /** Live-kanalerna från ett Xtream-konto, som HLS: det Apples spelare klarar. */
    suspend fun live(source: Source): List<Channel> {
        val base = source.server.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        val auth = "username=${encodeUrl(source.username)}&password=${encodeUrl(source.password)}"
        val categories = Json.parse(fetchText("$base/player_api.php?$auth&action=get_live_categories")) as? List<*> ?: emptyList<Any?>()
        val names = categories.mapNotNull { it as? Map<*, *> }
            .associate { (it["category_id"].asText() ?: "") to (it["category_name"].asText() ?: "Övrigt") }
        val streams = Json.parse(fetchText("$base/player_api.php?$auth&action=get_live_streams")) as? List<*>
            ?: throw HttpException("Kontot svarade inte med några kanaler. Stämmer användarnamn och lösenord?")
        val result = ArrayList<Channel>(streams.size)
        for (item in streams) {
            val stream = item as? Map<*, *> ?: continue
            val id = stream["stream_id"].asText() ?: continue
            result += Channel(
                key = "${source.id}|${result.size}",
                name = stream["name"].asText() ?: "Kanal",
                group = names[stream["category_id"].asText()] ?: "Övrigt",
                logo = stream["stream_icon"].asText()?.ifBlank { null },
                url = "$base/live/${source.username}/${source.password}/$id.m3u8",
            )
        }
        return result
    }
}

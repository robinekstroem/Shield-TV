// Delas med Windows-versionen (se windows/build.gradle.kts). Håll filen fri
// från android.*-importer, annars slutar Windows-bygget att kompilera.

package se.norrsken.tv.data.playlist

import java.io.BufferedReader

/**
 * En rad ur spellistan, direkt som leverantören skrev den.
 * Översättningen till databasrader sker i [PlaylistImporter].
 */
data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logoUrl: String? = null,
    val group: String? = null,
    val channelNumber: Int? = null,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
)

data class M3uPlaylist(
    /** EPG-adressen som ligger i #EXTM3U-raden, om leverantören satt en. */
    val epgUrl: String?,
    val entries: List<M3uEntry>,
)

// Vanlig sträng med escapes, inte raw string: en raw string som slutar på
// ett citattecken blir tvetydig för Kotlins lexer.
private val ATTRIBUTE = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

/**
 * Parsar en M3U-fil.
 *
 * Läser rad för rad ur strömmen i stället för att först läsa in hela filen
 * som en sträng — en spellista med 20 000 kanaler är lätt 8 MB, och på en
 * billig TV-box är det skillnaden mellan att fungera och att bli dödad av
 * systemet.
 *
 * Formatet ser ut så här:
 *
 *     #EXTM3U url-tvg="http://.../epg.xml.gz"
 *     #EXTINF:-1 tvg-id="svt1" tvg-logo="..." group-title="Sverige",SVT1
 *     #EXTGRP:Sverige
 *     #EXTVLCOPT:http-user-agent=VLC/3.0
 *     http://server/live/svt1.m3u8
 *
 * Mellan #EXTINF och URL:en kan det ligga hur många #-rader som helst.
 * Därför hålls senaste #EXTINF kvar tills en rad som inte börjar med # dyker
 * upp — det är då kanalen är komplett.
 */
fun parseM3u(
    reader: BufferedReader,
    onProgress: (parsed: Int) -> Unit = {},
): M3uPlaylist {
    val entries = ArrayList<M3uEntry>(1024)
    var epgUrl: String? = null
    var pending: M3uEntry? = null
    var pendingGroup: String? = null

    reader.forEachLine { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty()) return@forEachLine

        when {
            line.startsWith("#EXTM3U", ignoreCase = true) -> {
                val attrs = attributes(line)
                epgUrl = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]
            }

            line.startsWith("#EXTINF", ignoreCase = true) -> {
                pending = parseExtInf(line)
                pendingGroup = null
            }

            // Egen grupprad. Vissa leverantörer använder den i stället för
            // group-title, andra använder båda och låter dem säga olika saker.
            line.startsWith("#EXTGRP", ignoreCase = true) -> {
                pendingGroup = line.substringAfter(':', "").trim().ifEmpty { null }
            }

            line.startsWith("#") -> Unit // #EXTVLCOPT, kommentarer, allt annat

            else -> {
                val entry = pending
                if (entry != null) {
                    entries += entry.copy(
                        url = line,
                        group = entry.group ?: pendingGroup,
                    )
                    pending = null
                    pendingGroup = null
                    if (entries.size % 500 == 0) onProgress(entries.size)
                }
            }
        }
    }

    onProgress(entries.size)
    return M3uPlaylist(epgUrl = epgUrl, entries = entries)
}

private fun attributes(line: String): Map<String, String> =
    ATTRIBUTE.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

private fun parseExtInf(line: String): M3uEntry {
    val attrs = attributes(line)

    // Namnet står efter sista kommat. Sista, inte första: attributvärden
    // innehåller ofta komman ("group-title=Sport, Live").
    // Saknas kommat helt returnerar substringAfterLast hela raden, och
    // kanalen hade hetat "#EXTINF:-1 tvg-id=...". Därav kontrollen.
    val name = if (line.contains(',')) line.substringAfterLast(',').trim() else ""

    return M3uEntry(
        name = name.ifEmpty { attrs["tvg-name"] ?: "Namnlös kanal" },
        url = "",
        tvgId = attrs["tvg-id"]?.ifEmpty { null },
        tvgName = attrs["tvg-name"]?.ifEmpty { null },
        logoUrl = attrs["tvg-logo"]?.ifEmpty { null },
        group = attrs["group-title"]?.ifEmpty { null },
        channelNumber = attrs["tvg-chno"]?.toIntOrNull(),
        catchupDays = attrs["catchup-days"]?.toIntOrNull()
            ?: attrs["timeshift"]?.toIntOrNull()
            ?: 0,
        catchupSource = attrs["catchup-source"]?.ifEmpty { null },
    )
}

/**
 * Normaliserat namn för EPG-matchning: gemener, inga skiljetecken, inga
 * kvalitetsmarkörer. "SVT1 HD" och "svt 1" ska landa på samma sträng.
 */
fun normalizeChannelName(name: String): String = name
    .lowercase()
    .replace(Regex("""\b(uhd|4k|fhd|hd|sd|hevc|h265|raw)\b"""), " ")
    .replace(Regex("""[^a-z0-9åäö]+"""), " ")
    .trim()
    .replace(Regex(" +"), " ")

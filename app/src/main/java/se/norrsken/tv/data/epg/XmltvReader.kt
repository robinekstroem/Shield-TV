package se.norrsken.tv.data.epg

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

data class XmltvProgramme(
    val channel: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    val category: String?,
    val icon: String?,
    val episode: String?,
)

/**
 * Läser XMLTV i ström — en kanal eller ett program i taget.
 *
 * Tablåfiler är ofta flera hundra megabyte. Att läsa in hela dokumentet i
 * minnet, eller som en sträng, hade dödat appen på en TV-box. Här ligger
 * bara det aktuella elementet i minnet.
 *
 * Den som anropar styr takten med [next]. Det gör att databasen kan skrivas
 * i omgångar mitt i läsningen, vilket en callback inte hade tillåtit —
 * databasen är asynkron, tolken är det inte.
 *
 * Teckenkodningen läses ur filens eget XML-huvud. Leverantörer använder
 * både UTF-8 och ISO-8859-1, och att gissa fel ger "Ã¥" i stället för "å".
 */
class XmltvReader(stream: InputStream) {

    sealed interface Item {
        data class Channel(val channel: XmltvChannel) : Item
        data class Programme(val programme: XmltvProgramme) : Item
    }

    private val parser: XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(stream, null)
    }

    /** Nästa kanal eller program, eller null när filen är slut. */
    fun next(): Item? {
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> return null
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> readChannel()?.let { return Item.Channel(it) }
                    "programme" -> readProgramme()?.let { return Item.Programme(it) }
                }
            }
        }
    }

    private fun readChannel(): XmltvChannel? {
        val id = parser.getAttributeValue(null, "id")?.trim()
        val names = mutableListOf<String>()
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG && parser.name == "display-name") {
                parser.nextText().trim().takeIf { it.isNotEmpty() }?.let { names += it }
            }
        }
        return if (id.isNullOrEmpty()) null else XmltvChannel(id, names)
    }

    /**
     * Ett program utan kanal, start, slut eller titel hoppas över. Program
     * utan sluttid likaså — att hitta på en längd vore att visa en tablå
     * som inte stämmer.
     */
    private fun readProgramme(): XmltvProgramme? {
        val channel = parser.getAttributeValue(null, "channel")?.trim()
        val start = parseXmltvTime(parser.getAttributeValue(null, "start"))
        val stop = parseXmltvTime(parser.getAttributeValue(null, "stop"))

        var title: String? = null
        var description: String? = null
        var category: String? = null
        var icon: String? = null
        var episode: String? = null

        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event != XmlPullParser.START_TAG) continue

            // Titel och beskrivning finns ofta på flera språk. Den första
            // behålls — oftast leverantörens huvudspråk.
            when (parser.name) {
                "title" -> {
                    val text = parser.nextText().trim()
                    if (title == null && text.isNotEmpty()) title = text
                }
                "desc" -> if (description == null) {
                    description = parser.nextText().trim().ifEmpty { null }
                }
                "category" -> if (category == null) {
                    category = parser.nextText().trim().ifEmpty { null }
                }
                "icon" -> if (icon == null) {
                    icon = parser.getAttributeValue(null, "src")?.ifEmpty { null }
                }
                // "onscreen" är det människor läser ("S02E05"). xmltv_ns är
                // nollbaserat och maskinläsbart, och skulle visas fel.
                "episode-num" -> if (episode == null &&
                    parser.getAttributeValue(null, "system") == "onscreen"
                ) {
                    episode = parser.nextText().trim().ifEmpty { null }
                }
            }
        }

        if (channel.isNullOrEmpty() || start == null || stop == null || stop <= start) return null
        return XmltvProgramme(
            channel = channel,
            start = start,
            stop = stop,
            title = title ?: return null,
            description = description,
            category = category,
            icon = icon,
            episode = episode,
        )
    }
}

package se.norrsken.tv.desktop.data

import java.io.IOException
import org.xmlpull.v1.XmlPullParserException
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Shield-versionen av XMLTV-läsaren: Androids XmlPullParser i stället för
 * Javas StAX, som inte finns på Android. Samma resultat som Windows-filen.
 * Läser strömmande, så att stora tablåfiler aldrig ligger i minnet i sin helhet.
 */
object XmltvParser {
    private const val MAX_DESCRIPTION = 1_200

    /** Fel i själva XML-filen blir IOException, som andra fel vid hämtningen. */
    fun parse(input: InputStream, channels: List<LiveChannel>, from: Long, until: Long): SourceGuide =
        try {
            parseXml(input, channels, from, until)
        } catch (e: XmlPullParserException) {
            throw IOException("Tablåfilen gick inte att läsa (${e.message}).", e)
        }

    private fun parseXml(input: InputStream, channels: List<LiveChannel>, from: Long, until: Long): SourceGuide {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // Tål entiteter som &nbsp; utan DTD; leverantörernas filer är inte alltid strikta.
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setInput(BufferedInputStream(input, 1 shl 16), null)
        val declared = ArrayList<GuideChannel>()
        val programmes = HashMap<String, ArrayList<Programme>>()
        val strings = HashMap<String, String>()
        var wanted: Set<String>? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser)?.let { declared += it }
                    "programme" -> {
                        // Kanalerna står först i filen. Vid första programmet vet
                        // vi vilka tablåkanaler källans kanaler motsvarar.
                        val ids = wanted ?: matchGuide(channels, declared).values.toHashSet().also { wanted = it }
                        readProgramme(parser, ids, from, until, strings)?.let { (id, programme) ->
                            programmes.getOrPut(id) { ArrayList() } += programme
                        }
                    }
                }
            }
            event = parser.next()
        }
        return SourceGuide(
            channels = declared,
            programmes = programmes.mapValues { finish(it.value) },
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun readChannel(parser: XmlPullParser): GuideChannel? {
        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        val names = ArrayList<String>(2)
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> if (parser.name == "display-name") names += readText(parser) else depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return null
            }
        }
        return if (id.isEmpty()) null else GuideChannel(id, names.filter { it.isNotBlank() })
    }

    private fun readProgramme(
        parser: XmlPullParser,
        wanted: Set<String>,
        from: Long,
        until: Long,
        strings: HashMap<String, String>,
    ): Pair<String, Programme>? {
        val channel = parser.getAttributeValue(null, "channel")?.trim()?.lowercase()
        val start = parseXmltvTime(parser.getAttributeValue(null, "start"))
        val stop = parseXmltvTime(parser.getAttributeValue(null, "stop")) ?: 0L
        if (channel == null || start == null || channel !in wanted || start >= until || (stop in 1..from)) {
            skip(parser)
            return null
        }
        var title: String? = null
        var titleSwedish = false
        var subtitle: String? = null
        var description: String? = null
        var descriptionSwedish = false
        var category: String? = null
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    val swedish = parser.getAttributeValue(null, "lang")?.lowercase()?.startsWith("sv") == true
                    when (parser.name) {
                        "title" -> {
                            val text = readText(parser)
                            if (text.isNotEmpty() && (title == null || (swedish && !titleSwedish))) {
                                title = text
                                titleSwedish = swedish
                            }
                        }
                        "sub-title" -> readText(parser).takeIf { it.isNotEmpty() }?.let { if (subtitle == null) subtitle = it }
                        "desc" -> {
                            val text = readText(parser)
                            if (text.isNotEmpty() && (description == null || (swedish && !descriptionSwedish))) {
                                description = text.take(MAX_DESCRIPTION)
                                descriptionSwedish = swedish
                            }
                        }
                        "category" -> readText(parser).takeIf { it.isNotEmpty() }?.let { if (category == null) category = it }
                        else -> depth++
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return null
            }
        }
        val name = title ?: return null
        return channel to Programme(
            start = start,
            stop = stop,
            title = strings.getOrPut(name) { name },
            subtitle = subtitle,
            description = description,
            category = category?.let { value -> strings.getOrPut(value) { value } },
        )
    }

    /** Texten i ett element; står på START_TAG, slutar på dess END_TAG. */
    private fun readText(parser: XmlPullParser): String {
        val text = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT -> if (depth == 1) text.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return text.toString().trim()
    }

    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}

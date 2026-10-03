package se.norrsken.tv.desktop.data

import java.io.IOException
import javax.xml.stream.XMLStreamException
import java.io.BufferedInputStream
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader


/**
 * Läser XMLTV i ström. Tablåfiler är ofta flera hundra megabyte, så bara det
 * aktuella elementet ligger i minnet, och bara program för kanaler som finns
 * i källan och inom [from]–[until] sparas.
 *
 * Teckenkodningen läses ur filens XML-huvud av tolken själv — leverantörer
 * använder både UTF-8 och ISO-8859-1.
 */
object XmltvParser {
    private const val MAX_DESCRIPTION = 1_200

    private val factory: XMLInputFactory by lazy {
        XMLInputFactory.newFactory().apply {
            // Ingen DTD och inga externa entiteter: en fil från nätet ska inte
            // kunna få tolken att läsa filer eller hämta adresser.
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }
    }

    /** Fel i själva XML-filen blir IOException, som andra fel vid hämtningen. */
    fun parse(input: InputStream, channels: List<LiveChannel>, from: Long, until: Long): SourceGuide =
        try {
            parseXml(input, channels, from, until)
        } catch (e: XMLStreamException) {
            throw IOException("Tablåfilen gick inte att läsa (${e.message}).", e)
        }

    private fun parseXml(input: InputStream, channels: List<LiveChannel>, from: Long, until: Long): SourceGuide {
        val reader = factory.createXMLStreamReader(BufferedInputStream(input, 1 shl 16))
        val declared = ArrayList<GuideChannel>()
        val programmes = HashMap<String, ArrayList<Programme>>()
        val strings = HashMap<String, String>()
        var wanted: Set<String>? = null
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
                when (reader.localName) {
                    "channel" -> readChannel(reader)?.let { declared += it }
                    "programme" -> {
                        // Kanalerna står först i filen. Vid första programmet vet
                        // vi vilka tablåkanaler källans kanaler motsvarar.
                        val ids = wanted ?: matchGuide(channels, declared).values.toHashSet().also { wanted = it }
                        readProgramme(reader, ids, from, until, strings)?.let { (id, programme) ->
                            programmes.getOrPut(id) { ArrayList() } += programme
                        }
                    }
                }
            }
        } finally {
            runCatching { reader.close() }
        }
        return SourceGuide(
            channels = declared,
            programmes = programmes.mapValues { finish(it.value) },
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun readChannel(reader: XMLStreamReader): GuideChannel? {
        val id = reader.getAttributeValue(null, "id")?.trim().orEmpty()
        val names = ArrayList<String>(2)
        var depth = 1
        while (reader.hasNext() && depth > 0) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT ->
                    if (reader.localName == "display-name") names += readText(reader) else depth++
                XMLStreamConstants.END_ELEMENT -> depth--
            }
        }
        return if (id.isEmpty()) null else GuideChannel(id, names.filter { it.isNotBlank() })
    }

    private fun readProgramme(
        reader: XMLStreamReader,
        wanted: Set<String>,
        from: Long,
        until: Long,
        strings: HashMap<String, String>,
    ): Pair<String, Programme>? {
        val channel = reader.getAttributeValue(null, "channel")?.trim()?.lowercase()
        val start = parseXmltvTime(reader.getAttributeValue(null, "start"))
        val stop = parseXmltvTime(reader.getAttributeValue(null, "stop")) ?: 0L
        if (channel == null || start == null || channel !in wanted || start >= until || (stop in 1..from)) {
            skip(reader)
            return null
        }
        var title: String? = null
        var titleSwedish = false
        var subtitle: String? = null
        var description: String? = null
        var descriptionSwedish = false
        var category: String? = null
        var depth = 1
        while (reader.hasNext() && depth > 0) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    val swedish = reader.getAttributeValue(null, "lang")?.lowercase()?.startsWith("sv") == true
                    when (reader.localName) {
                        "title" -> {
                            val text = readText(reader)
                            if (text.isNotEmpty() && (title == null || (swedish && !titleSwedish))) {
                                title = text
                                titleSwedish = swedish
                            }
                        }
                        "sub-title" -> readText(reader).takeIf { it.isNotEmpty() }?.let { if (subtitle == null) subtitle = it }
                        "desc" -> {
                            val text = readText(reader)
                            if (text.isNotEmpty() && (description == null || (swedish && !descriptionSwedish))) {
                                description = text.take(MAX_DESCRIPTION)
                                descriptionSwedish = swedish
                            }
                        }
                        "category" -> readText(reader).takeIf { it.isNotEmpty() }?.let { if (category == null) category = it }
                        else -> depth++
                    }
                }
                XMLStreamConstants.END_ELEMENT -> depth--
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

    /** Texten i ett element; står på START_ELEMENT, slutar på dess END_ELEMENT. */
    private fun readText(reader: XMLStreamReader): String {
        val text = StringBuilder()
        var depth = 1
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (depth == 1) text.append(reader.text)
                XMLStreamConstants.START_ELEMENT -> depth++
                XMLStreamConstants.END_ELEMENT -> {
                    depth--
                    if (depth == 0) break
                }
            }
        }
        return text.toString().trim()
    }

    private fun skip(reader: XMLStreamReader) {
        var depth = 1
        while (reader.hasNext() && depth > 0) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> depth++
                XMLStreamConstants.END_ELEMENT -> depth--
            }
        }
    }
}

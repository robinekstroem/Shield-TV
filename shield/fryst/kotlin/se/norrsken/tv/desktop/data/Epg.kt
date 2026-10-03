package se.norrsken.tv.desktop.data

import se.norrsken.tv.data.playlist.normalizeChannelName
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
private const val HOUR_MS = 3_600_000L

/** Ett program i tablån. Tiderna är millisekunder sedan 1970, UTC. */
class Programme(
    val start: Long,
    stop: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val category: String?,
) {
    /** Slut. Saknas det i filen blir det nästa programs start. */
    var stop: Long = stop
        internal set

    fun isOn(now: Long): Boolean = now >= start && now < stop

    fun progress(now: Long): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)

    val minutes: Int get() = ((stop - start) / 60_000L).toInt()
}

/** En kanal som tablåfilen själv deklarerar i ett `<channel>`-element. */
class GuideChannel(val id: String, val names: List<String>)

/** Tablån för en källa: bara de kanaler och den tid appen har nytta av. */
class SourceGuide(
    val channels: List<GuideChannel>,
    /** Tablåns kanal-id, med små bokstäver → programmen i tidsordning. */
    val programmes: Map<String, List<Programme>>,
    val fetchedAt: Long,
) {
    companion object {
        /** Spellistor kan ange flera tablåfiler. */
        fun merge(a: SourceGuide, b: SourceGuide): SourceGuide {
            val channels = LinkedHashMap<String, GuideChannel>()
            (a.channels + b.channels).forEach { channels.putIfAbsent(it.id.lowercase(), it) }
            val programmes = HashMap<String, List<Programme>>(a.programmes)
            b.programmes.forEach { (id, list) ->
                val existing = programmes[id]
                programmes[id] = if (existing == null) list else finish(existing + list)
            }
            return SourceGuide(channels.values.toList(), programmes, maxOf(a.fetchedAt, b.fetchedAt))
        }
    }
}

/**
 * Kopplar kanaler till tablåns kanaler, med samma regler som Android-appen
 * (EpgMatcher): hellre ingen tablå än fel tablå. Man sätter på nyheterna och
 * får en matlagningsshow — det är värre än ingen tablå alls. Därför ingen
 * ungefärlig matchning, och en namnträff måste vara entydig.
 *
 * 1. tvg-id / epg_channel_id som tablån deklarerar.
 * 2. Exakt namn, entydigt.
 * 3. Normaliserat namn ("SVT1 HD" = "SVT 1"), entydigt.
 * 4. tvg-id som tablån inte deklarerar — fortfarande leverantörens egen koppling.
 *
 * Svarar med kanalens nyckel → tablåns kanal-id (små bokstäver).
 */
fun matchGuide(channels: List<LiveChannel>, declared: List<GuideChannel>): Map<String, String> {
    val declaredIds = HashSet<String>(declared.size * 2)
    val byName = HashMap<String, MutableSet<String>>()
    val byNormalized = HashMap<String, MutableSet<String>>()
    for (channel in declared) {
        val id = channel.id.lowercase()
        declaredIds += id
        for (name in channel.names) {
            val exact = name.trim().lowercase()
            if (exact.isNotEmpty()) byName.getOrPut(exact) { HashSet(2) } += id
            val normalized = normalizeChannelName(name)
            if (normalized.isNotEmpty()) byNormalized.getOrPut(normalized) { HashSet(2) } += id
        }
    }
    val result = HashMap<String, String>(channels.size * 2)
    for (channel in channels) {
        val epgId = channel.epgId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val id = when {
            epgId != null && epgId in declaredIds -> epgId
            else -> byName[channel.name.trim().lowercase()]?.singleOrNull()
                ?: byNormalized[normalizeChannelName(channel.name)]?.singleOrNull()
                ?: epgId
        }
        if (id != null) result[channel.key] = id
    }
    return result
}

/**
 * Tablån som skärmarna frågar: kanal → program. Vanlig klass med flit, så att
 * Compose jämför på identitet och inte program för program.
 */
class GuideIndex(private val schedules: Map<String, List<Programme>>) {
    val channelCount: Int get() = schedules.size
    val isEmpty: Boolean get() = schedules.isEmpty()

    fun hasSchedule(channel: LiveChannel): Boolean = schedules.containsKey(channel.key)

    fun schedule(channel: LiveChannel): List<Programme> = schedules[channel.key].orEmpty()

    fun current(channel: LiveChannel, now: Long): Programme? {
        val list = schedules[channel.key] ?: return null
        return list.getOrNull(lastStartingBefore(list, now))?.takeIf { now < it.stop }
    }

    fun next(channel: LiveChannel, now: Long): Programme? {
        val list = schedules[channel.key] ?: return null
        return list.getOrNull(lastStartingBefore(list, now) + 1)
    }

    companion object {
        val EMPTY = GuideIndex(emptyMap())

        fun build(
            sources: List<Source>,
            contents: Map<String, SourceContent>,
            guides: Map<String, SourceGuide>,
        ): GuideIndex {
            val schedules = HashMap<String, List<Programme>>()
            for (source in sources) {
                val guide = guides[source.id] ?: continue
                val channels = contents[source.id]?.channels ?: continue
                val mapping = matchGuide(channels, guide.channels)
                for (channel in channels) {
                    val list = mapping[channel.key]?.let { guide.programmes[it] } ?: continue
                    if (list.isNotEmpty()) schedules[channel.key] = list
                }
            }
            return GuideIndex(schedules)
        }
    }
}

/** Index för det sista programmet som börjat vid [now], eller -1. */
private fun lastStartingBefore(list: List<Programme>, now: Long): Int {
    var low = 0
    var high = list.size - 1
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (list[mid].start <= now) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

/** Sorterar, tar bort dubbletter och fyller i saknade sluttider. */
internal fun finish(list: List<Programme>): List<Programme> {
    val sorted = list.sortedBy { it.start }
    val result = ArrayList<Programme>(sorted.size)
    for (programme in sorted) {
        if (result.isNotEmpty() && result.last().start == programme.start) continue
        result += programme
    }
    for (i in result.indices) {
        val programme = result[i]
        if (programme.stop <= programme.start) {
            programme.stop = result.getOrNull(i + 1)?.start ?: (programme.start + HOUR_MS / 2)
        }
    }
    return result
}

/** XMLTV-tid: "20260930193000 +0200". Utan tidszon räknas den som UTC. */
internal fun parseXmltvTime(value: String?): Long? {
    val text = value?.trim() ?: return null
    var digits = 0
    while (digits < text.length && text[digits].isDigit()) digits++
    if (digits < 12) return null
    return try {
        val year = text.substring(0, 4).toInt()
        val month = text.substring(4, 6).toInt()
        val day = text.substring(6, 8).toInt()
        val hour = text.substring(8, 10).toInt()
        val minute = text.substring(10, 12).toInt()
        val second = if (digits >= 14) text.substring(12, 14).toInt() else 0
        val zone = text.substring(digits).trim()
        val offset = if (zone.length >= 5 && (zone[0] == '+' || zone[0] == '-')) {
            val sign = if (zone[0] == '-') -1 else 1
            sign * (zone.substring(1, 3).toInt() * 3600 + zone.substring(3, 5).toInt() * 60)
        } else {
            0
        }
        LocalDateTime.of(year, month, day, hour, minute, second).toEpochSecond(ZoneOffset.ofTotalSeconds(offset)) * 1000L
    } catch (e: RuntimeException) {
        null
    }
}

/**
 * Tablåerna på disk, en fil per källa, gzippad. Ingen kryptering: här finns
 * kanalnamn och programtitlar, ingen inloggning.
 */
class GuideStore {
    private val directory: File get() = File(AppDirs.cache, "epg").also { it.mkdirs() }

    fun save(sourceId: String, guide: SourceGuide) {
        runCatching {
            val target = file(sourceId)
            val temp = File(target.parentFile, target.name + ".tmp")
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(temp)), 1 shl 16)).use { write(it, guide) }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Program som slutade för mer än [keepAfter] kastas vid inläsning. */
    fun load(sourceId: String, keepAfter: Long): SourceGuide? = runCatching {
        val source = file(sourceId)
        if (!source.isFile) return null
        DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(source)), 1 shl 16)).use { read(it, keepAfter) }
    }.getOrNull()

    fun delete(sourceId: String) {
        runCatching { file(sourceId).delete() }
    }

    private fun file(sourceId: String) =
        File(directory, sourceId.filter { it.isLetterOrDigit() || it == '-' } + ".bin")

    private fun write(out: DataOutputStream, guide: SourceGuide) {
        out.writeInt(MAGIC)
        out.writeInt(FORMAT)
        out.writeLong(guide.fetchedAt)
        out.writeInt(guide.channels.size)
        for (channel in guide.channels) {
            out.putString(channel.id)
            out.writeInt(channel.names.size)
            channel.names.forEach { out.putString(it) }
        }
        out.writeInt(guide.programmes.size)
        for ((id, list) in guide.programmes) {
            out.putString(id)
            out.writeInt(list.size)
            for (programme in list) {
                out.writeLong(programme.start)
                out.writeLong(programme.stop)
                out.putString(programme.title)
                out.putString(programme.subtitle)
                out.putString(programme.description)
                out.putString(programme.category)
            }
        }
    }

    private fun read(input: DataInputStream, keepAfter: Long): SourceGuide? {
        if (input.readInt() != MAGIC || input.readInt() != FORMAT) return null
        val fetchedAt = input.readLong()
        val channels = List(input.count()) {
            val id = input.getString().orEmpty()
            GuideChannel(id, List(input.count()) { input.getString().orEmpty() })
        }
        val strings = HashMap<String, String>()
        val programmes = HashMap<String, List<Programme>>()
        repeat(input.count()) {
            val id = input.getString().orEmpty()
            val list = ArrayList<Programme>()
            repeat(input.count()) {
                val start = input.readLong()
                val stop = input.readLong()
                val title = input.getString().orEmpty()
                val subtitle = input.getString()
                val description = input.getString()
                val category = input.getString()
                if (stop > keepAfter) {
                    list += Programme(start, stop, strings.getOrPut(title) { title }, subtitle, description, category)
                }
            }
            if (list.isNotEmpty()) programmes[id] = list
        }
        return SourceGuide(channels, programmes, fetchedAt)
    }

    private companion object {
        const val MAGIC = 0x4E544731 // "NTG1"
        const val FORMAT = 1
        const val MAX_ITEMS = 5_000_000
    }

    private fun DataOutputStream.putString(value: String?) {
        if (value == null) {
            writeInt(-1)
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.getString(): String? {
        val size = readInt()
        if (size < 0) return null
        if (size > 1 shl 20) throw IOException("Trasig tablåfil")
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataInputStream.count(): Int {
        val value = readInt()
        if (value < 0 || value > MAX_ITEMS) throw IOException("Trasig tablåfil")
        return value
    }
}

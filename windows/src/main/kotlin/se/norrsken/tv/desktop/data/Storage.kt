package se.norrsken.tv.desktop.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class Settings(
    val volume: Int = 80,
    val sharpScaling: Boolean = true,
    /** VLC:s språknamn, t.ex. "Swedish". Null: inget val. */
    val audioLanguage: String? = null,
    /** Som ovan; "" betyder undertexter av. */
    val subtitleLanguage: String? = null,
    /** Sorteringen i Filmer och Serier, som namnet på en SortOrder. */
    val movieSort: String? = null,
    val seriesSort: String? = null,
    /** Namnet på ett AppTheme. */
    val theme: String? = null,
    /** TMDB-nyckeln, DPAPI-krypterad som lösenorden. */
    val tmdbKey: String? = null,
    /** Egna bakgrundsbilder: sökvägar till appens kopior. */
    val backgrounds: List<String> = emptyList(),
    /** När appen senast öppnades: det som kommit till sedan dess är "nytt". 0: aldrig. */
    val lastVisitUtc: Long = 0L,
    /** Dölj det som bara finns på andra språk än svenska, nordiska och engelska. */
    val hideForeign: Boolean = false,
    /** Dolda kategorier, som "m|Action" (LibraryFilter.groupKey). */
    val hiddenGroups: List<String> = emptyList(),
)

class Storage {
    private val sourcesFile: File get() = File(AppDirs.config, "sources.json")
    private val settingsFile: File get() = File(AppDirs.config, "settings.json")
    private val libraryDir: File get() = File(AppDirs.cache, "library").also { it.mkdirs() }

    // --- Källor ------------------------------------------------------------

    fun loadSources(): List<Source> = runCatching {
        val file = sourcesFile
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText(Charsets.UTF_8))
        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::sourceFromJson) }
    }.getOrDefault(emptyList())

    fun saveSources(sources: List<Source>) {
        val array = JSONArray()
        sources.forEach { array.put(it.toJson()) }
        writeAtomically(sourcesFile, array.toString(2).toByteArray(Charsets.UTF_8))
    }

    private fun Source.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("type", type.name)
        .put("displayUrl", displayUrl)
        .put("username", username)
        .put("secret", secret)
        .put("liveExtension", liveExtension)
        .put("lastSyncUtc", lastSyncUtc)

    private fun sourceFromJson(o: JSONObject): Source? {
        val id = o.optString("id", "").ifEmpty { return null }
        val type = runCatching { SourceType.valueOf(o.optString("type", "")) }.getOrNull() ?: return null
        return Source(
            id = id,
            name = o.optString("name", "").ifEmpty { "Källa" },
            type = type,
            displayUrl = o.optString("displayUrl", ""),
            username = o.optString("username", "").ifEmpty { null },
            secret = o.optString("secret", "").ifEmpty { null },
            liveExtension = o.optString("liveExtension", "").ifEmpty { "ts" },
            lastSyncUtc = if (o.has("lastSyncUtc")) o.optLong("lastSyncUtc", 0L) else null,
        )
    }

    // --- Inställningar -----------------------------------------------------

    fun loadSettings(): Settings = runCatching {
        val file = settingsFile
        if (!file.isFile) return Settings()
        val o = JSONObject(file.readText(Charsets.UTF_8))
        Settings(
            volume = o.optInt("volume", 80).coerceIn(0, 100),
            sharpScaling = o.optBoolean("sharpScaling", true),
            audioLanguage = if (o.has("audioLanguage")) o.optString("audioLanguage", "") else null,
            subtitleLanguage = if (o.has("subtitleLanguage")) o.optString("subtitleLanguage", "") else null,
            movieSort = o.optString("movieSort", "").ifEmpty { null },
            seriesSort = o.optString("seriesSort", "").ifEmpty { null },
            theme = o.optString("theme", "").ifEmpty { null },
            tmdbKey = o.optString("tmdbKey", "").ifEmpty { null },
            backgrounds = o.optJSONArray("backgrounds")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it, "").ifEmpty { null } }
            }.orEmpty(),
            lastVisitUtc = o.optLong("lastVisitUtc", 0L),
            hideForeign = o.optBoolean("hideForeign", false),
            hiddenGroups = o.optJSONArray("hiddenGroups")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it, "").ifEmpty { null } }
            }.orEmpty(),
        )
    }.getOrDefault(Settings())

    fun saveSettings(settings: Settings) {
        runCatching {
            val o = JSONObject()
                .put("volume", settings.volume)
                .put("sharpScaling", settings.sharpScaling)
                .put("audioLanguage", settings.audioLanguage)
                .put("subtitleLanguage", settings.subtitleLanguage)
                .put("movieSort", settings.movieSort)
                .put("seriesSort", settings.seriesSort)
                .put("theme", settings.theme)
                .put("tmdbKey", settings.tmdbKey)
                .put("backgrounds", JSONArray(settings.backgrounds))
                .put("lastVisitUtc", settings.lastVisitUtc)
                .put("hideForeign", settings.hideForeign)
                .put("hiddenGroups", JSONArray(settings.hiddenGroups))
            writeAtomically(settingsFile, o.toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    // --- Biblioteket -------------------------------------------------------

    /**
     * Biblioteket sparas komprimerat och DPAPI-krypterat i sin helhet.
     *
     * M3U-adresser bär ofta inloggningen i klartext (…/live/robin/hemligt/1.ts),
     * så cachen är lika känslig som lösenordet. Utan DPAPI cachas ingenting;
     * då hämtas biblioteket om vid start i stället.
     */
    fun saveContent(sourceId: String, content: SourceContent): Boolean = runCatching {
        val raw = ByteArrayOutputStream(1 shl 20)
        DataOutputStream(GZIPOutputStream(raw)).use { writeContent(it, content) }
        val sealed = SecretBox.seal(raw.toByteArray()) ?: return false
        writeAtomically(cacheFile(sourceId), sealed)
        true
    }.getOrDefault(false)

    fun loadContent(sourceId: String): SourceContent? = runCatching {
        val file = cacheFile(sourceId)
        if (!file.isFile) return null
        val raw = SecretBox.unseal(file.readBytes()) ?: return null
        DataInputStream(GZIPInputStream(ByteArrayInputStream(raw))).use { readContent(it, sourceId) }
    }.getOrNull()

    fun deleteContent(sourceId: String) {
        runCatching { cacheFile(sourceId).delete() }
    }

    private fun cacheFile(sourceId: String): File =
        File(libraryDir, sourceId.filter { it.isLetterOrDigit() || it == '-' } + ".bin")

    private fun writeContent(out: DataOutputStream, c: SourceContent) {
        out.writeInt(MAGIC)
        out.writeInt(FORMAT)
        out.writeText(c.epgUrl)
        out.writeStrings(c.liveGroups)
        out.writeStrings(c.movieGroups)
        out.writeStrings(c.seriesGroups)

        out.writeInt(c.channels.size)
        for (ch in c.channels) {
            out.writeText(ch.name)
            out.writeText(ch.group)
            out.writeText(ch.logo)
            out.writeInt(ch.number ?: NONE)
            out.writeText(ch.epgId)
            out.writeText(ch.url)
            out.writeInt(ch.streamId ?: NONE)
        }

        out.writeInt(c.movies.size)
        for (m in c.movies) {
            out.writeInt(m.streamId)
            out.writeText(m.name)
            out.writeText(m.group)
            out.writeText(m.poster)
            out.writeDouble(m.rating ?: Double.NaN)
            out.writeLong(m.addedUtc)
            out.writeText(m.extension)
        }

        out.writeInt(c.series.size)
        for (s in c.series) {
            out.writeInt(s.seriesId)
            out.writeText(s.name)
            out.writeText(s.group)
            out.writeText(s.cover)
            out.writeText(s.backdrop)
            out.writeText(s.plot)
            out.writeText(s.genre)
            out.writeText(s.releaseDate)
            out.writeDouble(s.rating ?: Double.NaN)
            out.writeText(s.trailer)
            out.writeLong(s.addedUtc)
        }
    }

    private fun readContent(input: DataInputStream, sourceId: String): SourceContent? {
        if (input.readInt() != MAGIC || input.readInt() != FORMAT) return null
        val epgUrl = input.readText()
        val liveGroups = input.readStrings()
        val movieGroups = input.readStrings()
        val seriesGroups = input.readStrings()

        val channels = List(input.readCount()) { index ->
            LiveChannel(
                key = "$sourceId:c$index",
                sourceId = sourceId,
                name = input.readText().orEmpty(),
                group = input.readText() ?: UNCATEGORIZED,
                logo = input.readText(),
                number = input.readInt().takeIf { it != NONE },
                epgId = input.readText(),
                url = input.readText(),
                streamId = input.readInt().takeIf { it != NONE },
            )
        }

        val movies = List(input.readCount()) { index ->
            Movie(
                key = "$sourceId:m$index",
                sourceId = sourceId,
                streamId = input.readInt(),
                name = input.readText().orEmpty(),
                group = input.readText() ?: UNCATEGORIZED,
                poster = input.readText(),
                rating = input.readDouble().takeUnless { it.isNaN() },
                addedUtc = input.readLong(),
                extension = input.readText() ?: "mp4",
            )
        }

        val series = List(input.readCount()) { index ->
            Series(
                key = "$sourceId:s$index",
                sourceId = sourceId,
                seriesId = input.readInt(),
                name = input.readText().orEmpty(),
                group = input.readText() ?: UNCATEGORIZED,
                cover = input.readText(),
                backdrop = input.readText(),
                plot = input.readText(),
                genre = input.readText(),
                releaseDate = input.readText(),
                rating = input.readDouble().takeUnless { it.isNaN() },
                trailer = input.readText(),
                addedUtc = input.readLong(),
            )
        }

        return SourceContent(channels, movies, series, liveGroups, movieGroups, seriesGroups, epgUrl)
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(bytes)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val MAGIC = 0x4E545631 // "NTV1"
        // Höjs när formatet ändras. En cache i gammalt format läses inte, och
        // källan hämtas då om automatiskt vid start.
        const val FORMAT = 3
        const val NONE = Int.MIN_VALUE
        const val MAX_ITEMS = 2_000_000
    }

    // Egen längdprefixad UTF-8 i stället för writeUTF: den har ett tak på
    // 64 kB per sträng, och handlingar kan vara långa.
    private fun DataOutputStream.writeText(value: String?) {
        if (value == null) {
            writeInt(-1)
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String? {
        val size = readInt()
        if (size < 0) return null
        if (size > 16 shl 20) throw IOException("Trasig cache")
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataOutputStream.writeStrings(values: List<String>) {
        writeInt(values.size)
        values.forEach { writeText(it) }
    }

    private fun DataInputStream.readStrings(): List<String> =
        List(readCount()) { readText().orEmpty() }

    private fun DataInputStream.readCount(): Int {
        val count = readInt()
        if (count < 0 || count > MAX_ITEMS) throw IOException("Trasig cache")
        return count
    }
}

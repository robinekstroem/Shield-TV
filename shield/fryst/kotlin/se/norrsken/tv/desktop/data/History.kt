package se.norrsken.tv.desktop.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class WatchKind { LIVE, MOVIE, EPISODE }

/**
 * Något man har tittat på. Bara id:n, namn och bild — aldrig adresser,
 * eftersom M3U- och Xtream-adresser bär inloggningen. Uppspelningen byggs
 * om ur biblioteket när man klickar i historiken.
 */
data class WatchEntry(
    val kind: WatchKind,
    val sourceId: String,
    /** Kanalens eller filmens stream-id, avsnittets id, eller "namn:…" för M3U-kanaler. */
    val itemId: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val watchedAtUtc: Long,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    // Avsnitt: det som behövs för att spela det igen utan serien öppen.
    val seriesId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val extension: String? = null,
) {
    /** Samma sak, oavsett när den sågs. */
    val identity: String get() = "${kind.name}:$sourceId:$itemId"

    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Värt att fortsätta: mer än en minut in och inte nästan slut. */
    val resumable: Boolean
        get() = kind != WatchKind.LIVE && positionMs > 60_000 && (durationMs <= 0 || positionMs < durationMs * 0.95)
}

/** Historiken i %APPDATA%, de senaste 300 sakerna. */
class HistoryStore {
    private val file: File get() = File(AppDirs.config, "history.json")

    fun load(): List<WatchEntry> = runCatching {
        val current = file
        if (!current.isFile) return emptyList()
        val array = JSONArray(current.readText(Charsets.UTF_8))
        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::entryFrom) }
    }.getOrDefault(emptyList())

    fun save(entries: List<WatchEntry>) {
        runCatching {
            val array = JSONArray()
            entries.forEach { array.put(it.toJson()) }
            writeFileSafely(file, array.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun WatchEntry.toJson(): JSONObject = JSONObject()
        .put("kind", kind.name)
        .put("sourceId", sourceId)
        .put("itemId", itemId)
        .put("title", title)
        .put("subtitle", subtitle)
        .put("image", image)
        .put("watchedAtUtc", watchedAtUtc)
        .put("positionMs", positionMs)
        .put("durationMs", durationMs)
        .put("seriesId", seriesId)
        .put("season", season)
        .put("episode", episode)
        .put("extension", extension)

    private fun entryFrom(o: JSONObject): WatchEntry? {
        val kind = runCatching { WatchKind.valueOf(o.optString("kind", "")) }.getOrNull() ?: return null
        val sourceId = o.optString("sourceId", "").ifEmpty { return null }
        val itemId = o.optString("itemId", "").ifEmpty { return null }
        return WatchEntry(
            kind = kind,
            sourceId = sourceId,
            itemId = itemId,
            title = o.optString("title", ""),
            subtitle = o.optString("subtitle", "").ifEmpty { null },
            image = o.optString("image", "").ifEmpty { null },
            watchedAtUtc = o.optLong("watchedAtUtc", 0L),
            positionMs = o.optLong("positionMs", 0L),
            durationMs = o.optLong("durationMs", 0L),
            seriesId = if (o.has("seriesId")) o.optInt("seriesId") else null,
            season = if (o.has("season")) o.optInt("season") else null,
            episode = if (o.has("episode")) o.optInt("episode") else null,
            extension = o.optString("extension", "").ifEmpty { null },
        )
    }
}

/** Skriv via en tillfällig fil, så att en krasch aldrig lämnar en halv fil efter sig. */
internal fun writeFileSafely(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, target.name + ".tmp")
    temp.writeBytes(bytes)
    try {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

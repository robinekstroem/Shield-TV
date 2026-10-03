package se.norrsken.tv.desktop.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Vad som kan sparas i Min lista. */
enum class ListKind { MOVIE, SERIES, CHANNEL }

/** En sparad titel eller kanal, med bibliotekets nyckel. */
data class ListEntry(val kind: ListKind, val key: String, val addedUtc: Long)

/** Min lista på disk, bredvid historiken. Liten: skrivs om helt vid varje ändring. */
class MyListStore {
    private val file: File get() = File(AppDirs.config, "minlista.json")

    fun load(): List<ListEntry> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText(Charsets.UTF_8))
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val kind = runCatching { ListKind.valueOf(o.optString("kind", "")) }.getOrNull() ?: return@mapNotNull null
            val key = o.optString("key", "").ifEmpty { return@mapNotNull null }
            ListEntry(kind, key, o.optLong("addedUtc", 0L))
        }
    }.getOrDefault(emptyList())

    fun save(entries: List<ListEntry>) {
        runCatching {
            val array = JSONArray()
            entries.forEach { array.put(JSONObject().put("kind", it.kind.name).put("key", it.key).put("addedUtc", it.addedUtc)) }
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeText(array.toString(2), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }
}

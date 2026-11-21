package se.norrsken.tv.desktop.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** En film i startens filmvägg: titeln och omslaget, och bakgrundsbilden om den finns. */
class IntroReelItem(val title: String, val poster: String, val backdrop: String?)

/**
 * Filmväggen från förra gången. Starten visar den direkt — omslagen ligger
 * redan på disken — i stället för att vänta på att biblioteket laddats.
 * Liten: skrivs om helt när den ändras.
 */
object IntroReelStore {
    private val file: File get() = File(AppDirs.config, "startfilm.json")

    fun load(): List<IntroReelItem> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText(Charsets.UTF_8))
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("t", "").ifBlank { return@mapNotNull null }
            val poster = o.optString("p", "").ifBlank { return@mapNotNull null }
            IntroReelItem(title, poster, o.optString("b", "").ifBlank { null })
        }
    }.getOrDefault(emptyList())

    fun save(items: List<IntroReelItem>) {
        runCatching {
            val array = JSONArray()
            items.forEach { item ->
                val o = JSONObject().put("t", item.title).put("p", item.poster)
                item.backdrop?.let { o.put("b", it) }
                array.put(o)
            }
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeText(array.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }
}

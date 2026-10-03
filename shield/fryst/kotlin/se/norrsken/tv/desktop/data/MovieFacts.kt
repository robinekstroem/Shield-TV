package se.norrsken.tv.desktop.data

import org.json.JSONObject
import java.io.File

/**
 * Det appen lärt sig om en film från dess infosida hos leverantören:
 * genren och året. Filmlistan har inget av det, så det hämtas en gång per
 * film i bakgrunden och sparas här.
 */
data class MovieFacts(val genre: String, val year: Int?)

class MovieFactsStore {
    private val file: File get() = File(AppDirs.cache, "movie-facts.json")

    fun load(): Map<String, MovieFacts> = runCatching {
        val current = file
        if (!current.isFile) return emptyMap()
        val o = JSONObject(current.readText(Charsets.UTF_8))
        val result = HashMap<String, MovieFacts>(o.length() * 2)
        for (key in o.keys()) {
            val entry = o.optJSONObject(key) ?: continue
            result[key] = MovieFacts(entry.optString("g", ""), if (entry.has("y")) entry.optInt("y") else null)
        }
        result
    }.getOrDefault(emptyMap())

    fun save(facts: Map<String, MovieFacts>) {
        runCatching {
            val o = JSONObject()
            for ((key, value) in facts) {
                val entry = JSONObject().put("g", value.genre)
                value.year?.let { entry.put("y", it) }
                o.put(key, entry)
            }
            writeFileSafely(file, o.toString().toByteArray(Charsets.UTF_8))
        }
    }

    companion object {
        fun keyOf(sourceId: String, streamId: Int): String = "$sourceId:$streamId"
    }
}

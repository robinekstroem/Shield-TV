package se.norrsken.tv.desktop.data

import org.json.JSONObject
import java.io.File

/**
 * Var introt och eftertexterna brukar ligga i en serie. Appen lär sig det av
 * hur man själv spolar: hoppar man fram en bit i början av ett avsnitt är det
 * introt, spolar man förbi slutet är det eftertexterna. Nästa avsnitt
 * erbjuder samma hopp.
 */
data class SeriesMark(
    /** Där introt börjar och slutar. -1: okänt. */
    val introFromMs: Long = -1L,
    val introToMs: Long = -1L,
    /** Hur långt före slutet eftertexterna börjar. 0: okänt. */
    val outroLeadMs: Long = 0L,
) {
    val hasIntro: Boolean get() = introFromMs >= 0L && introToMs > introFromMs
}

/** Seriernas intro och eftertexter, per rensad seriettitel. */
class SeriesMarksStore {
    private val file: File get() = File(AppDirs.config, "serier.json")

    fun load(): Map<String, SeriesMark> = runCatching {
        val current = file
        if (!current.isFile) return emptyMap()
        val o = JSONObject(current.readText(Charsets.UTF_8))
        val result = HashMap<String, SeriesMark>()
        for (key in o.keys()) {
            val e = o.optJSONObject(key) ?: continue
            result[key] = SeriesMark(
                introFromMs = e.optLong("if", -1L),
                introToMs = e.optLong("it", -1L),
                outroLeadMs = e.optLong("ut", 0L),
            )
        }
        result
    }.getOrDefault(emptyMap())

    fun save(marks: Map<String, SeriesMark>) {
        runCatching {
            val o = JSONObject()
            for ((key, mark) in marks) {
                o.put(key, JSONObject().put("if", mark.introFromMs).put("it", mark.introToMs).put("ut", mark.outroLeadMs))
            }
            writeFileSafely(file, o.toString().toByteArray(Charsets.UTF_8))
        }
    }

    companion object {
        /** Samma nyckel för alla versioner av serien: den rensade titeln. */
        fun keyOf(title: String): String = TitleCleaner.normalize(title)
    }
}

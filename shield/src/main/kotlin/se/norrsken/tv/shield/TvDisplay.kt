package se.norrsken.tv.shield

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import se.norrsken.tv.desktop.data.AppDirs

/**
 * Hur stort allt är på tv:n: hur bred skärmen är i layoutens enheter. Bredare
 * får plats med mer och gör varje sak mindre, som en datorskärm. Det ändrar
 * inte skärpan — allt ritas i fönstrets fulla upplösning. Texten skalas för
 * sig, så att den går att läsa från soffan även i det minsta läget.
 */
enum class TvResolution(
    val widthDp: Int,
    val label: String,
    /** Textens storlek i bildpunkter per sp, räknat på en 1080p-skärm. */
    val textPx: Float,
) {
    SMALL(1280, "Stor", 1.725f),
    MEDIUM(1600, "Mellan", 1.5f),
    LARGE(1920, "Liten", 1.3f),
}

object TvDisplay {
    private const val PREFS = "tv-shield"
    private const val KEY = "upplosning"

    var resolution by mutableStateOf(load())
        private set

    fun select(option: TvResolution) {
        resolution = option
        prefs().edit().putString(KEY, option.name).apply()
    }


    private fun load(): TvResolution =
        runCatching { TvResolution.valueOf(prefs().getString(KEY, null).orEmpty()) }.getOrDefault(TvResolution.MEDIUM)

    private fun prefs() = AppDirs.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

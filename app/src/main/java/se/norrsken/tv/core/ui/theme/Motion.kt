package se.norrsken.tv.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Rörelse svarar på vad användaren gör — den underhåller inte.
 *
 * Fokusanimationen är den enda som körs konstant, så den är kort. Backdrop-
 * övergången är lång eftersom den ligger bakom allt och inte får dra blicken.
 *
 * [reduced] slår ned allt till nära noll. Sätts när användaren valt
 * "Reducera effekter" eller när enheten visat sig vara för svag.
 */
@Immutable
data class Motion(
    val focusMs: Int = 200,
    val transitionMs: Int = 300,
    val backdropCrossfadeMs: Int = 500,
    val overlayMs: Int = 180,
    val reduced: Boolean = false,
) {
    fun scale(ms: Int): Int = if (reduced) 0 else ms
}

val LocalMotion = staticCompositionLocalOf { Motion() }

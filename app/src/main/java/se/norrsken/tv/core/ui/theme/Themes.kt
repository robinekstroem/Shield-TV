package se.norrsken.tv.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight

/**
 * Det som styr rubrikernas form. Ett enda utseende numera — flera teman gav
 * fyra halvfärdiga looker i stället för en genomarbetad.
 */
@Immutable
data class ThemeStyle(
    val uppercaseHeadings: Boolean = false,
    val headingAccentBar: Boolean = false,
    val displayWeight: FontWeight = FontWeight.SemiBold,
    val displayTrackingDelta: Float = 0f,
    /** Minimalt läge: förklarande text tas bort. Användarens val. */
    val minimal: Boolean = false,
)

val LocalThemeStyle = staticCompositionLocalOf { ThemeStyle() }

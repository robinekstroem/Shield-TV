package se.norrsken.tv.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import se.norrsken.tv.data.settings.ThemeChoice

/**
 * Det ett tema andrar utover farger: hur rubriker ser ut och star.
 *
 * Ett tema som bara byter accentfarg ar en accentfarg, inte ett tema.
 */
@Immutable
data class ThemeStyle(
    /** Versala rubriker. Bar tyngd — men blir skrikigt i lopande text. */
    val uppercaseHeadings: Boolean,
    /** Kort accentstreck fore radrubriker. */
    val headingAccentBar: Boolean,
    val displayWeight: FontWeight,
    /** Laggs pa rubrikernas tracking. Versaler behover luft, gemener inte. */
    val displayTrackingDelta: Float,
)

val LocalThemeStyle = staticCompositionLocalOf { styleFor(ThemeChoice.NORRSKEN) }

fun styleFor(theme: ThemeChoice): ThemeStyle = when (theme) {
    ThemeChoice.NORRSKEN -> ThemeStyle(
        uppercaseHeadings = false,
        headingAccentBar = false,
        displayWeight = FontWeight.SemiBold,
        displayTrackingDelta = 0f,
    )
    ThemeChoice.TITAN -> ThemeStyle(
        uppercaseHeadings = true,
        headingAccentBar = true,
        displayWeight = FontWeight.Black,
        displayTrackingDelta = 1.2f,
    )
    ThemeChoice.MIDNIGHT -> ThemeStyle(
        uppercaseHeadings = false,
        headingAccentBar = false,
        displayWeight = FontWeight.Medium,
        displayTrackingDelta = 0f,
    )
}

fun paletteFor(theme: ThemeChoice): Palette = when (theme) {
    /** Kall nordisk natt. Ursprungstemat. */
    ThemeChoice.NORRSKEN -> Palette()

    /**
     * Hjaltefilmens energi: djup svarta med en varm rod underton,
     * karmosinrod accent, vit fokus som slar igenom allt. Accenten anvands
     * sparsamt — live-markor, progress, rubrikstreck. Hade allt varit rott
     * hade inget varit det.
     */
    ThemeChoice.TITAN -> Palette(
        background = Color(0xFF0A0607),
        surface = Color(0xFF171011),
        surfaceRaised = Color(0xFF221718),
        hairline = Color(0xFF3A2526),
        textPrimary = Color(0xFFF7F1F1),
        textSecondary = Color(0xFFB3A1A2),
        textTertiary = Color(0xFF7A6667),
        focus = Color(0xFFFFFFFF),
        signal = Color(0xFFE0283A),
        alert = Color(0xFFFF8A5B),
        scrim = Color(0xE60A0607),
        scrimClear = Color(0x000A0607),
        ambient = Color(0xFF2A0A10),
        onAccent = Color(0xFF0A0607),
    )

    /**
     * OLED. Helt svart bakgrund — pixlarna ar avstangda, sa bilden far sta
     * for allt ljus. Minimalt med gra dis.
     */
    ThemeChoice.MIDNIGHT -> Palette(
        background = Color(0xFF000000),
        surface = Color(0xFF0B0D10),
        surfaceRaised = Color(0xFF14171C),
        hairline = Color(0xFF1F242B),
        textPrimary = Color(0xFFEDEFF2),
        textSecondary = Color(0xFF8A919B),
        textTertiary = Color(0xFF565D66),
        focus = Color(0xFFFFFFFF),
        signal = Color(0xFF6FA8FF),
        alert = Color(0xFFD97C72),
        scrim = Color(0xE6000000),
        scrimClear = Color(0x00000000),
        ambient = Color(0xFF05070A),
        onAccent = Color(0xFF000000),
    )
}

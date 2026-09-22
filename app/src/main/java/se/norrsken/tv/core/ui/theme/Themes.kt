package se.norrsken.tv.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import se.norrsken.tv.core.ui.effects.BackgroundEffect
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
    /**
     * Minimalt lage: forklarande text tas bort, dekor dampas. Satts av
     * anvandarens val, inte av temat — darfor default false har.
     */
    val minimal: Boolean = false,
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
    ThemeChoice.SNOW -> ThemeStyle(
        uppercaseHeadings = false,
        headingAccentBar = true,
        displayWeight = FontWeight.SemiBold,
        displayTrackingDelta = 0.3f,
    )
    ThemeChoice.MIDNIGHT -> ThemeStyle(
        uppercaseHeadings = false,
        headingAccentBar = false,
        displayWeight = FontWeight.Medium,
        displayTrackingDelta = 0f,
    )
}

/**
 * Temats atmosfär. Midnatt har ingen: på en OLED är svart släckta pixlar,
 * och varje partikel tänder dem igen.
 */
fun effectFor(theme: ThemeChoice): BackgroundEffect = when (theme) {
    ThemeChoice.NORRSKEN -> BackgroundEffect.AURORA
    ThemeChoice.TITAN -> BackgroundEffect.EMBERS
    ThemeChoice.SNOW -> BackgroundEffect.SNOW
    ThemeChoice.MIDNIGHT -> BackgroundEffect.NONE
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
        secondary = Color(0xFFFF6A2B),
    )

    /**
     * Vinternatt. Djupt nattblått i stället för svart, så snön har något att
     * lysa mot, och en isblå accent. Vitt är redan snöns färg — därför är
     * accenten blå och inte vit.
     */
    ThemeChoice.SNOW -> Palette(
        background = Color(0xFF070B12),
        surface = Color(0xFF101722),
        surfaceRaised = Color(0xFF1A2330),
        hairline = Color(0xFF26334A),
        textPrimary = Color(0xFFF2F7FF),
        textSecondary = Color(0xFF9FB2C8),
        textTertiary = Color(0xFF66788F),
        focus = Color(0xFFFFFFFF),
        signal = Color(0xFF8FD3FF),
        alert = Color(0xFFFF9A8A),
        scrim = Color(0xE6070B12),
        scrimClear = Color(0x00070B12),
        ambient = Color(0xFF0E1A2B),
        onAccent = Color(0xFF070B12),
        secondary = Color(0xFFDDF3FF),
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
        secondary = Color(0xFF9FC3FF),
    )
}

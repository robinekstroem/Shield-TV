package se.norrsken.tv.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Designprincip: gränssnittet är avsiktligt färglöst.
 *
 * Bakgrund, ytor och text ligger alla på en kall, avmättad gråskala. Den enda
 * mättade färgen på skärmen ska komma från innehållet — omslagsbildens
 * extraherade färg i ambient glow bakom UI:t. Gör man chrome färgstarkt
 * konkurrerar det med postern och bilden slutar vara skärmens huvudperson.
 *
 * Fokusmarkeringen är ljus, inte färgad. På tre meters avstånd läser ögat
 * ljusstyrka snabbare än kulör, och ljus fungerar över vilken backdrop som helst.
 */
@Immutable
data class Palette(
    /** Appens botten. Nästan svart, men med kall blåton så OLED-svart inte blir platt. */
    val background: Color = Color(0xFF000000),
    /** Kort, paneler, rader. Ett steg upp från botten. */
    val surface: Color = Color(0xFF0B0D10),
    /** Upphöjd yta: dialoger, menyer som ligger över innehåll. */
    val surfaceRaised: Color = Color(0xFF15181C),
    /** Hårfina linjer och vilolägets kortkant. */
    val hairline: Color = Color(0xFF22262B),

    val textPrimary: Color = Color(0xFFF2F5F7),
    val textSecondary: Color = Color(0xFFA8B0B8),
    /** Text på dämpad bakgrund, t.ex. metadata under en poster. */
    val textTertiary: Color = Color(0xFF6B747C),

    /** Fokusring och markerat läge. Ljus, inte kulör. */
    val focus: Color = Color(0xFFF5F8FA),
    /**
     * Pagaende sandning, progress, "live nu". Enda kuloraccenten i chrome.
     * Kall bla, inte teal: den ligger nara bakgrundens blaton och konkurrerar
     * darfor inte med omslagens varma farger.
     */
    val signal: Color = Color(0xFF57E6F2),
    /** Fel och avbrott. Dämpad — aldrig larmröd i en mörk mediaapp. */
    val alert: Color = Color(0xFFD97C72),

    /** Gradient som backdropen tonas ned mot så text förblir läsbar. */
    val scrim: Color = Color(0xE6000000),
    /** Helt genomskinlig variant av botten — gradientens ljusa ande. */
    val scrimClear: Color = Color(0x00000000),
    /**
     * Svagt ljus bakom sidomenyn. Skillnaden mot bakgrunden ar knappt
     * markbar, och det ar meningen: den ger djup utan att bli en ruta.
     */
    val ambient: Color = Color(0xFF06090C),
    /** Vit knapp for den primara handlingen, som i mockupen. */
    val onAccent: Color = Color(0xFF000000),
    /**
     * Andra accenten. Används bara i övergångar tillsammans med [signal] —
     * progress och laddning går från cyan till grön, som ett norrsken.
     * Aldrig ensam som färg på en knapp: då vore det två accenter som
     * tävlar.
     */
    val secondary: Color = Color(0xFF56F0B4),
)

val LocalPalette = staticCompositionLocalOf { Palette() }

/** Anvandarens val, tillgangliga i hela tradet utan att skickas ned. */
val LocalPreferences =
    staticCompositionLocalOf { se.norrsken.tv.data.settings.AppPreferences() }

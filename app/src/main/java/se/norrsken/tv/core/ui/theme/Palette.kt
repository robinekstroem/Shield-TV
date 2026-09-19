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
    val background: Color = Color(0xFF0A0C10),
    /** Kort, paneler, rader. Ett steg upp från botten. */
    val surface: Color = Color(0xFF141922),
    /** Upphöjd yta: dialoger, menyer som ligger över innehåll. */
    val surfaceRaised: Color = Color(0xFF1C232E),
    /** Hårfina linjer och vilolägets kortkant. */
    val hairline: Color = Color(0xFF283242),

    val textPrimary: Color = Color(0xFFE9EFF5),
    val textSecondary: Color = Color(0xFF8B99AA),
    /** Text på dämpad bakgrund, t.ex. metadata under en poster. */
    val textTertiary: Color = Color(0xFF5C6878),

    /** Fokusring och markerat läge. Ljus, inte kulör. */
    val focus: Color = Color(0xFFF2F6FA),
    /** Pågående sändning, progress, "live nu". Enda kuloraccenten i chrome. */
    val signal: Color = Color(0xFF5FC9B8),
    /** Fel och avbrott. Dämpad — aldrig larmröd i en mörk mediaapp. */
    val alert: Color = Color(0xFFD97C72),

    /** Gradient som backdropen tonas ned mot så text förblir läsbar. */
    val scrim: Color = Color(0xE60A0C10),
)

val LocalPalette = staticCompositionLocalOf { Palette() }

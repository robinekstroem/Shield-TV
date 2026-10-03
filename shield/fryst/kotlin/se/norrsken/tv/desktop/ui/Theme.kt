package se.norrsken.tv.desktop.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Utseendet. Väljs under Inställningar → Utseende och sparas. */
enum class AppTheme(val label: String, val description: String) {
    GRAPHITE("Grafit", "Stilla gråskala. Markeringen är ljus, inte färg."),
    EMBER("Orange glöd", "Svart och orange, med en mjuk glöd kring det du pekar på."),
    CHRISTMAS("Julafton", "Snöfall, en ljusslinga som blinkar och varmt sken. Extra mysigt i december."),
    HERO("Superhjälte", "Kraftiga färger, serietidningsraster och fart."),
    MAGIC("Trolldom", "Midnattsblått, pergament och guld, med glittrande stjärnstoft."),
    EPIC("Fantasyepos", "Jordnära gröna toner, gammalt guld och dimma som driver förbi."),
}

/** Det som rör sig eller ligger i bakgrunden, per tema. */
enum class Decoration { NONE, SNOW, SPARKLES, HALFTONE, MIST }

/**
 * Färgerna. Grafit är samma palett som Android-appen (core/ui/theme/Color.kt):
 * nästan färglös, markering är ljus, inte kulör. Orange glöd bygger på
 * appikonens orangea kassett.
 */
@Immutable
data class Palette(
    val background: Color = Color(0xFF000000),
    val ambient: Color = Color(0xFF06090C),
    val surface: Color = Color(0xFF0B0D10),
    val surfaceRaised: Color = Color(0xFF15181C),
    val hairline: Color = Color(0xFF22262B),
    val textPrimary: Color = Color(0xFFF2F5F7),
    val textSecondary: Color = Color(0xFFA8B0B8),
    val textTertiary: Color = Color(0xFF6B747C),
    /** Accenten: markering, knappar, reglage. */
    val focus: Color = Color(0xFFF5F8FA),
    /** Det som är levande: LIVE, nu-linjen, pågående import. */
    val signal: Color = Color(0xFF57E6F2),
    val secondary: Color = Color(0xFF56F0B4),
    val alert: Color = Color(0xFFD97C72),
    val scrim: Color = Color(0xE6000000),
    /** Glöden kring markerat och hovrat. Genomskinlig: ingen glöd. */
    val glow: Color = Color.Transparent,
    /** Text på accentfärgen, som i den primära knappen. */
    val onFocus: Color = Color(0xFF000000),
    val decoration: Decoration = Decoration.NONE,
    /** Rubrikernas typsnitt: antikva för saga och jul, fet kursiv för serietidning. */
    val headingFont: FontFamily = FontFamily.Default,
    val headingWeight: FontWeight = FontWeight.SemiBold,
    val headingStyle: FontStyle = FontStyle.Normal,
)

/** Glödens färg i en viss styrka, eller genomskinlig om temat inte glöder. */
fun Palette.glowOf(strength: Float): Color =
    if (glow.alpha <= 0f) Color.Transparent else glow.copy(alpha = (glow.alpha * strength).coerceIn(0f, 1f))

val LocalPalette = staticCompositionLocalOf { Palette() }

private val graphite = Palette()

private val ember = Palette(
    background = Color(0xFF000000),
    ambient = Color(0xFF070403),
    surface = Color(0xFF0E0906),
    surfaceRaised = Color(0xFF1B120B),
    hairline = Color(0xFF2E1E12),
    textPrimary = Color(0xFFFFF3E6),
    textSecondary = Color(0xFFDDBFA3),
    textTertiary = Color(0xFF927460),
    focus = Color(0xFFFF8A1F),
    signal = Color(0xFFFFB547),
    secondary = Color(0xFFFFD089),
    alert = Color(0xFFFF5E4D),
    scrim = Color(0xE6000000),
    // Alfa 0,55: all glöd i temat blir ungefär hälften så stark som förut.
    glow = Color(0x8CFF7A00),
)

private val christmas = Palette(
    background = Color(0xFF07110C),
    ambient = Color(0xFF050D09),
    surface = Color(0xFF0D1913),
    surfaceRaised = Color(0xFF16261D),
    hairline = Color(0xFF26392D),
    textPrimary = Color(0xFFFFF6EA),
    textSecondary = Color(0xFFE6D2B5),
    textTertiary = Color(0xFF9A8A73),
    focus = Color(0xFFD93A3A),
    onFocus = Color(0xFFFFF6EA),
    signal = Color(0xFFF2C14E),
    secondary = Color(0xFF8FD18A),
    alert = Color(0xFFFF6B5B),
    // Som levande ljus: varm och svag.
    glow = Color(0x59FFB347),
    decoration = Decoration.SNOW,
    headingFont = FontFamily.Serif,
)

private val hero = Palette(
    background = Color(0xFF0A0E1C),
    ambient = Color(0xFF070A15),
    surface = Color(0xFF111833),
    surfaceRaised = Color(0xFF1A2347),
    hairline = Color(0xFF2B3766),
    textPrimary = Color(0xFFF6F8FF),
    textSecondary = Color(0xFFBAC3E8),
    textTertiary = Color(0xFF7480AE),
    focus = Color(0xFFE63946),
    onFocus = Color(0xFFFFFFFF),
    signal = Color(0xFFFFD60A),
    secondary = Color(0xFF4CC9F0),
    alert = Color(0xFFFF8A3D),
    decoration = Decoration.HALFTONE,
    headingFont = FontFamily.SansSerif,
    headingWeight = FontWeight.Black,
    headingStyle = FontStyle.Italic,
)

private val magic = Palette(
    background = Color(0xFF0C0A14),
    ambient = Color(0xFF08060F),
    surface = Color(0xFF141022),
    surfaceRaised = Color(0xFF1E1832),
    hairline = Color(0xFF2E2648),
    textPrimary = Color(0xFFF5ECD7),
    textSecondary = Color(0xFFCDBB95),
    textTertiary = Color(0xFF8C7D61),
    focus = Color(0xFFD9B45A),
    onFocus = Color(0xFF1A1208),
    signal = Color(0xFFEBCB75),
    secondary = Color(0xFFB7A6E8),
    alert = Color(0xFFE06C5C),
    glow = Color(0x4DE8C46A),
    decoration = Decoration.SPARKLES,
    headingFont = FontFamily.Serif,
)

private val epic = Palette(
    background = Color(0xFF0A0C09),
    ambient = Color(0xFF060805),
    surface = Color(0xFF11140E),
    surfaceRaised = Color(0xFF1A1F16),
    hairline = Color(0xFF2C3325),
    textPrimary = Color(0xFFEFE6CF),
    textSecondary = Color(0xFFC9BC98),
    textTertiary = Color(0xFF857A5E),
    focus = Color(0xFFC8A24A),
    onFocus = Color(0xFF15110A),
    signal = Color(0xFFD8B65C),
    secondary = Color(0xFFA7C080),
    alert = Color(0xFFD46A4F),
    glow = Color(0x40C8A24A),
    decoration = Decoration.MIST,
    headingFont = FontFamily.Serif,
)

fun paletteFor(theme: AppTheme): Palette = when (theme) {
    AppTheme.GRAPHITE -> graphite
    AppTheme.EMBER -> ember
    AppTheme.CHRISTMAS -> christmas
    AppTheme.HERO -> hero
    AppTheme.MAGIC -> magic
    AppTheme.EPIC -> epic
}

// Skrivbordsstorlekar: läsavstånd en halv meter, inte tre meter som på tv:n.
// I glödtemat lyser rubrikerna svagt, som neon bakom glas.
private fun typographyFor(palette: Palette): Typography {
    val neon = if (palette.glow.alpha > 0f) {
        Shadow(color = palette.glowOf(0.45f), offset = Offset.Zero, blurRadius = 14f)
    } else {
        null
    }
    return Typography(
        displaySmall = TextStyle(fontFamily = palette.headingFont, fontWeight = palette.headingWeight, fontStyle = palette.headingStyle, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp, shadow = neon),
        headlineMedium = TextStyle(fontFamily = palette.headingFont, fontWeight = palette.headingWeight, fontStyle = palette.headingStyle, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp, shadow = neon),
        headlineSmall = TextStyle(fontFamily = palette.headingFont, fontWeight = palette.headingWeight, fontStyle = palette.headingStyle, fontSize = 19.sp, lineHeight = 25.sp, shadow = neon),
        titleLarge = TextStyle(fontFamily = palette.headingFont, fontWeight = palette.headingWeight, fontStyle = palette.headingStyle, fontSize = 17.sp, lineHeight = 23.sp, shadow = neon),
        titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
        titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp),
        bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
        bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
        bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
        labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
        labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
    )
}

@Composable
fun TvTheme(theme: AppTheme = AppTheme.GRAPHITE, translucent: Boolean = false, content: @Composable () -> Unit) {
    // Med egna bakgrundsbilder släpper sidomenyn och panelerna igenom lite av bilden.
    val palette = paletteFor(theme).let {
        if (translucent) it.copy(ambient = it.ambient.copy(alpha = 0.8f), surface = it.surface.copy(alpha = 0.86f)) else it
    }
    val typography = remember(palette) { typographyFor(palette) }
    val colors = darkColorScheme(
        primary = palette.signal,
        onPrimary = palette.background,
        secondary = palette.focus,
        onSecondary = palette.background,
        background = palette.background,
        onBackground = palette.textPrimary,
        surface = palette.surface,
        onSurface = palette.textPrimary,
        surfaceVariant = palette.surfaceRaised,
        onSurfaceVariant = palette.textSecondary,
        surfaceContainer = palette.surfaceRaised,
        surfaceContainerHigh = palette.surfaceRaised,
        surfaceContainerHighest = palette.surfaceRaised,
        outline = palette.hairline,
        error = palette.alert,
    )
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(
            LocalPalette provides palette,
            LocalContentColor provides palette.textPrimary,
        ) {
            ProvideScrollbars(palette) { content() }
        }
    }
}

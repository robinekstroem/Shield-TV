package se.norrsken.tv.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.TimeSource
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
    CRYSTAL(
        "Kristall",
        "Frostat glas mot ett levande, flytande ljus i violett, rosa och cyan. Det du markerar får en skimrande kant och ett glansigt ljussvep.",
    ),
    EMBER("Orange glöd", "Svart och orange, med en mjuk glöd kring det du pekar på."),
    CHRISTMAS("Julafton", "Vinternatt i månsken: snö som driver i vinden, granar, en röd stuga med ljus i fönstren och en ljusslinga som blinkar."),
    HERO("Superhjälte", "Kraftiga färger, serietidningsraster och fart."),
    MAGIC("Trolldom", "Midnattsblått, pergament och guld, med glittrande stjärnstoft."),
    EPIC("Fantasyepos", "Jordnära gröna toner, gammalt guld och dimma som driver förbi."),
    AURORA("Nordljus", "Arktisk natt där norrskenet böljar långsamt över fjällen."),
    RETRO("Retrovåg", "Åttiotalsneon och VHS: rosa rutnät mot en randig sol, och svaga svep över bilden."),
}

/** Det som rör sig eller ligger i bakgrunden, per tema. */
enum class Decoration { NONE, SNOW, SPARKLES, HALFTONE, MIST, AURORA, RETRO, CRYSTAL }

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
    /** Kanten kring det markerade som ett skimmer i flera färger. Tom: accentfärgen. */
    val ring: List<Color> = emptyList(),
    /** Ett ljussvep över omslag och kort när markeringen kommer dit, och en kant som vandrar. */
    val shine: Boolean = false,
    /** Egna bakgrundsbilder: temats himmel blir genomskinlig, så att bilderna syns. */
    val customBackdrop: Boolean = false,
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

// Vinternatt: djupt midnattsblått, julrött och guld som levande ljus.
private val christmas = Palette(
    background = Color(0xFF050B18),
    ambient = Color(0xFF040915),
    surface = Color(0xFF0A1428),
    surfaceRaised = Color(0xFF122039),
    hairline = Color(0xFF223351),
    textPrimary = Color(0xFFFFF7EC),
    textSecondary = Color(0xFFE3D6C3),
    textTertiary = Color(0xFF8E96A8),
    focus = Color(0xFFD93A3A),
    onFocus = Color(0xFFFFF6EA),
    signal = Color(0xFFF2C14E),
    secondary = Color(0xFF8FD18A),
    alert = Color(0xFFFF6B5B),
    // Som levande ljus: varm och svag.
    glow = Color(0x66FFB347),
    decoration = Decoration.SNOW,
    headingFont = FontFamily.Serif,
)

// Arktisk natt: norrskensgrönt med violett, kallt och klart.
private val aurora = Palette(
    background = Color(0xFF03080F),
    ambient = Color(0xFF02060C),
    surface = Color(0xFF07111C),
    surfaceRaised = Color(0xFF0E1B29),
    hairline = Color(0xFF1B2D3E),
    textPrimary = Color(0xFFEFFAF7),
    textSecondary = Color(0xFFA9C7C1),
    textTertiary = Color(0xFF6A8580),
    focus = Color(0xFF3EF0B2),
    onFocus = Color(0xFF02150F),
    signal = Color(0xFFB98BFF),
    secondary = Color(0xFF5CC8FF),
    alert = Color(0xFFFF7A8A),
    glow = Color(0x733EF0B2),
    decoration = Decoration.AURORA,
)

// Kristall: frostat glas. Ytorna är halvgenomskinliga, så att ljuset bakom
// skymtar genom menyn och korten. Markeringen skimrar i cyan, violett och rosa.
private val crystal = Palette(
    background = Color(0xFF05040F),
    ambient = Color(0xC209081C),
    surface = Color(0xB80E0C26),
    surfaceRaised = Color(0xD01A1738),
    hairline = Color(0x47B9ABFF),
    textPrimary = Color(0xFFF8F6FF),
    textSecondary = Color(0xFFC6C0EA),
    textTertiary = Color(0xFF8D87B8),
    focus = Color(0xFFEAE4FF),
    onFocus = Color(0xFF15102F),
    signal = Color(0xFF4FE3FF),
    secondary = Color(0xFFFF7AD9),
    alert = Color(0xFFFF6B8B),
    scrim = Color(0xE605040F),
    glow = Color(0x998C6CFF),
    decoration = Decoration.CRYSTAL,
    headingWeight = FontWeight.Bold,
    ring = listOf(Color(0xFF5EEBFF), Color(0xFFA98BFF), Color(0xFFFF7AD9), Color(0xFFFFD58A), Color(0xFF5EEBFF)),
    shine = true,
)

// Åttiotal: neonrosa och cyan på lila, som omslaget till en VHS-kassett.
private val retro = Palette(
    background = Color(0xFF0C0416),
    ambient = Color(0xFF090312),
    surface = Color(0xFF150826),
    surfaceRaised = Color(0xFF211038),
    hairline = Color(0xFF3B1D61),
    textPrimary = Color(0xFFFFF0FB),
    textSecondary = Color(0xFFD6B4E6),
    textTertiary = Color(0xFF8E73A6),
    focus = Color(0xFFFF3EA5),
    onFocus = Color(0xFF1A0010),
    signal = Color(0xFF36E2FF),
    secondary = Color(0xFFFF9F43),
    alert = Color(0xFFFF5C7A),
    glow = Color(0x80FF3EA5),
    decoration = Decoration.RETRO,
    headingWeight = FontWeight.Bold,
    headingStyle = FontStyle.Italic,
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
    AppTheme.CRYSTAL -> crystal
    AppTheme.EMBER -> ember
    AppTheme.CHRISTMAS -> christmas
    AppTheme.HERO -> hero
    AppTheme.MAGIC -> magic
    AppTheme.EPIC -> epic
    AppTheme.AURORA -> aurora
    AppTheme.RETRO -> retro
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
        if (translucent) {
            it.copy(
                ambient = it.ambient.copy(alpha = minOf(it.ambient.alpha, 0.8f)),
                surface = it.surface.copy(alpha = minOf(it.surface.alpha, 0.86f)),
                customBackdrop = true,
            )
        } else {
            it
        }
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

/** Kanten kring det markerade: skimrande i Kristall, annars i accentfärgen. */
fun Modifier.focusRing(palette: Palette, width: Dp, shape: Shape, alpha: Float = 1f): Modifier =
    if (palette.ring.size >= 2) {
        border(width, Brush.linearGradient(palette.ring.map { it.copy(alpha = it.alpha * alpha) }), shape)
    } else {
        border(width, palette.focus.copy(alpha = palette.focus.alpha * alpha), shape)
    }

/**
 * Kristall: ljussvepet och kantens skimmer för ett markerat kort. Värdena
 * läses först när kortet ritas, så att bara ritningen görs om.
 */
@Stable
class CardShine {
    internal val sweep = Animatable(0f)
    /** Hur många varv kantens färger vandrat. */
    internal val turn = mutableFloatStateOf(0f)
}

/** Null i teman utan ljussvep. Svepet går en gång när [active] blir sant; kanten vandrar så länge. */
@Composable
fun rememberCardShine(active: Boolean): CardShine? {
    if (!LocalPalette.current.shine) return null
    val shine = remember { CardShine() }
    LaunchedEffect(active) {
        shine.sweep.snapTo(0f)
        if (!active) return@LaunchedEffect
        launch { shine.sweep.animateTo(1f, tween(SHINE_MS, easing = FastOutSlowInEasing)) }
        val start = TimeSource.Monotonic.markNow()
        val from = shine.turn.floatValue
        // Tjugo gånger i sekunden räcker för en kant som vandrar så sakta.
        while (true) {
            delay(50)
            shine.turn.floatValue = from + start.elapsedNow().inWholeMilliseconds / RING_TURN_MS.toFloat()
        }
    }
    return shine
}

/** Ritar ljussvepet och den vandrande kanten. Läggs efter clip, så att bägge stannar innanför kortet. */
fun Modifier.cardShine(shine: CardShine?, palette: Palette, width: Dp, shape: Shape): Modifier {
    if (shine == null) return this
    val colors = palette.ring.ifEmpty { listOf(palette.focus, palette.focus) }
    return drawWithContent {
        drawContent()
        val w = size.width
        val h = size.height
        val p = shine.sweep.value
        if (p > 0f && p < 1f) {
            // Ett ljust band, vinkelrätt mot diagonalen, som drar från övre vänstra hörnet till nedre högra.
            val middle = Offset(-0.3f * w + p * 1.6f * w, -0.3f * h + p * 1.6f * h)
            val reach = Offset(w * 0.18f, h * 0.18f)
            drawRect(
                Brush.linearGradient(
                    0f to Color.Transparent,
                    0.5f to Color.White.copy(alpha = 0.30f),
                    1f to Color.Transparent,
                    start = middle - reach,
                    end = middle + reach,
                ),
            )
        }
        val angle = shine.turn.floatValue * 2f * PI.toFloat()
        val radius = maxOf(w, h) / 2f
        val along = Offset(cos(angle) * radius, sin(angle) * radius)
        drawOutline(
            shape.createOutline(size, layoutDirection, this),
            Brush.linearGradient(colors, start = center - along, end = center + along),
            // Halva strecket hamnar utanför kortet och klipps bort.
            style = Stroke(width.toPx() * 2f),
        )
    }
}

private const val SHINE_MS = 950
private const val RING_TURN_MS = 5200

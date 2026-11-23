package se.norrsken.tv.desktop.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.delay
import se.norrsken.tv.desktop.AppState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Starten, som inledningen på en film på bio — i ett enda flöde.
 *
 * Långsamt först: filmväggen tonas fram ur mörkret, från mitten och utåt,
 * medan kameran sakta glider bakåt och de första omslagen mjukt tonar över
 * i nya. Sedan tar det fart: väggen klipper mellan filmerna i vågor från
 * mitten, allt tätare, raderna glider fortare och kameran glider fram mot
 * väggen. På toppen, utan att något stannar, flyter väggen över i
 * startsidan: omslag lyfter och flyger i mjuka bågar till startsidans
 * omslag — det stora överst först, sedan raderna i en våg från vänster —
 * och lägger sig precis på dem, medan klippen glesnar, väggen tonar ner
 * till bakgrunden, biofälten glider undan och startsidan kommer fram runt
 * omkring. Samma film syns aldrig två gånger, och inget roteras. Ingen
 * logga, ingen text, inget ljud, ingen strålkastare. En knapptryckning
 * eller ett klick hoppar över, eller skyndar på slutet.
 *
 * Väggen själv ([PosterWall]) delas med bakgrunden: här ritas den i full
 * styrka, med färgton, mörkare hörn och biofält ovanpå.
 */
@Composable
internal fun StartIntro(
    state: AppState,
    wall: PosterWall,
    morph: IntroMorph,
    ready: Boolean,
    skip: Boolean,
    onFinished: () -> Unit,
) {
    // Millisekunder sedan starten. Läses bara i ritningen.
    val clock = remember { mutableLongStateOf(0L) }
    val finished = rememberUpdatedState(onFinished)
    val looks = remember { IntroLooks() }
    val ending = remember { Ending() }
    val frame = remember { OverlayFrame() }
    var clicked by remember { mutableStateOf(false) }
    val skipping = skip || clicked

    // Slutet börjar: omslagen får sina kort, och startsidan kommer fram bakom.
    fun begin(fast: Boolean) {
        if (ending.started) return
        ending.started = true
        ending.rate = if (fast) SKIP_RATE else 1f
        ending.plain = !wall.lit
        val spots = morph.close()
        var lastLanding = 0L
        if (!ending.plain) {
            val chosen = targetsOf(spots, frame)
            val targets = chosen.map { spot ->
                val urls = spot.images.map { it.trim() }.filter { it.startsWith("http", ignoreCase = true) }
                MorphTarget(
                    left = spot.left - frame.x,
                    top = spot.top - frame.y,
                    right = spot.right - frame.x,
                    bottom = spot.bottom - frame.y,
                    urls = urls,
                    image = urls.firstNotNullOfOrNull { Images.cached(it, spot.maxSide) },
                    prominent = spot.prominent,
                )
            }
            val lands = runCatching { wall.planMorph(targets) }.getOrElse { LongArray(targets.size) { -1L } }
            chosen.forEachIndexed { i, spot ->
                spot.landAt = lands[i]
                lastLanding = max(lastLanding, lands[i])
            }
        }
        ending.total = if (ending.plain) EXIT_PLAIN_MS else max(WALL_FADE_TO_MS, lastLanding + MORPH_DISSOLVE_MS) + 40L
        morph.clock.longValue = 0L
        state.introRevealing = true
    }

    // Klockan: startens tid, och slutets — som går fortare om man skyndar på det.
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        var last = start
        var time = 0.0
        while (true) {
            val over = withFrameMillis { now ->
                clock.longValue = now - start
                // En tung bild (startsidan som kommer fram) får inte slutet att hoppa.
                val dt = (now - last).coerceIn(0L, 50L)
                last = now
                // Klippen har nått toppen: väggen flyter över i startsidan, i samma bild.
                if (!ending.started && ending.armed && wall.time >= wall.peakAt) begin(fast = false)
                if (ending.started) {
                    time += dt * ending.rate
                    morph.clock.longValue = time.toLong()
                    time >= ending.total
                } else {
                    false
                }
            }
            if (over) break
        }
        wall.endMorph()
        morph.finish()
        finished.value()
    }
    LaunchedEffect(ready, skipping) {
        if (ending.started) {
            // Mitt i slutet: en knapptryckning skyndar på det.
            if (skipping) ending.rate = max(ending.rate, SKIP_RATE)
            return@LaunchedEffect
        }
        if (!ready) return@LaunchedEffect
        if (skipping) {
            begin(fast = true)
            return@LaunchedEffect
        }
        // Omslagen laddas: väggen får tändas först.
        while (!wall.lit && wall.lightingSoon(nowMs())) delay(40)
        if (wall.lit) {
            // Klippen börjar ta fart; på toppen börjar slutet (se klockan ovan).
            wall.startRush()
            ending.armed = true
        } else {
            while (clock.longValue < AMBIENT_MIN_MS) delay(40)
            begin(fast = false)
        }
    }
    LaunchedEffect(Unit) {
        delay(MAX_SHOW_MS)
        begin(fast = true)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { frame.place(it) }
            .graphicsLayer {
                // Utan vägg tonar allt bort på en gång.
                alpha = if (ending.plain) 1f - progress(morph.clock.longValue, EXIT_PLAIN_MS.toFloat()) else 1f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            // Ett klick hoppar över, och går inte vidare till sidan bakom.
            .pointerInput(Unit) { detectTapGestures { clicked = true } },
    ) {
        // Väggen i full styrka, med färgtonen och hörnen. I slutet tonar
        // den ner till bakgrunden, som är samma vägg, mörk, bakom startsidan.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = wallAlpha(ending, morph.clock.longValue)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .drawBehind {
                    val t = clock.longValue
                    if (wallAlpha(ending, morph.clock.longValue) <= 0.002f) return@drawBehind
                    drawRect(INTRO_BLACK)
                    wall.drawIntro(this)
                    if (!wall.lit) drawGlow(t)
                    looks.drawWall(this)
                },
        )
        // Omslagen på väg till startsidan, och biofälten överst.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val m = morph.clock.longValue
                    if (m >= 0L && !ending.plain) wall.drawFlyers(this, m)
                    looks.drawBars(this, barsOpen(ending, m))
                },
        )
    }
}

/**
 * Korten omslagen landar på: de som syns helt, det stora överst först och
 * sedan uppifrån och från vänster, högst [MAX_FLYERS].
 */
private fun targetsOf(spots: List<MorphSpot>, frame: OverlayFrame): List<MorphSpot> {
    val inside = spots.filter { spot ->
        spot.right - spot.left >= MIN_TARGET_PX &&
            spot.left - frame.x >= 0f && spot.top - frame.y >= 0f &&
            spot.right - frame.x <= frame.width && spot.bottom - frame.y <= frame.height
    }
    val hero = inside.filter { it.prominent }.take(1)
    return hero + inside.filter { !it.prominent }.take(MAX_FLYERS - hero.size)
}

private fun progress(m: Long, length: Float): Float = if (m < 0L) 0f else (m / length).coerceIn(0f, 1f)

/** Väggen i full styrka, tills den i slutet tonar ner till bakgrunden. */
private fun wallAlpha(ending: Ending, m: Long): Float =
    if (!ending.started || ending.plain || m < 0L) 1f else 1f - smoothstep(WALL_FADE_FROM_MS, WALL_FADE_TO_MS.toFloat(), m.toFloat())

/** Hur långt biofälten glidit undan, 0–1. */
private fun barsOpen(ending: Ending, m: Long): Float = when {
    !ending.started || m < 0L -> 0f
    ending.plain -> progress(m, EXIT_PLAIN_MS.toFloat())
    else -> smoothstep(0f, BARS_MS, m.toFloat())
}

/**
 * Slutet: väntar på toppen ([armed]), om det börjat, om väggen saknas, hur
 * fort det går och hur länge det varar.
 */
private class Ending {
    var armed = false
    var started = false
    var plain = false
    var rate = 1f
    var total = 0L
}

/** Var starten ligger i fönstret, och hur stor den är, i bildpunkter. */
private class OverlayFrame {
    var x = 0f
    var y = 0f
    var width = 0f
    var height = 0f

    fun place(coordinates: LayoutCoordinates) {
        val position = coordinates.positionInWindow()
        x = position.x
        y = position.y
        width = coordinates.size.width.toFloat()
        height = coordinates.size.height.toFloat()
    }
}

/** Innan väggen tänts: ett svagt sken i mörkret. */
private fun DrawScope.drawGlow(t: Long) {
    val shown = smoothstep(150f, 1400f, t.toFloat())
    if (shown <= 0f) return
    val breath = 0.8f + 0.2f * sin(t / 2600f * TAU)
    drawRect(
        Brush.radialGradient(
            0f to COLD.copy(alpha = 0.07f * shown * breath),
            1f to Color.Transparent,
            center = Offset(size.width / 2f, size.height / 2f),
            radius = min(size.width, size.height) * 0.7f,
        ),
    )
}

/**
 * Det som ligger ovanpå väggen i starten: färgtonen och mörkare hörn
 * ([drawWall]), och biofälten — bilden i 2,39:1 ([drawBars]).
 */
private class IntroLooks {
    private var width = -1f
    private var height = -1f
    private var grade: Brush? = null
    private var vignette: Brush? = null

    fun drawWall(scope: DrawScope) {
        with(scope) {
            measure()
            grade?.let { drawRect(it) }
            vignette?.let { drawRect(it) }
        }
    }

    /** Biofälten, som glider undan när [open] går mot 1. */
    fun drawBars(scope: DrawScope, open: Float) {
        with(scope) {
            measure()
            val full = ((height - width / LETTERBOX) / 2f).coerceAtLeast(0f)
            val bar = full * (1f - open)
            if (bar > 0.5f) {
                drawRect(INTRO_BLACK, topLeft = Offset.Zero, size = Size(width, bar))
                drawRect(INTRO_BLACK, topLeft = Offset(0f, height - bar), size = Size(width, bar))
            }
        }
    }

    private fun DrawScope.measure() {
        if (size.width == width && size.height == height) return
        width = size.width
        height = size.height
        grade = Brush.verticalGradient(
            0f to COLD.copy(alpha = 0.10f),
            0.45f to Color.Transparent,
            1f to WARM.copy(alpha = 0.06f),
            startY = 0f,
            endY = height,
        )
        vignette = Brush.radialGradient(
            0f to Color.Transparent,
            VIGNETTE_FROM to Color.Transparent,
            1f to Color.Black.copy(alpha = VIGNETTE_DARK),
            center = Offset(width / 2f, height / 2f),
            radius = max(width, height) * VIGNETTE_REACH,
        )
    }
}

// --- Startsidans kort i slutet --------------------------------------------------------

/**
 * Slutet på starten, sett från startsidan: korten anmäler här var de står
 * ([MorphSpot]). När slutet börjar väljs de som syns helt, och ett kort
 * visar sig först när omslaget som flyger dit är framme — då ligger
 * omslaget precis över kortet, med samma bild, och tonar bort.
 */
internal class IntroMorph {
    private val spots = ArrayList<MorphSpot>()

    /** Korten anmäler sig tills slutet börjar. */
    var accepting = true
        private set

    /** Starten är slut: korten behöver inte längre tänka på den. */
    var done = false
        private set

    /** Ms sedan slutet började, eller -1 innan dess. Läses i ritningen. */
    val clock = mutableLongStateOf(-1L)

    fun add(spot: MorphSpot) {
        if (accepting && spot !in spots) spots.add(spot)
    }

    fun remove(spot: MorphSpot) {
        spots.remove(spot)
    }

    /** Slutet börjar: korten som syns helt, uppifrån och från vänster. */
    fun close(): List<MorphSpot> {
        accepting = false
        return spots.filter { it.whole }.sortedWith(compareBy<MorphSpot>({ it.top }, { it.left }))
    }

    fun finish() {
        done = true
        spots.clear()
    }

    /** Kortet tonas in under omslaget strax innan det landar. */
    fun cardAlpha(spot: MorphSpot): Float {
        val m = clock.longValue
        if (m < 0L) return 0f
        return smoothstep((spot.landAt - CARD_LEAD_MS).toFloat(), spot.landAt.toFloat(), m.toFloat())
    }
}

internal val LocalIntroMorph = staticCompositionLocalOf<IntroMorph?> { null }

/** Ett kort på startsidan som ett omslag från väggen kan landa på. */
internal class MorphSpot(val morph: IntroMorph) {
    /** Kortets bildadresser i den ordning det provar dem, och hur skarpt det laddar dem. */
    var images: List<String> = emptyList()
    var maxSide = 480
    /** Det stora omslaget överst på startsidan. */
    var prominent = false
    /** Var bilden står, i fönstrets bildpunkter. */
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f
    /** Hela bilden syns: inget av den är utanför sin lista eller sidan. */
    var whole = false
    /** När omslaget som flyger hit landar, i ms efter att slutet började; -1 om inget gör det. */
    var landAt = -1L

    fun place(coordinates: LayoutCoordinates) {
        if (!morph.accepting) return
        val bounds = coordinates.boundsInWindow()
        left = bounds.left
        top = bounds.top
        right = bounds.right
        bottom = bounds.bottom
        val size = coordinates.size
        whole = size.width > 0 && size.height > 0 &&
            abs(bounds.width - size.width) < 1.5f && abs(bounds.height - size.height) < 1.5f
    }
}

/**
 * Kortets plats för startens slut, så länge starten pågår; null sedan.
 * [images]: bildadresserna kortet visar, [maxSide] hur skarpt de laddas.
 */
@Composable
internal fun rememberMorphSpot(images: List<String>, maxSide: Int, prominent: Boolean = false): MorphSpot? {
    val morph = LocalIntroMorph.current ?: return null
    if (morph.done) return null
    val spot = remember(morph) { MorphSpot(morph) }
    spot.images = images
    spot.maxSide = maxSide
    spot.prominent = prominent
    DisposableEffect(spot) {
        morph.add(spot)
        onDispose { morph.remove(spot) }
    }
    return spot
}

/** Mäter var kortets bild står, medan starten pågår. Läggs på själva bilden. */
internal fun Modifier.morphSpot(spot: MorphSpot?): Modifier =
    if (spot == null || !spot.morph.accepting) this else onGloballyPositioned { spot.place(it) }

/**
 * Ett kort som ett omslag landar på: osynligt tills omslaget är framme.
 * Genomskinligheten läggs direkt på det som ritas, utan mellanlager.
 */
internal fun Modifier.morphReveal(spot: MorphSpot?): Modifier {
    if (spot == null || spot.landAt < 0L) return this
    val morph = spot.morph
    return graphicsLayer {
        alpha = morph.cardAlpha(spot)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

private val INTRO_BLACK = Color(0xFF020306)
private val WARM = Color(0xFFFFC27A)
private val COLD = Color(0xFF3E8BFF)

/** Utan vägg: minst så här länge innan startsidan kommer fram. */
private const val AMBIENT_MIN_MS = 900L
/** Slutet utan vägg: allt tonar bort. */
private const val EXIT_PLAIN_MS = 500L
/** Slutet: väggen tonar ner till bakgrunden, och biofälten glider undan. */
private const val WALL_FADE_FROM_MS = 140f
private const val WALL_FADE_TO_MS = 1_150L
private const val BARS_MS = 700f
/** En knapptryckning eller ett klick: så mycket fortare går slutet. */
private const val SKIP_RATE = 1.8f
/** Högst så många omslag flyger, och inget till kort mindre än så (bildpunkter). */
private const val MAX_FLYERS = 18
private const val MIN_TARGET_PX = 24f
/** Korten tonas in under omslaget så här länge innan det landar. */
private const val CARD_LEAD_MS = 160L
/** Längre än så väntar den inte, även om inget blivit klart. */
private const val MAX_SHOW_MS = 20_000L

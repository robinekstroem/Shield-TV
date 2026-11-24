package se.norrsken.tv.desktop.ui

import androidx.compose.runtime.Stable
import androidx.compose.ui.layout.onPlaced
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
    morph: CardMorph,
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
        val spots = morph.visibleSpots()
        var lastLanding = 0L
        if (!ending.plain) {
            val chosen = targetsOf(spots, frame)
            val targets = chosen.map { it.toTarget(frame) }
            val lands = runCatching { wall.planMorph(targets) }.getOrElse { LongArray(targets.size) { -1L } }
            chosen.forEachIndexed { i, spot ->
                spot.landAt = lands[i]
                lastLanding = max(lastLanding, lands[i])
            }
        }
        ending.total = if (ending.plain) EXIT_PLAIN_MS else max(WALL_FADE_TO_MS, lastLanding + MORPH_DISSOLVE_MS) + 40L
        morph.start(MorphPhase.SCATTER)
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
        morph.finish(intro = true)
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

// --- Korten som omslagen flyger till och från ------------------------------------------

/** En flygning mellan väggen och korten: ingen, på väg in på väggen, väggen täcker allt, eller på väg ut till korten. */
internal enum class MorphPhase { NONE, GATHER, COVERED, SCATTER }

/**
 * Korten som omslagen från filmväggen flyger till och från — i slutet på
 * starten, och när skärmsläckaren kommer och går. Korten anmäler här var
 * de står ([MorphSpot]). När en flygning börjar väljs de som syns helt. På
 * väg in på väggen tonar ett kort bort när dess omslag lyfter; på väg ut
 * visar det sig först när omslaget som flyger dit är framme — då ligger
 * omslaget precis över kortet, med samma bild, och tonar bort.
 */
internal class CardMorph {
    private val spots = ArrayList<MorphSpot>()

    /** Starten är slut. På datorn, utan skärmsläckare, behöver korten inte längre anmäla sig. */
    var introDone = false
        private set

    /** Flygningen just nu, och ms sedan den började. Läses i ritningen. */
    val phase = mutableStateOf(MorphPhase.NONE)
    val clock = mutableLongStateOf(-1L)

    fun add(spot: MorphSpot) {
        if (spot !in spots) spots.add(spot)
    }

    fun remove(spot: MorphSpot) {
        spots.remove(spot)
    }

    /** Korten som syns helt just nu, uppifrån och från vänster, med var de står. Ingen flygning bestämd än. */
    fun visibleSpots(): List<MorphSpot> {
        val seen = ArrayList<MorphSpot>()
        for (spot in spots) {
            spot.liftAt = -1L
            spot.landAt = -1L
            if (spot.measure()) seen.add(spot)
        }
        return seen.sortedWith(compareBy<MorphSpot>({ it.top }, { it.left }))
    }

    /** Flygningen [next] börjar nu. */
    fun start(next: MorphPhase) {
        clock.longValue = 0L
        phase.value = next
    }

    /** Väggen täcker allt: korten som flög dit förblir dolda, utan att klockan behöver gå. */
    fun cover() {
        phase.value = MorphPhase.COVERED
    }

    fun finish(intro: Boolean = false) {
        phase.value = MorphPhase.NONE
        clock.longValue = -1L
        for (spot in spots) {
            spot.liftAt = -1L
            spot.landAt = -1L
        }
        if (intro) introDone = true
    }

    /** Hur synligt kortet är: borta när dess omslag lyft, tillbaka när omslaget som flyger dit landar. */
    fun cardAlpha(spot: MorphSpot): Float {
        val m = clock.longValue.toFloat()
        return when (phase.value) {
            MorphPhase.NONE -> 1f
            MorphPhase.GATHER, MorphPhase.COVERED ->
                if (spot.liftAt < 0L) 1f else 1f - smoothstep(spot.liftAt.toFloat(), (spot.liftAt + CARD_HIDE_MS).toFloat(), m)
            MorphPhase.SCATTER ->
                if (spot.landAt < 0L) 1f else smoothstep((spot.landAt - CARD_LEAD_MS).toFloat(), spot.landAt.toFloat(), m)
        }
    }
}

internal val LocalCardMorph = staticCompositionLocalOf<CardMorph?> { null }

/** Ett kort som ett omslag från väggen kan landa på, eller lyfta från. */
internal class MorphSpot(val morph: CardMorph) {
    /** Kortets bildadresser i den ordning det provar dem, och hur skarpt det laddar dem. */
    var images: List<String> = emptyList()
    var maxSide = 480
    /** Det stora omslaget överst på startsidan. */
    var prominent = false
    /** Bilden på kortet, som den placerats. Mäts först när en flygning börjar. */
    var coordinates: LayoutCoordinates? = null
    /** Var bilden står, i fönstrets bildpunkter (se [measure]). */
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f
    /** När omslaget lyfter härifrån och när det som flyger hit landar, i ms efter att flygningen började; -1 annars. */
    var liftAt = -1L
    var landAt = -1L

    /**
     * Var bilden står just nu. Falskt om den inte syns helt: utanför sin
     * lista, sidan eller fönstret. Ett markerat kort som växt räknas som helt.
     */
    fun measure(): Boolean {
        val placed = coordinates ?: return false
        if (!placed.isAttached) return false
        val size = placed.size
        if (size.width <= 0 || size.height <= 0) return false
        val bounds = placed.boundsInWindow()
        left = bounds.left
        top = bounds.top
        right = bounds.right
        bottom = bounds.bottom
        val scaleX = bounds.width / size.width
        val scaleY = bounds.height / size.height
        return scaleX > 0.5f && abs(scaleX - scaleY) < 0.01f
    }

}

/** Kortet som mål för en flygning, i överlagrets bildpunkter. */
private fun MorphSpot.toTarget(frame: OverlayFrame): MorphTarget {
    val urls = images.map { it.trim() }.filter { it.startsWith("http", ignoreCase = true) }
    return MorphTarget(
        left = left - frame.x,
        top = top - frame.y,
        right = right - frame.x,
        bottom = bottom - frame.y,
        urls = urls,
        image = urls.firstNotNullOfOrNull { Images.cached(it, maxSide) },
        prominent = prominent,
    )
}

/**
 * Kortets plats för flygningarna: på tv:n alltid (skärmsläckaren), på
 * datorn bara medan starten pågår; annars null. [images]: bildadresserna
 * kortet visar, [maxSide] hur skarpt de laddas.
 */
@Composable
internal fun rememberMorphSpot(images: List<String>, maxSide: Int, prominent: Boolean = false): MorphSpot? {
    val morph = LocalCardMorph.current ?: return null
    if (morph.introDone && !tvLayout) return null
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

/** Minns var kortets bild placerats; den mäts först när en flygning börjar. Läggs på själva bilden. */
internal fun Modifier.morphSpot(spot: MorphSpot?): Modifier =
    if (spot == null) this else onPlaced { spot.coordinates = it }

/**
 * Kortet döljs medan dess omslag flyger: borta när det lyft, tillbaka när
 * omslaget som flyger hit landat. Annars helt synligt. Genomskinligheten
 * läggs direkt på det som ritas, utan mellanlager.
 */
internal fun Modifier.morphReveal(spot: MorphSpot?): Modifier {
    if (spot == null) return this
    val morph = spot.morph
    return graphicsLayer {
        alpha = morph.cardAlpha(spot)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

// --- Skärmsläckaren ------------------------------------------------------------------

/** Skärmsläckaren: om den syns, om någon tryckt på en knapp, och om väggen täcker allt. */
@Stable
internal class SaverControl {
    var shown by mutableStateOf(false)
        private set
    var wake by mutableStateOf(false)
    var covering by mutableStateOf(false)

    fun start() {
        wake = false
        covering = false
        shown = true
    }

    fun stop() {
        shown = false
        covering = false
        wake = false
    }
}

/**
 * Skärmsläckarens läge, för ritningen: väggens och biofältens styrka, 0–1.
 * På väg in tonas de fram när alla omslag lyft ([coverFrom] till [coverAt]).
 */
private class SaverLook {
    var phase = MorphPhase.GATHER
    var coverFrom = 0f
    var coverAt = 1f

    fun wall(m: Long): Float = when (phase) {
        MorphPhase.GATHER -> smoothstep(coverFrom, coverAt, m.toFloat())
        MorphPhase.SCATTER -> 1f - smoothstep(WALL_FADE_FROM_MS, WALL_FADE_TO_MS.toFloat(), m.toFloat())
        else -> 1f
    }

    fun bars(m: Long): Float = when (phase) {
        MorphPhase.GATHER -> smoothstep(coverFrom, coverAt, m.toFloat())
        MorphPhase.SCATTER -> 1f - smoothstep(0f, BARS_MS, m.toFloat())
        else -> 1f
    }
}

/**
 * Skärmsläckaren på tv:n, när ingen rört fjärrkontrollen på en stund (se
 * AppRoot). Sidan flyter över i filmväggen: omslagen på sidan lyfter och
 * flyger in på väggen medan den tonas fram i full styrka och biofälten
 * glider in. Där glider väggen sakta, omslag tonar över i nya, och då och
 * då går en mjuk våg av nya filmer ut från mitten. En knapptryckning flyter
 * tillbaka, som slutet på starten: omslag flyger från väggen till sidans
 * omslag och lägger sig på dem. Trycker man redan på vägen in går allt
 * tillbaka samma väg. Knappen gör inget annat. Lämnas appen försvinner
 * skärmsläckaren direkt.
 */
@Composable
internal fun ScreenSaver(state: AppState, wall: PosterWall, morph: CardMorph, saver: SaverControl) {
    val looks = remember { IntroLooks() }
    val frame = remember { OverlayFrame() }
    val look = remember { SaverLook() }

    LaunchedEffect(Unit) {
        // Varje tryck på fjärren väcker, också ett som tv:n tog hand om själv (se AppState.noteInput).
        val quietSince = state.lastInputAt
        fun awake() = saver.wake || state.lastInputAt != quietSince
        wall.startSaver()
        try {
            // Var skärmsläckaren ligger är känt först efter första bilden.
            var last = withFrameMillis { it }
            // In: sidans omslag flyger in på väggen.
            val chosen = targetsOf(morph.visibleSpots(), frame)
            val times = wall.planGather(chosen.map { it.toTarget(frame) })
            chosen.forEachIndexed { i, spot -> spot.liftAt = times.lift[i] }
            look.coverFrom = (times.coveredAt - GATHER_COVER_MS).toFloat().coerceAtLeast(0f)
            look.coverAt = times.coveredAt.toFloat().coerceAtLeast(look.coverFrom + 1f)
            look.phase = MorphPhase.GATHER
            val inTotal = max(times.coveredAt, times.land.maxOrNull() ?: 0L) + 40L
            morph.start(MorphPhase.GATHER)
            var time = 0.0
            while (time < inTotal && !awake() && state.appVisible) {
                withFrameMillis { now ->
                    time += (now - last).coerceIn(0L, 50L)
                    last = now
                    morph.clock.longValue = time.toLong()
                }
            }
            // Appen lämnades: ingen väg tillbaka att visa.
            if (!state.appVisible) return@LaunchedEffect
            if (awake()) {
                // En knapp innan väggen hunnit täcka allt: allt går tillbaka samma
                // väg — omslagen flyger hem till sina kort och väggen tonar bort.
                while (time > 0.0 && state.appVisible) {
                    withFrameMillis { now ->
                        time -= (now - last).coerceIn(0L, 50L) * SAVER_EXIT_RATE
                        last = now
                        morph.clock.longValue = max(0L, time.toLong())
                    }
                }
                return@LaunchedEffect
            }
            wall.endGather()
            // Väggen täcker allt, tills någon trycker på en knapp.
            look.phase = MorphPhase.COVERED
            morph.cover()
            saver.covering = true
            while (!awake() && state.appVisible) delay(50)
            saver.covering = false
            if (!state.appVisible) return@LaunchedEffect
            // Ut: omslag flyger från väggen till sidans omslag.
            val back = targetsOf(morph.visibleSpots(), frame)
            val lands = wall.planMorph(back.map { it.toTarget(frame) })
            back.forEachIndexed { i, spot -> spot.landAt = lands[i] }
            val outTotal = max(WALL_FADE_TO_MS, (lands.maxOrNull() ?: 0L) + MORPH_DISSOLVE_MS) + 40L
            look.phase = MorphPhase.SCATTER
            morph.start(MorphPhase.SCATTER)
            time = 0.0
            last = withFrameMillis { it }
            while (time < outTotal) {
                withFrameMillis { now ->
                    time += (now - last).coerceIn(0L, 50L) * SAVER_EXIT_RATE
                    last = now
                    morph.clock.longValue = time.toLong()
                }
            }
        } finally {
            wall.endFlights()
            wall.stopSaver()
            morph.finish()
            saver.stop()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { frame.place(it) },
    ) {
        // Väggen i full styrka, med färgtonen och hörnen.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = look.wall(morph.clock.longValue)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .drawBehind {
                    if (look.wall(morph.clock.longValue) <= 0.002f) return@drawBehind
                    drawRect(INTRO_BLACK)
                    // Filmsiden: sidenbandet slingrar sig genom väggen också här.
                    wall.drawIntro(this, state.wallStyle.silk)
                    looks.drawWall(this)
                },
        )
        // Omslagen på väg, och biofälten överst. Eget lager: sidan under ritas inte om för varje bild.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawBehind {
                    val m = morph.clock.longValue
                    if (m >= 0L) wall.drawFlyers(this, m)
                    looks.drawBars(this, 1f - look.bars(m))
                },
        )
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
/** Korten tonas in under omslaget så här länge innan det landar, och ut så här länge när det lyfter. */
private const val CARD_LEAD_MS = 160L
private const val CARD_HIDE_MS = 200L
/** Skärmsläckaren: tillbaka går lite fortare än starten. */
private const val SAVER_EXIT_RATE = 1.3f
/** Längre än så väntar den inte, även om inget blivit klart. */
private const val MAX_SHOW_MS = 20_000L

package se.norrsken.tv.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import se.norrsken.tv.desktop.AppState
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Starten, som inledningen på en film på bio.
 *
 * Projektorn tänds: ljusstrålar uppifrån flimrar till och blir stadiga, och
 * i ljuset tonas filmväggen fram, från mitten och utåt, medan kameran sakta
 * glider bakåt. Sedan klipper väggen mellan filmerna i vågor, allt tätare,
 * som en filmtrailer — och vid slaget byter alla på en gång och allt
 * stannar: kameran studsar till och ljuset slår upp över hela väggen.
 * Samma film syns aldrig två gånger, och inget roteras.
 *
 * När startsidan är klar delar sig väggen på mitten och glider isär åt
 * sidorna, som ridån på bio, biofälten försvinner och man kör in i appen.
 * Väggen blir kvar bakom sidorna, mörk och dämpad, medan startsidans omslag
 * tonas fram. Ingen logga, ingen text, inget ljud. En knapptryckning eller
 * ett klick hoppar över.
 *
 * Väggen själv ([PosterWall]) delas med bakgrunden: här ritas den i full
 * styrka, med projektorn, färgton, mörkare hörn och biofält ovanpå.
 */
@Composable
internal fun StartIntro(state: AppState, wall: PosterWall, ready: Boolean, skip: Boolean, onFinished: () -> Unit) {
    // Millisekunder sedan starten. Läses bara i ritningen.
    val clock = remember { mutableLongStateOf(0L) }
    val exit = remember { Animatable(0f) }
    val finished = rememberUpdatedState(onFinished)
    val looks = remember { IntroLooks() }
    var clicked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { clock.longValue = it - start }
        }
    }
    val skipping = skip || clicked
    LaunchedEffect(ready, skipping) {
        if (!ready) return@LaunchedEffect
        // Spelas klart först, om man inte hoppar över.
        if (!skipping) {
            while (!mayEnd(wall, clock.longValue)) delay(40)
        }
        // Startsidan kommer fram bakom ridån, och dess omslag tonas fram.
        state.introRevealing = true
        val duration = when {
            !wall.lit -> EXIT_PLAIN_MS
            skipping -> EXIT_SKIP_MS
            else -> EXIT_MS
        }
        exit.animateTo(1f, tween(duration, easing = LinearEasing))
        finished.value()
    }
    LaunchedEffect(Unit) {
        delay(MAX_SHOW_MS)
        state.introRevealing = true
        exit.animateTo(1f, tween(EXIT_SKIP_MS, easing = LinearEasing))
        finished.value()
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Det sista av ridån tonar bort när den nästan glidit undan.
                alpha = 1f - smoothstep(if (wall.lit) FADE_FROM else 0f, 1f, exit.value)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            // Ett klick hoppar över, och går inte vidare till sidan bakom.
            .pointerInput(Unit) { detectTapGestures { clicked = true } },
    ) {
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val t = clock.longValue
                    val e = exit.value
                    val open = if (wall.lit) easeInOutCubic((e / CURTAIN_OPEN).coerceIn(0f, 1f)) else 0f
                    if (open <= 0f) {
                        drawRect(INTRO_BLACK)
                        wall.drawIntro(this)
                    } else {
                        curtain(left = true, open = open) { wall.drawIntro(this) }
                        curtain(left = false, open = open) { wall.drawIntro(this) }
                    }
                    if (!wall.lit) drawGlow(t)
                    looks.draw(this, t, e, open)
                },
        )
    }
}

/** Tidigast när starten får gå över i startsidan. */
private fun mayEnd(wall: PosterWall, elapsed: Long): Boolean = when {
    wall.lit -> wall.time >= WALL_MIN_MS
    // Omslagen laddas: väggen tänds strax.
    wall.lightingSoon(nowMs()) -> false
    else -> elapsed >= AMBIENT_MIN_MS
}

/**
 * Ridån: väggens vänstra eller högra halva, som glider undan åt sitt håll
 * medan kameran kör fram mot öppningen. Inget vrids — halvorna bara glider,
 * och mörknar lite på vägen ut.
 */
private inline fun DrawScope.curtain(left: Boolean, open: Float, content: DrawScope.() -> Unit) {
    val w = size.width
    val hh = size.height
    val half = w / 2f
    val shift = (if (left) -1f else 1f) * half * CURTAIN_TRAVEL * open
    val drive = 1f + CURTAIN_DRIVE * open * open
    scale(drive, drive, pivot = Offset(half, hh / 2f)) {
        translate(left = shift) {
            clipRect(if (left) 0f else half, 0f, if (left) half else w, hh) {
                drawRect(INTRO_BLACK)
                content()
                drawRect(Color.Black, alpha = CURTAIN_SHADE * open)
            }
        }
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
 * Det som ligger ovanpå väggen i starten: projektorns strålar, färgtonen,
 * mörkare hörn och biofälten (bilden i 2,39:1).
 */
private class IntroLooks {
    private var width = -1f
    private var height = -1f
    private var grade: Brush? = null
    private var vignette: Brush? = null
    private val rays = Array(RAYS) { Path() }

    fun draw(scope: DrawScope, t: Long, e: Float, open: Float) {
        with(scope) { drawLooks(t, e, open) }
    }

    private fun DrawScope.drawLooks(t: Long, e: Float, open: Float) {
        if (size.width != width || size.height != height) {
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
                0.55f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.55f),
                center = Offset(width / 2f, height / 2f),
                radius = max(width, height) * 0.72f,
            )
        }
        val keep = 1f - open
        drawRays(t, keep)
        // Färgton och hörn hör till väggen: de går bort när ridån öppnar sig.
        grade?.let { drawRect(it, alpha = keep) }
        vignette?.let { drawRect(it, alpha = keep) }
        val full = ((height - width / LETTERBOX) / 2f).coerceAtLeast(0f)
        val bar = full * (1f - smoothstep(BARS_OPEN_FROM, BARS_OPEN_TO, e))
        if (bar > 0.5f) {
            drawRect(INTRO_BLACK, topLeft = Offset.Zero, size = Size(width, bar))
            drawRect(INTRO_BLACK, topLeft = Offset(0f, height - bar), size = Size(width, bar))
        }
    }

    /**
     * Projektorn: strålar som solfjäder uppifrån, ovanför bilden. Lampan
     * flimrar till ett par gånger när den tänds, lyser stadigt medan väggen
     * tonas fram, och bleknar sedan bort.
     */
    private fun DrawScope.drawRays(t: Long, strength: Float) {
        val level = projector(t) * strength
        if (level <= 0.003f) return
        val sourceX = width / 2f
        val sourceY = -0.42f * height
        val length = height * 1.75f
        val middle = (RAYS - 1) / 2f
        for (i in 0 until RAYS) {
            val spread = (i - middle) / middle
            val angle = (spread * 27f + 1.3f * sin(t / 2900f + i * 1.7f)) * DEG
            val dirX = sin(angle)
            val dirY = cos(angle)
            val narrow = width * 0.004f
            val wide = width * (0.045f + 0.03f * rayWidth(i))
            val endX = sourceX + dirX * length
            val endY = sourceY + dirY * length
            val path = rays[i]
            path.reset()
            path.moveTo(sourceX + dirY * narrow, sourceY - dirX * narrow)
            path.lineTo(endX + dirY * wide, endY - dirX * wide)
            path.lineTo(endX - dirY * wide, endY + dirX * wide)
            path.lineTo(sourceX - dirY * narrow, sourceY + dirX * narrow)
            path.close()
            val alpha = level * (0.035f + 0.03f * rayWidth(i + 7))
            drawPath(
                path,
                Brush.linearGradient(
                    0f to BEAM.copy(alpha = alpha),
                    0.55f to BEAM.copy(alpha = alpha * 0.45f),
                    1f to Color.Transparent,
                    start = Offset(sourceX, sourceY),
                    end = Offset(endX, endY),
                ),
            )
        }
    }
}

/** Lampan: två korta blink när den tänds, sedan stadigt, och till sist bleknar den. */
private fun projector(t: Long): Float {
    val lamp = when {
        t < 180L -> 0f
        t < 260L -> 0.55f
        t < 330L -> 0.08f
        t < 380L -> 0.8f
        t < 470L -> 0.22f
        else -> 0.45f + 0.55f * smoothstep(470f, 950f, t.toFloat())
    }
    return lamp * (1f - smoothstep(2_300f, 3_800f, t.toFloat()))
}

/** Strålarnas bredd och styrka varierar: fast slumptal 0–1 för [i]. */
private fun rayWidth(i: Int): Float {
    var x = i * 1_103_515_245 + 12_345
    x = x xor (x ushr 15)
    return (x and 0xFFFF) / 65_535f
}

private val INTRO_BLACK = Color(0xFF020306)
private val WARM = Color(0xFFFFC27A)
private val COLD = Color(0xFF3E8BFF)
private val BEAM = Color(0xFFFFF0D8)

/** Projektorns strålar. */
private const val RAYS = 7

/** Slutet, i förloppet 0–1: biofälten och ridån öppnar sig, och till sist tonar det sista bort. */
private const val BARS_OPEN_FROM = 0f
private const val BARS_OPEN_TO = 0.65f
private const val CURTAIN_OPEN = 0.9f
private const val FADE_FROM = 0.8f
/** Ridån: hur långt halvorna glider (halva skärmar), hur långt kameran kör fram, och skuggan. */
private const val CURTAIN_TRAVEL = 1.05f
private const val CURTAIN_DRIVE = 0.14f
private const val CURTAIN_SHADE = 0.45f

/** Hur länge: minst så länge väggen visas (efter slaget), minst utan vägg, och slutens längd. */
private const val WALL_MIN_MS = SLAM_MS + 900L
private const val AMBIENT_MIN_MS = 900L
private const val EXIT_MS = 1_600
private const val EXIT_SKIP_MS = 1_000
private const val EXIT_PLAIN_MS = 500
/** Längre än så väntar den inte, även om inget blivit klart. */
private const val MAX_SHOW_MS = 20_000L

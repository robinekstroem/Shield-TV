package se.norrsken.tv.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import se.norrsken.tv.desktop.AppState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Starten, som inledningen på en film: filmväggen tänds i mörkret. Omslagen
 * vänds fram i en våg från mitten medan kameran sakta glider bakåt, raderna
 * driver åt olika håll och ett ljus vandrar över väggen; då och då vänds ett
 * omslag och en annan film kommer fram. Samma film syns aldrig två gånger.
 *
 * När startsidan är klar öppnar sig biofälten, väggen dämpas och blir kvar
 * som appens bakgrund, och startsidan tonar fram framför den medan dess
 * omslag vänds fram. Ingen logga, ingen text, inget ljud. En knapptryckning
 * eller ett klick hoppar över.
 *
 * Väggen själv ([PosterWall]) delas med bakgrunden: här ritas den i full
 * styrka, med biofält, färgton och mörkare hörn ovanpå.
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
        // Startsidan tonar fram och dess omslag vänds fram, medan väggen dämpas.
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
                // Väggen här tonar över i den dämpade väggen bakom startsidan.
                alpha = 1f - smoothstep(if (wall.lit) FADE_FROM else 0f, 1f, exit.value)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .background(INTRO_BLACK)
            // Ett klick hoppar över, och går inte vidare till sidan bakom.
            .pointerInput(Unit) { detectTapGestures { clicked = true } },
    ) {
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val t = clock.longValue
                    val e = exit.value
                    wall.drawIntro(this)
                    if (!wall.lit) drawGlow(t)
                    looks.draw(this, e)
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

/** Innan väggen tänts: ett svagt sken i mörkret. */
private fun DrawScope.drawGlow(t: Long) {
    val shown = smoothstep(150f, 1400f, t.toFloat())
    if (shown <= 0f) return
    val breath = 0.8f + 0.2f * sin(t / 2600f * 6.2831855f)
    drawRect(
        Brush.radialGradient(
            0f to COLD.copy(alpha = 0.07f * shown * breath),
            1f to Color.Transparent,
            center = Offset(size.width / 2f, size.height / 2f),
            radius = min(size.width, size.height) * 0.7f,
        ),
    )
}

/** Färgtonen, mörkare hörn och biofälten (bilden i 2,39:1), som öppnar sig i slutet. */
private class IntroLooks {
    private var width = -1f
    private var height = -1f
    private var grade: Brush? = null
    private var vignette: Brush? = null

    fun draw(scope: DrawScope, e: Float) {
        with(scope) { drawLooks(e) }
    }

    private fun DrawScope.drawLooks(e: Float) {
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
        grade?.let { drawRect(it) }
        vignette?.let { drawRect(it) }
        val full = ((height - width / LETTERBOX) / 2f).coerceAtLeast(0f)
        val bar = full * (1f - smoothstep(BARS_OPEN_FROM, BARS_OPEN_TO, e))
        if (bar > 0.5f) {
            drawRect(INTRO_BLACK, topLeft = Offset.Zero, size = Size(width, bar))
            drawRect(INTRO_BLACK, topLeft = Offset(0f, height - bar), size = Size(width, bar))
        }
    }
}

private val INTRO_BLACK = Color(0xFF020306)
private val WARM = Color(0xFFFFC27A)
private val COLD = Color(0xFF3E8BFF)

/** Slutet, i förloppet 0–1: biofälten öppnar sig, och väggen här tonar över i bakgrunden. */
private const val BARS_OPEN_FROM = 0f
private const val BARS_OPEN_TO = 0.6f
private const val FADE_FROM = 0.1f

/** Hur länge: minst så länge väggen visas, minst utan vägg, och slutens längd. */
private const val WALL_MIN_MS = 4_200L
private const val AMBIENT_MIN_MS = 900L
private const val EXIT_MS = 1_600
private const val EXIT_SKIP_MS = 1_000
private const val EXIT_PLAIN_MS = 500
/** Längre än så väntar den inte, även om inget blivit klart. */
private const val MAX_SHOW_MS = 20_000L

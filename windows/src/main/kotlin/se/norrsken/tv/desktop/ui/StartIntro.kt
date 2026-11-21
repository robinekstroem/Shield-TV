package se.norrsken.tv.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Starten: tv:n slås på. En ljus linje drar ut över den svarta skärmen och
 * öppnar sig som på en gammal bildrörs-tv, symbolen studsar fram med ett
 * ljussvep över sig, ringar går ut från den som en signal, och namnet glider
 * upp. Under tiden laddas biblioteket och startsidan ordnas; när den står
 * klar tonar allt bort och startsidan finns där bakom.
 *
 * Allt som rör sig läses i ritningen eller i lagren: inget mäts om och
 * inget sätts ihop på nytt medan det spelas, så att det går jämnt på Shield
 * också medan appen laddar som mest.
 *
 * [ready]: startsidan (eller det som visas i stället) är klar. Animationen
 * visas ändå en kort stund, så att den hinner spelas, och tonar bort efter
 * [MAX_SHOW_MS] även om inget blivit klart — då syns det som hänger.
 */
@Composable
internal fun StartIntro(ready: Boolean, onFinished: () -> Unit) {
    val palette = LocalPalette.current
    val accent = menuFill(palette)
    // Millisekunder sedan starten. Läses bara i ritningen och i lagren.
    val clock = remember { mutableLongStateOf(0L) }
    val logo = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    val finished = rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        launch {
            delay(LOGO_AT_MS)
            // Studsar lite förbi sin storlek och tillbaka.
            logo.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 240f))
        }
        launch {
            delay(WORDS_AT_MS)
            words.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
        }
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { clock.longValue = it - start }
        }
    }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        val left = MIN_SHOW_MS - clock.longValue
        if (left > 0) delay(left)
        exit.animateTo(1f, tween(EXIT_MS, easing = FastOutSlowInEasing))
        finished.value()
    }
    LaunchedEffect(Unit) {
        delay(MAX_SHOW_MS)
        exit.animateTo(1f, tween(EXIT_MS))
        finished.value()
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Hela bilden tonar bort mot startsidan bakom, utan ett extra lager.
                alpha = 1f - exit.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .background(palette.background),
        contentAlignment = Alignment.Center,
    ) {
        // Ett sken bakom symbolen som andas långsamt.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind { drawBreathingGlow(clock.longValue, logo.value, accent) },
        )
        // Bildrörets svaga linjer och mörkare hörn. Står still: ritas en gång.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawBehind { drawScanlines() },
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // Signalringarna, större än sin plats: de får gå ut över skärmen.
                Spacer(
                    Modifier
                        .requiredSize(640.dp)
                        .drawBehind { drawRings(clock.longValue, accent) },
                )
                Icon(
                    Glyphs.AppSymbol,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier
                        .size(136.dp)
                        .graphicsLayer {
                            val p = logo.value
                            val zoom = (0.3f + 0.7f * p) * (1f + 0.16f * exit.value)
                            scaleX = zoom
                            scaleY = zoom
                            alpha = (p * 1.8f).coerceIn(0f, 1f)
                            // Eget lager, så att ljussvepet bara lägger sig på symbolen.
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            drawSweep(clock.longValue)
                        },
                )
            }
            Spacer(Modifier.height(26.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    val p = words.value
                    alpha = p
                    translationY = (1f - p) * 28.dp.toPx()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            ) {
                Text(
                    "Tv",
                    style = TextStyle(
                        fontWeight = FontWeight.Black,
                        fontSize = 66.sp,
                        lineHeight = 70.sp,
                        letterSpacing = (-1.5).sp,
                    ),
                    color = palette.textPrimary,
                    maxLines = 1,
                )
                Text(
                    "FÖR ${platformName.uppercase()}",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 5.sp),
                    color = palette.textTertiary,
                    maxLines = 1,
                )
            }
            // Tar laddningen en stund: ett ljus som löper längs en tunn linje.
            Spacer(Modifier.height(34.dp))
            Spacer(
                Modifier
                    .size(width = 190.dp, height = 3.dp)
                    .drawBehind { drawComet(clock.longValue, accent) },
            )
        }
        // Tv:n slås på, överst: linjen och blixten när bilden öppnar sig.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind { drawPowerOn(clock.longValue) },
        )
    }
}

/** Linjen som drar ut från mitten och öppnar sig uppåt och nedåt, som när ett bildrör tänds. */
private fun DrawScope.drawPowerOn(t: Long) {
    if (t >= POWER_END_MS) return
    val grow = easeOut((t / POWER_LINE_MS.toFloat()).coerceIn(0f, 1f))
    val open = easeInOut(((t - POWER_OPEN_AT_MS) / POWER_OPEN_MS.toFloat()).coerceIn(0f, 1f))
    val minHeight = 2.dp.toPx()
    val width = size.width * grow
    val height = minHeight + (size.height - minHeight) * open
    val fade = (1f - open) * (1f - open)
    val left = (size.width - width) / 2
    val top = (size.height - height) / 2
    val halo = 46.dp.toPx()
    // Ljuset kring linjen.
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, POWER_LIGHT.copy(alpha = 0.40f * fade), Color.Transparent),
            startY = top - halo,
            endY = top + height + halo,
        ),
        topLeft = Offset(left, top - halo),
        size = Size(width, height + halo * 2),
    )
    drawRect(POWER_LIGHT.copy(alpha = 0.62f * fade), topLeft = Offset(left, top), size = Size(width, height))
    // Linjens kärna: vit och skarp tills bilden öppnat sig.
    if (open < 0.5f) {
        drawRect(
            Color.White.copy(alpha = 1f - open * 2f),
            topLeft = Offset(left, size.height / 2 - minHeight / 2),
            size = Size(width, minHeight),
        )
    }
}

/** Ringar som går ut från symbolen, en i taget, och tonar bort: en signal som sänds. */
private fun DrawScope.drawRings(t: Long, color: Color) {
    if (t < RINGS_AT_MS) return
    val inner = 86.dp.toPx()
    val outer = size.minDimension / 2
    for (ring in 0 until RINGS) {
        val local = t - RINGS_AT_MS - ring * RING_GAP_MS
        if (local < 0) continue
        val phase = (local % RING_PERIOD_MS) / RING_PERIOD_MS.toFloat()
        val eased = easeOut(phase)
        val radius = inner + (outer - inner) * eased
        val strength = (1f - phase) * (1f - phase)
        drawCircle(
            color.copy(alpha = 0.55f * strength),
            radius = radius,
            center = center,
            style = Stroke(width = (1f + 2.5f * (1f - phase)).dp.toPx()),
        )
    }
}

/** Skenet bakom symbolen: tänds med den och andas sedan långsamt. */
private fun DrawScope.drawBreathingGlow(t: Long, logo: Float, color: Color) {
    val shown = logo.coerceIn(0f, 1f)
    if (shown <= 0f) return
    val breath = 0.78f + 0.22f * sin(t / GLOW_PERIOD_MS.toFloat() * 2f * PI.toFloat())
    drawRect(
        Brush.radialGradient(
            listOf(
                color.copy(alpha = 0.26f * shown * breath),
                Brand.violet.copy(alpha = 0.08f * shown * breath),
                Color.Transparent,
            ),
            center = Offset(size.width / 2, size.height * 0.45f),
            radius = size.minDimension * 0.62f,
        ),
    )
}

/** Ett ljus som sveper snett över symbolen, en gång, strax efter att den kommit fram. */
private fun DrawScope.drawSweep(t: Long) {
    val local = t - SWEEP_AT_MS
    if (local < 0 || local > SWEEP_MS) return
    val f = local / SWEEP_MS.toFloat()
    val band = size.width * 0.55f
    val x = -band + (size.width + band * 2) * f
    drawRect(
        Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.85f), Color.Transparent),
            start = Offset(x - band / 2, 0f),
            end = Offset(x + band / 2, size.height),
        ),
        blendMode = BlendMode.SrcAtop,
    )
}

/** Laddningen: ett ljus som löper längs en tunn linje. Syns först om starten drar ut på tiden. */
private fun DrawScope.drawComet(t: Long, color: Color) {
    if (t < COMET_AT_MS) return
    val fade = ((t - COMET_AT_MS) / 500f).coerceIn(0f, 1f)
    drawRoundRect(Color.White.copy(alpha = 0.10f * fade), cornerRadius = CornerRadius(size.height / 2))
    val phase = ((t - COMET_AT_MS) % COMET_PERIOD_MS) / COMET_PERIOD_MS.toFloat()
    val length = size.width * 0.38f
    val x = -length + (size.width + length) * easeInOut(phase)
    clipRect {
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, color.copy(alpha = fade), Color.White.copy(alpha = fade)),
                startX = x,
                endX = x + length,
            ),
            topLeft = Offset(x, 0f),
            size = Size(length, size.height),
        )
    }
}

/** Bildrörets linjer, mycket svaga, och mörkare hörn. */
private fun DrawScope.drawScanlines() {
    val gap = 4.dp.toPx()
    val line = Color.White.copy(alpha = 0.022f)
    var y = 0f
    while (y < size.height) {
        drawRect(line, topLeft = Offset(0f, y), size = Size(size.width, 1f))
        y += gap
    }
    drawRect(
        Brush.radialGradient(
            0f to Color.Transparent,
            0.55f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.55f),
            center = center,
            radius = size.maxDimension * 0.72f,
        ),
    )
}

private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

private fun easeInOut(x: Float): Float = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f

private val POWER_LIGHT = Color(0xFFD6ECFF)

/** Linjen drar ut, och bilden öppnar sig strax innan den nått kanterna. */
private const val POWER_LINE_MS = 260
private const val POWER_OPEN_AT_MS = 200L
private const val POWER_OPEN_MS = 320
private const val POWER_END_MS = 540L
private const val LOGO_AT_MS = 360L
private const val WORDS_AT_MS = 640L
private const val RINGS_AT_MS = 620L
private const val RINGS = 3
private const val RING_PERIOD_MS = 1800L
private const val RING_GAP_MS = 600L
private const val SWEEP_AT_MS = 980L
private const val SWEEP_MS = 640L
private const val GLOW_PERIOD_MS = 2600L
private const val COMET_AT_MS = 1500L
private const val COMET_PERIOD_MS = 1300L

/** Så länge animationen visas minst: tills symbolen och namnet hunnit fram. */
private const val MIN_SHOW_MS = 1500L
private const val EXIT_MS = 420
/** Längre än så väntar den inte, även om inget blivit klart. */
private const val MAX_SHOW_MS = 20_000L

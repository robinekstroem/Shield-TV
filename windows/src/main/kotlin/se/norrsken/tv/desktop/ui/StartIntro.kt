package se.norrsken.tv.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Starten, som inledningen på en film. Svart duk inom biofält. Strålkastare
 * tänds nedifrån och sveper långsamt över himlen medan stoft svävar i
 * ljuset. Symbolen slår in mot kameran med ett blått, liggande linsljus
 * genom bilden, ett ljussvep går över den, och "Tv" drar ihop sig ur två
 * håll. När startsidan är klar öppnar sig biofälten, kameran åker genom
 * symbolen och allt tonar bort mot startsidan bakom.
 *
 * Allt som rör sig läses i ritningen eller i lagren: inget mäts om och
 * inget sätts ihop på nytt medan det spelas, så att det går jämnt på Shield
 * också medan appen laddar som mest. Ingen oskärpa, inga extra lager utöver
 * symbolens.
 *
 * [ready]: startsidan (eller det som visas i stället) är klar. Animationen
 * spelas ändå klart först, och tonar bort efter [MAX_SHOW_MS] även om inget
 * blivit klart — då syns det som hänger.
 */
@Composable
internal fun StartIntro(ready: Boolean, onFinished: () -> Unit) {
    val palette = LocalPalette.current
    val accent = menuFill(palette)
    // Millisekunder sedan starten. Läses bara i ritningen och i lagren.
    val clock = remember { mutableLongStateOf(0L) }
    val exit = remember { Animatable(0f) }
    // Var symbolens mitt står på skärmen: linsljuset går genom den.
    val logoY = remember { mutableFloatStateOf(-1f) }
    val finished = rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
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
                // Allt tonar bort mot startsidan bakom, utan ett extra lager.
                alpha = (1f - exit.value * 1.15f).coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .background(INTRO_BLACK),
        contentAlignment = Alignment.Center,
    ) {
        // Strålkastarna och stoftet i ljuset.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val t = clock.longValue
                    drawSearchlights(t)
                    drawDust(t)
                },
        )
        // Ett sken bakom symbolen som andas.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind { drawGlow(clock.longValue, logoY.floatValue, accent) },
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Glyphs.AppSymbol,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .size(148.dp)
                    .onGloballyPositioned { logoY.floatValue = it.boundsInRoot().center.y }
                    .graphicsLayer {
                        val t = clock.longValue
                        val p = easeOutExpo(progress(t, LOGO_AT_MS, LOGO_MS))
                        // Slår in mot kameran, andas sedan nästan omärkligt; vid slutet åker kameran igenom.
                        val breath = 1f + 0.012f * sin(t / 1400f * 2f * PI.toFloat()) * p
                        val zoom = (1.9f - 0.9f * p) * breath * (1f + 0.6f * exit.value * exit.value)
                        scaleX = zoom
                        scaleY = zoom
                        alpha = (p * 1.6f).coerceIn(0f, 1f)
                        // Eget lager, så att ljussvepet bara lägger sig på symbolen.
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawSweep(clock.longValue)
                    },
            )
            Spacer(Modifier.height(30.dp))
            // "Tv": bokstäverna drar ihop sig från var sitt håll.
            Row(
                Modifier.graphicsLayer {
                    val zoom = 1f + 0.25f * exit.value
                    scaleX = zoom
                    scaleY = zoom
                },
            ) {
                val title = TextStyle(fontWeight = FontWeight.Black, fontSize = 72.sp, lineHeight = 76.sp)
                Text(
                    "T",
                    style = title,
                    color = palette.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer {
                        val p = easeOutCubic(progress(clock.longValue, LETTERS_AT_MS, LETTERS_MS))
                        translationX = -(1f - p) * LETTER_SPREAD.toPx()
                        alpha = p
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                )
                Text(
                    "v",
                    style = title,
                    color = palette.textPrimary,
                    maxLines = 1,
                    modifier = Modifier
                        .offset(x = (-3).dp)
                        .graphicsLayer {
                            val p = easeOutCubic(progress(clock.longValue, LETTERS_AT_MS, LETTERS_MS))
                            translationX = (1f - p) * LETTER_SPREAD.toPx()
                            alpha = p
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                )
            }
            Text(
                "FÖR ${platformName.uppercase()}",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 7.sp),
                color = palette.textTertiary,
                maxLines = 1,
                modifier = Modifier.graphicsLayer {
                    val p = progress(clock.longValue, SUBTITLE_AT_MS, SUBTITLE_MS)
                    alpha = p
                    translationY = (1f - easeOutCubic(p)) * 10.dp.toPx()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            )
        }
        // Linsljuset: ett blått, liggande streck genom symbolen.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind { drawFlare(clock.longValue, exit.value, logoY.floatValue) },
        )
        // Biofälten, mörka hörn, och laddningen i det nedre fältet om starten drar ut på tiden.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawVignette()
                    drawLetterbox(exit.value)
                    drawLoader(clock.longValue, accent, exit.value)
                },
        )
    }
}

// --- Det som ritas -------------------------------------------------------------------

/** Strålkastare nedanför bilden som sveper fram och tillbaka, var och en i sin takt. */
private fun DrawScope.drawSearchlights(t: Long) {
    val shown = easeOutCubic(progress(t, LIGHTS_AT_MS, LIGHTS_MS))
    if (shown <= 0f) return
    val w = size.width
    val h = size.height
    val length = h * 1.5f
    val half = length * 0.075f
    val seconds = t / 1000f
    for (beam in 0 until BEAMS) {
        val pivot = Offset(w * (0.14f + 0.24f * beam), h + 30.dp.toPx())
        val period = 6.2f + beam * 1.3f
        val sway = sin((seconds / period + beam * 0.27f) * 2f * PI.toFloat())
        // Ytterst lutar utåt, innerst nästan rakt upp.
        val lean = (beam - (BEAMS - 1) / 2f) * 9f
        val angle = lean + sway * 15f
        val path = Path().apply {
            moveTo(pivot.x, pivot.y)
            lineTo(pivot.x - half, pivot.y - length)
            lineTo(pivot.x + half, pivot.y - length)
            close()
        }
        rotate(angle, pivot) {
            drawPath(
                path,
                Brush.verticalGradient(
                    listOf(Color.Transparent, BEAM.copy(alpha = 0.07f * shown), BEAM.copy(alpha = 0.22f * shown)),
                    startY = pivot.y - length,
                    endY = pivot.y,
                ),
            )
        }
    }
}

/** Stoft som svävar uppåt i ljuset och glimtar. */
private fun DrawScope.drawDust(t: Long) {
    val shown = progress(t, DUST_AT_MS, 1200)
    if (shown <= 0f) return
    val w = size.width
    val h = size.height
    val seconds = t / 1000f
    val unit = 1.dp.toPx()
    for (i in 0 until DUST) {
        val speed = (6f + 18f * hash(i, 1)) * unit
        val y = h - ((seconds * speed + hash(i, 2) * h) % (h + 40f * unit)) + 20f * unit
        val x = w * hash(i, 3) + sin(seconds * (0.4f + hash(i, 4)) + i) * 14f * unit
        val twinkle = 0.55f + 0.45f * sin(seconds * (1.2f + 2f * hash(i, 5)) + i * 1.7f)
        val alpha = (0.06f + 0.30f * hash(i, 6)) * twinkle * shown
        drawCircle(DUST_COLOR.copy(alpha = alpha), radius = (0.7f + 1.6f * hash(i, 7)) * unit, center = Offset(x, y))
    }
}

/** Skenet bakom symbolen: tänds med den och andas sedan långsamt. */
private fun DrawScope.drawGlow(t: Long, logoY: Float, color: Color) {
    val shown = progress(t, LOGO_AT_MS, LOGO_MS)
    if (shown <= 0f) return
    val breath = 0.8f + 0.2f * sin(t / 2600f * 2f * PI.toFloat())
    val center = Offset(size.width / 2, if (logoY > 0f) logoY else size.height * 0.42f)
    drawRect(
        Brush.radialGradient(
            listOf(color.copy(alpha = 0.30f * shown * breath), Brand.violet.copy(alpha = 0.07f * shown * breath), Color.Transparent),
            center = center,
            radius = size.minDimension * 0.55f,
        ),
    )
}

/** Ljussvepet över symbolen, en gång, strax efter att den slagit in. */
private fun DrawScope.drawSweep(t: Long) {
    val local = t - SWEEP_AT_MS
    if (local < 0 || local > SWEEP_MS) return
    val f = local / SWEEP_MS.toFloat()
    val band = size.width * 0.55f
    val x = -band + (size.width + band * 2) * easeInOutCubic(f)
    drawRect(
        Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.9f), Color.Transparent),
            start = Offset(x - band / 2, 0f),
            end = Offset(x + band / 2, size.height),
        ),
        blendMode = BlendMode.SrcAtop,
    )
}

/**
 * Det liggande linsljuset genom symbolen: blixtrar till när den slår in, och
 * en gång till när kameran åker igenom vid slutet. Ett skarpt vitt streck
 * med ett blått sken omkring, som tonar ut mot kanterna.
 */
private fun DrawScope.drawFlare(t: Long, exit: Float, logoY: Float) {
    val x = progress(t, FLARE_AT_MS, FLARE_MS)
    val reveal = when {
        x <= 0f || x >= 1f -> 0f
        x < 0.12f -> x / 0.12f
        else -> ((1f - x) / 0.88f).pow(1.6f)
    }
    val strength = maxOf(reveal, sin(exit * PI.toFloat()) * 0.8f)
    if (strength <= 0.01f) return
    val y = if (logoY > 0f) logoY else size.height * 0.42f
    val w = size.width
    val halo = 40.dp.toPx()
    // Skenet: brett och blått, tonar ut mot sidorna och uppåt och nedåt.
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, FLARE_BLUE.copy(alpha = 0.32f * strength), Color.Transparent),
            startY = y - halo,
            endY = y + halo,
        ),
        topLeft = Offset(0f, y - halo),
        size = Size(w, halo * 2),
    )
    // Strecket: vitt i mitten, blått ut mot kanterna.
    val core = 2.dp.toPx()
    drawRect(
        Brush.horizontalGradient(
            0f to Color.Transparent,
            0.25f to FLARE_BLUE.copy(alpha = 0.5f * strength),
            0.5f to Color.White.copy(alpha = strength),
            0.75f to FLARE_BLUE.copy(alpha = 0.5f * strength),
            1f to Color.Transparent,
        ),
        topLeft = Offset(0f, y - core / 2),
        size = Size(w, core),
    )
}

/** Mörkare hörn, som i en biosalong. */
private fun DrawScope.drawVignette() {
    drawRect(
        Brush.radialGradient(
            0f to Color.Transparent,
            0.55f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.65f),
            center = center,
            radius = size.maxDimension * 0.7f,
        ),
    )
}

/** Biofälten upptill och nedtill (bilden i 2,39:1); de öppnar sig när det är slut. */
private fun DrawScope.drawLetterbox(exit: Float) {
    val bar = ((size.height - size.width / 2.39f) / 2f).coerceAtLeast(0f) * (1f - easeInCubic(exit))
    if (bar <= 0.5f) return
    drawRect(INTRO_BLACK, topLeft = Offset.Zero, size = Size(size.width, bar))
    drawRect(INTRO_BLACK, topLeft = Offset(0f, size.height - bar), size = Size(size.width, bar))
}

/** Laddningen, om starten drar ut på tiden: ett ljus som löper längs en tunn linje i det nedre fältet. */
private fun DrawScope.drawLoader(t: Long, color: Color, exit: Float) {
    if (t < LOADER_AT_MS) return
    val fade = progress(t, LOADER_AT_MS, 600) * (1f - exit)
    if (fade <= 0f) return
    val width = 200.dp.toPx()
    val height = 2.dp.toPx()
    val bar = ((size.height - size.width / 2.39f) / 2f).coerceAtLeast(48.dp.toPx())
    val left = (size.width - width) / 2
    val top = size.height - bar / 2 - height / 2
    drawRoundRect(
        Color.White.copy(alpha = 0.10f * fade),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2),
    )
    val phase = ((t - LOADER_AT_MS) % LOADER_PERIOD_MS) / LOADER_PERIOD_MS.toFloat()
    val length = width * 0.36f
    val x = left - length + (width + length) * easeInOutCubic(phase)
    clipRect(left, top, left + width, top + height) {
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, color.copy(alpha = fade), Color.White.copy(alpha = fade)),
                startX = x,
                endX = x + length,
            ),
            topLeft = Offset(x, top),
            size = Size(length, height),
        )
    }
}

// --- Tid och kurvor ------------------------------------------------------------------

/** 0–1 för hur långt något som börjar vid [at] och tar [duration] ms kommit vid [t]. */
private fun progress(t: Long, at: Long, duration: Int): Float = ((t - at) / duration.toFloat()).coerceIn(0f, 1f)

private fun easeOutCubic(x: Float): Float = 1f - (1f - x).pow(3)

private fun easeInCubic(x: Float): Float = x * x * x

private fun easeInOutCubic(x: Float): Float = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f

private fun easeOutExpo(x: Float): Float = if (x >= 1f) 1f else 1f - 2f.pow(-10f * x)

/** Ett fast slumptal 0–1 för stoftkorn [i], egenskap [k]: samma varje bild, utan att något skapas. */
private fun hash(i: Int, k: Int): Float {
    var x = i * 374_761_393 + k * 668_265_263
    x = (x xor (x ushr 13)) * 1_274_126_177
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16_777_215f
}

private val INTRO_BLACK = Color(0xFF020306)
private val BEAM = Color(0xFFCFE4FF)
private val DUST_COLOR = Color(0xFFE6F0FF)
private val FLARE_BLUE = Color(0xFF5EA8FF)
private val LETTER_SPREAD = 64.dp

private const val BEAMS = 4
private const val DUST = 64
private const val DUST_AT_MS = 150L
private const val LIGHTS_AT_MS = 180L
private const val LIGHTS_MS = 900
private const val LOGO_AT_MS = 520L
private const val LOGO_MS = 1000
private const val FLARE_AT_MS = 560L
private const val FLARE_MS = 1300
private const val LETTERS_AT_MS = 1000L
private const val LETTERS_MS = 900
private const val SUBTITLE_AT_MS = 1600L
private const val SUBTITLE_MS = 600
private const val SWEEP_AT_MS = 1550L
private const val SWEEP_MS = 700
private const val LOADER_AT_MS = 2900L
private const val LOADER_PERIOD_MS = 1300L

/** Så länge animationen visas minst: tills symbolen, namnet och ljussvepet hunnit fram. */
private const val MIN_SHOW_MS = 2_350L
private const val EXIT_MS = 560
/** Längre än så väntar den inte, även om inget blivit klart. */
private const val MAX_SHOW_MS = 20_000L

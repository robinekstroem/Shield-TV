package se.norrsken.tv.desktop.ui

import kotlin.time.TimeSource
import kotlin.math.min
import kotlin.math.abs
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Temats bakgrund, bakom innehållet: vinternatt, stjärnhimmel, serieraster,
 * dimma, norrsken eller neonhorisont. Syns där skärmarna är genomskinliga.
 */
@Composable
fun ThemeBackdrop(decoration: Decoration, running: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    when (decoration) {
        Decoration.NONE -> Unit
        Decoration.SNOW -> WinterNight(running, modifier)
        Decoration.SPARKLES -> Starfield(palette.textPrimary, modifier)
        Decoration.HALFTONE -> Halftone(palette.surfaceRaised, palette.focus, modifier)
        Decoration.MIST -> Mist(running, palette.secondary, modifier)
        Decoration.AURORA -> AuroraSky(running, modifier)
        Decoration.RETRO -> RetroHorizon(running, modifier)
        Decoration.CRYSTAL -> CrystalLight(running, modifier)
    }
}

/**
 * Det som rör sig ovanpå allt: snö och ljusslinga, stjärnstoft, VHS-svep.
 * Ritas bara, tar aldrig emot klick — allt under fungerar som vanligt.
 */
@Composable
fun ThemeOverlay(decoration: Decoration, running: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    when (decoration) {
        Decoration.SNOW -> {
            Snowfall(running, modifier)
            FairyLights(running, Modifier.fillMaxWidth().height(24.dp))
        }
        Decoration.SPARKLES -> Stardust(running, palette.signal, modifier)
        Decoration.RETRO -> Scanlines(running, modifier)
        else -> Unit
    }
}

/** Med egna bakgrundsbilder blir temats himmel genomskinlig, så att bilderna syns. */
@Composable
private fun skyVeil(): Float = if (LocalPalette.current.customBackdrop) 0.35f else 1f

/**
 * Sekunder sedan animationen började, uppdaterad [fps] gånger i sekunden
 * medan [running]. Läses bara i ritsteget, så bara själva ritningen görs om.
 */
@Composable
internal fun rememberSeconds(running: Boolean, fps: Int): MutableFloatState {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, fps) {
        if (!running) return@LaunchedEffect
        val offset = seconds.floatValue
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            if (fps >= 60) withFrameNanos { } else delay(1000L / fps)
            seconds.floatValue = offset + start.elapsedNow().inWholeNanoseconds / 1_000_000_000f
        }
    }
    return seconds
}

// --- Julafton --------------------------------------------------------------------
//
// En svensk vinternatt: månsken och stjärnor, snötäckta kullar med granar och
// en röd stuga med varmt ljus i fönstren och rök ur skorstenen. Framför allt
// faller snö i tre lager som driver i vinden, och en ljusslinga blinkar längs
// överkanten. En gång i minuten glider tomtens släde förbi månen.

private class Flake(
    val x: Float,
    val y: Float,
    val radius: Float,
    val speed: Float,
    val sway: Float,
    val phase: Float,
    val alpha: Float,
    /** 0,35 långt bort, 0,7 mitt emellan, 1 nära: nära faller fortare och driver mer. */
    val depth: Float,
)

/** Snö i tre lager, i en vind som långsamt byter riktning och styrka. */
@Composable
private fun Snowfall(running: Boolean, modifier: Modifier) {
    val flakes = remember {
        val random = Random(24)
        List(170) { i ->
            val depth = when {
                i < 100 -> 0.35f
                i < 155 -> 0.7f
                else -> 1f
            }
            Flake(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = when {
                    depth < 0.5f -> 0.7f + random.nextFloat() * 0.8f
                    depth < 1f -> 1.4f + random.nextFloat() * 1.2f
                    else -> 3f + random.nextFloat() * 2.2f
                },
                speed = 0.025f + random.nextFloat() * 0.035f,
                sway = 5f + random.nextFloat() * 14f,
                phase = random.nextFloat() * 6.28f,
                alpha = when {
                    depth < 0.5f -> 0.25f + random.nextFloat() * 0.3f
                    depth < 1f -> 0.4f + random.nextFloat() * 0.4f
                    else -> 0.2f + random.nextFloat() * 0.16f
                },
                depth = depth,
            )
        }
    }
    val seconds = rememberSeconds(running, fps = 30)
    Canvas(modifier) {
        val t = seconds.floatValue
        // Vindens förskjutning: summan av en vind som byter riktning sakta.
        // Den svänger fram och tillbaka, så flingorna blåser aldrig iväg för gott.
        val drift = -0.6f / 0.11f * cos(t * 0.11f) - 0.4f / 0.037f * cos(t * 0.037f + 1.3f)
        for (flake in flakes) {
            val fall = flake.speed * (0.45f + flake.depth)
            val y = ((flake.y + t * fall) % 1f) * (size.height + 40f) - 20f
            val shifted = (flake.x + drift * 0.006f * flake.depth) % 1f
            val x = (if (shifted < 0f) shifted + 1f else shifted) * size.width +
                sin(t * 0.5f + flake.phase) * flake.sway * density
            val center = Offset(x, y)
            if (flake.depth >= 1f) {
                // Nära: stor och mjuk, som ur fokus.
                drawCircle(Color.White.copy(alpha = flake.alpha * 0.45f), radius = flake.radius * 1.9f * density, center = center)
            }
            drawCircle(Color.White.copy(alpha = flake.alpha), radius = flake.radius * density, center = center)
        }
    }
}

/** Ljusslinga längs fönstrets överkant: sladd i bågar, lampor som blinkar i otakt. */
@Composable
private fun FairyLights(running: Boolean, modifier: Modifier) {
    val seconds = rememberSeconds(running, fps = 30)
    val colors = remember { listOf(Color(0xFFFF5A4E), Color(0xFFFFC94D), Color(0xFF6DDB7B), Color(0xFF63B3FF)) }
    Canvas(modifier) {
        val t = seconds.floatValue
        val spacing = 46.dp.toPx()
        val top = 3.dp.toPx()
        val sag = 10.dp.toPx()
        val count = (size.width / spacing).toInt() + 1
        val wire = Path()
        wire.moveTo(0f, top)
        for (i in 0 until count) {
            val x0 = i * spacing
            wire.cubicTo(x0 + spacing / 3f, top + sag, x0 + spacing * 2f / 3f, top + sag, x0 + spacing, top)
        }
        drawPath(wire, Color.Black.copy(alpha = 0.55f), style = Stroke(width = 1.2.dp.toPx()))
        for (i in 0 until count) {
            // Lampan hänger där bågen är som lägst.
            val center = Offset(i * spacing + spacing / 2f, top + sag * 0.75f)
            val color = colors[i % colors.size]
            val twinkle = 0.55f + 0.45f * sin(t * (1.3f + (i % 3) * 0.4f) + i * 1.7f)
            drawCircle(color.copy(alpha = 0.22f * twinkle), radius = 8.dp.toPx(), center = center)
            drawCircle(color.copy(alpha = 0.95f * twinkle.coerceAtLeast(0.35f)), radius = 2.6.dp.toPx(), center = center)
        }
    }
}

private class Pine(val x: Float, val scale: Float, val front: Boolean)

/** Himmel, måne, stjärnor, kullar, granar, stugan och släden. Det stilla ritas en gång. */
@Composable
private fun WinterNight(running: Boolean, modifier: Modifier) {
    val veil = skyVeil()
    val stars = remember {
        val random = Random(41)
        List(90) { Star(random.nextFloat(), random.nextFloat() * 0.6f, 0.5f + random.nextFloat() * 1.1f, 0.25f + random.nextFloat() * 0.55f) }
    }
    val pines = remember {
        val random = Random(7)
        List(28) { Pine(random.nextFloat(), 0.6f + random.nextFloat() * 0.8f, front = it % 3 == 0) }
    }
    val seconds = rememberSeconds(running, fps = 20)
    Spacer(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val unit = h * 0.045f
            val sky = Brush.verticalGradient(
                0f to Color(0xFF040A1A).copy(alpha = veil),
                0.6f to Color(0xFF0A1931).copy(alpha = veil),
                1f to Color(0xFF132A4C).copy(alpha = veil),
            )
            val backLand = hillPath(w, h, ::backHill)
            val backRidge = ridgePath(w, h, ::backHill)
            val frontLand = hillPath(w, h, ::frontHill)
            val frontRidge = ridgePath(w, h, ::frontHill)
            val frontFill = Brush.verticalGradient(0f to Color(0xFF243656), 1f to Color(0xFF111C30), startY = h * 0.88f, endY = h)
            val cottageX = 0.73f
            val backPines = pines.filter { !it.front }
                .map { pinePath(Offset(it.x * w, backHill(it.x) * h + unit * 0.15f), unit * it.scale) }
            val frontPines = pines.filter { it.front && abs(it.x - cottageX) > 0.07f }
                .map { pinePath(Offset(it.x * w, frontHill(it.x) * h + unit * 0.2f), unit * it.scale * 1.35f) }
            val cottage = Offset(cottageX * w, frontHill(cottageX) * h + unit * 0.1f)
            val moonRadius = (min(w, h) * 0.05f).coerceAtLeast(14f)
            val moon = Offset(w * 0.84f, h * 0.16f)
            val moonGlow = Brush.radialGradient(listOf(Color(0x30DCE8FF), Color.Transparent), center = moon, radius = moonRadius * 5f)
            onDrawBehind {
                val t = seconds.floatValue
                drawRect(sky)
                stars.forEachIndexed { i, star ->
                    val twinkle = 0.65f + 0.35f * sin(t * (0.5f + (i % 5) * 0.15f) + i * 1.3f)
                    drawCircle(Color.White.copy(alpha = star.alpha * twinkle), radius = star.radius * density, center = Offset(star.x * w, star.y * h))
                }
                drawCircle(moonGlow, radius = moonRadius * 5f, center = moon)
                drawCircle(Color(0xFFF3F0E4), radius = moonRadius, center = moon)
                drawCircle(Color(0x12000000), radius = moonRadius * 0.3f, center = moon + Offset(-moonRadius * 0.3f, -moonRadius * 0.15f))
                drawCircle(Color(0x0E000000), radius = moonRadius * 0.18f, center = moon + Offset(moonRadius * 0.35f, moonRadius * 0.3f))
                // Släden: tolv sekunder förbi månen, en gång i minuten.
                val cycle = t % 60f
                if (cycle < 12f) {
                    val p = cycle / 12f
                    drawSleigh(
                        Offset(moon.x + moonRadius * 4.5f - p * moonRadius * 9f, moon.y + moonRadius * 0.1f - sin(p * 3.14f) * moonRadius * 0.6f),
                        moonRadius * 0.32f,
                    )
                }
                drawPath(backLand, Color(0xFF0D1B31))
                drawPath(backRidge, Color.White.copy(alpha = 0.10f), style = Stroke(width = 1.5.dp.toPx()))
                for ((tree, cap) in backPines) {
                    drawPath(tree, Color(0xFF0A1528))
                    drawPath(cap, Color.White.copy(alpha = 0.28f))
                }
                drawCottage(cottage, unit * 0.62f, t)
                drawPath(frontLand, frontFill)
                drawPath(frontRidge, Color.White.copy(alpha = 0.16f), style = Stroke(width = 2.dp.toPx()))
                for ((tree, cap) in frontPines) {
                    drawPath(tree, Color(0xFF07101E))
                    drawPath(cap, Color.White.copy(alpha = 0.32f))
                }
            }
        },
    )
}

private fun backHill(x: Float): Float = 0.84f + 0.022f * sin(x * 5.1f + 0.7f) + 0.01f * sin(x * 11.3f)

private fun frontHill(x: Float): Float = 0.915f + 0.018f * sin(x * 3.7f + 2.1f) + 0.007f * sin(x * 9.4f + 1f)

/** En kulle: kammen enligt [ridge] (andel av höjden), fylld ner till underkanten. */
private fun hillPath(w: Float, h: Float, ridge: (Float) -> Float): Path = ridgePath(w, h, ridge).apply {
    lineTo(w, h)
    lineTo(0f, h)
    close()
}

/** Bara kammen, för snön längs kanten. */
private fun ridgePath(w: Float, h: Float, ridge: (Float) -> Float): Path = Path().apply {
    moveTo(0f, ridge(0f) * h)
    for (i in 1..48) {
        val x = i / 48f
        lineTo(x * w, ridge(x) * h)
    }
}

/** En gran i tre våningar, och snön i toppen. */
private fun pinePath(base: Offset, height: Float): Pair<Path, Path> {
    val half = height * 0.42f
    val tree = Path().apply {
        moveTo(base.x, base.y - height)
        lineTo(base.x + half * 0.55f, base.y - height * 0.55f)
        lineTo(base.x + half * 0.32f, base.y - height * 0.55f)
        lineTo(base.x + half * 0.8f, base.y - height * 0.2f)
        lineTo(base.x + half * 0.45f, base.y - height * 0.2f)
        lineTo(base.x + half, base.y)
        lineTo(base.x - half, base.y)
        lineTo(base.x - half * 0.45f, base.y - height * 0.2f)
        lineTo(base.x - half * 0.8f, base.y - height * 0.2f)
        lineTo(base.x - half * 0.32f, base.y - height * 0.55f)
        lineTo(base.x - half * 0.55f, base.y - height * 0.55f)
        close()
    }
    val cap = Path().apply {
        moveTo(base.x, base.y - height)
        lineTo(base.x + half * 0.22f, base.y - height * 0.8f)
        lineTo(base.x - half * 0.22f, base.y - height * 0.8f)
        close()
    }
    return tree to cap
}

/** Röd stuga med vita knutar, snö på taket, ljus som fladdrar i fönstren och rök ur skorstenen. */
private fun DrawScope.drawCottage(base: Offset, unit: Float, t: Float) {
    val width = unit * 2.6f
    val height = unit * 1.4f
    val left = base.x - width / 2f
    val top = base.y - height
    val chimney = Offset(base.x + unit * 0.55f, top - unit * 0.62f)
    for (i in 0 until 4) {
        val p = (t * 0.12f + i * 0.25f) % 1f
        drawCircle(
            Color(0xFFB8C4D8).copy(alpha = 0.16f * (1f - p)),
            radius = unit * (0.18f + p * 0.45f),
            center = Offset(chimney.x + unit * 0.14f + sin(t * 0.6f + i) * unit * 0.25f * p, chimney.y - p * unit * 2.2f),
        )
    }
    drawRect(Color(0xFF241417), topLeft = chimney, size = Size(unit * 0.28f, unit * 0.6f))
    drawRect(Color(0xFF6E1717), topLeft = Offset(left, top), size = Size(width, height))
    drawRect(Color(0x66EDE6DA), topLeft = Offset(left, top), size = Size(unit * 0.12f, height))
    drawRect(Color(0x66EDE6DA), topLeft = Offset(left + width - unit * 0.12f, top), size = Size(unit * 0.12f, height))
    val roof = Path().apply {
        moveTo(left - unit * 0.3f, top + unit * 0.05f)
        lineTo(base.x, top - unit * 0.95f)
        lineTo(left + width + unit * 0.3f, top + unit * 0.05f)
        close()
    }
    drawPath(roof, Color(0xFF1B202B))
    val snow = Path().apply {
        moveTo(left - unit * 0.32f, top + unit * 0.05f)
        lineTo(base.x, top - unit * 1.0f)
        lineTo(left + width + unit * 0.32f, top + unit * 0.05f)
        lineTo(left + width + unit * 0.12f, top - unit * 0.08f)
        lineTo(base.x, top - unit * 0.78f)
        lineTo(left - unit * 0.12f, top - unit * 0.08f)
        close()
    }
    drawPath(snow, Color(0xD9EEF3FF))
    val flicker = 0.88f + 0.12f * sin(t * 2.3f) * sin(t * 1.7f + 1f)
    val pane = unit * 0.42f
    for (x in listOf(left + width * 0.2f, left + width * 0.62f)) {
        val window = Offset(x, top + height * 0.3f)
        drawCircle(Color(0xFFFFB347).copy(alpha = 0.18f * flicker), radius = unit * 1.1f, center = window + Offset(pane / 2f, pane / 2f))
        drawRect(Color(0xFFFFC46B).copy(alpha = 0.95f * flicker), topLeft = window, size = Size(pane, pane))
        drawLine(Color(0xFF6E1717), Offset(window.x + pane / 2f, window.y), Offset(window.x + pane / 2f, window.y + pane), strokeWidth = unit * 0.05f)
        drawLine(Color(0xFF6E1717), Offset(window.x, window.y + pane / 2f), Offset(window.x + pane, window.y + pane / 2f), strokeWidth = unit * 0.05f)
    }
    drawRect(Color(0xFF3A0F0F), topLeft = Offset(left + width * 0.42f, top + height * 0.42f), size = Size(unit * 0.38f, height * 0.58f))
}

/** Tomtens släde med två renar, som siluett mot månen. Färdas åt vänster. */
private fun DrawScope.drawSleigh(at: Offset, unit: Float) {
    val ink = Color(0xF0091222)
    val sleigh = Path().apply {
        moveTo(at.x, at.y)
        lineTo(at.x + unit * 2.2f, at.y)
        lineTo(at.x + unit * 2.6f, at.y - unit * 1.3f)
        lineTo(at.x + unit * 1.9f, at.y - unit * 0.9f)
        lineTo(at.x + unit * 0.5f, at.y - unit * 0.9f)
        close()
    }
    drawPath(sleigh, ink)
    drawLine(ink, Offset(at.x - unit * 0.3f, at.y + unit * 0.35f), Offset(at.x + unit * 2.4f, at.y + unit * 0.35f), strokeWidth = unit * 0.15f)
    drawCircle(ink, radius = unit * 0.35f, center = Offset(at.x + unit * 1.9f, at.y - unit * 1.2f))
    for (i in 0 until 2) {
        val body = Offset(at.x - unit * (1.8f + i * 1.9f), at.y - unit * 0.7f)
        drawOval(ink, topLeft = Offset(body.x - unit * 0.7f, body.y - unit * 0.3f), size = Size(unit * 1.4f, unit * 0.6f))
        drawLine(ink, Offset(body.x - unit * 0.6f, body.y), Offset(body.x - unit * 0.95f, body.y - unit * 0.6f), strokeWidth = unit * 0.2f)
        drawCircle(ink, radius = unit * 0.2f, center = Offset(body.x - unit * 1.0f, body.y - unit * 0.7f))
        drawLine(ink, Offset(body.x - unit * 1.0f, body.y - unit * 0.85f), Offset(body.x - unit * 0.8f, body.y - unit * 1.25f), strokeWidth = unit * 0.08f)
        drawLine(ink, Offset(body.x - unit * 1.05f, body.y - unit * 0.85f), Offset(body.x - unit * 1.3f, body.y - unit * 1.2f), strokeWidth = unit * 0.08f)
        drawLine(ink, Offset(body.x - unit * 0.45f, body.y + unit * 0.2f), Offset(body.x - unit * 0.85f, body.y + unit * 0.6f), strokeWidth = unit * 0.1f)
        drawLine(ink, Offset(body.x + unit * 0.45f, body.y + unit * 0.2f), Offset(body.x + unit * 0.85f, body.y + unit * 0.55f), strokeWidth = unit * 0.1f)
    }
    drawLine(ink, Offset(at.x + unit * 0.2f, at.y - unit * 0.5f), Offset(at.x - unit * 3.4f, at.y - unit * 0.7f), strokeWidth = unit * 0.06f)
}

private class Star(val x: Float, val y: Float, val radius: Float, val alpha: Float)

@Composable
private fun Starfield(color: Color, modifier: Modifier) {
    val stars = remember {
        val random = Random(11)
        List(180) { Star(random.nextFloat(), random.nextFloat(), 0.5f + random.nextFloat() * 1.1f, 0.15f + random.nextFloat() * 0.45f) }
    }
    Canvas(modifier) {
        for (star in stars) {
            drawCircle(
                color.copy(alpha = star.alpha),
                radius = star.radius * density,
                center = Offset(star.x * size.width, star.y * size.height),
            )
        }
    }
}

private class Mote(
    val x: Float,
    val y: Float,
    val radius: Float,
    val rise: Float,
    val drift: Float,
    val phase: Float,
    val twinkle: Float,
)

/** Gyllene stoft som stiger långsamt och glimmar. */
@Composable
private fun Stardust(running: Boolean, color: Color, modifier: Modifier) {
    val motes = remember {
        val random = Random(5)
        List(48) {
            Mote(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 0.8f + random.nextFloat() * 1.4f,
                rise = 0.01f + random.nextFloat() * 0.025f,
                drift = 10f + random.nextFloat() * 24f,
                phase = random.nextFloat() * 6.28f,
                twinkle = 0.8f + random.nextFloat() * 2.2f,
            )
        }
    }
    val seconds = rememberSeconds(running, fps = 30)
    Canvas(modifier) {
        val t = seconds.floatValue
        for (mote in motes) {
            val y = (1f - ((mote.y + t * mote.rise) % 1f)) * size.height
            val x = mote.x * size.width + sin(t * 0.3f + mote.phase) * mote.drift * density
            val shine = 0.2f + 0.8f * ((sin(t * mote.twinkle + mote.phase) + 1f) / 2f)
            val center = Offset(x, y)
            drawCircle(color.copy(alpha = 0.18f * shine), radius = mote.radius * 4f * density, center = center)
            drawCircle(color.copy(alpha = 0.75f * shine), radius = mote.radius * density, center = center)
        }
    }
}

// --- Superhjälte ----------------------------------------------------------------------

/** Serietidningsraster och fartstreck. Stillastående: kostar inget efter första ritningen. */
@Composable
private fun Halftone(dot: Color, accent: Color, modifier: Modifier) {
    Canvas(modifier) {
        val origin = Offset(-size.width * 0.1f, -size.height * 0.1f)
        for (i in 0 until 14) {
            val angle = 0.15f + i * 0.085f
            val end = Offset(origin.x + cos(angle) * size.maxDimension * 1.6f, origin.y + sin(angle) * size.maxDimension * 1.6f)
            drawLine(accent.copy(alpha = 0.035f), origin, end, strokeWidth = (10 + (i % 3) * 8).dp.toPx())
        }
        // Större prickar mot nedre högra hörnet, som skuggningen i en serieruta.
        val step = 14.dp.toPx()
        var y = 0f
        var row = 0
        while (y < size.height + step) {
            var x = if (row % 2 == 0) 0f else step / 2f
            while (x < size.width + step) {
                val k = (x / size.width) * 0.55f + (y / size.height) * 0.45f
                drawCircle(dot, radius = step * (0.06f + 0.24f * k * k), center = Offset(x, y))
                x += step
            }
            y += step * 0.866f
            row++
        }
    }
}

// --- Fantasyepos ----------------------------------------------------------------------

/** Dimma som driver långsamt, några stora mjuka moln i temats gröna ton. */
@Composable
private fun Mist(running: Boolean, tint: Color, modifier: Modifier) {
    val seconds = rememberSeconds(running, fps = 15)
    Canvas(modifier) {
        val t = seconds.floatValue
        for (i in 0 until 4) {
            val cx = size.width * (0.1f + 0.8f * ((sin(t * 0.03f * (i + 1) + i * 2.1f) + 1f) / 2f))
            val cy = size.height * (0.45f + 0.45f * ((cos(t * 0.022f * (i + 2) + i) + 1f) / 2f))
            val radius = size.maxDimension * (0.28f + 0.07f * i)
            val center = Offset(cx, cy)
            drawCircle(
                Brush.radialGradient(colors = listOf(tint.copy(alpha = 0.08f), Color.Transparent), center = center, radius = radius),
                radius = radius,
                center = center,
            )
        }
    }
}


// --- Nordljus ------------------------------------------------------------------------
//
// Arktisk natt: stjärnor, norrsken i tre band som böljar sakta och ändrar
// styrka, och fjäll som mörka siluetter längst ner.

@Composable
private fun AuroraSky(running: Boolean, modifier: Modifier) {
    val veil = skyVeil()
    val stars = remember {
        val random = Random(77)
        List(110) { Star(random.nextFloat(), random.nextFloat() * 0.7f, 0.4f + random.nextFloat() * 1.0f, 0.2f + random.nextFloat() * 0.6f) }
    }
    val seconds = rememberSeconds(running, fps = 20)
    Spacer(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val sky = Brush.verticalGradient(
                0f to Color(0xFF02060D).copy(alpha = veil),
                0.6f to Color(0xFF051320).copy(alpha = veil),
                1f to Color(0xFF082233).copy(alpha = veil),
            )
            val backFells = Path().apply {
                moveTo(0f, h * 0.86f)
                lineTo(w * 0.1f, h * 0.79f)
                lineTo(w * 0.2f, h * 0.83f)
                lineTo(w * 0.34f, h * 0.73f)
                lineTo(w * 0.47f, h * 0.81f)
                lineTo(w * 0.6f, h * 0.75f)
                lineTo(w * 0.74f, h * 0.83f)
                lineTo(w * 0.88f, h * 0.77f)
                lineTo(w, h * 0.82f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            val frontFells = Path().apply {
                moveTo(0f, h * 0.93f)
                lineTo(w * 0.16f, h * 0.87f)
                lineTo(w * 0.3f, h * 0.92f)
                lineTo(w * 0.52f, h * 0.86f)
                lineTo(w * 0.7f, h * 0.93f)
                lineTo(w * 0.86f, h * 0.88f)
                lineTo(w, h * 0.92f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            onDrawBehind {
                val t = seconds.floatValue
                drawRect(sky)
                stars.forEachIndexed { i, star ->
                    val twinkle = 0.7f + 0.3f * sin(t * (0.4f + (i % 4) * 0.2f) + i)
                    drawCircle(Color.White.copy(alpha = star.alpha * twinkle), radius = star.radius * density, center = Offset(star.x * w, star.y * h))
                }
                drawAuroraBand(t, base = 0.24f, amp = 0.06f, thickness = 0.24f, speed = 0.05f, phase = 0f, color = Color(0xFF3EF0B2), alpha = 0.34f)
                drawAuroraBand(t, base = 0.34f, amp = 0.05f, thickness = 0.18f, speed = -0.035f, phase = 2.1f, color = Color(0xFF5CF2C8), alpha = 0.22f)
                drawAuroraBand(t, base = 0.16f, amp = 0.04f, thickness = 0.12f, speed = 0.028f, phase = 4.2f, color = Color(0xFFB98BFF), alpha = 0.18f)
                drawPath(backFells, Color(0xFF071521))
                drawPath(frontFells, Color(0xFF040B13))
            }
        },
    )
}

/** Ett norrskensband: en böljande ridå som lyser starkast strax under överkanten. */
private fun DrawScope.drawAuroraBand(
    t: Float,
    base: Float,
    amp: Float,
    thickness: Float,
    speed: Float,
    phase: Float,
    color: Color,
    alpha: Float,
) {
    val w = size.width
    val h = size.height
    val steps = 40
    val band = Path()
    for (i in 0..steps) {
        val fx = i / steps.toFloat()
        val y = (base + amp * sin(fx * 6f + t * speed * 6.28f + phase) + amp * 0.5f * sin(fx * 13f - t * speed * 9f + phase)) * h
        if (i == 0) band.moveTo(0f, y) else band.lineTo(fx * w, y)
    }
    for (i in steps downTo 0) {
        val fx = i / steps.toFloat()
        val y = (base + thickness + amp * 0.6f * sin(fx * 5f + t * speed * 5f + phase + 1f)) * h
        band.lineTo(fx * w, y)
    }
    band.close()
    val pulse = 0.8f + 0.2f * sin(t * 0.4f + phase)
    drawPath(
        band,
        Brush.verticalGradient(
            0f to color.copy(alpha = 0f),
            0.25f to color.copy(alpha = alpha * pulse),
            1f to color.copy(alpha = 0f),
            startY = (base - amp * 1.5f) * h,
            endY = (base + thickness + amp) * h,
        ),
    )
}

// --- Retrovåg -----------------------------------------------------------------------
//
// Åttiotalsneon: himlen går från lila till rosa mot horisonten, en randig sol
// går ner bakom ett rosa rutnät som rör sig mot betraktaren, och svaga
// VHS-svep drar över bilden.

@Composable
private fun RetroHorizon(running: Boolean, modifier: Modifier) {
    val veil = skyVeil()
    val stars = remember {
        val random = Random(88)
        List(60) { Star(random.nextFloat(), random.nextFloat() * 0.45f, 0.4f + random.nextFloat() * 0.9f, 0.2f + random.nextFloat() * 0.5f) }
    }
    val seconds = rememberSeconds(running, fps = 30)
    val grid = Color(0xFFFF3EA5)
    Spacer(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val horizon = h * 0.66f
            val sky = Brush.verticalGradient(
                0f to Color(0xFF0C0416).copy(alpha = veil),
                0.55f to Color(0xFF2A0B3D).copy(alpha = veil),
                1f to Color(0xFF5A1250).copy(alpha = veil),
                endY = horizon,
            )
            val sun = Offset(w * 0.64f, horizon)
            val sunRadius = min(w, h) * 0.2f
            val sunFill = Brush.verticalGradient(0f to Color(0xFFFFD36E), 1f to Color(0xFFFF3E8A), startY = sun.y - sunRadius, endY = sun.y)
            val sunGlow = Brush.radialGradient(listOf(Color(0x40FF3E8A), Color.Transparent), center = sun, radius = sunRadius * 2.2f)
            val floor = Brush.verticalGradient(0f to Color(0xFF14031F), 1f to Color(0xFF07010D), startY = horizon, endY = h)
            onDrawBehind {
                val t = seconds.floatValue
                drawRect(sky, size = Size(w, horizon))
                stars.forEachIndexed { i, star ->
                    val twinkle = 0.7f + 0.3f * sin(t * 0.5f + i)
                    drawCircle(Color.White.copy(alpha = star.alpha * twinkle * veil), radius = star.radius * density, center = Offset(star.x * w, star.y * h))
                }
                drawCircle(sunGlow, radius = sunRadius * 2.2f, center = sun)
                drawCircle(sunFill, radius = sunRadius, center = sun)
                // Ränderna i solens nedre halva, tjockare ju längre ner.
                for (i in 0 until 6) {
                    val y = sun.y - sunRadius * (0.55f - i * 0.1f)
                    drawRect(Color(0xFF3A0E45).copy(alpha = veil), topLeft = Offset(sun.x - sunRadius, y), size = Size(sunRadius * 2f, sunRadius * (0.025f + i * 0.012f)))
                }
                drawRect(floor, topLeft = Offset(0f, horizon), size = Size(w, h - horizon))
                // Rutnätet: linjer mot en flyktpunkt i solen, och tvärlinjer som kommer emot.
                for (i in -14..14) {
                    drawLine(
                        grid.copy(alpha = 0.32f),
                        start = Offset(sun.x + i * w * 0.004f, horizon),
                        end = Offset(sun.x + i * w * 0.09f, h),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                val scroll = (t * 0.35f) % 1f
                for (k in 0 until 12) {
                    val z = (k + scroll) / 12f
                    val p = z * z
                    val y = horizon + (h - horizon) * p
                    drawLine(grid.copy(alpha = 0.12f + 0.4f * p), Offset(0f, y), Offset(w, y), strokeWidth = (0.6f + 1.4f * p).dp.toPx())
                }
                drawLine(Color(0xFFFF8AD8).copy(alpha = 0.8f), Offset(0f, horizon), Offset(w, horizon), strokeWidth = 1.5.dp.toPx())
            }
        },
    )
}

/** Svaga sveplinjer som på ett VHS-band, och ett ljust svep som drar nerför bilden. */
@Composable
private fun Scanlines(running: Boolean, modifier: Modifier) {
    // Linjerna står still: ritas en gång och sparas.
    Spacer(
        modifier.drawWithCache {
            val step = 3.dp.toPx()
            val line = Color.Black.copy(alpha = 0.06f)
            onDrawBehind {
                var y = 0f
                while (y < size.height) {
                    drawRect(line, topLeft = Offset(0f, y), size = Size(size.width, 1f))
                    y += step
                }
            }
        },
    )
    val seconds = rememberSeconds(running, fps = 30)
    Canvas(modifier) {
        val band = size.height * 0.08f
        val y = ((seconds.floatValue / 9f) % 1f) * (size.height + band) - band
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.045f),
                1f to Color.Transparent,
                startY = y,
                endY = y + band,
            ),
            topLeft = Offset(0f, y),
            size = Size(size.width, band),
        )
    }
}

// --- Kristall ------------------------------------------------------------------------
//
// Frostat glas mot ett levande ljus: stora mjuka färgfält i violett, rosa, cyan
// och blått som flyter långsamt om varandra, kristallstoft som glimmar, och en
// gång i halvminuten en svag reflex som drar snett över allt, som i en glasruta.

private class Glint(val x: Float, val y: Float, val radius: Float, val speed: Float, val phase: Float)

@Composable
private fun CrystalLight(running: Boolean, modifier: Modifier) {
    val veil = skyVeil()
    val glints = remember {
        val random = Random(19)
        List(36) {
            Glint(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 0.5f + random.nextFloat() * 1.1f,
                speed = 0.4f + random.nextFloat() * 1.2f,
                phase = random.nextFloat() * 6.28f,
            )
        }
    }
    val seconds = rememberSeconds(running, fps = 24)
    Spacer(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val base = Brush.verticalGradient(
                0f to Color(0xFF06051A).copy(alpha = veil),
                0.55f to Color(0xFF080622).copy(alpha = veil),
                1f to Color(0xFF0C0829).copy(alpha = veil),
            )
            val reach = size.maxDimension
            onDrawBehind {
                val t = seconds.floatValue
                drawRect(base)
                drawBlob(CRYSTAL_VIOLET, 0.30f, reach * 0.55f, Offset(w * (0.22f + 0.14f * sin(t * 0.050f)), h * (0.28f + 0.14f * cos(t * 0.043f))))
                drawBlob(CRYSTAL_PINK, 0.20f, reach * 0.42f, Offset(w * (0.82f + 0.10f * sin(t * 0.037f + 1f)), h * (0.22f + 0.16f * sin(t * 0.051f + 2f))))
                drawBlob(CRYSTAL_CYAN, 0.18f, reach * 0.50f, Offset(w * (0.62f + 0.18f * cos(t * 0.031f + 3f)), h * (0.88f + 0.08f * sin(t * 0.047f))))
                drawBlob(CRYSTAL_BLUE, 0.22f, reach * 0.60f, Offset(w * (0.06f + 0.10f * cos(t * 0.029f + 4f)), h * (0.92f + 0.07f * sin(t * 0.041f + 1f))))
                for (glint in glints) {
                    val twinkle = 0.5f + 0.5f * sin(t * glint.speed + glint.phase)
                    val center = Offset(glint.x * w, glint.y * h)
                    drawCircle(Color.White.copy(alpha = 0.10f * twinkle), radius = glint.radius * 3.2f * density, center = center)
                    drawCircle(Color.White.copy(alpha = 0.55f * twinkle * twinkle), radius = glint.radius * density, center = center)
                }
                // Reflexen: tre sekunder snett över bilden, en gång var tjugofjärde sekund.
                val cycle = t % 24f
                if (cycle < 3.2f) {
                    val x = -w * 0.4f + cycle / 3.2f * w * 1.8f
                    drawRect(
                        Brush.linearGradient(
                            0f to Color.Transparent,
                            0.5f to Color.White.copy(alpha = 0.045f),
                            1f to Color.Transparent,
                            start = Offset(x - w * 0.12f, 0f),
                            end = Offset(x + w * 0.12f, h * 0.5f),
                        ),
                    )
                }
            }
        },
    )
}

/** Ett mjukt färgfält som tonar ut åt alla håll. */
private fun DrawScope.drawBlob(color: Color, alpha: Float, radius: Float, center: Offset) {
    drawCircle(
        Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            0.45f to color.copy(alpha = alpha * 0.45f),
            1f to color.copy(alpha = 0f),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

private val CRYSTAL_VIOLET = Color(0xFF7C4DFF)
private val CRYSTAL_PINK = Color(0xFFFF4FB8)
private val CRYSTAL_CYAN = Color(0xFF2EC8FF)
private val CRYSTAL_BLUE = Color(0xFF3550FF)

/** En liten stillbild av dekoren, för temakorten i inställningarna. */
internal fun DrawScope.decorationHint(palette: Palette) {
    when (palette.decoration) {
        Decoration.NONE -> Unit
        Decoration.SNOW -> {
            drawCircle(Color(0xFFF3F0E4), radius = size.height * 0.1f, center = Offset(size.width * 0.82f, size.height * 0.22f))
            val random = Random(3)
            repeat(16) {
                drawCircle(
                    Color.White.copy(alpha = 0.7f),
                    radius = (0.8f + random.nextFloat() * 1.4f) * density,
                    center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height),
                )
            }
            for ((x, scale) in listOf(0.68f to 0.34f, 0.8f to 0.26f, 0.9f to 0.3f)) {
                val (tree, cap) = pinePath(Offset(size.width * x, size.height), size.height * scale)
                drawPath(tree, Color(0xFF07101E))
                drawPath(cap, Color.White.copy(alpha = 0.5f))
            }
        }
        Decoration.SPARKLES -> {
            val random = Random(9)
            repeat(9) {
                val center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
                drawCircle(palette.signal.copy(alpha = 0.2f), radius = 4f * density, center = center)
                drawCircle(palette.signal, radius = 1.1f * density, center = center)
            }
        }
        Decoration.HALFTONE -> {
            val step = 6.dp.toPx()
            var y = 0f
            while (y < size.height) {
                var x = size.width * 0.45f
                while (x < size.width) {
                    drawCircle(palette.surfaceRaised, radius = step * 0.3f * (x / size.width), center = Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        Decoration.MIST -> {
            val center = Offset(size.width * 0.7f, size.height * 0.8f)
            val radius = size.width * 0.6f
            drawCircle(
                Brush.radialGradient(colors = listOf(palette.secondary.copy(alpha = 0.18f), Color.Transparent), center = center, radius = radius),
                radius = radius,
                center = center,
            )
        }
        Decoration.AURORA -> {
            drawAuroraBand(3f, base = 0.12f, amp = 0.08f, thickness = 0.4f, speed = 0.05f, phase = 0f, color = Color(0xFF3EF0B2), alpha = 0.55f)
            drawAuroraBand(3f, base = 0.3f, amp = 0.06f, thickness = 0.3f, speed = -0.03f, phase = 2f, color = Color(0xFFB98BFF), alpha = 0.35f)
        }
        Decoration.CRYSTAL -> {
            drawBlob(CRYSTAL_VIOLET, 0.42f, size.width * 0.5f, Offset(size.width * 0.3f, size.height * 0.25f))
            drawBlob(CRYSTAL_PINK, 0.32f, size.width * 0.4f, Offset(size.width * 0.88f, size.height * 0.15f))
            drawBlob(CRYSTAL_CYAN, 0.32f, size.width * 0.45f, Offset(size.width * 0.72f, size.height * 1.0f))
        }
        Decoration.RETRO -> {
            val horizon = size.height * 0.7f
            val sun = Offset(size.width * 0.72f, horizon)
            val radius = size.height * 0.28f
            drawCircle(
                Brush.verticalGradient(0f to Color(0xFFFFD36E), 1f to Color(0xFFFF3E8A), startY = sun.y - radius, endY = sun.y),
                radius = radius,
                center = sun,
            )
            drawRect(palette.background, topLeft = Offset(0f, horizon), size = Size(size.width, size.height - horizon))
            for (i in -4..4) {
                drawLine(Color(0xFFFF3EA5).copy(alpha = 0.6f), Offset(sun.x + i * 2f, horizon), Offset(sun.x + i * size.width * 0.12f, size.height), strokeWidth = 1f)
            }
        }
    }
}

package se.norrsken.tv.desktop.ui

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
 * Temats bakgrund, bakom innehållet: brasglöd, stjärnhimmel, serieraster
 * eller dimma. Syns där skärmarna är genomskinliga.
 */
@Composable
fun ThemeBackdrop(decoration: Decoration, running: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    when (decoration) {
        Decoration.NONE -> Unit
        Decoration.SNOW -> Hearth(modifier)
        Decoration.SPARKLES -> Starfield(palette.textPrimary, modifier)
        Decoration.HALFTONE -> Halftone(palette.surfaceRaised, palette.focus, modifier)
        Decoration.MIST -> Mist(running, palette.secondary, modifier)
    }
}

/**
 * Det som rör sig ovanpå allt: snö och ljusslinga, stjärnstoft. Ritas bara,
 * tar aldrig emot klick — allt under fungerar som vanligt.
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
        else -> Unit
    }
}

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
        val start = System.nanoTime()
        while (true) {
            if (fps >= 60) withFrameNanos { } else delay(1000L / fps)
            seconds.floatValue = offset + (System.nanoTime() - start) / 1_000_000_000f
        }
    }
    return seconds
}

// --- Julafton --------------------------------------------------------------------

private class Flake(
    val x: Float,
    val y: Float,
    val radius: Float,
    val speed: Float,
    val sway: Float,
    val phase: Float,
    val alpha: Float,
)

@Composable
private fun Snowfall(running: Boolean, modifier: Modifier) {
    val flakes = remember {
        val random = Random(24)
        List(120) {
            Flake(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 1.1f + random.nextFloat() * 2.4f,
                speed = 0.03f + random.nextFloat() * 0.05f,
                sway = 6f + random.nextFloat() * 16f,
                phase = random.nextFloat() * 6.28f,
                alpha = 0.3f + random.nextFloat() * 0.5f,
            )
        }
    }
    val seconds = rememberSeconds(running, fps = 60)
    Canvas(modifier) {
        val t = seconds.floatValue
        for (flake in flakes) {
            // Stora flingor faller lite fortare, som i verkligheten.
            val fall = flake.speed * (0.6f + flake.radius / 3f)
            val y = ((flake.y + t * fall) % 1f) * (size.height + 24f) - 12f
            val x = flake.x * size.width + sin(t * 0.5f + flake.phase) * flake.sway * density
            drawCircle(Color.White.copy(alpha = flake.alpha), radius = flake.radius * density, center = Offset(x, y))
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

/** Varmt sken nerifrån, som från en brasa. */
@Composable
private fun Hearth(modifier: Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width * 0.55f, size.height * 1.1f)
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color(0x30FF9A3C), Color(0x10FF6A1A), Color.Transparent),
                center = center,
                radius = size.maxDimension * 0.75f,
            ),
        )
    }
}

// --- Trolldom -----------------------------------------------------------------------

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

/** En liten stillbild av dekoren, för temakorten i inställningarna. */
internal fun DrawScope.decorationHint(palette: Palette) {
    when (palette.decoration) {
        Decoration.NONE -> Unit
        Decoration.SNOW -> {
            val random = Random(3)
            repeat(16) {
                drawCircle(
                    Color.White.copy(alpha = 0.7f),
                    radius = (0.8f + random.nextFloat() * 1.4f) * density,
                    center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height),
                )
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
    }
}

package se.norrsken.tv.core.ui.effects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import se.norrsken.tv.data.settings.EffectLevel
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Vilken atmosfär ett tema har. Temat väljer, användaren styr nivån. */
enum class BackgroundEffect { NONE, SNOW, EMBERS, AURORA }

/**
 * En partikel beskrivs helt av sina startvärden. Positionen räknas fram ur
 * tiden varje bildruta — inget flyttas, inget allokeras. Det är därför
 * effekten klarar sextio bilder i sekunden även på en äldre box.
 */
private class Particle(
    val x: Float,
    val y: Float,
    val speed: Float,
    val radius: Float,
    val drift: Float,
    val driftRate: Float,
    val phase: Float,
    val alpha: Float,
)

private fun particles(count: Int, seed: Int): List<Particle> {
    val random = Random(seed)
    return List(count) {
        Particle(
            x = random.nextFloat(),
            y = random.nextFloat(),
            speed = 0.02f + random.nextFloat() * 0.06f,
            radius = 1.2f + random.nextFloat() * 2.8f,
            drift = 0.4f + random.nextFloat(),
            driftRate = 0.3f + random.nextFloat() * 0.8f,
            phase = random.nextFloat() * (2 * PI).toFloat(),
            alpha = 0.25f + random.nextFloat() * 0.55f,
        )
    }
}

private fun baseCount(effect: BackgroundEffect): Int = when (effect) {
    BackgroundEffect.SNOW -> 140
    BackgroundEffect.EMBERS -> 60
    BackgroundEffect.AURORA -> 0
    BackgroundEffect.NONE -> 0
}

/**
 * Ritar temats atmosfär bakom gränssnittet.
 *
 * Ligger aldrig bakom videon — spelaren har eget lager utan effekter. Att
 * rita snö bakom en film vore att bränna grafikkort på något ingen ser.
 */
@Composable
fun AmbientBackground(
    effect: BackgroundEffect,
    level: EffectLevel,
    isTv: Boolean,
    modifier: Modifier = Modifier,
) {
    if (effect == BackgroundEffect.NONE || level == EffectLevel.OFF) return

    // Telefonen får färre partiklar: mindre skärm, och batteriet räknas.
    val deviceFactor = if (isTv) 1f else 0.55f
    val count = (baseCount(effect) * level.particleFactor * deviceFactor).toInt()
    val seeds = remember(effect, count) { particles(count, seed = effect.ordinal * 7919) }
    val aurora = remember { List(3) { Path() } }

    var time by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(effect, level) {
        // Reducerat läge ritar med minst 33 ms mellanrum, alltså runt 30
        // bilder i sekunden. Rörelsen är långsam nog att det inte syns.
        val minGap = if (level == EffectLevel.REDUCED) 33_000_000L else 0L
        var start = -1L
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (start < 0) start = now
                if (now - last >= minGap) {
                    time = (now - start) / 1_000_000_000f
                    last = now
                }
            }
        }
    }

    val grain = remember(level) { if (level == EffectLevel.FULL) grainTexture() else null }

    Canvas(modifier = modifier.fillMaxSize()) {
        // time läses här inne, i ritfasen. Då ritas bara Canvasen om varje
        // bildruta — resten av gränssnittet komponeras inte om.
        val t = time
        when (effect) {
            BackgroundEffect.SNOW -> drawSnow(seeds, t)
            BackgroundEffect.EMBERS -> drawEmbers(seeds, t)
            BackgroundEffect.AURORA -> drawAurora(aurora, t)
            BackgroundEffect.NONE -> Unit
        }
        drawVignette()
        grain?.let { drawGrain(it) }
    }
}

/**
 * Mörkare kanter. Drar blicken mot mitten och gör att atmosfären slutar
 * mjukt i stället för att klippas av vid skärmkanten.
 */
private fun DrawScope.drawVignette() {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color(0x8C000000)),
            center = center,
            radius = size.maxDimension * 0.72f,
        ),
    )
}

/**
 * Filmkorn. En liten brusbild som upprepas — genererad en gång, aldrig per
 * bildruta. Svag nog att det nästan inte går att se när man letar efter
 * det — det ska bara få ytan att kännas som film i stället för plast.
 */
private fun DrawScope.drawGrain(texture: ImageBitmap) {
    drawRect(
        brush = ShaderBrush(ImageShader(texture, TileMode.Repeated, TileMode.Repeated)),
        alpha = 0.06f,
    )
}

private fun grainTexture(): ImageBitmap {
    val side = 128
    val random = Random(42)
    // Ljusa och mörka prickar med varierande täckning, i stället för grått.
    // Grått ovanpå svart hade lyft hela bakgrunden en nyans — prickar åt
    // båda hållen tar ut varandra och lämnar svärtan orörd.
    val pixels = IntArray(side * side) {
        val v = random.nextInt(256)
        val alpha = kotlin.math.abs(v - 128) * 2 - 1
        val rgb = if (v >= 128) 0xFFFFFF else 0x000000
        (alpha.coerceIn(0, 255) shl 24) or rgb
    }
    val bitmap = ImageBitmap(side, side)
    bitmap.asAndroidBitmap().setPixels(pixels, 0, side, 0, 0, side, side)
    return bitmap
}

private fun DrawScope.drawSnow(seeds: List<Particle>, t: Float) {
    val scale = size.minDimension / 540f
    for (p in seeds) {
        val y = ((p.y + t * p.speed) % 1f) * size.height
        // Flingor pendlar i sidled i stället för att falla rakt. Rak snö
        // ser ut som regn.
        val sway = sin(t * p.driftRate + p.phase) * p.drift * 14f * scale
        val x = p.x * size.width + sway
        drawCircle(
            color = Color.White.copy(alpha = p.alpha * 0.7f),
            radius = p.radius * scale,
            center = Offset(x, y),
        )
    }
}

private fun DrawScope.drawEmbers(seeds: List<Particle>, t: Float) {
    val scale = size.minDimension / 540f
    for (p in seeds) {
        val progress = (p.y + t * p.speed * 0.7f) % 1f
        val y = (1f - progress) * size.height
        val sway = sin(t * p.driftRate * 0.6f + p.phase) * p.drift * 22f * scale
        val x = p.x * size.width + sway
        // Gnistor falnar mot toppen och flimrar — som aska över en eld,
        // inte som konfetti.
        val flicker = 0.65f + 0.35f * sin(t * 3.1f + p.phase * 5f)
        val fade = (1f - progress).coerceIn(0f, 1f)
        val warm = if (p.phase > PI.toFloat()) Color(0xFFFF6A2B) else Color(0xFFE0283A)
        drawCircle(
            color = warm.copy(alpha = p.alpha * flicker * fade * 0.8f),
            radius = p.radius * 0.8f * scale,
            center = Offset(x, y),
        )
    }
}

private val AURORA_COLORS = listOf(
    Color(0xFF57E6F2),
    Color(0xFF56F0B4),
    Color(0xFF92E8FF),
)

/**
 * Tre ljusridåer som hänger från skärmens överkant. Varje ridå har en
 * underkant som böljar med två sinuskurvor i olika takt — en enda kurva ser
 * mekanisk ut, två ser levande ut.
 */
private fun DrawScope.drawAurora(paths: List<Path>, t: Float) {
    val width = size.width
    val steps = 48
    paths.forEachIndexed { index, path ->
        val base = size.height * (0.22f + index * 0.07f)
        val amplitude = size.height * (0.05f + index * 0.015f)
        // Ett varv tar 31–45 sekunder. Snabbare än så syns loopen.
        val speed = 0.14f + index * 0.03f
        val phase = index * 1.9f

        path.reset()
        path.moveTo(0f, 0f)
        for (step in 0..steps) {
            val fx = step / steps.toFloat()
            val wave = sin(fx * 2.3f * 2 * PI.toFloat() + t * speed + phase) * amplitude +
                sin(fx * 5.1f * 2 * PI.toFloat() - t * speed * 1.4f) * amplitude * 0.35f
            path.lineTo(fx * width, base + wave)
        }
        path.lineTo(width, 0f)
        path.close()

        val color = AURORA_COLORS[index]
        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                colors = listOf(
                    color.copy(alpha = 0f),
                    color.copy(alpha = 0.10f),
                    color.copy(alpha = 0.16f),
                    color.copy(alpha = 0f),
                ),
                startY = 0f,
                endY = base + amplitude * 1.5f,
            ),
        )
    }
}

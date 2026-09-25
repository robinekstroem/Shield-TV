package se.norrsken.tv.core.ui.effects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.data.settings.EffectLevel
import kotlin.math.abs
import kotlin.random.Random

/**
 * Bakgrunden: svart, med en svag glöd och mörkare kanter.
 *
 * Ingenting rör sig. Tidigare låg här partiklar och böljande ridåer som
 * ritades om sextio gånger i sekunden — de tog uppmärksamhet från
 * innehållet och kostade ström på en TV-box som aldrig stängs av. En stilla
 * glöd ger djup utan att konkurrera.
 *
 * Aldrig bakom videon — spelaren har eget svart lager.
 */
@Composable
fun AmbientBackground(level: EffectLevel, modifier: Modifier = Modifier) {
    if (level == EffectLevel.OFF) return
    val palette = LocalPalette.current
    val glow = palette.signal
    val grain = remember(level) { if (level == EffectLevel.FULL) grainTexture() else null }

    Canvas(modifier = modifier.fillMaxSize()) {
        // Två ljuskällor: en större uppe till vänster där innehållet börjar,
        // en svagare nere till höger som balans. Båda långt under vad ögat
        // läser som "färg" — de gör svärtan djup, inte blå.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.075f), Color.Transparent),
                center = Offset(size.width * 0.18f, size.height * 0.10f),
                radius = size.maxDimension * 0.75f,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.035f), Color.Transparent),
                center = Offset(size.width * 0.92f, size.height * 0.95f),
                radius = size.maxDimension * 0.6f,
            ),
        )
        drawVignette()
        grain?.let { drawGrain(it) }
    }
}

/** Mörkare kanter. Drar blicken mot mitten. */
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
 * bildruta.
 */
private fun DrawScope.drawGrain(texture: ImageBitmap) {
    drawRect(
        brush = ShaderBrush(ImageShader(texture, TileMode.Repeated, TileMode.Repeated)),
        alpha = 0.03f,
    )
}

private fun grainTexture(): ImageBitmap {
    val side = 128
    val random = Random(42)
    // Ljusa och mörka prickar, inte grått: grått ovanpå svart hade lyft
    // hela bakgrunden en nyans.
    val pixels = IntArray(side * side) {
        val v = random.nextInt(256)
        val alpha = (abs(v - 128) * 2 - 1).coerceIn(0, 255)
        val rgb = if (v >= 128) 0xFFFFFF else 0x000000
        (alpha shl 24) or rgb
    }
    val bitmap = ImageBitmap(side, side)
    bitmap.asAndroidBitmap().setPixels(pixels, 0, side, 0, 0, side, side)
    return bitmap
}

package se.norrsken.tv.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.norrsken.tv.core.ui.theme.LocalMotion
import se.norrsken.tv.core.ui.theme.LocalPalette

private const val DURATION_MS = 2400

/**
 * Startsekvensen.
 *
 * Ligger som ett lager ovanpå appen, som byggs färdigt under tiden — den
 * gör alltså aldrig starten långsammare, den täcker bara uppbyggnaden.
 * Ett tryck hoppar över den.
 *
 * Ordningen: ett svagt norrsken tänds, bågen ritas fram, ordet NORRSKEN
 * framträder, ett ljus sveper genom bokstäverna, taggraden kommer, allt
 * tonar bort. Allt styrs av en enda tidslinje från 0 till 1, så delarna
 * aldrig kan hamna i otakt.
 */
@Composable
fun SplashOverlay(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val motion = LocalMotion.current
    val progress = remember { Animatable(0f) }

    LaunchedEffect(motion.reduced) {
        // Med rörelse avstängd ska ingen animation visas alls.
        if (motion.reduced) {
            onFinished()
            return@LaunchedEffect
        }
        progress.animateTo(1f, tween(DURATION_MS, easing = LinearEasing))
        onFinished()
    }

    val p = progress.value
    fun phase(start: Float, end: Float) = ((p - start) / (end - start)).coerceIn(0f, 1f)

    val glow = phase(0.0f, 0.35f)
    val arc = phase(0.12f, 0.45f)
    val word = phase(0.25f, 0.52f)
    val sweep = phase(0.45f, 0.78f)
    val tagline = phase(0.58f, 0.80f)
    val exit = 1f - phase(0.84f, 1.0f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = exit }
            .background(palette.background)
            .pointerInput(Unit) { detectTapGestures { onFinished() } }
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(palette.signal.copy(alpha = 0.20f * glow), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.40f),
                        radius = size.minDimension * 0.9f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(palette.secondary.copy(alpha = 0.10f * glow), Color.Transparent),
                        center = Offset(size.width * 0.64f, size.height * 0.28f),
                        radius = size.minDimension * 0.7f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Symbolen: en båge som ritas fram från vänster, som ett
            // norrsken över en horisont.
            Canvas(Modifier.width(160.dp).height(44.dp)) {
                val path = Path().apply {
                    moveTo(0f, size.height)
                    quadraticTo(size.width / 2f, -size.height * 0.6f, size.width, size.height)
                }
                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            palette.signal.copy(alpha = arc),
                            palette.secondary.copy(alpha = arc),
                            Color.Transparent,
                        ),
                        startX = 0f,
                        endX = size.width * (0.2f + arc),
                    ),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            Text(
                text = "NORRSKEN",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Light,
                    letterSpacing = 14.sp,
                ),
                color = palette.textPrimary,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = word
                        translationY = (1f - word) * 14.dp.toPx()
                        // Offscreen krävs för att ljussvepet bara ska lysa
                        // upp bokstäverna, inte rutan runt dem.
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        if (sweep > 0f && sweep < 1f) {
                            val band = size.width * 0.3f
                            val x = -band + sweep * (size.width + band * 2f)
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.9f),
                                        Color.Transparent,
                                    ),
                                    start = Offset(x - band / 2f, 0f),
                                    end = Offset(x + band / 2f, size.height),
                                ),
                                blendMode = BlendMode.SrcAtop,
                            )
                        }
                    },
            )

            Text(
                text = "STREAM BEYOND",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 6.sp),
                color = palette.textSecondary,
                modifier = Modifier.graphicsLayer { alpha = tagline * 0.8f },
            )
        }
    }
}

package se.norrsken.tv.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalMotion
import se.norrsken.tv.core.ui.theme.LocalPalette

/**
 * Framsteg som en norrskensgradient i stället för en platt stapel.
 * Används för tidslinje, "fortsätt titta" och import.
 */
@Composable
fun AuroraProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier = modifier
            .height(height)
            .background(palette.hairline, shape),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(listOf(palette.signal, palette.secondary)),
                    shape,
                ),
        )
    }
}

/**
 * Laddning utan känd längd. Ett ljusband som glider över stapeln — samma
 * rörelse som ljuset i startsekvensen, i stället för Materials snurra.
 *
 * Står stilla när rörelse är avstängd: då visas stapeln bara dämpat, så man
 * ändå ser att något pågår.
 */
@Composable
fun AuroraLoader(
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
) {
    val palette = LocalPalette.current
    val motion = LocalMotion.current
    val shape = RoundedCornerShape(height / 2)

    val sweep = if (motion.reduced) {
        0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "auroraLoader")
        val value by transition.animateFloat(
            initialValue = -0.3f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sweep",
        )
        value
    }

    Box(
        modifier = modifier
            .height(height)
            .background(palette.hairline, shape)
            .drawBehind {
                val band = size.width * 0.3f
                val x = sweep * size.width
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            palette.signal,
                            palette.secondary,
                            Color.Transparent,
                        ),
                        startX = x - band,
                        endX = x + band,
                    ),
                )
            },
    )
}

/**
 * Omslag som saknas. Titeln på en mörk norrskensyta — inte en grå ruta.
 *
 * Aldrig en annan titels bild. En felaktig affisch är värre än ingen, för
 * användaren tror på den.
 */
@Composable
fun ArtworkPlaceholder(title: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(palette.ambient)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(palette.signal.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.2f, size.height * 0.15f),
                        radius = size.maxDimension * 0.8f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(palette.secondary.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.9f, size.height * 0.9f),
                        radius = size.maxDimension * 0.7f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(metrics.spaceMd),
        )
    }
}

package se.norrsken.tv.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette

/** Subtilt filmiskt ljus ovanpå backdropen. Ingen tung blur/GPU-effekt. */
@Composable
fun CinematicLightField(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(palette.signal.copy(alpha = .13f), Color.Transparent),
                center = Offset(size.width * .78f, size.height * .28f),
                radius = size.maxDimension * .52f,
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(palette.secondary.copy(alpha = .07f), Color.Transparent),
                center = Offset(size.width * .58f, size.height * .70f),
                radius = size.maxDimension * .42f,
            )
        )
    }
}

/** Liten glaslik metadata-chip för premiumkänsla utan att bli plottrig. */
@Composable
fun MetaChip(text: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = .34f), RoundedCornerShape(50))
            .padding(horizontal = metrics.spaceSm, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = palette.textSecondary)
    }
}

@Composable
fun PremiumBadge(text: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier = modifier
            .background(palette.textPrimary.copy(alpha = .12f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = palette.textPrimary)
    }
}

package se.norrsken.tv.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette

/**
 * Appens två handlingsknappar.
 *
 * Den primära är ljus och fylld, den sekundära en dämpad yta. Skillnaden i
 * ljushet gör att ögat hittar huvudvalet först, utan att den andra blir
 * svår att se.
 */
@Composable
fun SecondaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    FocusableSurface(
        onClick = onClick,
        focusScale = 1.04f,
        cornerRadius = metrics.radiusCard,
        modifier = modifier,
    ) { focused ->
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) palette.focus else palette.textPrimary,
            modifier = Modifier.padding(horizontal = metrics.spaceLg, vertical = metrics.spaceSm),
        )
    }
}

/**
 * Huvudvalet. Ljus fylld yta med en spelikon — den ska gå att hitta på en
 * halv sekund från soffan, utan att läsa.
 */
@Composable
fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Boolean = true,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    FocusableSurface(
        onClick = onClick,
        focusScale = 1.04f,
        cornerRadius = metrics.radiusCard,
        // Egen ljus bakgrund nedan — ytan ska vara ljus, inte mörk som
        // FocusableSurfaces standardyta.
        background = false,
        modifier = modifier,
    ) { focused ->
        Row(
            modifier = Modifier
                .background(
                    color = if (focused) palette.focus else palette.textPrimary,
                    shape = RoundedCornerShape(metrics.radiusCard),
                )
                .padding(horizontal = metrics.spaceLg, vertical = metrics.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.spaceXs),
        ) {
            if (icon) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = palette.onAccent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = palette.onAccent,
            )
        }
    }
}

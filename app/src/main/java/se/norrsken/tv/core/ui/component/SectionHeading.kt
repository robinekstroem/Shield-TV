package se.norrsken.tv.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.core.ui.theme.LocalThemeStyle

/**
 * Rubrik over en rad eller sektion, i temats stil.
 *
 * I Titan: versaler och ett kort accentstreck fore. I de andra: lugn text.
 * Ett stalle for det, sa rubrikerna inte driver isar mellan skarmarna.
 */
@Composable
fun SectionHeading(title: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val style = LocalThemeStyle.current

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm),
    ) {
        if (style.headingAccentBar) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .background(palette.signal, RoundedCornerShape(1.dp))
            )
        }
        Text(
            text = if (style.uppercaseHeadings) title.uppercase() else title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
        )
    }
}

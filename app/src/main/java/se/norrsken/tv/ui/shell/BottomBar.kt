package se.norrsken.tv.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.navigation.Destination

/**
 * Mobilmenyn. Fem platser — resten (Favoriter, Sök, Inställningar) når man
 * inifrån respektive skärm. Åtta ikoner i en bottenrad på telefon blir
 * oträffbara.
 */
private val HandheldDestinations = listOf(
    Destination.Home,
    Destination.LiveTv,
    Destination.Guide,
    Destination.Movies,
    Destination.Series,
)

@Composable
fun BottomBar(
    current: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.surface)
            .navigationBarsPadding()
            .padding(vertical = metrics.spaceSm),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HandheldDestinations.forEach { destination ->
            val selected = destination == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(metrics.spaceXs),
                modifier = Modifier
                    .selectable(
                        selected = selected,
                        onClick = { onSelect(destination) },
                    )
                    .padding(horizontal = metrics.spaceSm, vertical = metrics.spaceXs),
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = null,
                    tint = if (selected) palette.textPrimary else palette.textTertiary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(destination.label),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) palette.textPrimary else palette.textTertiary,
                )
            }
        }
    }
}

package se.norrsken.tv.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * Telefonens översta rad.
 *
 * Finns för att bottenraden bara rymmer fem flikar. Favoriter, Sök och
 * Inställningar hamnar här — utan dem går det inte ens att lägga till en
 * spellista på en telefon.
 */
private val TopDestinations = listOf(
    Destination.Favorites,
    Destination.Search,
    Destination.Settings,
)

@Composable
fun TopBar(
    current: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(horizontal = metrics.spaceMd, vertical = metrics.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = stringResource(current.label),
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
            modifier = Modifier.weight(1f),
        )

        TopDestinations.forEach { destination ->
            IconButton(onClick = { onSelect(destination) }) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = stringResource(destination.label),
                    tint = if (destination == current) {
                        palette.textPrimary
                    } else {
                        palette.textTertiary
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

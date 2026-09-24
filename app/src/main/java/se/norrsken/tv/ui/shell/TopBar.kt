package se.norrsken.tv.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.navigation.Destination

private val TopDestinations = listOf(Destination.Favorites, Destination.Search, Destination.Settings)

/** Transparent chrome: artwork may continue behind the header instead of ending at a black bar. */
@Composable
fun TopBar(current: Destination, onSelect: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier.fillMaxWidth().background(Color.Transparent).padding(start = 20.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Text(stringResource(current.label), style = MaterialTheme.typography.titleLarge,
            color = palette.textPrimary, modifier = Modifier.weight(1f))
        TopDestinations.forEach { destination ->
            IconButton(onClick = { onSelect(destination) }, modifier = Modifier.size(42.dp)) {
                Icon(destination.icon, stringResource(destination.label),
                    tint = if (destination == current) palette.textPrimary else palette.textSecondary,
                    modifier = Modifier.size(21.dp))
            }
        }
    }
}

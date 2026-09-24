package se.norrsken.tv.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.navigation.Destination

private val HandheldDestinations = listOf(
    Destination.Home, Destination.LiveTv, Destination.Guide, Destination.Movies, Destination.Series,
)

/** Floating, glass-like mobile navigation inspired by premium streaming clients. */
@Composable
fun BottomBar(current: Destination, onSelect: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Box(
        modifier = modifier.fillMaxWidth().background(Color.Transparent).navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().background(palette.surface.copy(alpha = .96f), RoundedCornerShape(22.dp))
                .padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HandheldDestinations.forEach { destination ->
                val selected = destination == current
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.selectable(selected, onClick = { onSelect(destination) })
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (selected) Box(Modifier.size(width = 34.dp, height = 3.dp)
                            .align(Alignment.TopCenter).background(palette.signal, RoundedCornerShape(50)))
                        Icon(destination.icon, null,
                            tint = if (selected) palette.textPrimary else palette.textTertiary,
                            modifier = Modifier.padding(top = 6.dp).size(21.dp))
                    }
                    Text(stringResource(destination.label), style = MaterialTheme.typography.labelSmall,
                        color = if (selected) palette.textPrimary else palette.textTertiary)
                }
            }
        }
    }
}

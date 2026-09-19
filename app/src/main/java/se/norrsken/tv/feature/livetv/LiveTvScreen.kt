package se.norrsken.tv.feature.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import se.norrsken.tv.core.ui.component.EmptyState
import se.norrsken.tv.core.ui.component.FocusableSurface
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.data.db.entity.ChannelEntity

@Composable
fun LiveTvScreen(
    modifier: Modifier = Modifier,
    viewModel: LiveTvViewModel = hiltViewModel(),
) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val metrics = LocalMetrics.current

    if (channels.isEmpty()) {
        EmptyState(
            modifier = modifier,
            title = "Inga kanaler",
            body = "Lägg till en M3U-länk under Inställningar så fylls listan.",
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(metrics.screenEdge),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
    ) {
        items(channels, key = { it.id }) { channel ->
            ChannelRow(channel = channel, onClick = { /* Fas 3: öppnar spelaren */ })
        }
    }
}

@Composable
private fun ChannelRow(channel: ChannelEntity, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    FocusableSurface(onClick = onClick, modifier = Modifier.fillMaxWidth()) { _ ->
        Row(
            modifier = Modifier.padding(metrics.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
        ) {
            // Loggorna ligger på leverantörens server och saknas ofta.
            // Platshållaren är kanalnumret eller inget alls — aldrig en
            // annan kanals logga.
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logoUrl != null) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = channel.channelNumber?.toString() ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textTertiary,
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel.groupTitle != null) {
                    Text(
                        text = channel.groupTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

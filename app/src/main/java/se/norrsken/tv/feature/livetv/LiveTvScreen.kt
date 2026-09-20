package se.norrsken.tv.feature.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import se.norrsken.tv.core.device.LocalDeviceProfile
import se.norrsken.tv.core.ui.component.CategoryColumn
import se.norrsken.tv.core.ui.component.CategoryRow
import se.norrsken.tv.core.ui.component.EmptyState
import se.norrsken.tv.core.ui.component.FocusableSurface
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.data.db.entity.ChannelEntity

@Composable
fun LiveTvScreen(
    onOpenChannel: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LiveTvViewModel = hiltViewModel(),
) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val profile = LocalDeviceProfile.current
    val metrics = LocalMetrics.current

    if (channels.isEmpty() && categories.isEmpty()) {
        EmptyState(
            modifier = modifier,
            title = "Inga kanaler",
            body = "Lägg till en M3U-länk eller ett Xtream-konto under Inställningar.",
        )
        return
    }

    // TV: kategorierna i en egen kolumn, så D-paden går vänster/höger mellan
    // lista och kanaler. Telefon: en rad med chips, eftersom en sidokolumn
    // äter för mycket bredd på en liten skärm.
    if (profile.isTv) {
        Row(modifier = modifier.fillMaxSize().padding(metrics.screenEdge)) {
            CategoryColumn(
                categories = categories,
                selected = selected,
                onSelect = viewModel::select,
                allLabel = "Alla kanaler",
                modifier = Modifier.width(240.dp),
            )
            ChannelList(
                channels = channels,
                onOpenChannel = onOpenChannel,
                modifier = Modifier.padding(start = metrics.spaceLg),
            )
        }
    } else {
        Column(modifier = modifier.fillMaxSize().padding(metrics.screenEdge)) {
            CategoryRow(
                categories = categories,
                selected = selected,
                onSelect = viewModel::select,
                allLabel = "Alla",
            )
            ChannelList(
                channels = channels,
                onOpenChannel = onOpenChannel,
                modifier = Modifier.padding(top = metrics.spaceMd),
            )
        }
    }
}

@Composable
private fun ChannelList(
    channels: List<ChannelEntity>,
    onOpenChannel: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalMetrics.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
    ) {
        items(channels, key = { it.id }) { channel ->
            ChannelRow(channel = channel, onClick = { onOpenChannel(channel.id) })
        }

        // Tyst kapning ar samre an ett kort besked: utan den har raden ser
        // det ut som att kanalerna saknas i spellistan.
        if (channels.size >= CHANNEL_PAGE) {
            item { ListTruncated() }
        }
    }
}

const val CHANNEL_PAGE = 500

@Composable
private fun ListTruncated() {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    Text(
        text = "Visar de forsta $CHANNEL_PAGE. Valj en kategori for att se fler.",
        style = MaterialTheme.typography.labelMedium,
        color = palette.textTertiary,
        modifier = Modifier.padding(metrics.spaceMd),
    )
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
            // Platshållaren är kanalnumret — aldrig en annan kanals bild.
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

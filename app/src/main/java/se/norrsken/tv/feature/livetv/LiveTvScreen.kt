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
import se.norrsken.tv.core.ui.component.AuroraProgress
import se.norrsken.tv.core.ui.component.CategoryColumn
import se.norrsken.tv.core.ui.component.CategoryRow
import se.norrsken.tv.core.ui.component.EmptyState
import se.norrsken.tv.core.ui.component.FocusableSurface
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.data.db.entity.ChannelEntity
import se.norrsken.tv.data.epg.NowNext

@Composable
fun LiveTvScreen(
    onOpenChannel: (Long) -> Unit,
    onManageCategories: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LiveTvViewModel = hiltViewModel(),
) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val sortLabel by viewModel.channelSortLabel.collectAsStateWithLifecycle()
    val categorySortLabel by viewModel.categorySortLabel.collectAsStateWithLifecycle()
    val nowNext by viewModel.nowNext.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
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
                sortLabel = sortLabel,
                onCycleSort = viewModel::cycleChannelSort,
                categorySortLabel = categorySortLabel,
                onCycleCategorySort = viewModel::cycleCategorySort,
                onManage = onManageCategories,
                modifier = Modifier.width(240.dp),
            )
            ChannelList(
                channels = channels,
                nowNext = nowNext,
                now = now,
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
                sortLabel = sortLabel,
                onCycleSort = viewModel::cycleChannelSort,
                onManage = onManageCategories,
            )
            ChannelList(
                channels = channels,
                nowNext = nowNext,
                now = now,
                onOpenChannel = onOpenChannel,
                modifier = Modifier.padding(top = metrics.spaceMd),
            )
        }
    }
}

@Composable
private fun ChannelList(
    channels: List<ChannelEntity>,
    nowNext: Map<Long, NowNext>,
    now: Long,
    onOpenChannel: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalMetrics.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
    ) {
        items(channels, key = { it.id }) { channel ->
            ChannelRow(
                channel = channel,
                nowNext = nowNext[channel.id],
                now = now,
                onClick = { onOpenChannel(channel.id) },
            )
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
        text = "Visar de första $CHANNEL_PAGE. Välj en kategori för att se fler.",
        style = MaterialTheme.typography.labelMedium,
        color = palette.textTertiary,
        modifier = Modifier.padding(metrics.spaceMd),
    )
}

@Composable
private fun ChannelRow(
    channel: ChannelEntity,
    nowNext: NowNext?,
    now: Long,
    onClick: () -> Unit,
) {
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
                val current = nowNext?.current
                if (current != null) {
                    // Med tablå: vad som sänds, hur långt det kommit, vad som
                    // kommer sedan. Gruppnamnet ger ingenting man inte redan
                    // ser i kategorilistan.
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    nowNext.progress(now)?.let {
                        AuroraProgress(
                            fraction = it,
                            height = 2.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                        )
                    }
                    nowNext.next?.let { next ->
                        Text(
                            text = "Sedan: " + next.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (channel.groupTitle != null) {
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

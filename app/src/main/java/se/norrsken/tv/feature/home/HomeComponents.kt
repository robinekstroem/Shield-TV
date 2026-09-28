package se.norrsken.tv.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import se.norrsken.tv.core.device.LocalDeviceProfile
import se.norrsken.tv.core.ui.component.ArtworkPlaceholder
import se.norrsken.tv.core.ui.component.AuroraProgress
import se.norrsken.tv.core.ui.component.FocusableSurface
import se.norrsken.tv.core.ui.component.RailItem
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** En genväg till en avdelning. Färgen skiljer dem åt på håll. */
data class QuickTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
)

@Composable
fun QuickTileRow(tiles: List<QuickTile>, onOpen: (QuickTile) -> Unit) {
    val metrics = LocalMetrics.current
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = metrics.screenEdge,
            top = metrics.spaceXs,
            bottom = metrics.spaceXs,
        ),
    ) {
        items(tiles) { tile -> QuickTileCard(tile) { onOpen(tile) } }
    }
}

@Composable
private fun QuickTileCard(tile: QuickTile, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    FocusableSurface(
        onClick = onClick,
        cornerRadius = metrics.radiusCard,
        background = false,
        modifier = Modifier
            .width(if (LocalDeviceProfile.current.isTv) 232.dp else 168.dp)
            .height(if (LocalDeviceProfile.current.isTv) 92.dp else 74.dp),
    ) { focused ->
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    // Färgen tonar ut mot ytan — en fylld platta i full
                    // styrka hade tagit över hela skärmen.
                    Brush.horizontalGradient(
                        listOf(
                            tile.tint.copy(alpha = if (focused) 0.85f else 0.62f),
                            tile.tint.copy(alpha = 0.10f),
                        )
                    ),
                    RoundedCornerShape(metrics.radiusCard),
                )
                .padding(metrics.spaceMd),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
            ) {
                Icon(
                    imageVector = tile.icon,
                    contentDescription = null,
                    tint = palette.textPrimary,
                    modifier = Modifier.size(28.dp),
                )
                Column {
                    Text(
                        text = tile.title.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                        maxLines = 1,
                    )
                    Text(
                        text = tile.subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textPrimary.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Kanaler som kvadratiska brickor — logotypen är det man känner igen, inte
 * kanalnamnet. Namnet står under som stöd.
 */
@Composable
fun ChannelTileRow(channels: List<RailItem>, onOpen: (RailItem) -> Unit) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = metrics.screenEdge,
            top = metrics.spaceXs,
            bottom = metrics.spaceXs,
        ),
    ) {
        items(channels, key = { it.id }) { channel ->
            Column(
                modifier = Modifier.width(104.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FocusableSurface(
                    onClick = { onOpen(channel) },
                    cornerRadius = metrics.radiusCard,
                    glow = true,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                ) { _ ->
                    Box(Modifier.fillMaxSize().padding(metrics.spaceSm)) {
                        if (channel.imageUrl != null) {
                            AsyncImage(
                                model = channel.imageUrl,
                                contentDescription = channel.title,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            ArtworkPlaceholder(title = channel.title)
                        }
                    }
                }
                Text(
                    text = channel.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = metrics.spaceXs),
                )
            }
        }
    }
}

/**
 * Påbörjat: liggande kort med bild, vad det var och hur långt det är kvar.
 * Ett omslag i porträtt säger inte var man är — det gör progressraden.
 */
@Composable
fun ContinueRow(items: List<RailItem>, onOpen: (RailItem) -> Unit) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = metrics.screenEdge,
            top = metrics.spaceXs,
            bottom = metrics.spaceXs,
        ),
    ) {
        items(items, key = { it.id }) { item ->
            FocusableSurface(
                onClick = { onOpen(item) },
                cornerRadius = metrics.radiusCard,
                modifier = Modifier
            .width(if (LocalDeviceProfile.current.isTv) 320.dp else 260.dp)
            .height(96.dp),
            ) { _ ->
                Row(
                    modifier = Modifier.fillMaxSize().padding(metrics.spaceSm),
                    horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(16f / 9f)
                            .background(palette.ambient, RoundedCornerShape(6.dp)),
                    ) {
                        if (item.imageUrl != null) {
                            AsyncImage(
                                model = item.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(metrics.spaceXs),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.subtitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.textTertiary,
                                maxLines = 1,
                            )
                        }
                        item.progress?.let {
                            AuroraProgress(fraction = it, height = 3.dp, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/** Sökfältet och klockan. Klockan tickar varje halvminut, inget annat. */
@Composable
fun HomeTopBar(onSearch: () -> Unit) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = metrics.screenEdge, vertical = metrics.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
    ) {
        FocusableSurface(
            onClick = onSearch,
            focusScale = 1f,
            cornerRadius = 24.dp,
            // weight, inte fast bredd: 420 dp är bredare än en telefon, och
            // då fick klockan noll kvar och sattes en bokstav per rad.
            modifier = Modifier.weight(1f),
        ) { focused ->
            Text(
                text = "Sök efter kanaler, filmer, serier…",
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) palette.focus else palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = metrics.spaceLg, vertical = metrics.spaceSm),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = clock.format(Date(now)),
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = day.format(Date(now)),
                style = MaterialTheme.typography.labelMedium,
                color = palette.textTertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
private val day = SimpleDateFormat("EEE d MMM", Locale.getDefault())

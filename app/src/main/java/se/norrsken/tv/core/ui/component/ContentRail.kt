package se.norrsken.tv.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusRestorer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette

/**
 * En rad med innehall, som i en streamingtjanst.
 *
 * [progress] ritas bara nar den faktiskt finns. En tom progressrad pa varje
 * kort ser ut som att allt ar pabörjat, vilket ar fel information snyggt
 * presenterad — det ar samre an ingen information.
 */
data class RailItem(
    val id: Long,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val progress: Float? = null,
    val wide: Boolean = false,
    /** Avgör vad ett tryck ska göra: spela, eller öppna avsnittslistan. */
    val isSeries: Boolean = false,
    // Fälten nedan används av heron när kortet markeras, inte av kortet.
    val backdropUrl: String? = null,
    val overview: String? = null,
    /** Färdigbyggd faktarad. Tom när inget är känt — aldrig "2026 • •". */
    val meta: String? = null,
    val isLive: Boolean = false,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContentRail(
    title: String,
    items: List<RailItem>,
    onOpen: (RailItem) -> Unit,
    modifier: Modifier = Modifier,
    onMissingImage: (RailItem) -> Unit = {},
    onFocusItem: (RailItem) -> Unit = {},
) {
    if (items.isEmpty()) return

    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Column(modifier = modifier) {
        SectionHeading(
            title = title,
            modifier = Modifier.padding(
                start = metrics.screenEdge,
                bottom = metrics.spaceSm,
            ),
        )
        LazyRow(
            // focusRestorer: raden minns vilket kort som senast hade fokus
            // och återställer det när man kommer tillbaka nerifrån eller
            // från en detaljsida.
            modifier = Modifier.focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(metrics.railSpacing),
            contentPadding = PaddingValues(
                // Marginal bara till vänster: korten ska fortsätta ut ur
                // bilden åt höger, så det syns att raden har mer.
                start = metrics.screenEdge,
                // Luft uppåt och nedåt så att ett förstorat kort inte
                // klipps av radens kant.
                top = metrics.spaceSm,
                bottom = metrics.spaceSm,
            ),
        ) {
            items(items, key = { it.id }) { item ->
                RailCard(
                item = item,
                onOpen = onOpen,
                onMissingImage = onMissingImage,
                onFocusItem = onFocusItem,
            )
            }
        }
    }
}

@Composable
private fun RailCard(
    item: RailItem,
    onOpen: (RailItem) -> Unit,
    onMissingImage: (RailItem) -> Unit,
    onFocusItem: (RailItem) -> Unit,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    if (item.imageUrl == null) {
        LaunchedEffect(item.id) { onMissingImage(item) }
    }

    val width = if (item.wide) metrics.wideCardWidth else metrics.posterWidth
    val ratio = if (item.wide) 16f / 9f else 2f / 3f

    Column(Modifier.width(width)) {
        FocusableSurface(
            onClick = { onOpen(item) },
            focusScale = metrics.focusScalePoster,
            cornerRadius = metrics.radiusPoster,
            glow = true,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .onFocusChanged { if (it.isFocused) onFocusItem(item) },
        ) { _ ->
            Box(Modifier.fillMaxSize()) {
                if (item.imageUrl != null) {
                    AsyncImage(
                        model = item.imageUrl,
                        contentDescription = item.title,
                        // Omslag fyller rutan, logotyper far inte beskaras.
                        // En kanallogga i en 16:9-ruta med Crop blir uppblast
                        // och avkapad i kanterna.
                        contentScale = if (item.wide) {
                            ContentScale.Fit
                        } else {
                            ContentScale.Crop
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (item.wide) Modifier.padding(metrics.spaceMd) else Modifier
                            ),
                    )
                } else {
                    ArtworkPlaceholder(title = item.title)
                }

                item.progress?.let { value ->
                    AuroraProgress(
                        fraction = value,
                        height = 3.dp,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(),
                    )
                }
            }
        }

        Text(
            text = item.title,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = metrics.spaceSm),
        )
        if (item.subtitle != null) {
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

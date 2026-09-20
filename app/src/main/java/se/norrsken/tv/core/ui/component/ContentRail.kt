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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
)

@Composable
fun ContentRail(
    title: String,
    items: List<RailItem>,
    onOpen: (RailItem) -> Unit,
    modifier: Modifier = Modifier,
    onMissingImage: (RailItem) -> Unit = {},
) {
    if (items.isEmpty()) return

    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
            modifier = Modifier.padding(
                start = metrics.screenEdge,
                bottom = metrics.spaceSm,
            ),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(metrics.railSpacing),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = metrics.screenEdge,
            ),
        ) {
            items(items, key = { it.id }) { item ->
                RailCard(item = item, onOpen = onOpen, onMissingImage = onMissingImage)
            }
        }
    }
}

@Composable
private fun RailCard(
    item: RailItem,
    onOpen: (RailItem) -> Unit,
    onMissingImage: (RailItem) -> Unit,
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
            modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
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
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textTertiary,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(metrics.spaceSm),
                    )
                }

                item.progress?.let { value ->
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(palette.hairline)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(value.coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(palette.signal, RoundedCornerShape(2.dp))
                        )
                    }
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

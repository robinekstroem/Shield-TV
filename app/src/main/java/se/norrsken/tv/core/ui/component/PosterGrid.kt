package se.norrsken.tv.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette

data class PosterItem(
    val id: Long,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
)

/**
 * Rutnät med omslag i 2:3 — formatet nästan alla filmaffischer har.
 *
 * Saknas omslaget visas titeln i en tom ram. Aldrig en slumpmässig bild och
 * aldrig en annan titels omslag: en felaktig affisch är värre än ingen, för
 * användaren tror på den.
 */
@Composable
fun PosterGrid(
    items: List<PosterItem>,
    onOpen: (PosterItem) -> Unit,
    modifier: Modifier = Modifier,
    onMissingPoster: (PosterItem) -> Unit = {},
) {
    val metrics = LocalMetrics.current

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = metrics.posterWidth),
        modifier = modifier.fillMaxSize().padding(metrics.screenEdge),
        horizontalArrangement = Arrangement.spacedBy(metrics.railSpacing),
        verticalArrangement = Arrangement.spacedBy(metrics.railSpacing),
    ) {
        items(items.size, key = { items[it].id }) { index ->
            PosterCard(
                item = items[index],
                onOpen = onOpen,
                onMissingPoster = onMissingPoster,
            )
        }
    }
}

@Composable
private fun PosterCard(
    item: PosterItem,
    onOpen: (PosterItem) -> Unit,
    onMissingPoster: (PosterItem) -> Unit,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    // Kortet är komponerat, alltså syns det eller är strax på väg in.
    // Först då är det värt ett nätanrop — och bara en gång per titel,
    // eftersom nyckeln är id:t och posterUrl slutar vara null efteråt.
    if (item.posterUrl == null) {
        LaunchedEffect(item.id) { onMissingPoster(item) }
    }

    Column {
        FocusableSurface(
            onClick = { onOpen(item) },
            focusScale = metrics.focusScalePoster,
            cornerRadius = metrics.radiusPoster,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
        ) { _ ->
            if (item.posterUrl != null) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().padding(metrics.spaceSm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textTertiary,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
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
            )
        }
    }
}

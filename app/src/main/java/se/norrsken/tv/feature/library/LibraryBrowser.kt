package se.norrsken.tv.feature.library

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import se.norrsken.tv.core.device.LocalDeviceProfile
import se.norrsken.tv.core.ui.component.CategoryColumn
import se.norrsken.tv.core.ui.component.CategoryRow
import se.norrsken.tv.core.ui.component.EmptyState
import se.norrsken.tv.core.ui.component.PosterGrid
import se.norrsken.tv.core.ui.component.PosterItem
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.core.ui.theme.LocalThemeStyle
import se.norrsken.tv.data.db.entity.CategoryEntity

/**
 * Bläddringsvyn för filmer och serier.
 *
 * Tre saker gör skillnad mot ett rent rutnät:
 *
 * Rummet reagerar. Markerar man en titel byts bakgrunden mot dess backdrop
 * och fakta visas överst — man ser vad något är utan att öppna det.
 *
 * Filtret. Med tiotusen titlar är bläddring inte ett sätt att hitta något
 * bestämt. Att skriva tre bokstäver är det.
 *
 * Fler sidor. Rutnätet hämtar mer när slutet närmar sig, i stället för att
 * tyst sluta vid första sidan.
 */
@Composable
fun LibraryBrowser(
    items: List<PosterItem>,
    categories: List<CategoryEntity>,
    selectedCategory: Long?,
    onSelectCategory: (Long?) -> Unit,
    sortLabel: String,
    onCycleSort: () -> Unit,
    categorySortLabel: String,
    onCycleCategorySort: () -> Unit,
    onManageCategories: () -> Unit,
    filter: String,
    onFilterChange: (String) -> Unit,
    onOpen: (PosterItem) -> Unit,
    onMissingPoster: (PosterItem) -> Unit,
    onNeedMore: () -> Unit,
    allLabel: String,
    filterLabel: String,
    emptyTitle: String,
    emptyBody: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val isTv = LocalDeviceProfile.current.isTv

    var focusedItem by remember { mutableStateOf<PosterItem?>(null) }
    // Markeringen kan peka på en titel som filtrerats bort. Visa bara den
    // som fortfarande finns i listan.
    val highlighted = focusedItem?.takeIf { current -> items.any { it.id == current.id } }

    Box(modifier = modifier.fillMaxSize()) {
        // Backdropen ligger bakom allt och byts mjukt. Kort övergång: den
        // ska följa bläddringen, inte släpa efter den.
        Crossfade(
            targetState = highlighted?.backdropUrl,
            animationSpec = tween(400),
            label = "browserBackdrop",
        ) { url ->
            if (url != null) {
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Utan de här två skulle texten ligga direkt på en
                    // okontrollerad bild och bli oläsbar på ljusa motiv.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(palette.scrim, palette.background),
                                )
                            )
                    )
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            // TV får en reaktiv informationsyta ovanför biblioteket. På mobil
            // finns ingen D-pad-fokus och ytan blev därför bara ett stort tomt
            // svart hål innan kategorierna. Mobilen går direkt på innehållet.
            if (isTv) HighlightHeader(item = highlighted)

            if (isTv) {
                Row(Modifier.fillMaxSize()) {
                    CategoryColumn(
                        categories = categories,
                        selected = selectedCategory,
                        onSelect = onSelectCategory,
                        allLabel = allLabel,
                        sortLabel = sortLabel,
                        onCycleSort = onCycleSort,
                        categorySortLabel = categorySortLabel,
                        onCycleCategorySort = onCycleCategorySort,
                        onManage = onManageCategories,
                        modifier = Modifier
                            .width(240.dp)
                            .padding(start = metrics.screenEdge),
                    )
                    Column(Modifier.fillMaxSize()) {
                        FilterField(filter, onFilterChange, filterLabel)
                        Content(items, onOpen, onMissingPoster, onNeedMore, { focusedItem = it },
                            emptyTitle, emptyBody, filter)
                    }
                }
            } else {
                CategoryRow(
                    categories = categories,
                    selected = selectedCategory,
                    onSelect = onSelectCategory,
                    allLabel = allLabel,
                    sortLabel = sortLabel,
                    onCycleSort = onCycleSort,
                    onManage = onManageCategories,
                    modifier = Modifier.padding(horizontal = metrics.screenEdge, vertical = 4.dp),
                )
                FilterField(filter, onFilterChange, filterLabel, compact = true)
                Content(items, onOpen, onMissingPoster, onNeedMore, { focusedItem = it },
                    emptyTitle, emptyBody, filter)
            }
        }
    }
}

@Composable
private fun Content(
    items: List<PosterItem>,
    onOpen: (PosterItem) -> Unit,
    onMissingPoster: (PosterItem) -> Unit,
    onNeedMore: () -> Unit,
    onFocusItem: (PosterItem) -> Unit,
    emptyTitle: String,
    emptyBody: String,
    filter: String,
) {
    if (items.isEmpty()) {
        // Tomt på grund av filtret är något annat än tomt bibliotek.
        if (filter.isNotBlank()) {
            EmptyState(title = "Inga träffar", body = "Ingen titel innehåller \"$filter\".")
        } else {
            EmptyState(title = emptyTitle, body = emptyBody)
        }
        return
    }
    PosterGrid(
        items = items,
        onOpen = onOpen,
        onMissingPoster = onMissingPoster,
        onFocusItem = onFocusItem,
        onNeedMore = onNeedMore,
    )
}

/**
 * Fakta om den markerade titeln. Fast höjd — annars hoppar rutnätet varje
 * gång man flyttar sig mellan en film med beskrivning och en utan.
 */
@Composable
private fun HighlightHeader(item: PosterItem?) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val style = LocalThemeStyle.current
    val isTv = LocalDeviceProfile.current.isTv

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isTv) 168.dp else 118.dp)
            .padding(horizontal = metrics.screenEdge, vertical = metrics.spaceMd)
            .widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceXs, Alignment.Bottom),
    ) {
        if (item == null) return@Column
        Text(
            text = if (style.uppercaseHeadings) item.title.uppercase() else item.title,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val facts = listOfNotNull(item.subtitle, item.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) })
        if (facts.isNotEmpty()) {
            Text(
                text = facts.joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = palette.signal,
            )
        }
        item.overview?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FilterField(value: String, onChange: (String) -> Unit, label: String, compact: Boolean = false) {
    val metrics = LocalMetrics.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // Smalt fält, inte en låda tvärs över skärmen. Ett sökfält ska
        // finnas till hands, inte dominera vyn.
        modifier = Modifier
            .then(if (compact) Modifier.fillMaxWidth() else Modifier.widthIn(max = 380.dp))
            .padding(horizontal = metrics.screenEdge, vertical = if (compact) 4.dp else metrics.spaceSm),
    )
}

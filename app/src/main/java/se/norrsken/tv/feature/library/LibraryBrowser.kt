package se.norrsken.tv.feature.library

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import se.norrsken.tv.core.device.LocalDeviceProfile
import se.norrsken.tv.core.ui.component.CategoryColumn
import se.norrsken.tv.core.ui.component.CategoryRow
import se.norrsken.tv.core.ui.component.EmptyState
import se.norrsken.tv.core.ui.component.PosterGrid
import se.norrsken.tv.core.ui.component.PosterItem
import se.norrsken.tv.core.ui.component.PrimaryAction
import se.norrsken.tv.core.ui.component.SecondaryAction
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.core.ui.theme.LocalPreferences
import se.norrsken.tv.core.ui.theme.LocalThemeStyle
import se.norrsken.tv.data.db.entity.CategoryEntity

/**
 * Bläddringsvyn för filmer och serier.
 *
 * Kategorierna står i en kolumn till vänster, innehållet till höger: överst
 * det markerade — titel, fakta, skådespelare, regissör, beskrivning och
 * knappar — och under det rutnätet.
 *
 * Kolumnen kostar bredd. Med den kvar ryms fem till sex omslag per rad på
 * 1080p i stället för åtta; antalet är en inställning.
 */
@OptIn(FlowPreview::class)
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
    onPlay: (PosterItem) -> Unit,
    onTrailer: (PosterItem) -> Unit,
    onMissingPoster: (PosterItem) -> Unit,
    onNeedMore: () -> Unit,
    /** Anropas när markeringen stått still en stund — hämtar detaljer. */
    onFocusHold: (Long) -> Unit,
    /** Antal i kategorin enligt databasen, inte antal inlästa. */
    total: Int,
    allLabel: String,
    filterLabel: String,
    emptyTitle: String,
    emptyBody: String,
    /** Serier spelas inte direkt — man väljer avsnitt först. */
    isSeries: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val isTv = LocalDeviceProfile.current.isTv
    val columns = LocalPreferences.current.gridColumns

    // Markeringen ligger i ett tillståndsobjekt som skärmens kropp aldrig
    // läser. Läste den värdet här skulle varje fokusflytt rita om hela
    // skärmen — rutnätet, sökfältet och alla rader — bara för att
    // markeringen flyttat ett steg. Nu ritas bara de delar om som visar den.
    val focusedState = remember { mutableStateOf<PosterItem?>(null) }
    val heroState = remember { mutableStateOf<PosterItem?>(null) }
    val categoryName = categories.firstOrNull { it.id == selectedCategory }?.name ?: allLabel

    // Paus innan bilden byts. Utan den startar en ny bildhämtning per
    // affisch man passerar — åtta i sekunden när man håller höger.
    LaunchedEffect(Unit) {
        snapshotFlow { focusedState.value }
            .debounce(200)
            .collect { heroState.value = it }
    }

    // Detaljerna hämtas först när markeringen verkligen stannat.
    LaunchedEffect(Unit) {
        snapshotFlow { focusedState.value }
            .debounce(600)
            .collect { item -> item?.id?.let(onFocusHold) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Crossfade(
            targetState = heroState.value?.backdropUrl,
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
                    // Utan mörkningen hamnar texten direkt på en okontrollerad
                    // bild och blir oläsbar på ljusa motiv.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(listOf(palette.scrim, palette.scrimClear))
                            )
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.35f to palette.scrimClear,
                                    1f to palette.background,
                                )
                            )
                    )
                }
            }
        }

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
                        .width(224.dp)
                        .fillMaxHeight()
                        .padding(start = metrics.screenEdge),
                )
                Column(Modifier.fillMaxSize()) {
                    HighlightHeader(heroState, items, isSeries, onPlay, onOpen, onTrailer)
                    GridHeader(categoryName, heroState, items, total, filter, onFilterChange, filterLabel)
                    Content(items, onOpen, onMissingPoster, onNeedMore, { focusedState.value = it },
                        emptyTitle, emptyBody, filter, columns)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                CategoryRow(
                    categories = categories,
                    selected = selectedCategory,
                    onSelect = onSelectCategory,
                    allLabel = allLabel,
                    sortLabel = sortLabel,
                    onCycleSort = onCycleSort,
                    onManage = onManageCategories,
                    modifier = Modifier.padding(horizontal = metrics.screenEdge),
                )
                HighlightHeader(heroState, items, isSeries, onPlay, onOpen, onTrailer)
                GridHeader(categoryName, heroState, items, total, filter, onFilterChange, filterLabel)
                Content(items, onOpen, onMissingPoster, onNeedMore, { focusedState.value = it },
                    emptyTitle, emptyBody, filter, columns)
            }
        }
    }
}

/**
 * Den markerade titeln: vad den är, och vad man kan göra med den.
 *
 * Fast höjd — annars hoppar rutnätet varje gång man rör sig mellan en titel
 * med beskrivning och en utan. Knapparna nås genom att gå uppåt från
 * rutnätets första rad, och markeringen ligger kvar medan man står på dem.
 */
@Composable
private fun HighlightHeader(
    heroState: State<PosterItem?>,
    items: List<PosterItem>,
    isSeries: Boolean,
    onPlay: (PosterItem) -> Unit,
    onOpen: (PosterItem) -> Unit,
    onTrailer: (PosterItem) -> Unit,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val style = LocalThemeStyle.current
    val isTv = LocalDeviceProfile.current.isTv

    // Markeringen kan peka på en titel som filtrerats bort.
    val item = heroState.value?.takeIf { current -> items.any { it.id == current.id } }

    // Fast höjd på TV: där finns alltid en markering, och utan fast höjd
    // hoppar rutnätet mellan titlar med och utan beskrivning. På en telefon
    // finns ingen markering alls — då ska ytan inte finnas, i stället för
    // att stå tom och skjuta ner allt annat.
    if (!isTv && item == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isTv) 170.dp else 168.dp)
            .padding(horizontal = metrics.screenEdge, vertical = metrics.spaceMd)
            .widthIn(max = 820.dp),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceXs, Alignment.Bottom),
    ) {
        if (item == null) return@Column

        Text(
            text = if (style.uppercaseHeadings) item.title.uppercase() else item.title,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        val facts = listOfNotNull(
            item.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
            item.subtitle,
        )
        if (facts.isNotEmpty()) {
            Text(
                text = facts.joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = palette.signal,
            )
        }

        // Skådespelare och regissör kommer från leverantören och saknas för
        // serier. Raden visas bara när det finns något att visa.
        item.cast?.takeIf { it.isNotBlank() }?.let {
            CreditRow("Skådespelare", it)
        }
        item.director?.takeIf { it.isNotBlank() }?.let {
            CreditRow("Regissör", it)
        }

        item.overview?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
                // Två rader, inte tre: höjden går till rutnätet i stället.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.padding(top = metrics.spaceXs),
            horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
        ) {
            PrimaryAction(
                label = if (isSeries) "Visa avsnitt" else "Spela",
                onClick = { if (isSeries) onOpen(item) else onPlay(item) },
            )
            // Trailerknappen finns bara när leverantören skickat en.
            if (item.trailerUrl != null) {
                SecondaryAction(label = "Trailer", onClick = { onTrailer(item) })
            }
            SecondaryAction(label = "Mer info", onClick = { onOpen(item) })
        }
    }
}

@Composable
private fun CreditRow(label: String, value: String) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    Row(horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textTertiary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Kategorins namn, var man är i den, och filtret. */
@Composable
private fun GridHeader(
    categoryName: String,
    heroState: State<PosterItem?>,
    items: List<PosterItem>,
    total: Int,
    filter: String,
    onFilterChange: (String) -> Unit,
    filterLabel: String,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val isTv = LocalDeviceProfile.current.isTv
    val position = heroState.value
        ?.let { current -> items.indexOfFirst { it.id == current.id } + 1 }
        ?: 0

    // På en telefon får namn, filter och räknare inte plats bredvid
    // varandra — räknaren pressades till fyra tecken per rad. Filtret får
    // en egen rad där.
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = metrics.screenEdge)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
        ) {
            // På TV står kategorierna i kolumnen till vänster och namnet
            // behövs här. På telefonen står det redan som markerad knapp i
            // raden ovanför — samma ord två gånger på två rader.
            if (isTv) {
                Text(
                    text = categoryName,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(Modifier.weight(1f))
            }
            if (isTv) {
                SearchField(filter, onFilterChange, filterLabel, Modifier.widthIn(max = 320.dp))
            }
            if (total > 0) {
                Text(
                    text = if (position > 0) "$position / $total" else "$total",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (!isTv) {
            SearchField(
                filter, onFilterChange, filterLabel,
                Modifier.fillMaxWidth().padding(top = metrics.spaceXs),
            )
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier,
    )
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
    columns: Int,
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
        columns = columns,
    )
}

package se.norrsken.tv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.CollectionSlot
import se.norrsken.tv.desktop.data.CollectionKeys
import se.norrsken.tv.desktop.data.DisneyIndex
import se.norrsken.tv.desktop.data.DisneyShelf
import se.norrsken.tv.desktop.data.Franchise
import se.norrsken.tv.desktop.data.Movie

/** Ett kort i galleriet: en filmserie, eller en av Disneys samlingar. */
internal class CollectionCard(
    val id: String,
    /** Kategorin som öppnas: samlingens nyckel, eller Disneys. */
    val group: String,
    val title: String,
    val tagline: String,
    val owned: Int,
    /** Hur många filmer serien har. Null för kortfilmerna, som inte har något slut. */
    val total: Int?,
    val covers: List<List<String>>,
    /** Filmen vars bakgrundsbild står överst medan kortet är markerat. */
    val feature: Movie?,
    val from: Color,
    val to: Color,
    val accent: Color,
)

/** Galleriets kort: filmserierna man har något ur, med Disneys tre efter Marvel och Star Wars. */
internal fun collectionCards(state: AppState): List<CollectionCard> {
    val index = state.collections
    val franchises = index.present().map { franchise ->
        val owned = index.ownedIn(franchise)
        val inOrder = franchise.films.indices.mapNotNull { owned[it] }
        CollectionCard(
            id = franchise.id,
            group = franchise.key,
            title = franchise.title,
            tagline = franchise.tagline,
            owned = owned.size,
            total = franchise.films.size,
            covers = inOrder.asSequence().map { state.posterCandidates(it) }.filter { it.isNotEmpty() }.take(3).toList(),
            feature = inOrder.firstOrNull(),
            from = Color(franchise.from),
            to = Color(franchise.to),
            accent = Color(franchise.accent),
        )
    }
    val disney = DisneyShelf.entries.mapNotNull { shelf ->
        val count = state.disney.counts[shelf] ?: 0
        if (count == 0) return@mapNotNull null
        val look = shelfLook(shelf)
        CollectionCard(
            id = disneyCardId(shelf),
            group = shelf.key,
            title = shelf.title,
            tagline = when (shelf) {
                DisneyShelf.CLASSICS -> "De tecknade klassikerna sedan 1937"
                DisneyShelf.PIXAR -> "Från Toy Story och framåt"
                DisneyShelf.SHORTS -> "Kortfilmerna från 30-talet och framåt"
            },
            owned = count,
            total = when (shelf) {
                DisneyShelf.CLASSICS -> DisneyIndex.CLASSICS_TOTAL
                DisneyShelf.PIXAR -> DisneyIndex.PIXAR_TOTAL
                DisneyShelf.SHORTS -> null
            },
            covers = state.disneyCovers(shelf, 3),
            feature = state.disneyFeature(shelf),
            from = look.from,
            to = look.to,
            accent = look.accent,
        )
    }
    val at = minOf(2, franchises.size)
    return franchises.take(at) + disney + franchises.drop(at)
}

internal fun disneyCardId(shelf: DisneyShelf): String = "disney-" + shelf.name

/** Kortet i galleriet för en samling eller en av Disneys: dit markeringen går tillbaka. */
internal fun collectionCardId(group: String?): String? {
    CollectionKeys.franchiseOf(group)?.let { return it.id }
    if (!DisneyShelf.isDisney(group)) return null
    return disneyCardId(DisneyShelf.of(group) ?: DisneyShelf.CLASSICS)
}

/** Samlingar överst bland kategorierna i Filmer. Bara om det finns någon. */
internal fun collectionsPinned(state: AppState): List<PinnedGroup> {
    val count = state.collections.present().size + DisneyShelf.entries.count { (state.disney.counts[it] ?: 0) > 0 }
    return if (count > 0) listOf(PinnedGroup(CollectionKeys.GALLERY, "Samlingar", count)) else emptyList()
}

/**
 * Galleriet: ett stort kort per filmserie, i seriens färger med ens egna
 * omslag i en solfjäder och hur många av filmerna man har. Överst den
 * markerade seriens bild och namn.
 */
@Composable
internal fun CollectionsGallery(state: AppState, gridState: LazyGridState, entry: TvEntry, modifier: Modifier = Modifier) {
    val cards = remember(state.collections, state.disney, state.library, state.versions) { collectionCards(state) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var hoveredId by remember { mutableStateOf<String?>(null) }
    val activeId = if (tvLayout) focusedId else hoveredId
    var spotlightId by remember(cards) { mutableStateOf(cards.firstOrNull()?.id) }
    LaunchedEffect(activeId) {
        val id = activeId ?: return@LaunchedEffect
        delay(if (tvLayout) 180 else 260)
        spotlightId = id
    }
    val spotlight = cards.firstOrNull { it.id == spotlightId } ?: cards.firstOrNull()
    Box(modifier.fillMaxSize()) {
        val feature = spotlight?.feature
        if (feature != null) {
            SpotlightBackdrop(
                url = state.tmdbFor(feature)?.backdrop,
                fallback = state.posterCandidates(feature).firstOrNull(),
                running = state.nowPlaying == null || state.miniPlayer,
            )
        }
        Column(Modifier.fillMaxSize()) {
            CollectionsBar(title = "Samlingar", detail = if (cards.size == 1) "1 samling" else "${cards.size} samlingar")
            if (spotlight != null) CollectionHero(spotlight)
            if (cards.isEmpty()) {
                EmptyState(
                    title = "Inga samlingar än",
                    body = "Appen letar efter Marvel, Star Wars, Harry Potter, James Bond och ett trettiotal andra filmserier i biblioteket, men hittade ingen av dem.",
                )
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TvScrolling(lead = 0.05f, trail = 0.08f, snap = true) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (tvLayout) 440.dp else 330.dp),
                            state = gridState,
                            contentPadding = if (tvLayout) {
                                PaddingValues(start = 40.dp, end = 40.dp, top = 14.dp, bottom = 40.dp)
                            } else {
                                PaddingValues(start = 20.dp, end = 28.dp, top = 6.dp, bottom = 28.dp)
                            },
                            horizontalArrangement = Arrangement.spacedBy(if (tvLayout) 22.dp else 14.dp),
                            verticalArrangement = Arrangement.spacedBy(if (tvLayout) 22.dp else 14.dp),
                        ) {
                            items(cards, key = { it.id }) { card ->
                                CollectionTile(
                                    card = card,
                                    modifier = entry.mark(card.id).onFocusChanged {
                                        if (it.isFocused) {
                                            focusedId = card.id
                                            entry.last = card.id
                                        }
                                    },
                                    onActive = { if (!tvLayout) hoveredId = card.id },
                                    onClick = { state.openCollection(card.group) },
                                )
                            }
                        }
                    }
                    Scrollbar(gridState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 6.dp, bottom = 12.dp))
                }
            }
        }
    }
}

/**
 * En samling: hela serien i ordning, med numret i hörnet. Filmerna man
 * saknar står nedtonade på sin plats. Överst delarna (som Marvels faser) och,
 * där det finns, valet mellan utgivningens och berättelsens ordning.
 */
@Composable
internal fun CollectionPage(
    state: AppState,
    franchise: Franchise,
    gridState: LazyGridState,
    entry: TvEntry,
    restore: FocusRequester,
    openedKey: String?,
    onOpen: (Movie) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val slots = remember(state.collections, franchise, state.collectionPart, state.collectionStory, state.versions) {
        state.collectionSlots(franchise)
    }
    val owned = state.collections.count(franchise)
    val accent = Color(franchise.accent)
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var hoveredKey by remember { mutableStateOf<String?>(null) }
    val activeKey = if (tvLayout) focusedKey else hoveredKey
    var spotlightKey by remember(slots) { mutableStateOf<String?>(null) }
    LaunchedEffect(activeKey) {
        val key = activeKey ?: return@LaunchedEffect
        delay(if (tvLayout) 180 else 260)
        spotlightKey = key
    }
    val spotlight = slots.firstOrNull { slotKey(franchise, it) == spotlightKey }
        ?: slots.firstOrNull { it.movie != null }
        ?: slots.firstOrNull()
    Box(modifier.fillMaxSize()) {
        val movie = spotlight?.movie
        if (movie != null) {
            SpotlightBackdrop(
                url = state.tmdbFor(movie)?.backdrop,
                fallback = state.posterCandidates(movie).firstOrNull(),
                running = state.nowPlaying == null || state.miniPlayer,
            )
        } else {
            // Saknas filmen: seriens färg som ett sken uppe till höger.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (tvLayout) 600.dp else 440.dp)
                    .drawBehind {
                        drawRect(
                            Brush.radialGradient(
                                listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                                center = Offset(size.width * 0.8f, 0f),
                                radius = size.width * 0.6f,
                            ),
                        )
                    },
            )
        }
        Column(Modifier.fillMaxSize()) {
            CollectionsBar(title = franchise.title, detail = "$owned av ${franchise.films.size} filmer") {
                if (franchise.story != null) {
                    Chip("Utgivning", selected = !state.collectionStory, onClick = { state.collectionStory = false })
                    Chip("Berättelsen", selected = state.collectionStory, onClick = { state.collectionStory = true })
                }
            }
            if (franchise.parts.size > 1) {
                Row(
                    Modifier.fillMaxWidth().padding(start = if (tvLayout) 40.dp else 22.dp, end = 28.dp, top = 8.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip("Alla", selected = state.collectionPart == null, onClick = { state.collectionPart = null })
                    franchise.parts.forEachIndexed { index, part ->
                        Chip(part.title, selected = state.collectionPart == index, onClick = { state.collectionPart = index })
                    }
                }
            }
            if (spotlight != null) SlotInfo(state, spotlight)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TvScrolling(lead = 0.05f, trail = 0.08f, snap = true) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (tvLayout) TV_POSTER_MIN else 152.dp),
                        state = gridState,
                        contentPadding = if (tvLayout) {
                            PaddingValues(start = 40.dp, end = 40.dp, top = 14.dp, bottom = 40.dp)
                        } else {
                            PaddingValues(start = 20.dp, end = 28.dp, bottom = 28.dp)
                        },
                        horizontalArrangement = Arrangement.spacedBy(if (tvLayout) 16.dp else 8.dp),
                        verticalArrangement = Arrangement.spacedBy(if (tvLayout) 20.dp else 12.dp),
                    ) {
                        items(slots, key = { slotKey(franchise, it) }) { slot ->
                            val key = slotKey(franchise, slot)
                            val track = Modifier
                                .then(entry.mark(key))
                                .onFocusChanged {
                                    if (it.hasFocus) {
                                        focusedKey = key
                                        entry.last = key
                                    }
                                }
                            val film = slot.movie
                            if (film != null) {
                                PosterCard(
                                    title = state.titleOf(film),
                                    images = state.posterCandidates(film),
                                    meta = movieMeta(state, film),
                                    onClick = { onOpen(film) },
                                    modifier = (if (film.key == openedKey) Modifier.focusRequester(restore) else Modifier).then(track),
                                    onActive = { if (!tvLayout) hoveredKey = key },
                                    badge = state.badgeFor(film),
                                    showTitle = !tvLayout,
                                    order = slot.number,
                                )
                            } else {
                                MissingPoster(slot, accent, modifier = track, onActive = { if (!tvLayout) hoveredKey = key })
                            }
                        }
                    }
                }
                Scrollbar(gridState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 6.dp, bottom = 12.dp))
            }
        }
    }
}

private fun slotKey(franchise: Franchise, slot: CollectionSlot): String =
    slot.movie?.key ?: ("saknas-" + franchise.id + "-" + franchise.positionOf(slot.film))

/** Överst i en samling: filmen man står på, med numret i ordningen. Saknas den sägs det. */
@Composable
private fun SlotInfo(state: AppState, slot: CollectionSlot) {
    val palette = LocalPalette.current
    val movie = slot.movie
    if (movie != null) {
        val tmdb = state.tmdbFor(movie)
        val facts = state.movieFacts[movie.key]
        SpotlightInfo(
            state = state,
            kind = "movie",
            tmdbId = tmdb?.id,
            title = state.titleOf(movie),
            meta = listOfNotNull(
                "Nr ${slot.number}",
                (tmdb?.year ?: facts?.year ?: slot.film.year).toString(),
                formatRating(tmdb?.rating ?: movie.rating),
                tmdb?.genres?.ifBlank { null } ?: facts?.genre?.ifBlank { null },
            ).joinToString("  ·  "),
            overview = tmdb?.overview?.ifBlank { null },
        )
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .height(if (tvLayout) 238.dp else 176.dp)
            .padding(start = if (tvLayout) 46.dp else 24.dp, end = 36.dp, top = if (tvLayout) 12.dp else 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (tvLayout) 9.dp else 6.dp),
    ) {
        Text(
            slot.film.title,
            style = if (tvLayout) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Nr ${slot.number}  ·  ${slot.film.year}",
            style = if (tvLayout) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            color = palette.focus,
        )
        Text(
            "Finns inte i ditt bibliotek.",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.textTertiary,
        )
    }
}

/** Raden överst: namnet, hur mycket man har, och till höger val som ordningen. */
@Composable
private fun CollectionsBar(title: String, detail: String, trailing: @Composable RowScope.() -> Unit = {}) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(
            start = if (tvLayout) 46.dp else 28.dp,
            end = if (tvLayout) 40.dp else 28.dp,
            top = if (tvLayout) 22.dp else 24.dp,
            bottom = 2.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.6.sp),
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(detail, style = MaterialTheme.typography.labelLarge, color = palette.textTertiary, maxLines = 1)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** Överst i galleriet: den markerade seriens namn, undertitel och hur mycket man har. */
@Composable
private fun CollectionHero(card: CollectionCard) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(if (tvLayout) 238.dp else 176.dp)
            .padding(start = if (tvLayout) 46.dp else 24.dp, end = 36.dp, top = if (tvLayout) 18.dp else 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (tvLayout) 10.dp else 6.dp),
    ) {
        Text(
            card.title,
            style = if (tvLayout) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            card.tagline,
            style = if (tvLayout) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            color = card.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val total = card.total
            if (total != null && total > 0) OwnedBar(card.owned.toFloat() / total, card.accent, if (tvLayout) 260.dp else 180.dp)
            Text(ownedText(card), style = MaterialTheme.typography.labelLarge, color = LocalPalette.current.textSecondary)
        }
    }
}

private fun ownedText(card: CollectionCard): String {
    val total = card.total
    return when {
        total == null -> if (card.owned == 1) "1 titel i ditt bibliotek" else "${card.owned} titlar i ditt bibliotek"
        card.owned >= total -> "Hela samlingen, alla $total"
        else -> "${card.owned} av $total i ditt bibliotek"
    }
}

/** Hur mycket av serien man har, som en tunn stapel i seriens färg. */
@Composable
private fun OwnedBar(fraction: Float, color: Color, width: Dp) {
    Box(
        Modifier
            .width(width)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.16f)),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

/** Ett kort i galleriet: seriens färger, ett sken nere till vänster, omslagen i en solfjäder till höger. */
@Composable
private fun CollectionTile(card: CollectionCard, modifier: Modifier, onActive: () -> Unit, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    Pressable(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        hoverScale = if (tvLayout) 1.05f else 1.02f,
        glowing = false,
    ) { hovered ->
        LaunchedEffect(hovered) { if (hovered) onActive() }
        val shine = rememberCardShine(hovered)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2.05f)
                .glow(if (hovered) card.accent.copy(alpha = 0.6f) else Color.Transparent, shape, 34.dp)
                .clip(shape)
                .background(Brush.linearGradient(listOf(card.from, card.to)))
                .drawBehind {
                    val radius = size.width * 0.7f
                    drawCircle(
                        Brush.radialGradient(
                            listOf(card.accent.copy(alpha = 0.30f), Color.Transparent),
                            center = Offset(0f, size.height),
                            radius = radius,
                        ),
                        radius = radius,
                        center = Offset(0f, size.height),
                    )
                }
                .then(if (hovered && shine == null) Modifier.focusRing(palette, 3.dp, shape) else Modifier)
                .then(if (hovered) Modifier.cardShine(shine, palette, 3.dp, shape) else Modifier),
        ) {
            PosterFan(
                covers = card.covers,
                posterWidth = if (tvLayout) 106.dp else 78.dp,
                step = if (tvLayout) 46.dp else 34.dp,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp),
            )
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(0.56f).padding(start = 22.dp, bottom = 18.dp, end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    card.title,
                    style = if (tvLayout) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    card.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val total = card.total
                    if (total != null && total > 0) OwnedBar(card.owned.toFloat() / total, card.accent, if (tvLayout) 90.dp else 64.dp)
                    Text(
                        if (total != null) "${card.owned} av $total" else "${card.owned} titlar",
                        style = MaterialTheme.typography.labelMedium,
                        color = card.accent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Upp till tre omslag i en solfjäder: mitten överst och lite högre. */
@Composable
private fun PosterFan(covers: List<List<String>>, posterWidth: Dp, step: Dp, modifier: Modifier) {
    if (covers.isEmpty()) return
    val palette = LocalPalette.current
    Box(modifier.width(posterWidth + step * (covers.size - 1) + 12.dp), contentAlignment = Alignment.CenterStart) {
        covers.forEachIndexed { index, candidates ->
            val middle = covers.size == 3 && index == 1
            val tilt = when (covers.size) {
                1 -> 0f
                2 -> if (index == 0) -6f else 6f
                else -> (index - 1) * 8f
            }
            Box(
                Modifier
                    .offset(x = step * index, y = if (middle) (-6).dp else 6.dp)
                    .zIndex(if (middle) 2f else 1f)
                    .graphicsLayer { rotationZ = tilt }
                    .width(posterWidth)
                    .aspectRatio(2f / 3f)
                    .shadow(12.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.surfaceRaised),
            ) {
                Artwork(candidates = candidates, modifier = Modifier.fillMaxSize(), maxSide = 260)
            }
        }
    }
}

/** En film i serien som inte finns i biblioteket: på sin plats, nedtonad, med numret och året. */
@Composable
private fun MissingPoster(slot: CollectionSlot, accent: Color, modifier: Modifier, onActive: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    Pressable(
        onClick = {},
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        hoverScale = if (tvLayout) 1.06f else 1.02f,
        glowing = false,
    ) { hovered ->
        LaunchedEffect(hovered) { if (hovered) onActive() }
        Column(Modifier.fillMaxWidth().padding(6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .graphicsLayer { alpha = if (hovered) 1f else 0.7f }
                    .clip(shape)
                    .background(palette.surface)
                    .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.14f), Color.Transparent)))
                    .border(if (hovered) 2.dp else 1.dp, if (hovered) accent.copy(alpha = 0.85f) else palette.hairline, shape),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("${slot.number}", style = MaterialTheme.typography.headlineMedium, color = accent.copy(alpha = 0.9f))
                    Text(
                        slot.film.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("${slot.film.year}", style = MaterialTheme.typography.labelMedium, color = palette.textTertiary)
                }
                Text(
                    "SAKNAS",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                    color = palette.textTertiary,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
            }
            if (!tvLayout) {
                Spacer(Modifier.height(8.dp))
                Text(slot.film.title, style = MaterialTheme.typography.bodyMedium, color = palette.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${slot.film.year}", style = MaterialTheme.typography.labelSmall, color = palette.textTertiary, maxLines = 1)
            }
        }
    }
}

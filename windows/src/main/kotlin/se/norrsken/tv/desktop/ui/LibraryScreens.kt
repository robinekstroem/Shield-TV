package se.norrsken.tv.desktop.ui

import se.norrsken.tv.desktop.data.PersonalKeys

import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import se.norrsken.tv.desktop.data.WatchKind
import se.norrsken.tv.desktop.data.ListKind
import se.norrsken.tv.desktop.data.DisneyIndex
import se.norrsken.tv.desktop.data.CollectionKeys
import se.norrsken.tv.desktop.data.DisneyShelf
import kotlinx.coroutines.delay
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import se.norrsken.tv.data.xtream.XtreamEpisode
import se.norrsken.tv.data.xtream.XtreamVodInfo
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.data.LibraryFilter
import se.norrsken.tv.desktop.EpisodesState
import se.norrsken.tv.desktop.MovieInfoState
import se.norrsken.tv.desktop.Screen
import se.norrsken.tv.desktop.SortOrder
import se.norrsken.tv.desktop.data.Movie
import se.norrsken.tv.desktop.data.Series
import se.norrsken.tv.desktop.data.SourceType
import java.util.Locale
import androidx.compose.foundation.lazy.grid.items as gridItems

private val swedishLocale: Locale = Locale.forLanguageTag("sv-SE")

internal fun formatRating(rating: Double?): String? =
    rating?.takeIf { it > 0.0 }?.let { "★ " + String.format(swedishLocale, "%.1f", it) }

/** 1 h 52 min, eller 48 min. */
private fun formatRuntime(seconds: Int?): String? {
    val total = seconds?.takeIf { it > 0 } ?: return null
    val minutes = (total + 30) / 60
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

private fun yearOf(date: String?): String? =
    date?.trim()?.take(4)?.takeIf { it.length == 4 && it.all { c -> c.isDigit() } }

// --- Filmer ----------------------------------------------------------------------

@Composable
fun MoviesScreen(state: AppState) {
    // Före förgreningen: rutnätet ska stå kvar där det var när man backar ur en film.
    val gridState = rememberLazyGridState()
    val group = state.movieGroup
    val query = state.movieQuery
    LaunchedEffect(group, query, state.movieSort) { gridState.scrollToItem(0) }
    // Tv: omslaget som är markerat, och det man senast öppnade, så att
    // markeringen hamnar där igen när man backar ur filmen.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var openedKey by remember { mutableStateOf<String?>(null) }
    // Kortfilmer som ligger som serier öppnas härifrån, i Disneys samlingar.
    var seriesFromHere by remember { mutableStateOf(false) }
    // Tv: var markeringen landar från kategorierna, och Disneys samlingskort.
    val entry = remember { TvEntry() }
    val shelfFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // Samlingarna: galleriet och en samlings sida har ett eget rutnät.
    val collectionGrid = rememberLazyGridState()
    val collectionEntry = remember { TvEntry() }
    LaunchedEffect(group, state.collectionPart, state.collectionStory) { collectionGrid.scrollToItem(0) }

    val selected = state.selectedMovie
    if (selected != null) {
        MovieDetail(state, selected)
        return
    }
    val selectedSeries = state.selectedSeries
    if (seriesFromHere && selectedSeries != null) {
        SeriesDetail(state, selectedSeries)
        return
    }
    val library = state.library
    if (library.movies.isEmpty()) {
        NoVideoLibrary(state, what = "filmer")
        return
    }
    // Sorteringen av tiotusentals filmer görs i bakgrunden; listan står kvar under tiden.
    val sort = state.movieSort
    val character = state.disneyCharacter
    // Fortsätt titta och Min lista följer historiken och listan; andra kategorier gör det inte.
    val personal = if (PersonalKeys.isPersonal(group)) state.history to state.myList else null
    val computed = rememberInBackground(library, group, query, sort, state.tmdbFacts, state.versions, state.disney, character, personal) {
        Computed(listOf(group, query, sort, character), state.filteredMovies())
    }
    // Tv: medan man bläddrar bland omslagen byts listan inte ut under en.
    val computedMovies = rememberSteady(computed, browsing = tvLayout && focusedKey != null)
    val movies = computedMovies ?: emptyList()
    // Disney: alla tre samlingarna, eller en av dem.
    val disneyShelf = DisneyShelf.of(group)
    val disneyView = DisneyShelf.isDisney(group)
    val disneySeries = remember(library, group, query, state.versions, state.disney, state.disneyCharacter) {
        if (disneyView) state.disneySeries(disneyShelf) else emptyList()
    }
    // Filmen överst följer markeringen på tv:n och musen på datorn, med en kort
    // fördröjning så att snabbt bläddrande inte byter bakgrund för varje omslag.
    var spotlightKey by remember(movies) { mutableStateOf(movies.firstOrNull()?.key) }
    var hoveredKey by remember { mutableStateOf<String?>(null) }
    val activeKey = if (tvLayout) focusedKey else hoveredKey
    LaunchedEffect(activeKey) {
        val key = activeKey ?: return@LaunchedEffect
        // Tv: bakgrund och text byts först när markeringen stannat, inte för
        // varje omslag man passerar — annars blinkar hela skärmen när man bläddrar.
        delay(if (tvLayout) SPOTLIGHT_SETTLE_MS else 260)
        // En serie bland Disneys kortfilmer byter inte bakgrund: den ovanför står kvar.
        if (movies.any { it.key == key }) spotlightKey = key
    }
    val spotlight = remember(movies, spotlightKey) { movies.firstOrNull { it.key == spotlightKey } }
    val restore = remember { FocusRequester() }
    if (tvLayout) {
        LaunchedEffect(Unit) {
            if (openedKey != null) {
                withFrameNanos { }
                runCatching { restore.requestFocus() }
            }
        }
    }
    // Tv: från kategorierna in till omslagen — eller till samlingskorten i Disney.
    val enterGrid: () -> Unit = {
        scope.launch {
            val current = state.movieGroup
            if (CollectionKeys.isGallery(current) || CollectionKeys.franchiseOf(current) != null) {
                collectionEntry.enter { collectionGrid.layoutInfo.visibleItemsInfo.map { it.key } }
                return@launch
            }
            if (DisneyShelf.isDisney(state.movieGroup)) {
                repeat(2) { withFrameNanos { } }
                if (gridState.firstVisibleItemIndex == 0 && runCatching { shelfFocus.requestFocus() }.isSuccess) return@launch
            }
            entry.enter { posterKeys(gridState) }
        }
    }
    // Tv: mellan galleriet och en samling följer markeringen med — in till
    // samlingens första film, och tillbaka till samlingens kort.
    var previousGroup by remember { mutableStateOf(group) }
    LaunchedEffect(group) {
        val from = previousGroup
        previousGroup = group
        if (!tvLayout || from == group) return@LaunchedEffect
        if (!CollectionKeys.isCollections(from) || !CollectionKeys.isCollections(group)) return@LaunchedEffect
        collectionEntry.last = if (CollectionKeys.isGallery(group)) collectionCardId(from) else null
        enterGrid()
    }
    // Tv: "8 / 551" — var i kategorin det markerade omslaget står.
    val position = remember(movies, focusedKey) {
        val index = if (focusedKey == null) -1 else movies.indexOfFirst { it.key == focusedKey }
        if (index >= 0 && !disneyView) "${(index + 1).grouped()} / ${movies.size.grouped()}" else null
    }

    Row(Modifier.fillMaxSize()) {
        GroupColumn(
            allLabel = "Alla filmer",
            total = if (state.versions.isEmpty) library.movies.size else state.versions.movieCount,
            groups = state.visibleGroups(LibraryFilter.MOVIE, library.movieGroups),
            counts = library.movieCounts,
            // Inne i en samling eller i Disney är det Samlingar som är valt i listan.
            selected = if (CollectionKeys.isCollections(group)) CollectionKeys.GALLERY else group,
            onSelect = {
                state.movieGroup = it
                state.disneyCharacter = null
                state.collectionPart = null
            },
            pinned = personalPinned(state, LibraryFilter.MOVIE) + collectionsPinned(state),
            onEnterContent = enterGrid,
        )
        val franchise = CollectionKeys.franchiseOf(group)
        when {
            CollectionKeys.isGallery(group) -> CollectionsGallery(state, collectionGrid, collectionEntry, Modifier.weight(1f).fillMaxHeight())
            franchise != null -> CollectionPage(
                state = state,
                franchise = franchise,
                gridState = collectionGrid,
                entry = collectionEntry,
                restore = restore,
                openedKey = openedKey,
                onOpen = { movie ->
                    openedKey = movie.key
                    state.showMovie(movie)
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            else -> Box(Modifier.weight(1f).fillMaxHeight()) {
            if (spotlight != null) {
                val tmdb = state.tmdbFor(spotlight)
                SpotlightBackdrop(
                    url = tmdb?.backdrop,
                    fallback = state.posterCandidates(spotlight).firstOrNull(),
                    running = state.nowPlaying == null || state.miniPlayer,
                )
            }
            Column(Modifier.fillMaxSize()) {
                val title = when {
                    disneyShelf != null -> disneyShelf.title
                    disneyView -> "Disney"
                    else -> PersonalKeys.titleOf(group) ?: group ?: "Alla filmer"
                }
                val subtitle = if (disneyView) {
                    disneySubtitle(state, disneyShelf, movies, movies.size + disneySeries.size)
                } else {
                    "${movies.size.grouped()} filmer"
                }
                if (tvLayout) {
                    TvLibraryBar(
                        title = title,
                        position = position ?: subtitle,
                        sort = state.movieSort,
                        series = false,
                        onSort = { state.sortMovies(it) },
                        query = query,
                        onQuery = { state.movieQuery = it },
                        placeholder = "Sök film",
                    )
                } else {
                    ScreenHeader(
                        title = title,
                        subtitle = subtitle,
                        query = query,
                        onQuery = { state.movieQuery = it },
                        placeholder = "Sök film",
                    )
                    SortRow(state.movieSort, series = false, onSelect = { state.sortMovies(it) })
                }
                if (disneyShelf == DisneyShelf.SHORTS) DisneyCharacterChips(state)
                if (spotlight != null) {
                    val tmdb = state.tmdbFor(spotlight)
                    val facts = state.movieFacts[spotlight.key]
                    SpotlightInfo(
                        state = state,
                        kind = "movie",
                        tmdbId = tmdb?.id,
                        title = state.titleOf(spotlight),
                        meta = listOfNotNull(
                            if (disneyView) state.disney.numberOf(spotlight)?.let { "Nr $it" } else null,
                            (tmdb?.year ?: facts?.year)?.toString(),
                            formatRating(tmdb?.rating ?: spotlight.rating),
                            tmdb?.genres?.ifBlank { null } ?: facts?.genre?.ifBlank { null },
                        ).joinToString("  ·  ").ifEmpty { null },
                        overview = tmdb?.overview?.ifBlank { null },
                    )
                }
                if (computedMovies == null) {
                    Spacer(Modifier.weight(1f))
                } else if (movies.isEmpty() && disneySeries.isEmpty()) {
                    EmptyState(title = "Inga träffar", body = "Ingen film matchar sökningen.")
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        TvScrolling(lead = 0.05f, trail = 0.08f, snap = true) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(if (tvLayout) TV_POSTER_MIN else 152.dp),
                                state = gridState,
                                // Tv: luft runt omslagen, så att det markerade har plats att växa.
                                contentPadding = if (tvLayout) {
                                    PaddingValues(start = 40.dp, end = 40.dp, top = 14.dp, bottom = 40.dp)
                                } else {
                                    PaddingValues(start = 20.dp, end = 28.dp, bottom = 28.dp)
                                },
                                horizontalArrangement = Arrangement.spacedBy(if (tvLayout) 16.dp else 8.dp),
                                verticalArrangement = Arrangement.spacedBy(if (tvLayout) 20.dp else 12.dp),
                            ) {
                                val movieCell: @Composable (Movie) -> Unit = { movie ->
                                    PosterCard(
                                        title = state.titleOf(movie),
                                        images = state.posterCandidates(movie),
                                        meta = if (disneyView) disneyMeta(state, movie) else movieMeta(state, movie),
                                        onClick = {
                                            openedKey = movie.key
                                            state.showMovie(movie)
                                        },
                                        modifier = (if (movie.key == openedKey) Modifier.focusRequester(restore) else Modifier)
                                            .then(entry.mark(movie.key)),
                                        onFocusChange = { has ->
                                            if (has) {
                                                focusedKey = movie.key
                                                entry.last = movie.key
                                            } else if (focusedKey == movie.key) {
                                                focusedKey = null
                                            }
                                        },
                                        onActive = { if (!tvLayout) hoveredKey = movie.key },
                                        badge = state.badgeFor(movie),
                                        showTitle = !tvLayout,
                                    )
                                }
                                if (!disneyView) {
                                    gridItems(movies, key = { it.key }) { movie -> movieCell(movie) }
                                } else {
                                    // Samlingarna som stora kort överst; ett tryck byter samling.
                                    item(key = SHELVES_KEY, span = { GridItemSpan(maxLineSpan) }) {
                                        DisneyShelves(state, disneyShelf, shelfFocus) { shelf ->
                                            state.movieGroup = shelf?.key ?: DisneyShelf.ALL_KEY
                                            state.disneyCharacter = null
                                        }
                                    }
                                    // En rubrik per samling när alla tre visas, serierna först i kortfilmerna.
                                    for (section in DisneyShelf.entries) {
                                        if (disneyShelf != null && section != disneyShelf) continue
                                        val sectionMovies = movies.filter { state.disney.shelfOf(it) == section }
                                        val sectionSeries = disneySeries.filter { state.disney.shelfOf(it) == section }
                                        if (sectionMovies.isEmpty() && sectionSeries.isEmpty()) continue
                                        if (disneyShelf == null) {
                                            item(key = HEADER_PREFIX + section.name, span = { GridItemSpan(maxLineSpan) }) {
                                                DisneySectionHeader(section, sectionMovies.size + sectionSeries.size)
                                            }
                                        }
                                        gridItems(sectionSeries, key = { "serie-" + it.key }) { series ->
                                            val key = "serie-" + series.key
                                            PosterCard(
                                                title = state.titleOf(series),
                                                images = state.posterCandidates(series),
                                                meta = seriesMeta(state, series),
                                                onClick = {
                                                    openedKey = key
                                                    seriesFromHere = true
                                                    state.showSeries(series)
                                                },
                                                modifier = (if (key == openedKey) Modifier.focusRequester(restore) else Modifier)
                                                    .then(entry.mark(key)),
                                                onFocusChange = { has ->
                                                    if (has) {
                                                        focusedKey = key
                                                        entry.last = key
                                                    } else if (focusedKey == key) {
                                                        focusedKey = null
                                                    }
                                                },
                                                onActive = { if (!tvLayout) hoveredKey = key },
                                                badge = state.badgeFor(series),
                                                showTitle = !tvLayout,
                                            )
                                        }
                                        gridItems(sectionMovies, key = { it.key }) { movie -> movieCell(movie) }
                                    }
                                }
                            }
                        }
                        Scrollbar(gridState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 6.dp, bottom = 12.dp))
                    }
                }
            }
        }
        }
    }
}

/** Nycklarna i rutnätet som är omslag, inte rubriker eller samlingskort. */
private const val SHELVES_KEY = "disney-samlingar"
private const val HEADER_PREFIX = "rubrik-"

/** Omslagen som syns just nu, i ordning: dit markeringen kan gå in från kategorierna. */
private fun posterKeys(gridState: LazyGridState): List<Any> =
    gridState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
        (info.key as? String)?.takeIf { it != SHELVES_KEY && !it.startsWith(HEADER_PREFIX) }
    }

/**
 * Filmens sida: fakta först, uppspelning när man väljer det. Omslag, år och
 * betyg syns direkt; handling, regi och skådespelare kommer från leverantören
 * en sekund senare.
 */
@Composable
internal fun MovieDetail(state: AppState, movie: Movie) {
    val palette = LocalPalette.current
    val infoState = state.movieInfo
    val info = (infoState as? MovieInfoState.Loaded)?.info
    val scroll = rememberScrollState()
    var choosingDisney by remember(movie.key) { mutableStateOf(false) }
    // Tv: markeringen börjar på Spela (och hamnar där igen om den tappas), så att OK spelar filmen.
    val playFocus = remember { FocusRequester() }
    if (tvLayout) {
        LaunchedEffect(movie.key) {
            repeat(2) { withFrameNanos { } }
            runCatching { playFocus.requestFocus() }
        }
    }
    TvFocusFallback { runCatching { playFocus.requestFocus() }.isSuccess }

    Box(Modifier.fillMaxSize()) {
        // Bilden över hela skärmen, i full storlek: TMDB:s först, den är skarpast.
        DetailBackdrop(
            candidates = listOfNotNull(
                fullSize(state.tmdbFor(movie)?.backdrop),
                fullSize(info?.backdrop),
                info?.cover,
            ) + state.posterCandidates(movie),
            running = state.nowPlaying == null || state.miniPlayer,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(start = 48.dp, end = 48.dp, top = 104.dp, bottom = 40.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .width(240.dp)
                        .aspectRatio(2f / 3f)
                        .shadow(22.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surfaceRaised)
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp)),
                ) {
                    Artwork(
                        candidates = state.largePosters(state.posterCandidates(movie)) + listOfNotNull(info?.cover),
                        modifier = Modifier.fillMaxSize(),
                        maxSide = 600,
                        placeholder = { PosterPlaceholder(state.titleOf(movie)) },
                    )
                }
                Spacer(Modifier.width(44.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TitleLogo(
                        state = state,
                        kind = "movie",
                        tmdbId = state.tmdbIdOf(movie),
                        title = state.titleOf(movie),
                        style = detailTitleStyle(),
                        maxHeight = 136.dp,
                        maxWidth = 600.dp,
                        maxLines = 3,
                    )
                    DetailFacts(
                        parts = listOfNotNull(
                            yearOf(info?.releaseDate),
                            info?.genre?.takeIf { it.isNotBlank() },
                            formatRuntime(info?.durationSeconds),
                        ),
                        rating = info?.rating ?: movie.rating,
                    )
                    // Samma film i flera versioner: välj här, Spela spelar den valda.
                    val all = state.versionsOf(movie)
                    if (all.size > 1) {
                        VersionPicker(
                            labels = all.map { version ->
                                val started = state.history.any {
                                    it.kind == WatchKind.MOVIE && it.sourceId == version.sourceId &&
                                        it.itemId == version.streamId.toString() && it.resumable
                                }
                                state.versions.labelOf(version) + (if (started) " · påbörjad" else "")
                            },
                            selected = all.indexOfFirst { it.key == movie.key },
                            onSelect = { index -> state.showMovie(all[index], exact = true) },
                        )
                    }
                    Row(
                        Modifier.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val resumable = state.history.any {
                            it.kind == WatchKind.MOVIE && it.sourceId == movie.sourceId && it.itemId == movie.streamId.toString() && it.resumable
                        }
                        PrimaryButton(
                            if (resumable) "Fortsätt" else "Spela",
                            onClick = { state.playMovie(movie) },
                            icon = Glyphs.Play,
                            modifier = Modifier.focusRequester(playFocus),
                        )
                        trailerUrl(info?.trailer)?.let { url ->
                            SecondaryButton("Trailer", onClick = { openInBrowser(url) }, icon = Glyphs.Movies)
                        }
                        val saved = state.inMyList(movie)
                        SecondaryButton(
                            if (saved) "I min lista" else "Min lista",
                            onClick = { state.toggleMyList(movie) },
                            icon = if (saved) Glyphs.Check else Glyphs.Add,
                        )
                        // Disney: flytta filmen till en annan samling, lägg till den, eller ta bort den.
                        if (state.isDisneyCandidate(movie)) {
                            val shelf = state.disneyShelfOf(movie)
                            SecondaryButton(
                                shelf?.title ?: "Disney",
                                onClick = { choosingDisney = !choosingDisney },
                                icon = if (shelf != null) Glyphs.Check else Glyphs.Add,
                            )
                        }
                    }
                    if (choosingDisney) DisneyChooser(state, movie, onDone = { choosingDisney = false })
                    // Handlingen på svenska från TMDB när den finns, annars leverantörens text.
                    val swedishPlot = state.tmdbFor(movie)?.overview?.takeIf { it.isNotBlank() }
                    when (infoState) {
                        MovieInfoState.Loading -> if (swedishPlot != null) {
                            MovieFacts(null, swedishPlot)
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Spinner(size = 18.dp)
                                Text("Hämtar information…", style = MaterialTheme.typography.bodyMedium, color = palette.textTertiary)
                            }
                        }
                        is MovieInfoState.Failed -> if (swedishPlot != null) {
                            MovieFacts(null, swedishPlot)
                        } else {
                            Text(
                                "Informationen gick inte att hämta. ${infoState.message}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textTertiary,
                            )
                        }
                        is MovieInfoState.Loaded -> MovieFacts(infoState.info, swedishPlot)
                    }
                }
            }
        }

        IconAction(
            Glyphs.Back,
            "Tillbaka",
            onClick = { state.closeMovie() },
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            emphasized = true,
        )
    }
}

@Composable
private fun MovieFacts(info: XtreamVodInfo?, swedishPlot: String?) {
    val palette = LocalPalette.current
    val plot = swedishPlot ?: info?.plot?.takeIf { it.isNotBlank() }
    val director = info?.director?.takeIf { it.isNotBlank() }
    val cast = info?.cast?.takeIf { it.isNotBlank() }
    if (plot == null && director == null && cast == null) {
        Text(
            "Leverantören har ingen beskrivning av filmen.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textTertiary,
        )
        return
    }
    // Tv: en ruta markeringen kan stanna på, så att sidan rullar fram texten.
    TvFocusBox {
        Column(
            Modifier.padding(if (tvLayout) 10.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (plot != null) {
                Text(
                    plot,
                    style = detailPlotStyle(),
                    color = palette.textPrimary.copy(alpha = 0.88f),
                    modifier = Modifier.widthIn(max = 780.dp),
                )
            }
            if (director != null) Credit("Regi", director)
            if (cast != null) Credit("Skådespelare", cast)
        }
    }
}

@Composable
private fun Credit(label: String, value: String) {
    val palette = LocalPalette.current
    Row(Modifier.widthIn(max = 780.dp), verticalAlignment = Alignment.Top) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
            color = palette.textTertiary,
            modifier = Modifier.width(140.dp).padding(top = 2.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textPrimary.copy(alpha = 0.8f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Bakgrunden på film- och seriesidan: bilden i full storlek över hela
 * bredden, skarp och ljus till höger, mörkare till vänster där texten står
 * och nedåt mot det som kommer under, som på streamingtjänsterna.
 */
@Composable
private fun DetailBackdrop(candidates: List<String>, running: Boolean, modifier: Modifier) {
    val palette = LocalPalette.current
    Box(modifier) {
        KenBurns(running = running, modifier = Modifier.fillMaxSize()) {
            Artwork(
                candidates = candidates,
                modifier = Modifier.fillMaxSize(),
                maxSide = BACKDROP_SIDE,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
            )
        }
        // Mörkt där texten står, bilden fri till höger.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to palette.background.copy(alpha = 0.94f),
                    0.34f to palette.background.copy(alpha = 0.76f),
                    0.64f to palette.background.copy(alpha = 0.24f),
                    1f to palette.background.copy(alpha = 0.04f),
                ),
            ),
        )
        // Upptill lite mörkare för Tillbaka, nedtill ut i sidans bakgrund.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.38f),
                    0.16f to Color.Transparent,
                    0.55f to Color.Transparent,
                    0.86f to palette.background.copy(alpha = 0.8f),
                    1f to palette.background,
                ),
            ),
        )
    }
}

/** Titeln när loggan saknas: stor och fet, med en svag skugga mot bilden. */
@Composable
private fun detailTitleStyle(): TextStyle =
    MaterialTheme.typography.displaySmall.copy(
        fontWeight = FontWeight.Bold,
        shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), 12f),
    )

/** Handlingen: luftig och lätt att läsa från soffan. */
@Composable
private fun detailPlotStyle(): TextStyle {
    val base = MaterialTheme.typography.bodyLarge
    return base.copy(lineHeight = base.fontSize * 1.55f)
}

/** Betyget med en guldstjärna, sedan år, genre och längd, med prickar emellan. */
@Composable
private fun DetailFacts(parts: List<String>, rating: Double?) {
    val palette = LocalPalette.current
    val score = rating?.takeIf { it > 0.0 }
    if (score == null && parts.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (score != null) {
            Icon(Glyphs.Star, contentDescription = "Betyg", tint = STAR_GOLD, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                String.format(swedishLocale, "%.1f", score),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = palette.textPrimary,
                maxLines = 1,
            )
        }
        parts.forEachIndexed { index, part ->
            if (index > 0 || score != null) {
                Box(Modifier.padding(horizontal = 12.dp).size(4.dp).clip(RoundedCornerShape(2.dp)).background(palette.textTertiary))
            }
            Text(
                part,
                style = MaterialTheme.typography.titleSmall,
                color = palette.textPrimary.copy(alpha = 0.82f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

private val STAR_GOLD = Color(0xFFF5C518)

// --- Serier ----------------------------------------------------------------------

@Composable
fun SeriesScreen(state: AppState) {
    // Före förgreningen: listan ska stå kvar där den var när man backar ur en serie.
    val gridState = rememberLazyGridState()
    // Här, före förgreningen: att backa ur en serie ska inte hoppa till början.
    LaunchedEffect(state.seriesGroup, state.seriesQuery, state.seriesSort) { gridState.scrollToItem(0) }
    // Tv: omslaget som är markerat, och det man senast öppnade.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var openedKey by remember { mutableStateOf<String?>(null) }
    val entry = remember { TvEntry() }
    val scope = rememberCoroutineScope()
    val selected = state.selectedSeries
    if (selected != null) {
        SeriesDetail(state, selected)
        return
    }
    val library = state.library
    if (library.series.isEmpty()) {
        NoVideoLibrary(state, what = "serier")
        return
    }
    val group = state.seriesGroup
    val query = state.seriesQuery
    val sort = state.seriesSort
    val personal = if (PersonalKeys.isPersonal(group)) state.history to state.myList else null
    val computed = rememberInBackground(library, group, query, sort, state.tmdbFacts, state.versions, personal) {
        Computed(listOf(group, query, sort), state.filteredSeries())
    }
    // Tv: medan man bläddrar bland omslagen byts listan inte ut under en.
    val computedSeries = rememberSteady(computed, browsing = tvLayout && focusedKey != null)
    val series = computedSeries ?: emptyList()
    var spotlightKey by remember(series) { mutableStateOf(series.firstOrNull()?.key) }
    var hoveredKey by remember { mutableStateOf<String?>(null) }
    val activeKey = if (tvLayout) focusedKey else hoveredKey
    LaunchedEffect(activeKey) {
        val key = activeKey ?: return@LaunchedEffect
        delay(if (tvLayout) SPOTLIGHT_SETTLE_MS else 260)
        spotlightKey = key
    }
    val spotlight = remember(series, spotlightKey) { series.firstOrNull { it.key == spotlightKey } }
    val restore = remember { FocusRequester() }
    if (tvLayout) {
        LaunchedEffect(Unit) {
            if (openedKey != null) {
                withFrameNanos { }
                runCatching { restore.requestFocus() }
            }
        }
    }
    val position = remember(series, focusedKey) {
        val index = if (focusedKey == null) -1 else series.indexOfFirst { it.key == focusedKey }
        if (index >= 0) "${(index + 1).grouped()} / ${series.size.grouped()}" else null
    }

    Row(Modifier.fillMaxSize()) {
        GroupColumn(
            allLabel = "Alla serier",
            total = if (state.versions.isEmpty) library.series.size else state.versions.seriesCount,
            groups = state.visibleGroups(LibraryFilter.SERIES, library.seriesGroups),
            counts = library.seriesCounts,
            selected = group,
            onSelect = { state.seriesGroup = it },
            pinned = personalPinned(state, LibraryFilter.SERIES),
            onEnterContent = { scope.launch { entry.enter { posterKeys(gridState) } } },
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (spotlight != null) {
                val tmdb = state.tmdbFor(spotlight)
                SpotlightBackdrop(
                    url = spotlight.backdrop ?: tmdb?.backdrop,
                    fallback = state.posterCandidates(spotlight).firstOrNull(),
                    running = state.nowPlaying == null || state.miniPlayer,
                )
            }
            Column(Modifier.fillMaxSize()) {
                if (tvLayout) {
                    TvLibraryBar(
                        title = PersonalKeys.titleOf(group) ?: group ?: "Alla serier",
                        position = position ?: "${series.size.grouped()} serier",
                        sort = state.seriesSort,
                        series = true,
                        onSort = { state.sortSeries(it) },
                        query = query,
                        onQuery = { state.seriesQuery = it },
                        placeholder = "Sök serie",
                    )
                } else {
                    ScreenHeader(
                        title = PersonalKeys.titleOf(group) ?: group ?: "Alla serier",
                        subtitle = "${series.size.grouped()} serier",
                        query = query,
                        onQuery = { state.seriesQuery = it },
                        placeholder = "Sök serie",
                    )
                    SortRow(state.seriesSort, series = true, onSelect = { state.sortSeries(it) })
                }
                if (spotlight != null) {
                    val tmdb = state.tmdbFor(spotlight)
                    SpotlightInfo(
                        state = state,
                        kind = "tv",
                        tmdbId = tmdb?.id,
                        title = state.titleOf(spotlight),
                        meta = listOfNotNull(
                            spotlight.year,
                            formatRating(tmdb?.rating ?: spotlight.rating),
                            tmdb?.genres?.ifBlank { null } ?: spotlight.genre?.ifBlank { null },
                        ).joinToString("  ·  ").ifEmpty { null },
                        overview = tmdb?.overview?.ifBlank { null } ?: spotlight.plot?.ifBlank { null },
                    )
                }
                if (computedSeries == null) {
                    Spacer(Modifier.weight(1f))
                } else if (series.isEmpty()) {
                    EmptyState(title = "Inga träffar", body = "Ingen serie matchar sökningen.")
                } else {
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
                                gridItems(series, key = { it.key }) { item ->
                                    PosterCard(
                                        title = state.titleOf(item),
                                        images = state.posterCandidates(item),
                                        meta = seriesMeta(state, item),
                                        onClick = {
                                            openedKey = item.key
                                            state.showSeries(item)
                                        },
                                        modifier = (if (item.key == openedKey) Modifier.focusRequester(restore) else Modifier)
                                            .then(entry.mark(item.key)),
                                        onFocusChange = { has ->
                                            if (has) {
                                                focusedKey = item.key
                                                entry.last = item.key
                                            } else if (focusedKey == item.key) {
                                                focusedKey = null
                                            }
                                        },
                                        onActive = { if (!tvLayout) hoveredKey = item.key },
                                        badge = state.badgeFor(item),
                                        showTitle = !tvLayout,
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
}

@Composable
internal fun SeriesDetail(state: AppState, series: Series) {
    val palette = LocalPalette.current
    // Samma serie i flera versioner: väljs i huvudet, avsnitten följer valet.
    val all = state.versionsOf(series)
    // Tv: markeringen börjar på Fortsätt (eller Min lista), och hamnar där igen om den tappas.
    val primaryFocus = remember { FocusRequester() }
    if (tvLayout) {
        LaunchedEffect(series.key) {
            repeat(2) { withFrameNanos { } }
            runCatching { primaryFocus.requestFocus() }
        }
    }
    TvFocusFallback { runCatching { primaryFocus.requestFocus() }.isSuccess }
    Column(Modifier.fillMaxSize()) {
        // Huvudet: bakgrundsbild tonad mot svart, omslag och fakta.
        Box(Modifier.fillMaxWidth().height(if (all.size > 1) 400.dp else 350.dp)) {
            // Bilden i full storlek, skarp och ljus till höger, mörkare där texten står.
            DetailBackdrop(
                candidates = listOfNotNull(fullSize(state.tmdbFor(series)?.backdrop), fullSize(series.backdrop)) +
                    state.posterCandidates(series),
                running = state.nowPlaying == null || state.miniPlayer,
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 18.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    Modifier
                        .width(168.dp)
                        .aspectRatio(2f / 3f)
                        .shadow(18.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.surfaceRaised)
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
                ) {
                    Artwork(
                        candidates = state.largePosters(state.posterCandidates(series)),
                        modifier = Modifier.fillMaxSize(),
                        maxSide = 480,
                        placeholder = { PosterPlaceholder(state.titleOf(series)) },
                    )
                }
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TitleLogo(
                        state = state,
                        kind = "tv",
                        tmdbId = state.tmdbIdOf(series),
                        title = state.titleOf(series),
                        style = detailTitleStyle(),
                        maxHeight = 112.dp,
                        maxWidth = 540.dp,
                        maxLines = 2,
                    )
                    DetailFacts(
                        parts = listOfNotNull(series.year, series.genre?.takeIf { it.isNotBlank() }),
                        rating = series.rating,
                    )
                    // Handlingen på svenska från TMDB när den finns, annars leverantörens text.
                    val shownPlot = state.tmdbFor(series)?.overview?.takeIf { it.isNotBlank() }
                        ?: series.plot?.takeIf { it.isNotBlank() }
                    shownPlot?.let { plot ->
                        Text(
                            plot,
                            style = detailPlotStyle(),
                            color = palette.textPrimary.copy(alpha = 0.88f),
                            maxLines = if (all.size > 1) 2 else 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 780.dp),
                        )
                    }
                    if (all.size > 1) {
                        VersionPicker(
                            labels = all.map { state.versions.labelOf(it) },
                            selected = all.indexOfFirst { it.key == series.key },
                            onSelect = { index -> state.showSeries(all[index], exact = true) },
                        )
                    }
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val resume = latestEpisode(state.history, series)
                        if (resume != null) {
                            PrimaryButton(
                                if (resume.upNext) "Spela S${resume.season} E${resume.episode}" else "Fortsätt S${resume.season} E${resume.episode}",
                                onClick = { state.playFromHistory(resume) },
                                icon = Glyphs.Play,
                                modifier = Modifier.focusRequester(primaryFocus),
                            )
                        }
                        trailerUrl(series.trailer)?.let { url ->
                            SecondaryButton("Trailer", onClick = { openInBrowser(url) }, icon = Glyphs.Movies)
                        }
                        val saved = state.inMyList(series)
                        SecondaryButton(
                            if (saved) "I min lista" else "Min lista",
                            onClick = { state.toggleMyList(series) },
                            icon = if (saved) Glyphs.Check else Glyphs.Add,
                            modifier = if (resume == null) Modifier.focusRequester(primaryFocus) else Modifier,
                        )
                    }
                }
            }
            IconAction(
                Glyphs.Back,
                "Tillbaka",
                onClick = { state.closeSeries() },
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                emphasized = true,
            )
        }

        when (val episodes = state.episodes) {
            EpisodesState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
            is EpisodesState.Failed -> EmptyState(
                title = "Avsnitten gick inte att hämta",
                body = episodes.message,
                actionLabel = "Försök igen",
                actionIcon = Glyphs.Refresh,
                onAction = { state.showSeries(series, exact = true) },
            )
            is EpisodesState.Loaded -> EpisodeList(state, series, episodes.episodes)
        }
    }
}

@Composable
private fun EpisodeList(state: AppState, series: Series, episodes: List<XtreamEpisode>) {
    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    // Säsongen man håller på med visas först, annars den första.
    val started = latestEpisode(state.history, series)?.season?.takeIf { it in seasons }
    var season by remember(series.key) { mutableStateOf(started ?: seasons.first()) }
    val shown = remember(episodes, season) { episodes.filter { it.season == season }.sortedBy { it.episode } }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        if (seasons.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(seasons, key = { it }) { number ->
                    Chip("Säsong $number", selected = number == season, onClick = { season = number })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 28.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Index i nyckeln: vissa paneler skickar samma avsnitts-id två gånger.
                itemsIndexed(shown, key = { index, episode -> "${episode.id}#$index" }) { _, episode ->
                    EpisodeRow(episode, series, onClick = { state.playEpisode(series, episode) })
                }
            }
            Scrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 6.dp, bottom = 12.dp))
        }
    }
}

@Composable
private fun EpisodeRow(episode: XtreamEpisode, series: Series, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth()) { hovered ->
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(176.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.surfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                RemoteImage(
                    url = episode.thumbnail ?: series.backdrop,
                    modifier = Modifier.fillMaxSize(),
                    maxSide = 400,
                    placeholder = {
                        Text(
                            "${episode.episode}",
                            style = MaterialTheme.typography.headlineSmall,
                            color = palette.textTertiary,
                        )
                    },
                )
                if (hovered) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Glyphs.Play, contentDescription = "Spela", tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val duration = episode.durationSeconds?.takeIf { it > 0 }?.let { " · ${(it + 30) / 60} min" }.orEmpty()
                Text(
                    "Avsnitt ${episode.episode}$duration",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textTertiary,
                )
                Text(
                    episode.title.ifBlank { "Avsnitt ${episode.episode}" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                episode.plot?.takeIf { it.isNotBlank() }?.let { plot ->
                    Text(
                        plot,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// --- Gemensamt ---------------------------------------------------------------------

@Composable
internal fun PosterCard(
    title: String,
    /** Omslagen att prova, i ordning; det första som går att visa används. */
    images: List<String>,
    meta: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Tv: tonas ner medan ett annat omslag är markerat. */
    dimmed: Boolean = false,
    /** När markeringen kommer till omslaget eller lämnar det. */
    onFocusChange: (Boolean) -> Unit = {},
    /** När musen eller markeringen kommer till omslaget. */
    onActive: () -> Unit = {},
    /** Liten skylt uppe i hörnet, som "4K". */
    badge: String? = null,
    /** Titeln under omslaget. Tv:ns filmer och serier visar den stort ovanför i stället. */
    showTitle: Boolean = true,
) {
    val palette = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    val fade by animateFloatAsState(if (dimmed) 0.45f else 1f, tween(220), label = "omslag")
    // På tv:n är markeringen det enda man ser av fjärrkontrollen: omslaget växer
    // tydligt, får en hel ram och glöder. Med mus räcker en antydan.
    val scale = if (tvLayout) 1.10f else 1.03f
    val ring = if (tvLayout) 3.dp else 1.dp
    val ringAlpha = if (tvLayout) 1f else 0.55f
    val glowAlpha = if (tvLayout) 1f else 0.75f
    val glowRadius = if (tvLayout) 36.dp else 26.dp
    Pressable(
        onClick = onClick,
        // Ovanpå grannarna medan det är markerat, så att det inte hamnar under dem när det växer.
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            // Vänds fram när sidan visas, som omslagen på filmväggen.
            .coverEntrance()
            .graphicsLayer { alpha = fade }
            .onFocusChanged { change ->
                if (change.hasFocus != focused) {
                    focused = change.hasFocus
                    onFocusChange(change.hasFocus)
                }
            },
        shape = RoundedCornerShape(12.dp),
        hoverScale = scale,
        glowing = false,
    ) { hovered ->
        LaunchedEffect(hovered) { if (hovered) onActive() }
        // Kristall: ljussvep och en kant som vandrar, i stället för den stilla kanten.
        val shine = rememberCardShine(hovered)
        val shape = RoundedCornerShape(10.dp)
        Column(Modifier.fillMaxWidth().padding(6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    // Glöden och kanten visar vilken titel man står på.
                    .glow(if (hovered) palette.glowOf(glowAlpha) else Color.Transparent, shape, glowRadius)
                    .clip(shape)
                    .background(palette.surfaceRaised)
                    .then(if (hovered && shine == null) Modifier.focusRing(palette, ring, shape, ringAlpha) else Modifier)
                    .then(if (hovered) Modifier.cardShine(shine, palette, ring, shape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Artwork(
                    candidates = images,
                    modifier = Modifier.fillMaxSize(),
                    maxSide = 480,
                    placeholder = { PosterPlaceholder(title) },
                )
                if (badge != null) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        // "Ny" och "Nytt avsnitt" i accentfärgen, "4K" i vitt.
                        Text(badge, style = MaterialTheme.typography.labelSmall, color = if (badge.startsWith("Ny")) palette.focus else Color.White)
                    }
                }
            }
            if (showTitle) {
                // Alla kort i en rad lika höga: två rader titel och en rad under, också när
                // de är tomma. Ett högre grannkort räknas annars som "nedanför" av pil ner,
                // och markeringen hoppar åt höger i stället för till raden under.
                Spacer(Modifier.height(8.dp))
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta.orEmpty(), style = MaterialTheme.typography.labelSmall, color = palette.textTertiary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun NoVideoLibrary(state: AppState, what: String) {
    when {
        state.sources.isEmpty() || state.importingId != null -> NoLibrary(state)
        state.sources.all { it.type == SourceType.M3U } -> EmptyState(
            title = "Inga $what",
            body = "En vanlig M3U-lista innehåller bara kanaler. Filmer och serier kommer från ett Xtream-konto — lägg till det under Inställningar.",
            actionLabel = "Till Inställningar",
            actionIcon = Glyphs.Settings,
            onAction = { state.screen = Screen.SETTINGS },
        )
        else -> EmptyState(
            title = "Inga $what",
            body = "Leverantören har inga $what på kontot, eller så behöver källan uppdateras.",
            actionLabel = "Till Inställningar",
            actionIcon = Glyphs.Settings,
            onAction = { state.screen = Screen.SETTINGS },
        )
    }
}

/** Sorteringen, som chips under rubriken. Valet sparas. */
@Composable
private fun SortRow(current: SortOrder, series: Boolean, onSelect: (SortOrder) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Sortera", style = MaterialTheme.typography.labelMedium, color = palette.textTertiary)
        Spacer(Modifier.width(4.dp))
        SortOrder.entries.forEach { order ->
            // Leverantören anger när en serie senast ändrades, inte när den lades till.
            val label = if (series && order == SortOrder.ADDED) "Senast uppdaterade" else order.label
            Chip(label, selected = order == current, onClick = { onSelect(order) })
        }
    }
}


/**
 * Tv: titeln man står på som bakgrundsbild upptill, utan att täcka appens
 * vanliga bakgrund: bilden tonar ut mot vänster och nedåt. Utan en riktig
 * bakgrundsbild blir omslaget ett mjukt färgsken: några få bildpunkter stort,
 * så att det blir oskarpt på alla enheter.
 */
@Composable
internal fun SpotlightBackdrop(url: String?, fallback: String?, running: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (tvLayout) 600.dp else 440.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, 0.5f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
                drawRect(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.22f), 0.5f to Color.Black, 1f to Color.Black),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        if (url != null || fallback == null) {
            LivingBackdrop(url = url, modifier = Modifier.fillMaxSize(), running = running)
        } else {
            RemoteImage(
                url = fallback,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f },
                maxSide = 32,
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Tv: minsta omslagsbredd. Med infälld meny och kategorier blir det sex omslag i bredd vid 1600. */
internal val TV_POSTER_MIN = 200.dp

/** Tv: så länge markeringen ska stå still innan bakgrunden och texten överst byts. */
internal const val SPOTLIGHT_SETTLE_MS = 420L

/**
 * Titeln man står på, med logga, år, betyg, genrer och handling. Fast höjd,
 * så att omslagen inte hoppar när markeringen flyttas.
 */
@Composable
internal fun SpotlightInfo(state: AppState, kind: String, tmdbId: Int?, title: String, meta: String?, overview: String?) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .height(if (tvLayout) 238.dp else 176.dp)
            .padding(start = if (tvLayout) 46.dp else 24.dp, end = 36.dp, top = if (tvLayout) 12.dp else 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (tvLayout) 9.dp else 6.dp),
    ) {
        // Loggan eller titeln i ett fack med fast höjd, mot underkanten: år och
        // handling står still när loggan laddas eller när man går till nästa film.
        Box(
            Modifier.fillMaxWidth().height(if (tvLayout) 92.dp else 64.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            TitleLogo(
                state = state,
                kind = kind,
                tmdbId = tmdbId,
                title = title,
                style = if (tvLayout) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
                maxHeight = if (tvLayout) 92.dp else 64.dp,
                maxWidth = if (tvLayout) 540.dp else 380.dp,
                maxLines = 1,
            )
        }
        if (meta != null) {
            Text(
                meta,
                style = if (tvLayout) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                color = palette.focus,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (overview != null) {
            Text(
                overview,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(if (tvLayout) 0.56f else 0.72f),
            )
        }
    }
}

/** Under omslaget: år, betyg och hur många versioner filmen finns i. */
internal fun movieMeta(state: AppState, movie: Movie): String? {
    val count = state.versionsOf(movie).size
    return listOfNotNull(
        state.versions.yearOf(movie)?.toString(),
        formatRating(state.tmdbFor(movie)?.rating ?: movie.rating),
        if (count > 1) "$count versioner" else null,
    ).joinToString("  ").ifEmpty { null }
}

internal fun seriesMeta(state: AppState, series: Series): String? {
    val count = state.versionsOf(series).size
    return listOfNotNull(
        state.versions.yearOf(series)?.toString(),
        formatRating(state.tmdbFor(series)?.rating ?: series.rating),
        if (count > 1) "$count versioner" else null,
    ).joinToString("  ").ifEmpty { null }
}

/** Ett omslag som saknas: titeln på en tonad yta, så att det ser avsiktligt ut. */
@Composable
internal fun PosterPlaceholder(title: String) {
    val palette = LocalPalette.current
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(palette.surfaceRaised, palette.surface))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.titleSmall,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Filmens eller seriens versioner som val. Den valda spelas med Spela. */
@Composable
private fun VersionPicker(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${labels.size} versioner", style = MaterialTheme.typography.labelLarge, color = palette.textTertiary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { index, label ->
                Chip(label, selected = index == selected, onClick = { if (index != selected) onSelect(index) })
            }
        }
    }
}


/** "41 av 64 klassiker", "20 av 31 filmer", "86 titlar". */
private fun disneySubtitle(state: AppState, shelf: DisneyShelf?, movies: List<Movie>, shown: Int): String {
    val numbered = movies.filter { state.disney.shelfOf(it) == shelf }.mapNotNull { state.disney.numberOf(it) }.distinct().size
    return when (shelf) {
        DisneyShelf.CLASSICS -> "$numbered av ${DisneyIndex.CLASSICS_TOTAL} klassiker"
        DisneyShelf.PIXAR -> "$numbered av ${DisneyIndex.PIXAR_TOTAL} filmer"
        else -> if (shown == 1) "1 titel" else "${shown.grouped()} titlar"
    }
}

/** Under omslaget: "Nr 12  1950" för klassikerna och Pixar, annars som vanligt. */
private fun disneyMeta(state: AppState, movie: Movie): String? {
    val number = state.disney.numberOf(movie) ?: return movieMeta(state, movie)
    return listOfNotNull("Nr $number", state.versions.yearOf(movie)?.toString()).joinToString("  ")
}

/** Kortfilmerna: en knapp per figur, så att man kan visa bara Kalle Anka. */
@Composable
private fun DisneyCharacterChips(state: AppState) {
    val names = state.disney.characterNames
    if (names.size < 2) return
    LazyRow(
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Chip("Alla", selected = state.disneyCharacter == null, onClick = { state.disneyCharacter = null }) }
        items(names) { name ->
            Chip(name, selected = state.disneyCharacter == name, onClick = { state.disneyCharacter = name })
        }
    }
}

/** Rubriken för en samling när alla tre visas. Korten överst byter samling. */
@Composable
private fun DisneySectionHeader(shelf: DisneyShelf, count: Int) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(shelf.title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(10.dp))
        Text(
            if (count == 1) "1 titel" else "$count titlar",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textTertiary,
        )
    }
}

/**
 * Disneys tre samlingar som stora kort överst i Disney: Kalle Anka & vänner,
 * Disneyklassiker och Pixar, var och en med ett par omslag i en solfjäder.
 * Ett tryck visar samlingen; ett tryck till på den valda visar alla tre igen.
 */
@Composable
private fun DisneyShelves(state: AppState, current: DisneyShelf?, focus: FocusRequester, onPick: (DisneyShelf?) -> Unit) {
    val shelves = DisneyShelf.entries.filter { (state.disney.counts[it] ?: 0) > 0 }
    if (shelves.isEmpty()) return
    // Markeringen kommer in på den valda samlingen, eller den första.
    val target = current?.takeIf { it in shelves } ?: shelves.first()
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tvLayout) 18.dp else 12.dp),
    ) {
        shelves.forEach { shelf ->
            DisneyShelfCard(
                state = state,
                shelf = shelf,
                selected = shelf == current,
                modifier = Modifier.weight(1f).then(if (shelf == target) Modifier.focusRequester(focus) else Modifier),
                onClick = { onPick(if (shelf == current) null else shelf) },
            )
        }
    }
}

@Composable
private fun DisneyShelfCard(state: AppState, shelf: DisneyShelf, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(18.dp)
    val look = shelfLook(shelf)
    val covers = remember(state.disney, state.library, state.versions, shelf) { state.disneyCovers(shelf, 3) }
    val count = state.disney.counts[shelf] ?: 0
    val subtitle = when (shelf) {
        DisneyShelf.CLASSICS -> "${DisneyIndex.CLASSICS_TOTAL} klassiker sedan 1937"
        DisneyShelf.PIXAR -> "${DisneyIndex.PIXAR_TOTAL} filmer sedan 1995"
        DisneyShelf.SHORTS -> "Kortfilmerna från 30-talet och framåt"
    }
    val posterWidth = if (tvLayout) 86.dp else 62.dp
    val step = if (tvLayout) 50.dp else 36.dp
    Pressable(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        selected = selected,
        hoverScale = if (tvLayout) 1.04f else 1.02f,
        glowing = false,
    ) { hovered ->
        val shine = rememberCardShine(hovered)
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (tvLayout) 178.dp else 140.dp)
                .glow(if (hovered) look.accent.copy(alpha = 0.55f) else Color.Transparent, shape, 30.dp)
                .clip(shape)
                .background(Brush.linearGradient(listOf(look.from, look.to)))
                // Ett varmt ljus nere till vänster, som en strålkastare.
                .background(Brush.radialGradient(listOf(look.accent.copy(alpha = 0.34f), Color.Transparent), radius = 520f, center = Offset(0f, 520f)))
                .then(
                    when {
                        hovered && shine == null -> Modifier.focusRing(palette, 3.dp, shape)
                        selected && !hovered -> Modifier.border(2.dp, look.accent.copy(alpha = 0.9f), shape)
                        else -> Modifier
                    },
                )
                .then(if (hovered) Modifier.cardShine(shine, palette, 3.dp, shape) else Modifier),
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).padding(start = 22.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        shelf.title,
                        style = if (tvLayout) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (count == 1) "1 titel här" else "${count.grouped()} titlar här",
                        style = MaterialTheme.typography.labelMedium,
                        color = look.accent,
                        maxLines = 1,
                    )
                }
                // Omslagen i en solfjäder: mitten överst och lite högre.
                Box(Modifier.width(posterWidth + step * 2 + 16.dp).fillMaxHeight()) {
                    covers.forEachIndexed { index, candidates ->
                        val middle = index == 1
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = step * index, y = if (middle) (-6).dp else 8.dp)
                                .zIndex(if (middle) 2f else 1f)
                                .graphicsLayer { rotationZ = (index - 1) * 8f }
                                .width(posterWidth)
                                .aspectRatio(2f / 3f)
                                .shadow(10.dp, RoundedCornerShape(8.dp))
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.surfaceRaised),
                        ) {
                            Artwork(candidates = candidates, modifier = Modifier.fillMaxSize(), maxSide = 240)
                        }
                    }
                }
            }
        }
    }
}

/** Kortens färger: blått och rött som en gammal tecknad film, midnatt och guld, och himmelsblått. */
internal class ShelfLook(val from: Color, val to: Color, val accent: Color)

internal fun shelfLook(shelf: DisneyShelf): ShelfLook = when (shelf) {
    DisneyShelf.SHORTS -> ShelfLook(Color(0xFF1C3F94), Color(0xFFB4233A), Color(0xFFFFD45C))
    DisneyShelf.CLASSICS -> ShelfLook(Color(0xFF0E1440), Color(0xFF3B2A7A), Color(0xFFF2C66D))
    DisneyShelf.PIXAR -> ShelfLook(Color(0xFF0B5C78), Color(0xFF39B5D9), Color(0xFFFFE08A))
}

/**
 * Tv: överst i Filmer och Serier en enda rad — kategorin, var man är i den
 * ("8 / 551"), sorteringen som ett val man trycker sig igenom, och sök.
 */
@Composable
private fun TvLibraryBar(
    title: String,
    position: String,
    sort: SortOrder,
    series: Boolean,
    onSort: (SortOrder) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 46.dp, end = 40.dp, top = 22.dp, bottom = 2.dp),
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
        Text(position, style = MaterialTheme.typography.labelLarge, color = palette.textTertiary, maxLines = 1)
        Spacer(Modifier.weight(1f))
        val label = if (series && sort == SortOrder.ADDED) "Senast uppdaterade" else sort.label
        Chip("Sortera: $label", selected = false, onClick = {
            val all = SortOrder.entries
            onSort(all[(all.indexOf(sort) + 1) % all.size])
        })
        SearchField(query, onQuery, placeholder, Modifier.width(260.dp))
    }
}

/** Filmsidan: vilken Disneysamling filmen hör till, eller ingen. Gäller alla versioner av filmen. */
@Composable
private fun DisneyChooser(state: AppState, movie: Movie, onDone: () -> Unit) {
    val palette = LocalPalette.current
    val current = state.disneyShelfOf(movie)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Visa under", style = MaterialTheme.typography.labelMedium, color = palette.textTertiary)
        DisneyShelf.entries.forEach { shelf ->
            Chip(
                shelf.title,
                selected = current == shelf,
                onClick = {
                    state.setDisneyShelf(movie, shelf)
                    onDone()
                },
            )
        }
        Chip(
            "Inte Disney",
            selected = current == null,
            onClick = {
                state.setDisneyShelf(movie, null)
                onDone()
            },
        )
    }
}

/**
 * Fortsätt titta och Min lista (filmer, serier) eller Favoriter och Senast
 * sedda (kanaler) överst i kategorilistan, när de har något i sig. De var
 * förut rader på startsidan.
 */
@Composable
internal fun personalPinned(state: AppState, kind: Char): List<PinnedGroup> {
    // Räknas i bakgrunden: första gången byggs uppslagen för hela biblioteket.
    val groups = rememberInBackground(state.library, state.history, state.myList, state.versions, kind) {
        runCatching { state.personalGroups(kind) }.getOrDefault(emptyList())
    } ?: emptyList()
    return remember(groups) { groups.map { (key, count) -> PinnedGroup(key, PersonalKeys.titleOf(key) ?: key, count) } }
}

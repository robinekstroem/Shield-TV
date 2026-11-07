package se.norrsken.tv.desktop.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import se.norrsken.tv.desktop.data.WatchKind
import se.norrsken.tv.desktop.data.ListKind
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

    val selected = state.selectedMovie
    if (selected != null) {
        MovieDetail(state, selected)
        return
    }
    val library = state.library
    if (library.movies.isEmpty()) {
        NoVideoLibrary(state, what = "filmer")
        return
    }
    val movies = remember(library, group, query, state.movieSort, state.tmdbFacts, state.versions) { state.filteredMovies() }
    // Filmen överst följer markeringen på tv:n och musen på datorn, med en kort
    // fördröjning så att snabbt bläddrande inte byter bakgrund för varje omslag.
    var spotlightKey by remember(movies) { mutableStateOf(movies.firstOrNull()?.key) }
    var hoveredKey by remember { mutableStateOf<String?>(null) }
    val activeKey = if (tvLayout) focusedKey else hoveredKey
    LaunchedEffect(activeKey) {
        val key = activeKey ?: return@LaunchedEffect
        delay(if (tvLayout) 180 else 260)
        spotlightKey = key
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

    Row(Modifier.fillMaxSize()) {
        GroupColumn(
            allLabel = "Alla filmer",
            total = if (state.versions.isEmpty) library.movies.size else state.versions.movieCount,
            groups = state.visibleGroups(LibraryFilter.MOVIE, library.movieGroups),
            counts = library.movieCounts,
            selected = group,
            onSelect = { state.movieGroup = it },
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (spotlight != null) {
                val tmdb = state.tmdbFor(spotlight)
                SpotlightBackdrop(
                    url = tmdb?.backdrop,
                    fallback = state.posterCandidates(spotlight).firstOrNull(),
                    running = state.nowPlaying == null || state.miniPlayer,
                )
            }
            Column(Modifier.fillMaxSize()) {
                ScreenHeader(
                    title = group ?: "Alla filmer",
                    subtitle = "${movies.size.grouped()} filmer",
                    query = query,
                    onQuery = { state.movieQuery = it },
                    placeholder = "Sök film",
                )
                SortRow(state.movieSort, series = false, onSelect = { state.sortMovies(it) })
                if (spotlight != null) {
                    val tmdb = state.tmdbFor(spotlight)
                    val facts = state.movieFacts[spotlight.key]
                    SpotlightInfo(
                        state = state,
                        kind = "movie",
                        tmdbId = tmdb?.id,
                        title = state.titleOf(spotlight),
                        meta = listOfNotNull(
                            (tmdb?.year ?: facts?.year)?.toString(),
                            formatRating(tmdb?.rating ?: spotlight.rating),
                            tmdb?.genres?.ifBlank { null } ?: facts?.genre?.ifBlank { null },
                        ).joinToString("  ·  ").ifEmpty { null },
                        overview = tmdb?.overview?.ifBlank { null },
                    )
                }
                if (movies.isEmpty()) {
                    EmptyState(title = "Inga träffar", body = "Ingen film matchar sökningen.")
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (tvLayout) TV_POSTER_MIN else 152.dp),
                            state = gridState,
                            // Tv: luft runt omslagen, så att det markerade har plats att växa.
                            contentPadding = if (tvLayout) {
                                PaddingValues(start = 24.dp, end = 32.dp, top = 14.dp, bottom = 40.dp)
                            } else {
                                PaddingValues(start = 20.dp, end = 28.dp, bottom = 28.dp)
                            },
                            horizontalArrangement = Arrangement.spacedBy(if (tvLayout) 14.dp else 8.dp),
                            verticalArrangement = Arrangement.spacedBy(if (tvLayout) 18.dp else 12.dp),
                        ) {
                            gridItems(movies, key = { it.key }) { movie ->
                                PosterCard(
                                    title = state.titleOf(movie),
                                    images = state.posterCandidates(movie),
                                    meta = movieMeta(state, movie),
                                    onClick = {
                                        openedKey = movie.key
                                        state.showMovie(movie)
                                    },
                                    modifier = if (movie.key == openedKey) Modifier.focusRequester(restore) else Modifier,
                                    onFocusChange = { has ->
                                        if (has) focusedKey = movie.key else if (focusedKey == movie.key) focusedKey = null
                                    },
                                    onActive = { if (!tvLayout) hoveredKey = movie.key },
                                    badge = state.badgeFor(movie),
                                )
                            }
                        }
                        Scrollbar(gridState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 6.dp, bottom = 12.dp))
                    }
                }
            }
        }
    }
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

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(460.dp)) {
            KenBurns(running = state.nowPlaying == null || state.miniPlayer, modifier = Modifier.fillMaxSize()) {
                Artwork(
                    candidates = listOfNotNull(info?.backdrop, state.tmdbFor(movie)?.backdrop, info?.cover) + state.posterCandidates(movie),
                    modifier = Modifier.fillMaxSize(),
                    maxSide = 1600,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to palette.background.copy(alpha = 0.5f),
                        0.65f to palette.background.copy(alpha = 0.88f),
                        1f to palette.background,
                    ),
                ),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(start = 36.dp, end = 36.dp, top = 88.dp, bottom = 36.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .width(220.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.surfaceRaised),
                ) {
                    Artwork(
                        candidates = state.posterCandidates(movie) + listOfNotNull(info?.cover),
                        modifier = Modifier.fillMaxSize(),
                        maxSide = 600,
                        placeholder = { PosterPlaceholder(state.titleOf(movie)) },
                    )
                }
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TitleLogo(
                        state = state,
                        kind = "movie",
                        tmdbId = state.tmdbIdOf(movie),
                        title = state.titleOf(movie),
                        style = MaterialTheme.typography.displaySmall,
                        maxHeight = 120.dp,
                        maxWidth = 520.dp,
                        maxLines = 3,
                    )
                    val facts = listOfNotNull(
                        yearOf(info?.releaseDate),
                        info?.genre?.takeIf { it.isNotBlank() },
                        formatRuntime(info?.durationSeconds),
                        formatRating(info?.rating ?: movie.rating),
                    )
                    if (facts.isNotEmpty()) {
                        Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodyLarge, color = palette.textSecondary)
                    }
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
                        PrimaryButton(if (resumable) "Fortsätt" else "Spela", onClick = { state.playMovie(movie) }, icon = Glyphs.Play)
                        trailerUrl(info?.trailer)?.let { url ->
                            SecondaryButton("Trailer", onClick = { openInBrowser(url) }, icon = Glyphs.Movies)
                        }
                        val saved = state.inMyList(movie)
                        SecondaryButton(
                            if (saved) "I min lista" else "Min lista",
                            onClick = { state.toggleMyList(movie) },
                            icon = if (saved) Glyphs.Check else Glyphs.Add,
                        )
                    }
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
    if (plot != null) {
        Text(
            plot,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.textSecondary,
            modifier = Modifier.widthIn(max = 780.dp),
        )
    }
    if (director != null) Credit("Regi", director)
    if (cast != null) Credit("Skådespelare", cast)
}

@Composable
private fun Credit(label: String, value: String) {
    val palette = LocalPalette.current
    Row(Modifier.widthIn(max = 780.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textTertiary,
            modifier = Modifier.width(120.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

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
    val series = remember(library, group, query, state.seriesSort, state.tmdbFacts, state.versions) { state.filteredSeries() }
    var spotlightKey by remember(series) { mutableStateOf(series.firstOrNull()?.key) }
    var hoveredKey by remember { mutableStateOf<String?>(null) }
    val activeKey = if (tvLayout) focusedKey else hoveredKey
    LaunchedEffect(activeKey) {
        val key = activeKey ?: return@LaunchedEffect
        delay(if (tvLayout) 180 else 260)
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

    Row(Modifier.fillMaxSize()) {
        GroupColumn(
            allLabel = "Alla serier",
            total = if (state.versions.isEmpty) library.series.size else state.versions.seriesCount,
            groups = state.visibleGroups(LibraryFilter.SERIES, library.seriesGroups),
            counts = library.seriesCounts,
            selected = group,
            onSelect = { state.seriesGroup = it },
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
                ScreenHeader(
                    title = group ?: "Alla serier",
                    subtitle = "${series.size.grouped()} serier",
                    query = query,
                    onQuery = { state.seriesQuery = it },
                    placeholder = "Sök serie",
                )
                SortRow(state.seriesSort, series = true, onSelect = { state.sortSeries(it) })
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
                if (series.isEmpty()) {
                    EmptyState(title = "Inga träffar", body = "Ingen serie matchar sökningen.")
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (tvLayout) TV_POSTER_MIN else 152.dp),
                            state = gridState,
                            contentPadding = if (tvLayout) {
                                PaddingValues(start = 24.dp, end = 32.dp, top = 14.dp, bottom = 40.dp)
                            } else {
                                PaddingValues(start = 20.dp, end = 28.dp, bottom = 28.dp)
                            },
                            horizontalArrangement = Arrangement.spacedBy(if (tvLayout) 14.dp else 8.dp),
                            verticalArrangement = Arrangement.spacedBy(if (tvLayout) 18.dp else 12.dp),
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
                                    modifier = if (item.key == openedKey) Modifier.focusRequester(restore) else Modifier,
                                    onFocusChange = { has ->
                                        if (has) focusedKey = item.key else if (focusedKey == item.key) focusedKey = null
                                    },
                                    onActive = { if (!tvLayout) hoveredKey = item.key },
                                    badge = state.badgeFor(item),
                                )
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
    Column(Modifier.fillMaxSize()) {
        // Huvudet: bakgrundsbild tonad mot svart, omslag och fakta.
        Box(Modifier.fillMaxWidth().height(if (all.size > 1) 350.dp else 300.dp)) {
            KenBurns(running = state.nowPlaying == null || state.miniPlayer, modifier = Modifier.fillMaxSize()) {
                Artwork(
                    candidates = listOfNotNull(series.backdrop, state.tmdbFor(series)?.backdrop) + state.posterCandidates(series),
                    modifier = Modifier.fillMaxSize(),
                    maxSide = 1600,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to palette.background.copy(alpha = 0.55f),
                        0.6f to palette.background.copy(alpha = 0.85f),
                        1f to palette.background,
                    ),
                ),
            )
            Row(
                Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    Modifier
                        .width(150.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.surfaceRaised),
                ) {
                    Artwork(
                        candidates = state.posterCandidates(series),
                        modifier = Modifier.fillMaxSize(),
                        maxSide = 480,
                        placeholder = { PosterPlaceholder(state.titleOf(series)) },
                    )
                }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TitleLogo(
                        state = state,
                        kind = "tv",
                        tmdbId = state.tmdbIdOf(series),
                        title = state.titleOf(series),
                        style = MaterialTheme.typography.displaySmall,
                        maxHeight = 104.dp,
                        maxWidth = 480.dp,
                        maxLines = 2,
                    )
                    val facts = listOfNotNull(series.year, series.genre?.takeIf { it.isNotBlank() }, formatRating(series.rating))
                    if (facts.isNotEmpty()) {
                        Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
                    }
                    // Handlingen på svenska från TMDB när den finns, annars leverantörens text.
                    val shownPlot = state.tmdbFor(series)?.overview?.takeIf { it.isNotBlank() }
                        ?: series.plot?.takeIf { it.isNotBlank() }
                    shownPlot?.let { plot ->
                        Text(
                            plot,
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.textSecondary,
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
                        latestEpisode(state.history, series)?.let { resume ->
                            PrimaryButton(
                                if (resume.upNext) "Spela S${resume.season} E${resume.episode}" else "Fortsätt S${resume.season} E${resume.episode}",
                                onClick = { state.playFromHistory(resume) },
                                icon = Glyphs.Play,
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
    var season by remember(series.key) { mutableStateOf(seasons.first()) }
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
    rank: Int? = null,
    modifier: Modifier = Modifier,
    /** Tv: tonas ner medan ett annat omslag är markerat. */
    dimmed: Boolean = false,
    /** När markeringen kommer till omslaget eller lämnar det. */
    onFocusChange: (Boolean) -> Unit = {},
    /** När musen eller markeringen kommer till omslaget. */
    onActive: () -> Unit = {},
    /** Liten skylt uppe i hörnet, som "4K". */
    badge: String? = null,
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
        Column(Modifier.fillMaxWidth().padding(6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    // Glöden och kanten visar vilken titel man står på.
                    .glow(if (hovered) palette.glowOf(glowAlpha) else Color.Transparent, RoundedCornerShape(10.dp), glowRadius)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.surfaceRaised)
                    .then(if (hovered) Modifier.border(ring, palette.focus.copy(alpha = ringAlpha), RoundedCornerShape(10.dp)) else Modifier),
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
                // Placeringen i en topplista. De tre första i accentfärgen.
                if (rank != null) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "#$rank",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (rank <= 3) palette.focus else Color.White,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (meta != null) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = palette.textTertiary, maxLines = 1)
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
private fun SpotlightBackdrop(url: String?, fallback: String?, running: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(440.dp)
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
private val TV_POSTER_MIN = 200.dp

/**
 * Titeln man står på, med logga, år, betyg, genrer och handling. Fast höjd,
 * så att omslagen inte hoppar när markeringen flyttas.
 */
@Composable
private fun SpotlightInfo(state: AppState, kind: String, tmdbId: Int?, title: String, meta: String?, overview: String?) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .height(if (tvLayout) 196.dp else 176.dp)
            .padding(start = 24.dp, end = 36.dp, top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TitleLogo(
            state = state,
            kind = kind,
            tmdbId = tmdbId,
            title = title,
            style = MaterialTheme.typography.headlineMedium,
            maxHeight = 64.dp,
            maxWidth = 380.dp,
            maxLines = 1,
        )
        if (meta != null) {
            Text(meta, style = MaterialTheme.typography.titleSmall, color = palette.focus, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (overview != null) {
            Text(
                overview,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
                maxLines = if (tvLayout) 4 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.72f),
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

package se.norrsken.tv.desktop.ui

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
    val movies = remember(library, group, query, state.movieSort, state.tmdbFacts) { state.filteredMovies() }
    // Tv: filmen överst följer markeringen, med en kort fördröjning så att
    // snabbt bläddrande inte hämtar en bakgrundsbild per omslag man passerar.
    var spotlightKey by remember(movies) { mutableStateOf(movies.firstOrNull()?.key) }
    if (tvLayout) {
        LaunchedEffect(focusedKey) {
            val key = focusedKey ?: return@LaunchedEffect
            delay(180)
            spotlightKey = key
        }
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
            total = library.movies.size,
            groups = library.movieGroups,
            counts = library.movieCounts,
            selected = group,
            onSelect = { state.movieGroup = it },
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (tvLayout && spotlight != null) {
                val tmdb = state.tmdbFor(spotlight)
                SpotlightBackdrop(tmdb?.backdrop)
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
                if (tvLayout && spotlight != null) {
                    val tmdb = state.tmdbFor(spotlight)
                    val facts = state.movieFacts[spotlight.key]
                    SpotlightInfo(
                        title = spotlight.name,
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
                            columns = GridCells.Adaptive(if (tvLayout) 164.dp else 152.dp),
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
                                    title = movie.name,
                                    imageUrl = movie.poster ?: state.tmdbFor(movie)?.poster,
                                    meta = formatRating(state.tmdbFor(movie)?.rating ?: movie.rating),
                                    onClick = {
                                        openedKey = movie.key
                                        state.showMovie(movie)
                                    },
                                    modifier = if (movie.key == openedKey) Modifier.focusRequester(restore) else Modifier,
                                    dimmed = tvLayout && focusedKey != null && focusedKey != movie.key,
                                    onFocusChange = { has ->
                                        if (has) focusedKey = movie.key else if (focusedKey == movie.key) focusedKey = null
                                    },
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
            RemoteImage(
                url = info?.backdrop ?: info?.cover ?: movie.poster,
                modifier = Modifier.fillMaxSize(),
                maxSide = 1600,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
            )
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
                    RemoteImage(url = movie.poster ?: info?.cover, modifier = Modifier.fillMaxSize(), maxSide = 600)
                }
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(movie.name, style = MaterialTheme.typography.displaySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val facts = listOfNotNull(
                        yearOf(info?.releaseDate),
                        info?.genre?.takeIf { it.isNotBlank() },
                        formatRuntime(info?.durationSeconds),
                        formatRating(info?.rating ?: movie.rating),
                    )
                    if (facts.isNotEmpty()) {
                        Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodyLarge, color = palette.textSecondary)
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
                        MyListButton(state, ListKind.MOVIE, movie.key)
                    }
                    when (infoState) {
                        MovieInfoState.Loading -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Spinner(size = 18.dp)
                            Text("Hämtar information…", style = MaterialTheme.typography.bodyMedium, color = palette.textTertiary)
                        }
                        is MovieInfoState.Failed -> Text(
                            "Informationen gick inte att hämta. ${infoState.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textTertiary,
                        )
                        is MovieInfoState.Loaded -> MovieFacts(infoState.info)
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
private fun MovieFacts(info: XtreamVodInfo?) {
    val palette = LocalPalette.current
    val plot = info?.plot?.takeIf { it.isNotBlank() }
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
    val series = remember(library, group, query, state.seriesSort, state.tmdbFacts) { state.filteredSeries() }
    var spotlightKey by remember(series) { mutableStateOf(series.firstOrNull()?.key) }
    if (tvLayout) {
        LaunchedEffect(focusedKey) {
            val key = focusedKey ?: return@LaunchedEffect
            delay(180)
            spotlightKey = key
        }
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
            total = library.series.size,
            groups = library.seriesGroups,
            counts = library.seriesCounts,
            selected = group,
            onSelect = { state.seriesGroup = it },
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (tvLayout && spotlight != null) {
                val tmdb = state.tmdbFor(spotlight)
                SpotlightBackdrop(spotlight.backdrop ?: tmdb?.backdrop)
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
                if (tvLayout && spotlight != null) {
                    val tmdb = state.tmdbFor(spotlight)
                    SpotlightInfo(
                        title = spotlight.name,
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
                            columns = GridCells.Adaptive(if (tvLayout) 164.dp else 152.dp),
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
                                    title = item.name,
                                    imageUrl = item.cover ?: state.tmdbFor(item)?.poster,
                                    meta = listOfNotNull(item.year, formatRating(state.tmdbFor(item)?.rating ?: item.rating)).joinToString("  ").ifEmpty { null },
                                    onClick = {
                                        openedKey = item.key
                                        state.showSeries(item)
                                    },
                                    modifier = if (item.key == openedKey) Modifier.focusRequester(restore) else Modifier,
                                    dimmed = tvLayout && focusedKey != null && focusedKey != item.key,
                                    onFocusChange = { has ->
                                        if (has) focusedKey = item.key else if (focusedKey == item.key) focusedKey = null
                                    },
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
    Column(Modifier.fillMaxSize()) {
        // Huvudet: bakgrundsbild tonad mot svart, omslag och fakta.
        Box(Modifier.fillMaxWidth().height(300.dp)) {
            RemoteImage(
                url = series.backdrop ?: series.cover,
                modifier = Modifier.fillMaxSize(),
                maxSide = 1600,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
            )
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
                    RemoteImage(url = series.cover, modifier = Modifier.fillMaxSize(), maxSide = 480)
                }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(series.name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val facts = listOfNotNull(series.year, series.genre?.takeIf { it.isNotBlank() }, formatRating(series.rating))
                    if (facts.isNotEmpty()) {
                        Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
                    }
                    series.plot?.takeIf { it.isNotBlank() }?.let { plot ->
                        Text(
                            plot,
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.textSecondary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 780.dp),
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
                        MyListButton(state, ListKind.SERIES, series.key)
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
                onAction = { state.showSeries(series) },
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
    imageUrl: String?,
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
                RemoteImage(
                    url = imageUrl,
                    modifier = Modifier.fillMaxSize(),
                    maxSide = 480,
                    placeholder = {
                        Text(
                            title,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.textTertiary,
                            textAlign = TextAlign.Center,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
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
 * bakgrundsbild visas ingen.
 */
@Composable
private fun SpotlightBackdrop(url: String?) {
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
        Crossfade(targetState = url, animationSpec = tween(450), label = "bakgrund") { current ->
            if (current != null) {
                RemoteImage(
                    url = current,
                    modifier = Modifier.fillMaxSize(),
                    maxSide = 1280,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            }
        }
    }
}

/** Tv: titeln man står på, med år, betyg, genrer och handling. Fast höjd, så att omslagen inte hoppar. */
@Composable
private fun SpotlightInfo(title: String, meta: String?, overview: String?) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .height(196.dp)
            .padding(start = 24.dp, end = 36.dp, top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (meta != null) {
            Text(meta, style = MaterialTheme.typography.titleSmall, color = palette.focus, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (overview != null) {
            Text(
                overview,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.72f),
            )
        }
    }
}

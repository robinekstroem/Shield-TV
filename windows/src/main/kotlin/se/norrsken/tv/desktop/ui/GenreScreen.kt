package se.norrsken.tv.desktop.ui

import se.norrsken.tv.desktop.data.MovieFactsStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.SortOrder
import se.norrsken.tv.desktop.data.Genre
import se.norrsken.tv.desktop.data.GenreIndex

/**
 * Genrer: välj en genre och få filmerna eller serierna i den som en
 * topplista, bäst betyg först. Genrerna kommer ur leverantörens kategorinamn
 * och ur seriernas genretext; betyget är leverantörens, oftast från TMDB.
 */
@Composable
fun GenreScreen(state: AppState) {
    val palette = LocalPalette.current
    // Före förgreningarna: tillbaka från en film eller serie ska landa där man var.
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.genre, state.genreQuery, state.genreSort, state.genreShowsSeries) { gridState.scrollToItem(0) }
    // Tv: var markeringen landar från genrelistan.
    val entry = remember { TvEntry() }
    val scope = rememberCoroutineScope()

    val openMovie = state.selectedMovie
    if (openMovie != null) {
        MovieDetail(state, openMovie)
        return
    }
    val openSeries = state.selectedSeries
    if (openSeries != null) {
        SeriesDetail(state, openSeries)
        return
    }
    // Tv: tillbaka från en film eller serie hamnar markeringen på omslaget man öppnade.
    if (tvLayout) {
        LaunchedEffect(Unit) {
            if (entry.last != null) entry.enter { gridState.layoutInfo.visibleItemsInfo.map { it.key } }
        }
    }

    val library = state.library
    if (library.movies.isEmpty() && library.series.isEmpty()) {
        NoLibrary(state)
        return
    }

    val facts = state.movieFacts
    val tmdb = state.tmdbFacts
    val index = remember(library, facts, tmdb) {
        GenreIndex.build(library, facts, { state.tmdbFor(it)?.genres }, { state.tmdbFor(it)?.genres })
    }
    val showSeries = state.genreShowsSeries
    val genre = Genre.entries.firstOrNull { it.name == state.genre }
    val query = state.genreQuery.trim().lowercase()
    val sort = state.genreSort

    // Bara genrer som faktiskt har något, med antal: varje film en gång, hur många versioner den än har.
    val versions = state.versions
    val available = remember(index, showSeries, versions) {
        Genre.entries
            .map { entry ->
                entry to if (showSeries) {
                    index.series[entry].orEmpty().distinctBy { versions.groupKeyOf(it) }.size
                } else {
                    index.movies[entry].orEmpty().distinctBy { versions.groupKeyOf(it) }.size
                }
            }
            .filter { it.second > 0 }
    }
    val movies = remember(index, genre, query, sort, showSeries, versions) {
        if (showSeries) {
            emptyList()
        } else {
            (if (genre == null) library.movies else index.movies[genre].orEmpty())
                .asSequence()
                .filter { query.isEmpty() || query in it.searchKey }
                .distinctBy { it.sourceId to it.streamId }
                .toList()
                .ranked(sort, { state.scoreOf(it) }, { versions.yearOf(it) ?: facts[MovieFactsStore.keyOf(it.sourceId, it.streamId)]?.year ?: state.tmdbFor(it)?.year }, { it.addedUtc }, { state.tmdbFor(it)?.popularity })
                // En gång per film. Utan sökning visas filmens bästa version.
                .let { versions.collapseMovies(it, preferDefault = query.isEmpty()) }
        }
    }
    val series = remember(index, genre, query, sort, showSeries, versions) {
        if (!showSeries) {
            emptyList()
        } else {
            (if (genre == null) library.series else index.series[genre].orEmpty())
                .asSequence()
                .filter { query.isEmpty() || query in it.searchKey }
                .distinctBy { it.sourceId to it.seriesId }
                .toList()
                .ranked(sort, { state.scoreOf(it) }, { versions.yearOf(it) ?: state.tmdbFor(it)?.year }, { it.addedUtc }, { state.tmdbFor(it)?.popularity })
                .let { versions.collapseSeries(it, preferDefault = query.isEmpty()) }
        }
    }
    val count = if (showSeries) series.size else movies.size
    val what = if (showSeries) "serier" else "filmer"
    val how = when (sort) {
        SortOrder.POPULAR -> "populärast först"
        SortOrder.YEAR -> "nyast först"
        SortOrder.ADDED -> if (showSeries) "senast uppdaterade först" else "senast tillagda först"
        else -> "rankade efter betyg"
    }

    // Medan filmernas genrer hämtas fylls listorna på; säg hur långt det kommit.
    val scanProgress = if (state.tmdbConnected) state.tmdbProgress else state.factsProgress.takeIf { !showSeries }
    val scanNote = scanProgress
        ?.takeIf { (done, total) -> done < total }
        ?.let { (done, total) -> " · genrer hämtas: ${done.grouped()} av ${total.grouped()}" }
        .orEmpty()

    Row(Modifier.fillMaxSize()) {
        GroupColumn(
            allLabel = "Topplistan",
            total = when {
                versions.isEmpty -> if (showSeries) library.series.size else library.movies.size
                showSeries -> versions.seriesCount
                else -> versions.movieCount
            },
            groups = available.map { it.first.label },
            counts = available.associate { it.first.label to it.second },
            selected = genre?.label,
            onSelect = { label -> state.genre = Genre.entries.firstOrNull { it.label == label }?.name },
            title = "GENRER",
            onEnterContent = {
                scope.launch { entry.enter { gridState.layoutInfo.visibleItemsInfo.map { it.key } } }
            },
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ScreenHeader(
                title = genre?.label ?: "Topplistan",
                subtitle = "${count.grouped()} $what, $how" + scanNote,
                query = state.genreQuery,
                onQuery = { state.genreQuery = it },
                placeholder = if (showSeries) "Sök serie" else "Sök film",
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("Filmer", selected = !showSeries, onClick = { state.genreShowsSeries = false })
                Chip("Serier", selected = showSeries, onClick = { state.genreShowsSeries = true })
                Spacer(Modifier.width(16.dp))
                Text("Rangordna", style = MaterialTheme.typography.labelMedium, color = palette.textTertiary)
                for (order in listOf(SortOrder.POPULAR, SortOrder.RATING, SortOrder.YEAR, SortOrder.ADDED)) {
                    val label = if (showSeries && order == SortOrder.ADDED) "Senast uppdaterade" else order.label
                    Chip(label, selected = sort == order, onClick = { state.genreSort = order })
                }
            }
            if (count == 0) {
                EmptyState(
                    title = "Inget här",
                    body = if (query.isNotEmpty()) "Inget i genren matchar sökningen." else "Leverantören har inga $what i den här genren.",
                )
            } else {
                // Numreras bara när listan faktiskt är rangordnad efter betyg.
                val ranked = sort == SortOrder.RATING || sort == SortOrder.POPULAR
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TvScrolling(lead = 0.05f, trail = 0.08f, snap = true) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (tvLayout) 176.dp else 152.dp),
                            state = gridState,
                            contentPadding = if (tvLayout) {
                                PaddingValues(start = 40.dp, end = 40.dp, top = 10.dp, bottom = 36.dp)
                            } else {
                                PaddingValues(start = 20.dp, end = 28.dp, bottom = 28.dp)
                            },
                            horizontalArrangement = Arrangement.spacedBy(if (tvLayout) 14.dp else 8.dp),
                            verticalArrangement = Arrangement.spacedBy(if (tvLayout) 16.dp else 12.dp),
                        ) {
                            if (showSeries) {
                                itemsIndexed(series, key = { _, item -> item.key }) { position, item ->
                                    PosterCard(
                                        title = state.titleOf(item),
                                        images = state.posterCandidates(item),
                                        meta = seriesMeta(state, item),
                                        onClick = { state.showSeries(item) },
                                        rank = if (ranked) position + 1 else null,
                                        modifier = entry.mark(item.key),
                                        onFocusChange = { has -> if (has) entry.last = item.key },
                                        badge = state.badgeFor(item),
                                    )
                                }
                            } else {
                                itemsIndexed(movies, key = { _, item -> item.key }) { position, item ->
                                    PosterCard(
                                        title = state.titleOf(item),
                                        images = state.posterCandidates(item),
                                        meta = movieMeta(state, item),
                                        onClick = { state.showMovie(item) },
                                        rank = if (ranked) position + 1 else null,
                                        modifier = entry.mark(item.key),
                                        onFocusChange = { has -> if (has) entry.last = item.key },
                                        badge = state.badgeFor(item),
                                    )
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

/** Stabil rangordning: lika värden behåller ordningen, saknat värde hamnar sist. */
private fun <T> List<T>.ranked(
    order: SortOrder,
    rating: (T) -> Double?,
    year: (T) -> Int?,
    added: (T) -> Long,
    popularity: (T) -> Double?,
): List<T> =
    when (order) {
        SortOrder.POPULAR -> sortedWith(compareByDescending<T> { popularity(it) ?: -1.0 }.thenByDescending { rating(it) ?: -1.0 })
        SortOrder.YEAR -> sortedWith(compareByDescending<T> { year(it) ?: -1 }.thenByDescending { rating(it) ?: -1.0 })
        SortOrder.ADDED -> sortedByDescending { added(it) }
        else -> sortedWith(compareByDescending<T> { rating(it) ?: -1.0 }.thenByDescending { added(it) })
    }

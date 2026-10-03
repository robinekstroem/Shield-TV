package se.norrsken.tv.desktop.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.data.Genre
import se.norrsken.tv.desktop.data.GenreIndex
import se.norrsken.tv.desktop.data.Library
import se.norrsken.tv.desktop.data.ListEntry
import se.norrsken.tv.desktop.data.ListKind
import se.norrsken.tv.desktop.data.LiveChannel
import se.norrsken.tv.desktop.data.Movie
import se.norrsken.tv.desktop.data.MovieFacts
import se.norrsken.tv.desktop.data.Programme
import se.norrsken.tv.desktop.data.Series
import se.norrsken.tv.desktop.data.TitleCleaner
import se.norrsken.tv.desktop.data.TmdbFacts
import se.norrsken.tv.desktop.data.TmdbStore
import se.norrsken.tv.desktop.data.WatchEntry
import se.norrsken.tv.desktop.data.WatchKind
import se.norrsken.tv.desktop.data.weightedScore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

// --- Startsidan ----------------------------------------------------------------------
//
// Titeln man står på fyller skärmen: dess bakgrund bakom allt, och titel,
// fakta, handling och knappar överst. Under den raderna att välja ur. Allt
// tungt — sortering och genrer för tiotusentals titlar — räknas i bakgrunden,
// så att sidan aldrig blir seg.

/** Det som kan stå i en rad på startsidan. Nyckeln är unik inom raden. */
internal sealed interface HomeItem {
    val key: String
}

internal data class MovieItem(val movie: Movie) : HomeItem {
    override val key: String get() = "m:" + movie.key
}

internal data class SeriesItem(val series: Series) : HomeItem {
    override val key: String get() = "s:" + series.key
}

internal data class ResumeItem(val entry: WatchEntry) : HomeItem {
    override val key: String get() = "r:" + entry.identity
}

internal data class ChannelItem(val channel: LiveChannel, val queue: List<LiveChannel>) : HomeItem {
    override val key: String get() = "c:" + channel.key
}

internal class HomeRow(val title: String, val items: List<HomeItem>)

private const val ROW = 30

@Composable
fun HomeScreen(state: AppState) {
    // Före förgreningen: sidan ska stå där den stod när man backar ur en titel.
    val listState = remember { LazyListState() }
    val rowStates = remember { HashMap<String, LazyListState>() }
    var hero by remember { mutableStateOf<HomeItem?>(null) }
    var pending by remember { mutableStateOf<HomeItem?>(null) }
    var openedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pending) {
        val next = pending ?: return@LaunchedEffect
        // Kort paus: snabbt bläddrande ska inte byta bakgrund för varje titel man passerar.
        delay(200)
        hero = next
    }

    // Film- och seriesidorna öppnas här, så att Tillbaka leder hem igen.
    state.selectedMovie?.let {
        MovieDetail(state, it)
        return
    }
    state.selectedSeries?.let {
        SeriesDetail(state, it)
        return
    }

    val library = state.library
    if (library.movies.isEmpty() && library.series.isEmpty() && library.channels.isEmpty()) {
        if (!state.loading) NoLibrary(state)
        return
    }
    val tmdb = state.tmdbFacts
    val facts = state.movieFacts
    val catalog by produceState<HomeCatalog?>(null, library, tmdb, facts) {
        // TMDB fylls på i omgångar. Vänta tills det lugnat sig innan allt räknas om.
        if (value != null) delay(1_500)
        value = withContext(Dispatchers.Default) { buildCatalog(library, tmdb, facts) }
    }
    val current = catalog
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Startsidan ordnas…", style = MaterialTheme.typography.bodyLarge, color = LocalPalette.current.textTertiary)
        }
        return
    }
    val history = state.history
    val myList = state.myList
    val rows = remember(current, history, myList) { current.rowsFor(history, myList) }
    val shown = hero?.takeIf { item -> rows.any { row -> row.items.any { it.key == item.key } } }
        ?: rows.firstNotNullOfOrNull { row -> row.items.firstOrNull { it !is ChannelItem } }

    // Tv: markeringen börjar på Spela, så att man ser var man är. Backar man
    // ur en titel hamnar den på kortet man öppnade.
    val firstFocus = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    if (tvLayout) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            val restored = openedKey != null && runCatching { restore.requestFocus() }.isSuccess
            if (!restored) runCatching { firstFocus.requestFocus() }
        }
    }
    val heroHeight = if (tvLayout) 340.dp else 320.dp

    Box(Modifier.fillMaxSize()) {
        HeroBackdrop(backdropOf(state, current, shown), heroHeight + 160.dp)
        Column(Modifier.fillMaxSize()) {
            HeroInfo(state, current, shown, heroHeight, firstFocus, onOpen = { openedKey = it })
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 40.dp),
            ) {
                items(rows, key = { it.title }) { row ->
                    HomeRowView(
                        state = state,
                        row = row,
                        rowState = rowStates.getOrPut(row.title) { LazyListState() },
                        restoreKey = openedKey,
                        restore = restore,
                        onActive = { pending = it },
                        onOpen = { openedKey = it.key },
                    )
                }
            }
        }
    }
}

// --- Bakgrund och information ------------------------------------------------------

/**
 * Titelns bakgrundsbild upptill. Den tonar ut mot vänster och nedåt till
 * appens vanliga bakgrund — temat och egna bakgrundsbilder syns under — i
 * stället för att täcka sidan. Utan en riktig bakgrundsbild visas ingen.
 */
@Composable
private fun HeroBackdrop(url: String?, height: Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
                drawRect(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.22f), 0.5f to Color.Black, 1f to Color.Black),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        Crossfade(targetState = url, animationSpec = tween(500), label = "startbakgrund") { current ->
            if (current != null) {
                RemoteImage(
                    url = current,
                    modifier = Modifier.fillMaxSize(),
                    maxSide = 1920,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            }
        }
    }
}

/** Det som visas överst för en titel. */
private class Hero(
    val kicker: String?,
    val title: String,
    val meta: String?,
    val overview: String?,
    val playLabel: String,
    val play: () -> Unit,
    val more: (() -> Unit)?,
    /** Null: ingen Min lista-knapp. */
    val saved: Boolean?,
    val toggle: () -> Unit,
    val channel: Boolean = false,
)

@Composable
private fun HeroInfo(
    state: AppState,
    catalog: HomeCatalog,
    item: HomeItem?,
    height: Dp,
    firstFocus: FocusRequester,
    onOpen: (String) -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .padding(start = 40.dp, end = 40.dp, top = 26.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (item == null) return@Row
        val hero = heroOf(state, catalog, item)
        val channel = item is ChannelItem
        // Omslaget skarpt bredvid texten: det man väljer syns alltid, även utan bakgrundsbild.
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(if (channel) 16f / 9f else 2f / 3f, matchHeightConstraintsFirst = true)
                .shadow(16.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(palette.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            RemoteImage(
                url = posterOf(state, catalog, item),
                modifier = Modifier.fillMaxSize().then(if (channel) Modifier.padding(24.dp) else Modifier),
                maxSide = 600,
                contentScale = if (channel) ContentScale.Fit else ContentScale.Crop,
                placeholder = {
                    Text(
                        hero.title,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textTertiary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hero.kicker != null) {
                Text(hero.kicker.uppercase(), style = MaterialTheme.typography.labelLarge, color = palette.focus)
            }
            Text(
                hero.title,
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 820.dp),
            )
            if (hero.meta != null) {
                Text(hero.meta, style = MaterialTheme.typography.titleSmall, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hero.overview != null) {
                Text(
                    hero.overview,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 720.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(hero.playLabel, onClick = hero.play, modifier = Modifier.focusRequester(firstFocus), icon = Glyphs.Play)
                hero.more?.let { more ->
                    SecondaryButton("Mer info", onClick = {
                        onOpen(item.key)
                        more()
                    }, icon = Glyphs.Info)
                }
                hero.saved?.let { saved ->
                    val label = when {
                        hero.channel && saved -> "Favorit"
                        hero.channel -> "Lägg till favorit"
                        saved -> "I min lista"
                        else -> "Min lista"
                    }
                    val icon = when {
                        hero.channel -> if (saved) Glyphs.Star else Glyphs.StarOutline
                        else -> if (saved) Glyphs.Check else Glyphs.Add
                    }
                    SecondaryButton(label, onClick = hero.toggle, icon = icon)
                }
            }
        }
    }
}

private fun heroOf(state: AppState, catalog: HomeCatalog, item: HomeItem): Hero = when (item) {
    is MovieItem -> movieHero(state, item.movie, null)
    is SeriesItem -> seriesHero(state, item.series, null)
    is ResumeItem -> {
        val entry = item.entry
        val movie = if (entry.kind == WatchKind.MOVIE) catalog.movieByStream["${entry.sourceId}|${entry.itemId}"] else null
        val series = if (entry.kind == WatchKind.EPISODE) catalog.seriesById["${entry.sourceId}|${entry.seriesId}"] else null
        when {
            movie != null -> movieHero(state, movie, entry)
            series != null -> seriesHero(state, series, entry)
            else -> Hero(
                kicker = "Fortsätt titta",
                title = entry.title,
                meta = listOfNotNull(entry.subtitle, timeLeft(entry)).joinToString("  ·  ").ifEmpty { null },
                overview = null,
                playLabel = "Fortsätt",
                play = { state.playFromHistory(entry) },
                more = null,
                saved = null,
                toggle = {},
            )
        }
    }
    is ChannelItem -> {
        val channel = item.channel
        val now = state.guide.current(channel, state.clock)
        val next = state.guide.next(channel, state.clock)
        Hero(
            kicker = "Live-tv",
            title = channel.name,
            meta = now?.let { "Nu: ${it.title}  ·  ${clockOf(it.start)}–${clockOf(it.stop)}" },
            overview = now?.description?.ifBlank { null } ?: next?.let { "Sedan ${clockOf(it.start)}: ${it.title}" },
            playLabel = "Titta",
            play = { state.playChannel(channel, item.queue) },
            more = null,
            saved = state.inMyList(channel.key),
            toggle = { state.toggleMyList(ListKind.CHANNEL, channel.key) },
            channel = true,
        )
    }
}

private fun movieHero(state: AppState, movie: Movie, resume: WatchEntry?): Hero {
    val tmdb = state.tmdbFor(movie)
    val facts = state.movieFacts[movie.key]
    val earlier = resume ?: state.history.firstOrNull {
        it.kind == WatchKind.MOVIE && it.sourceId == movie.sourceId && it.itemId == movie.streamId.toString()
    }?.takeIf { it.resumable }
    return Hero(
        kicker = if (resume != null) "Fortsätt titta" else null,
        title = movie.name,
        meta = listOfNotNull(
            (tmdb?.year ?: facts?.year)?.toString(),
            formatRating(tmdb?.rating ?: movie.rating),
            tmdb?.genres?.ifBlank { null } ?: facts?.genre?.ifBlank { null },
        ).joinToString("  ·  ").ifEmpty { null },
        overview = tmdb?.overview?.ifBlank { null },
        playLabel = if (earlier != null) timeLeft(earlier)?.let { "Fortsätt  ·  $it" } ?: "Fortsätt" else "Spela",
        play = { state.playMovie(movie) },
        more = { state.showMovie(movie) },
        saved = state.inMyList(movie.key),
        toggle = { state.toggleMyList(ListKind.MOVIE, movie.key) },
    )
}

private fun seriesHero(state: AppState, series: Series, resume: WatchEntry?): Hero {
    val tmdb = state.tmdbFor(series)
    val latest = resume ?: latestEpisode(state.history, series)
    val episode = latest?.let { "S${it.season} E${it.episode}" }
    return Hero(
        kicker = when {
            resume?.upNext == true -> "Nästa avsnitt"
            resume != null -> "Fortsätt titta"
            else -> null
        },
        title = series.name,
        meta = if (resume != null) {
            listOfNotNull(resume.subtitle, if (resume.upNext) null else timeLeft(resume)).joinToString("  ·  ").ifEmpty { null }
        } else {
            listOfNotNull(
                series.year,
                formatRating(tmdb?.rating ?: series.rating),
                tmdb?.genres?.ifBlank { null } ?: series.genre?.ifBlank { null },
            ).joinToString("  ·  ").ifEmpty { null }
        },
        overview = tmdb?.overview?.ifBlank { null } ?: series.plot?.ifBlank { null },
        playLabel = when {
            latest == null -> "Visa avsnitt"
            latest.upNext -> "Spela $episode"
            else -> "Fortsätt $episode"
        },
        play = { if (latest != null) state.playFromHistory(latest) else state.showSeries(series) },
        more = { state.showSeries(series) },
        saved = state.inMyList(series.key),
        toggle = { state.toggleMyList(ListKind.SERIES, series.key) },
    )
}

/** Senaste påbörjade eller föreslagna avsnittet i en serie. */
internal fun latestEpisode(history: List<WatchEntry>, series: Series): WatchEntry? =
    history.firstOrNull {
        it.kind == WatchKind.EPISODE && it.sourceId == series.sourceId && it.seriesId == series.seriesId && (it.resumable || it.upNext)
    }

private fun timeLeft(entry: WatchEntry): String? {
    if (entry.durationMs <= 0L) return null
    val minutes = ((entry.durationMs - entry.positionMs) / 60_000L).coerceAtLeast(1L)
    return "$minutes min kvar"
}

private val homeClock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun clockOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(homeClock)

private fun backdropOf(state: AppState, catalog: HomeCatalog, item: HomeItem?): String? = when (item) {
    is MovieItem -> state.tmdbFor(item.movie)?.backdrop
    is SeriesItem -> item.series.backdrop ?: state.tmdbFor(item.series)?.backdrop
    is ResumeItem -> {
        val entry = item.entry
        when (entry.kind) {
            WatchKind.MOVIE -> catalog.movieByStream["${entry.sourceId}|${entry.itemId}"]?.let { state.tmdbFor(it)?.backdrop }
            WatchKind.EPISODE -> catalog.seriesById["${entry.sourceId}|${entry.seriesId}"]?.let { it.backdrop ?: state.tmdbFor(it)?.backdrop }
            else -> null
        }
    }
    is ChannelItem -> null
    null -> null
}

/** Omslaget (eller kanalens logga) som visas skarpt bredvid texten överst. */
private fun posterOf(state: AppState, catalog: HomeCatalog, item: HomeItem): String? = when (item) {
    is MovieItem -> item.movie.poster ?: state.tmdbFor(item.movie)?.poster
    is SeriesItem -> item.series.cover ?: state.tmdbFor(item.series)?.poster
    is ResumeItem -> {
        val entry = item.entry
        when (entry.kind) {
            WatchKind.MOVIE -> catalog.movieByStream["${entry.sourceId}|${entry.itemId}"]?.let { it.poster ?: state.tmdbFor(it)?.poster }
            WatchKind.EPISODE -> catalog.seriesById["${entry.sourceId}|${entry.seriesId}"]?.let { it.cover ?: state.tmdbFor(it)?.poster }
            else -> null
        } ?: entry.image
    }
    is ChannelItem -> item.channel.logo
}

// --- Raderna ----------------------------------------------------------------------------

@Composable
private fun HomeRowView(
    state: AppState,
    row: HomeRow,
    rowState: LazyListState,
    restoreKey: String?,
    restore: FocusRequester,
    onActive: (HomeItem) -> Unit,
    onOpen: (HomeItem) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(row.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 40.dp))
        LazyRow(
            state = rowState,
            // Luft runt korten, så att det markerade har plats att växa utan att klippas.
            contentPadding = PaddingValues(start = 34.dp, end = 34.dp, top = 14.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(if (tvLayout) 16.dp else 12.dp),
        ) {
            items(row.items, key = { it.key }) { item ->
                val focus = if (item.key == restoreKey) Modifier.focusRequester(restore) else Modifier
                when (item) {
                    is MovieItem -> Box(Modifier.width(if (tvLayout) 164.dp else 150.dp)) {
                        PosterCard(
                            title = item.movie.name,
                            imageUrl = item.movie.poster ?: state.tmdbFor(item.movie)?.poster,
                            meta = formatRating(state.tmdbFor(item.movie)?.rating ?: item.movie.rating),
                            onClick = {
                                onOpen(item)
                                state.showMovie(item.movie)
                            },
                            modifier = focus,
                            onActive = { onActive(item) },
                        )
                    }
                    is SeriesItem -> Box(Modifier.width(if (tvLayout) 164.dp else 150.dp)) {
                        PosterCard(
                            title = item.series.name,
                            imageUrl = item.series.cover ?: state.tmdbFor(item.series)?.poster,
                            meta = listOfNotNull(item.series.year, formatRating(state.tmdbFor(item.series)?.rating ?: item.series.rating))
                                .joinToString("  ").ifEmpty { null },
                            onClick = {
                                onOpen(item)
                                state.showSeries(item.series)
                            },
                            modifier = focus,
                            onActive = { onActive(item) },
                        )
                    }
                    is ResumeItem -> {
                        val entry = item.entry
                        WideCard(
                            title = entry.title,
                            subtitle = entry.subtitle ?: timeLeft(entry),
                            image = entry.image,
                            progress = if (entry.upNext) null else entry.progress,
                            badge = if (entry.upNext) "Nästa avsnitt" else null,
                            modifier = focus,
                            onClick = {
                                onOpen(item)
                                state.playFromHistory(entry)
                            },
                            onActive = { onActive(item) },
                        )
                    }
                    is ChannelItem -> ChannelCard(
                        channel = item.channel,
                        now = state.guide.current(item.channel, state.clock),
                        clock = state.clock,
                        modifier = focus,
                        onClick = {
                            onOpen(item)
                            state.playChannel(item.channel, item.queue)
                        },
                        onActive = { onActive(item) },
                    )
                }
            }
        }
    }
}

/** Fortsätt titta: en bred bild med hur långt man kommit. */
@Composable
private fun WideCard(
    title: String,
    subtitle: String?,
    image: String?,
    progress: Float?,
    badge: String?,
    modifier: Modifier,
    onClick: () -> Unit,
    onActive: () -> Unit,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Pressable(
        onClick = onClick,
        modifier = modifier.width(if (tvLayout) 340.dp else 300.dp),
        shape = shape,
        hoverScale = if (tvLayout) 1.07f else 1.03f,
        glowing = false,
    ) { hovered ->
        LaunchedEffect(hovered) { if (hovered) onActive() }
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .glow(if (hovered) palette.glowOf(if (tvLayout) 1f else 0.75f) else Color.Transparent, shape, if (tvLayout) 34.dp else 26.dp)
                .clip(shape)
                .background(palette.surfaceRaised)
                .then(if (hovered) Modifier.border(if (tvLayout) 3.dp else 1.dp, palette.focus.copy(alpha = if (tvLayout) 1f else 0.6f), shape) else Modifier),
        ) {
            RemoteImage(url = image, modifier = Modifier.fillMaxSize(), maxSide = 640, contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.88f))))
            if (badge != null) {
                Text(
                    badge.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.onFocus,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.focus)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = if (progress != null) 16.dp else 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(palette.focus))
                }
            }
        }
    }
}

/** En kanal: loggan på en platta, och vad som sänds nu. */
@Composable
private fun ChannelCard(
    channel: LiveChannel,
    now: Programme?,
    clock: Long,
    modifier: Modifier,
    onClick: () -> Unit,
    onActive: () -> Unit,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Pressable(
        onClick = onClick,
        modifier = modifier.width(if (tvLayout) 240.dp else 220.dp),
        shape = shape,
        hoverScale = if (tvLayout) 1.07f else 1.03f,
        glowing = false,
    ) { hovered ->
        LaunchedEffect(hovered) { if (hovered) onActive() }
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .glow(if (hovered) palette.glowOf(if (tvLayout) 1f else 0.75f) else Color.Transparent, shape, if (tvLayout) 34.dp else 26.dp)
                    .clip(shape)
                    .background(palette.surfaceRaised)
                    .then(if (hovered) Modifier.border(if (tvLayout) 3.dp else 1.dp, palette.focus.copy(alpha = if (tvLayout) 1f else 0.6f), shape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                RemoteImage(
                    url = channel.logo,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
                    maxSide = 320,
                    contentScale = ContentScale.Fit,
                    placeholder = {
                        Text(initials(channel.name), style = MaterialTheme.typography.headlineSmall, color = palette.textTertiary)
                    },
                )
                if (now != null) {
                    val span = (now.stop - now.start).coerceAtLeast(1L)
                    val done = ((clock - now.start).toFloat() / span).coerceIn(0f, 1f)
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(palette.hairline)) {
                        Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(palette.focus))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(channel.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                now?.title ?: " ",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

// --- Knappar som används på flera sidor -----------------------------------------------

/** Min lista på film- och seriesidan. */
@Composable
internal fun MyListButton(state: AppState, kind: ListKind, key: String) {
    val saved = state.inMyList(key)
    SecondaryButton(
        if (saved) "I min lista" else "Min lista",
        onClick = { state.toggleMyList(kind, key) },
        icon = if (saved) Glyphs.Check else Glyphs.Add,
    )
}

/** Stjärnan bredvid en kanal: favoritkanal eller inte. */
@Composable
internal fun FavoriteStar(saved: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Pressable(onClick = onClick, modifier = Modifier.size(44.dp), shape = CircleShape) { hovered ->
        Icon(
            if (saved) Glyphs.Star else Glyphs.StarOutline,
            contentDescription = if (saved) "Ta bort från favoritkanaler" else "Lägg till bland favoritkanaler",
            tint = when {
                saved -> palette.focus
                hovered -> palette.textPrimary
                else -> palette.textTertiary
            },
            modifier = Modifier.align(Alignment.Center).size(22.dp),
        )
    }
}

// --- Katalogen: räknas i bakgrunden ------------------------------------------------------

/** TMDB-nycklarna per titel, räknade en gång. Trådsäker: katalogen räknas i bakgrunden. */
private object HomeKeys {
    private val keys = ConcurrentHashMap<String, String>()

    fun movie(movie: Movie): String = keys.getOrPut("m:" + movie.key) {
        TitleCleaner.searchTitle(movie.name).let { (title, year) -> TmdbStore.keyOf("movie", title, year) }
    }

    fun series(series: Series): String = keys.getOrPut("s:" + series.key) {
        TitleCleaner.searchTitle(series.name).let { (title, year) -> TmdbStore.keyOf("tv", title, year ?: series.year?.toIntOrNull()) }
    }
}

/** Det som inte beror på vad man tittat på: uppslag, genrer och de stora raderna. */
internal class HomeCatalog(
    val movieByKey: Map<String, Movie>,
    val seriesByKey: Map<String, Series>,
    val channelByKey: Map<String, LiveChannel>,
    /** "källa|stream-id" (eller "källa|namn:…") → kanal, som historiken sparar dem. */
    val channelByHistoryId: Map<String, LiveChannel>,
    val movieByStream: Map<String, Movie>,
    val seriesById: Map<String, Series>,
    val genresOfMovie: Map<String, List<Genre>>,
    /** De bästa filmerna per genre, för rekommendationerna. */
    val bestByGenre: Map<Genre, List<Movie>>,
    val leading: List<HomeRow>,
    val trailing: List<HomeRow>,
) {
    /**
     * Raderna i ordning: det personliga först (Fortsätt titta, Min lista,
     * kanalerna), sedan katalogen, med Rekommenderat för dig en bit ner.
     */
    fun rowsFor(history: List<WatchEntry>, myList: List<ListEntry>): List<HomeRow> {
        val rows = ArrayList<HomeRow>()
        val resume = history.asSequence()
            .filter { it.kind != WatchKind.LIVE && (it.resumable || it.upNext) }
            .filter { it.kind != WatchKind.MOVIE || movieByStream.containsKey("${it.sourceId}|${it.itemId}") }
            // En plats per serie: det senaste avsnittet.
            .distinctBy { if (it.kind == WatchKind.EPISODE) "serie:${it.sourceId}:${it.seriesId}" else it.identity }
            .take(20)
            .map { ResumeItem(it) }
            .toList()
        if (resume.isNotEmpty()) rows += HomeRow("Fortsätt titta", resume)

        val mine = myList.mapNotNull { entry ->
            when (entry.kind) {
                ListKind.MOVIE -> movieByKey[entry.key]?.let { MovieItem(it) }
                ListKind.SERIES -> seriesByKey[entry.key]?.let { SeriesItem(it) }
                ListKind.CHANNEL -> null
            }
        }
        if (mine.isNotEmpty()) rows += HomeRow("Min lista", mine)

        val favorites = myList.filter { it.kind == ListKind.CHANNEL }.mapNotNull { channelByKey[it.key] }
        if (favorites.isNotEmpty()) rows += HomeRow("Favoritkanaler", favorites.map { ChannelItem(it, favorites) })

        val recentChannels = history.asSequence()
            .filter { it.kind == WatchKind.LIVE }
            .mapNotNull { channelByHistoryId["${it.sourceId}|${it.itemId}"] }
            .distinctBy { it.key }
            .take(15)
            .toList()
        if (recentChannels.isNotEmpty()) rows += HomeRow("Senaste kanaler", recentChannels.map { ChannelItem(it, recentChannels) })

        rows += leading
        recommended(history, myList)?.let { rows += it }
        rows += trailing
        return rows
    }

    /** Mer av de genrer man tittat på och sparat, utan det man redan sett. */
    private fun recommended(history: List<WatchEntry>, myList: List<ListEntry>): HomeRow? {
        val counts = HashMap<Genre, Int>()
        val seen = HashSet<String>()
        for (entry in history) {
            if (entry.kind != WatchKind.MOVIE) continue
            val movie = movieByStream["${entry.sourceId}|${entry.itemId}"] ?: continue
            seen += movie.key
            genresOfMovie[movie.key]?.forEach { counts[it] = (counts[it] ?: 0) + 2 }
        }
        for (entry in myList) {
            if (entry.kind != ListKind.MOVIE) continue
            seen += entry.key
            genresOfMovie[entry.key]?.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val top = counts.entries.sortedByDescending { it.value }.take(2).map { it.key }
        if (top.isEmpty()) return null
        // Varannan från var och en av de två genrerna, så att raden blir blandad.
        val lists = top.map { genre -> (bestByGenre[genre] ?: emptyList()).filter { it.key !in seen } }
        val picks = ArrayList<Movie>()
        val longest = lists.maxOf { it.size }
        for (i in 0 until longest) {
            for (list in lists) list.getOrNull(i)?.let { picks += it }
        }
        val items = picks.distinctBy { it.key }.take(ROW).map { MovieItem(it) }
        return if (items.size >= 6) HomeRow("Rekommenderat för dig", items) else null
    }
}

/** Genrerna som får egna rader, i den här ordningen. */
private val homeGenres = listOf(
    Genre.ACTION, Genre.COMEDY, Genre.THRILLER, Genre.DRAMA, Genre.SCIFI, Genre.CRIME, Genre.HORROR,
    Genre.FAMILY, Genre.ANIMATION, Genre.ROMANCE, Genre.FANTASY, Genre.ADVENTURE, Genre.DOCUMENTARY, Genre.WAR,
)

internal fun buildCatalog(library: Library, tmdb: Map<String, TmdbFacts>, facts: Map<String, MovieFacts>): HomeCatalog {
    fun movieTmdb(movie: Movie): TmdbFacts? = if (tmdb.isEmpty()) null else tmdb[HomeKeys.movie(movie)]?.takeIf { it.found }
    fun seriesTmdb(series: Series): TmdbFacts? = if (tmdb.isEmpty()) null else tmdb[HomeKeys.series(series)]?.takeIf { it.found }
    // Samma film finns ofta flera gånger hos leverantören (HD, 4K, språk): en gång per rad.
    fun identity(movie: Movie): String = movieTmdb(movie)?.id?.let { "tmdb:$it" } ?: TitleCleaner.normalize(movie.name)
    fun seriesIdentity(series: Series): String = seriesTmdb(series)?.id?.let { "tmdb:$it" } ?: TitleCleaner.normalize(series.name)

    val movies = library.movies
    val series = library.series
    val score = HashMap<String, Double>(movies.size * 2)
    for (movie in movies) {
        val value = movieTmdb(movie)?.let { weightedScore(it.rating, it.votes) } ?: weightedScore(movie.rating, PROVIDER_VOTES)
        if (value != null) score[movie.key] = value
    }

    val leading = ArrayList<HomeRow>()
    val trailing = ArrayList<HomeRow>()
    fun movieRow(title: String, list: List<Movie>, into: MutableList<HomeRow>) {
        val items = list.distinctBy { identity(it) }.take(ROW).map { MovieItem(it) }
        if (items.size >= 6) into += HomeRow(title, items)
    }
    fun seriesRow(title: String, list: List<Series>, into: MutableList<HomeRow>) {
        val items = list.distinctBy { seriesIdentity(it) }.take(ROW).map { SeriesItem(it) }
        if (items.size >= 6) into += HomeRow(title, items)
    }

    val popular = movies.mapNotNull { m -> movieTmdb(m)?.popularity?.takeIf { it > 0.0 }?.let { m to it } }
        .sortedByDescending { it.second }.map { it.first }
    movieRow("Populärt just nu", popular, leading)
    movieRow("Nyligen tillagda filmer", movies.sortedByDescending { it.addedUtc }, leading)

    val popularSeries = series.mapNotNull { s -> seriesTmdb(s)?.popularity?.takeIf { it > 0.0 }?.let { s to it } }
        .sortedByDescending { it.second }.map { it.first }
    seriesRow("Populära serier", popularSeries, trailing)
    seriesRow("Nya serier", series.sortedByDescending { it.addedUtc }, trailing)
    movieRow("Högst betyg", movies.filter { score.containsKey(it.key) }.sortedByDescending { score[it.key] }, trailing)

    val index = GenreIndex.build(library, facts, { movieTmdb(it)?.genres }, { seriesTmdb(it)?.genres })
    val genresOfMovie = HashMap<String, MutableList<Genre>>()
    index.movies.forEach { (genre, list) -> list.forEach { genresOfMovie.getOrPut(it.key) { ArrayList(3) } += genre } }
    val bestByGenre = index.movies.mapValues { (_, list) ->
        list.sortedByDescending { score[it.key] ?: 0.0 }.distinctBy { identity(it) }.take(60)
    }
    for (genre in homeGenres) movieRow(genre.label, bestByGenre[genre] ?: emptyList(), trailing)
    // Julfilmerna i november och december.
    val month = java.time.LocalDate.now().monthValue
    if (month >= 11) movieRow(Genre.CHRISTMAS.label, bestByGenre[Genre.CHRISTMAS] ?: emptyList(), leading)

    return HomeCatalog(
        movieByKey = movies.associateBy { it.key },
        seriesByKey = series.associateBy { it.key },
        channelByKey = library.channels.associateBy { it.key },
        channelByHistoryId = library.channels.associateBy { "${it.sourceId}|${it.streamId?.toString() ?: "namn:${it.name}"}" },
        movieByStream = movies.associateBy { "${it.sourceId}|${it.streamId}" },
        seriesById = series.associateBy { "${it.sourceId}|${it.seriesId}" },
        genresOfMovie = genresOfMovie,
        bestByGenre = bestByGenre,
        leading = leading,
        trailing = trailing,
    )
}

/** Som AppState: leverantörens betyg vägs som om få hade röstat. */
private const val PROVIDER_VOTES = 30

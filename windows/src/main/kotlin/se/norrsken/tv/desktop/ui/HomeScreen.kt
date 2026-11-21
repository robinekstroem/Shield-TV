package se.norrsken.tv.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.data.Genre
import se.norrsken.tv.desktop.data.GenreIndex
import se.norrsken.tv.desktop.data.Library
import se.norrsken.tv.desktop.data.LibraryFilter
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
import se.norrsken.tv.desktop.data.TrendingTitle
import se.norrsken.tv.desktop.data.VersionIndex
import se.norrsken.tv.desktop.data.WatchEntry
import se.norrsken.tv.desktop.data.WatchKind
import se.norrsken.tv.desktop.data.weightedScore

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

internal class HomeRow(
    val title: String,
    val items: List<HomeItem>,
    /** Topplista: placeringen som en stor siffra bredvid omslaget. */
    val ranked: Boolean = false,
)

private const val ROW = 30

@OptIn(ExperimentalFoundationApi::class)
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
        // Paus: snabbt bläddrande ska inte byta bakgrund för varje titel man passerar.
        delay(if (tvLayout) SPOTLIGHT_SETTLE_MS else 200L)
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
    var catalogError by remember { mutableStateOf<String?>(null) }
    var lastBuild by remember { mutableLongStateOf(0L) }
    val versions = state.versions
    val trending = state.trending
    val newSince = state.newSinceUtc
    val hiddenGroups = state.hiddenGroups
    val catalog by produceState<HomeCatalog?>(null, library, tmdb, facts, versions, trending, newSince, hiddenGroups) {
        // TMDB fylls på i omgångar. Medan hämtningen pågår räknas raderna om
        // högst var trettionde sekund, annars en kort stund efter sista ändringen:
        // omräkningen ska aldrig ta kraft från menyerna.
        if (value != null) {
            val busy = state.tmdbProgress != null || state.factsProgress != null
            val wait = if (busy) (30_000L - (System.currentTimeMillis() - lastBuild)).coerceAtLeast(1_500L) else 1_500L
            delay(wait)
        }
        // Ett fel här ska synas på startsidan, inte stänga appen.
        try {
            value = withContext(Dispatchers.Default) { buildCatalog(library, tmdb, facts, versions, trending, newSince, hiddenGroups) }
            lastBuild = System.currentTimeMillis()
            catalogError = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val cause = generateSequence(error) { it.cause }.last()
            catalogError = "${cause.javaClass.simpleName}: ${cause.message.orEmpty()}"
        }
    }
    val current = catalog
    // Startsidan har sina rader: de tunga bakgrundsjobben får börja.
    LaunchedEffect(current != null) { if (current != null) state.markHomeReady() }
    if (current == null) {
        Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
            val error = catalogError
            Text(
                if (error == null) "Startsidan ordnas…" else "Startsidan gick inte att ordna. Ta ett foto och skicka det i chatten.\n\n$error",
                style = MaterialTheme.typography.bodyLarge,
                color = LocalPalette.current.textTertiary,
            )
        }
        return
    }
    val history = state.history
    val myList = state.myList
    val rowsResult = remember(current, history, myList) { runCatching { current.rowsFor(history, myList) } }
    val rows = rowsResult.getOrNull()
    if (rows == null) {
        val cause = generateSequence(rowsResult.exceptionOrNull()) { it.cause }.lastOrNull()
        Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
            Text(
                "Startsidan gick inte att visa. Ta ett foto och skicka det i chatten.\n\n" +
                    "${cause?.javaClass?.simpleName}: ${cause?.message.orEmpty()}",
                style = MaterialTheme.typography.bodyLarge,
                color = LocalPalette.current.textTertiary,
            )
        }
        return
    }
    val shown = hero?.takeIf { item -> rows.any { row -> row.items.any { it.key == item.key } } }
        ?: rows.firstNotNullOfOrNull { row -> row.items.firstOrNull { it !is ChannelItem } }

    // Tv: markeringen börjar på Spela, så att man ser var man är. Backar man
    // ur en titel hamnar den på kortet man öppnade.
    val firstFocus = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    if (tvLayout) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            // Tillbaka i menyn bytte till startsidan: markeringen stannar i menyn,
            // så att ett Tillbaka till stänger appen.
            if (TvNav.holdMenu && TvNav.drawer == TvDrawer.MENU) return@LaunchedEffect
            val restored = openedKey != null && runCatching { restore.requestFocus() }.isSuccess
            if (!restored) runCatching { firstFocus.requestFocus() }
        }
    }
    // Tv: tappas markeringen (till exempel när den lilla bilden stängs) hamnar den
    // på kortet man senast öppnade, annars på Spela.
    TvFocusFallback {
        (openedKey != null && runCatching { restore.requestFocus() }.isSuccess) ||
            runCatching { firstFocus.requestFocus() }.isSuccess
    }
    val heroHeight = if (tvLayout) 340.dp else 320.dp

    Box(Modifier.fillMaxSize()) {
        HeroBackdrop(
            runCatching { backdropOf(state, current, shown) }.getOrNull(),
            heroHeight + 160.dp,
            running = state.nowPlaying == null || state.miniPlayer,
        )
        Column(Modifier.fillMaxSize()) {
            HeroInfo(state, current, shown, heroHeight, firstFocus, onOpen = { openedKey = it })
            // Korten i raderna rullar som förut; bara raderna själva lägger sig överst.
            val cardScrolling = LocalBringIntoViewSpec.current
            Box(Modifier.weight(1f).fillMaxWidth()) {
                HomeRowsScrolling {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 40.dp),
                    ) {
                        items(rows, key = { it.title }) { row ->
                            CompositionLocalProvider(LocalBringIntoViewSpec provides cardScrolling) {
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
        }
    }
}

/**
 * Tv: raderna rullar en hel rad i taget. Raden man går till lägger sig
 * överst under titelinformationen, med sin rubrik synlig — aldrig en halv
 * rad kvar ovanför. Syns raden redan hel står sidan still.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeRowsScrolling(content: @Composable () -> Unit) {
    if (!tvLayout) {
        content()
        return
    }
    val lead = with(LocalDensity.current) { HOME_ROW_LEAD.toPx() }
    val spec = remember(lead) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (offset >= lead && offset + size <= containerSize) 0f else offset - lead
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** Från radernas överkant till kortens: plats för radens rubrik och lite luft ovanför. */
private val HOME_ROW_LEAD = 58.dp

// --- Bakgrund och information ------------------------------------------------------

/**
 * Titelns bakgrundsbild upptill. Den tonar ut mot vänster och nedåt till
 * appens vanliga bakgrund — temat och egna bakgrundsbilder syns under — i
 * stället för att täcka sidan. Utan en riktig bakgrundsbild visas ingen.
 */
@Composable
private fun HeroBackdrop(url: String?, height: Dp, running: Boolean) {
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
        // Titelns bild tonar in och rör sig sakta, som en trailer.
        LivingBackdrop(url = url, modifier = Modifier.fillMaxSize(), running = running)
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
    /** För loggan: "movie" eller "tv", och titelns TMDB-id. */
    val logoKind: String? = null,
    val tmdbId: Int? = null,
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
        val hero = runCatching { heroOf(state, catalog, item) }.getOrNull() ?: return@Row
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
            Artwork(
                candidates = runCatching { posterOf(state, catalog, item) }.getOrNull().orEmpty(),
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
            val logoKind = hero.logoKind
            if (logoKind != null) {
                // Fast höjd, mot underkanten: texten under står still när loggan laddas
                // eller när man går till nästa titel.
                Box(
                    Modifier.fillMaxWidth().height(if (tvLayout) 104.dp else 96.dp),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    TitleLogo(
                        state = state,
                        kind = logoKind,
                        tmdbId = hero.tmdbId,
                        title = hero.title,
                        style = MaterialTheme.typography.headlineLarge,
                        maxHeight = if (tvLayout) 104.dp else 96.dp,
                        maxWidth = 460.dp,
                        modifier = Modifier.widthIn(max = 820.dp),
                    )
                }
            } else {
                Text(
                    hero.title,
                    style = MaterialTheme.typography.headlineLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 820.dp),
                )
            }
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

private fun movieHero(state: AppState, shown: Movie, resume: WatchEntry?): Hero {
    // Har man börjat på en annan version av filmen är det den som fortsätter.
    val all = state.versionsOf(shown)
    val earlier = resume ?: state.history.firstOrNull { entry ->
        entry.kind == WatchKind.MOVIE && all.any { it.sourceId == entry.sourceId && it.streamId.toString() == entry.itemId }
    }?.takeIf { it.resumable }
    val movie = earlier?.let { entry -> all.firstOrNull { it.sourceId == entry.sourceId && it.streamId.toString() == entry.itemId } } ?: shown
    val tmdb = state.tmdbFor(movie)
    val facts = state.movieFacts[movie.key]
    return Hero(
        kicker = if (resume != null) "Fortsätt titta" else null,
        title = state.titleOf(movie),
        meta = listOfNotNull(
            (tmdb?.year ?: facts?.year)?.toString(),
            formatRating(tmdb?.rating ?: movie.rating),
            tmdb?.genres?.ifBlank { null } ?: facts?.genre?.ifBlank { null },
        ).joinToString("  ·  ").ifEmpty { null },
        overview = tmdb?.overview?.ifBlank { null },
        playLabel = if (earlier != null) timeLeft(earlier)?.let { "Fortsätt  ·  $it" } ?: "Fortsätt" else "Spela",
        play = { state.playMovie(movie) },
        more = { state.showMovie(movie) },
        saved = state.inMyList(movie),
        toggle = { state.toggleMyList(movie) },
        logoKind = "movie",
        tmdbId = tmdb?.id,
    )
}

private fun seriesHero(state: AppState, series: Series, resume: WatchEntry?): Hero {
    val tmdb = state.tmdbFor(series)
    // Senaste avsnittet i någon av seriens versioner.
    val latest = resume ?: latestEpisode(state.history, state.versionsOf(series))
    val episode = latest?.let { "S${it.season} E${it.episode}" }
    return Hero(
        kicker = when {
            resume?.upNext == true -> "Nästa avsnitt"
            resume != null -> "Fortsätt titta"
            else -> null
        },
        title = state.titleOf(series),
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
        saved = state.inMyList(series),
        toggle = { state.toggleMyList(series) },
        logoKind = "tv",
        tmdbId = tmdb?.id,
    )
}

/** Senaste påbörjade eller föreslagna avsnittet i en serie. */
internal fun latestEpisode(history: List<WatchEntry>, series: Series): WatchEntry? =
    history.firstOrNull {
        it.kind == WatchKind.EPISODE && it.sourceId == series.sourceId && it.seriesId == series.seriesId && (it.resumable || it.upNext)
    }

/** Senaste påbörjade eller föreslagna avsnittet i någon av seriens versioner. */
internal fun latestEpisode(history: List<WatchEntry>, versions: List<Series>): WatchEntry? =
    history.firstOrNull { entry ->
        entry.kind == WatchKind.EPISODE && (entry.resumable || entry.upNext) &&
            versions.any { it.sourceId == entry.sourceId && it.seriesId == entry.seriesId }
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

/** Omslagen (eller kanalens logga) att visa skarpt bredvid texten överst, i ordning. */
private fun posterOf(state: AppState, catalog: HomeCatalog, item: HomeItem): List<String> = when (item) {
    is MovieItem -> state.posterCandidates(item.movie)
    is SeriesItem -> state.posterCandidates(item.series)
    is ResumeItem -> {
        val entry = item.entry
        val found = when (entry.kind) {
            WatchKind.MOVIE -> catalog.movieByStream["${entry.sourceId}|${entry.itemId}"]?.let { state.posterCandidates(it) }
            WatchKind.EPISODE -> catalog.seriesById["${entry.sourceId}|${entry.seriesId}"]?.let { state.posterCandidates(it) }
            else -> null
        }
        found.orEmpty() + listOfNotNull(entry.image)
    }
    is ChannelItem -> listOfNotNull(item.channel.logo)
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
            itemsIndexed(row.items, key = { _, item -> item.key }) { index, item ->
                val focus = if (item.key == restoreKey) Modifier.focusRequester(restore) else Modifier
                val cardWidth = if (tvLayout) 164.dp else 150.dp
                when (item) {
                    is MovieItem -> RankedOrPlain(if (row.ranked) index + 1 else null, cardWidth) {
                        PosterCard(
                            title = state.titleOf(item.movie),
                            images = state.posterCandidates(item.movie),
                            meta = movieMeta(state, item.movie),
                            onClick = {
                                onOpen(item)
                                state.showMovie(item.movie)
                            },
                            modifier = focus,
                            onActive = { onActive(item) },
                            badge = state.badgeFor(item.movie),
                        )
                    }
                    is SeriesItem -> RankedOrPlain(if (row.ranked) index + 1 else null, cardWidth) {
                        PosterCard(
                            title = state.titleOf(item.series),
                            images = state.posterCandidates(item.series),
                            meta = seriesMeta(state, item.series),
                            onClick = {
                                onOpen(item)
                                state.showSeries(item.series)
                            },
                            modifier = focus,
                            onActive = { onActive(item) },
                            badge = state.badgeFor(item.series),
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

/** Ett omslag i en rad: med en stor placeringssiffra i topplistor, annars som det är. */
@Composable
private fun RankedOrPlain(rank: Int?, cardWidth: Dp, content: @Composable () -> Unit) {
    if (rank != null) {
        RankedPoster(rank = rank, cardWidth = cardWidth, content = content)
    } else {
        Box(Modifier.width(cardWidth)) { content() }
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
        val shine = rememberCardShine(hovered)
        val ring = if (tvLayout) 3.dp else 1.dp
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .glow(if (hovered) palette.glowOf(if (tvLayout) 1f else 0.75f) else Color.Transparent, shape, if (tvLayout) 34.dp else 26.dp)
                .clip(shape)
                .background(palette.surfaceRaised)
                .then(if (hovered && shine == null) Modifier.focusRing(palette, ring, shape, if (tvLayout) 1f else 0.6f) else Modifier)
                .then(if (hovered) Modifier.cardShine(shine, palette, ring, shape) else Modifier),
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
        val shine = rememberCardShine(hovered)
        val ring = if (tvLayout) 3.dp else 1.dp
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .glow(if (hovered) palette.glowOf(if (tvLayout) 1f else 0.75f) else Color.Transparent, shape, if (tvLayout) 34.dp else 26.dp)
                    .clip(shape)
                    .background(palette.surfaceRaised)
                    .then(if (hovered && shine == null) Modifier.focusRing(palette, ring, shape, if (tvLayout) 1f else 0.6f) else Modifier)
                    .then(if (hovered) Modifier.cardShine(shine, palette, ring, shape) else Modifier),
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
    /** Versionerna katalogen byggdes med: samma film i flera utföranden räknas en gång. */
    val versions: VersionIndex,
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
            .distinctBy { entry ->
                when (entry.kind) {
                    WatchKind.EPISODE -> seriesById["${entry.sourceId}|${entry.seriesId}"]?.let { versions.groupKeyOf(it) }
                        ?: "serie:${entry.sourceId}:${entry.seriesId}"
                    WatchKind.MOVIE -> movieByStream["${entry.sourceId}|${entry.itemId}"]?.let { versions.groupKeyOf(it) } ?: entry.identity
                    else -> entry.identity
                }
            }
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
        }.distinctBy { item ->
            when (item) {
                is MovieItem -> versions.groupKeyOf(item.movie)
                is SeriesItem -> versions.groupKeyOf(item.series)
                else -> item.key
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
            // Sett i en version är sett i alla.
            versions.versionsOf(movie).forEach { seen += it.key }
            genresOfMovie[movie.key]?.forEach { counts[it] = (counts[it] ?: 0) + 2 }
        }
        for (entry in myList) {
            if (entry.kind != ListKind.MOVIE) continue
            seen += entry.key
            movieByKey[entry.key]?.let { saved -> versions.versionsOf(saved).forEach { seen += it.key } }
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
        val items = picks.distinctBy { versions.groupKeyOf(it) }.take(ROW).map { MovieItem(it) }
        return if (items.size >= 6) HomeRow("Rekommenderat för dig", items) else null
    }
}

/** Genrerna som får egna rader, i den här ordningen. */
private val homeGenres = listOf(
    Genre.ACTION, Genre.COMEDY, Genre.THRILLER, Genre.DRAMA, Genre.SCIFI, Genre.CRIME, Genre.HORROR,
    Genre.FAMILY, Genre.ANIMATION, Genre.ROMANCE, Genre.FANTASY, Genre.ADVENTURE, Genre.DOCUMENTARY, Genre.WAR,
)

internal fun buildCatalog(
    library: Library,
    tmdb: Map<String, TmdbFacts>,
    facts: Map<String, MovieFacts>,
    versions: VersionIndex,
    trending: List<TrendingTitle> = emptyList(),
    newSinceUtc: Long = 0L,
    hiddenGroups: Set<String> = emptySet(),
): HomeCatalog {
    fun movieTmdb(movie: Movie): TmdbFacts? = if (tmdb.isEmpty()) null else tmdb[HomeKeys.movie(movie)]?.takeIf { it.found }
    fun seriesTmdb(series: Series): TmdbFacts? = if (tmdb.isEmpty()) null else tmdb[HomeKeys.series(series)]?.takeIf { it.found }
    // Samma film finns ofta flera gånger hos leverantören (HD, 4K, språk): en gång per rad.
    // Versionerna avgör; tills de är klara duger TMDB-id eller titeln utan taggar och året.
    fun identity(movie: Movie): String =
        if (!versions.isEmpty) versions.identityOf(movie)
        else movieTmdb(movie)?.id?.let { "tmdb:$it" } ?: versions.identityOf(movie)
    fun seriesIdentity(series: Series): String =
        if (!versions.isEmpty) versions.identityOf(series)
        else seriesTmdb(series)?.id?.let { "tmdb:$it" } ?: versions.identityOf(series)

    // Det som döljs av språkvalet eller en dold kategori syns inte heller här.
    val movies = library.movies.filterNot { versions.isHidden(it) }
    val series = library.series.filterNot { versions.isHidden(it) }
    val channels = if (hiddenGroups.isEmpty()) {
        library.channels
    } else {
        library.channels.filter { LibraryFilter.groupKey(LibraryFilter.LIVE, it.group) !in hiddenGroups }
    }
    val score = HashMap<String, Double>(movies.size * 2)
    for (movie in movies) {
        val value = movieTmdb(movie)?.let { weightedScore(it.rating, it.votes) } ?: weightedScore(movie.rating, PROVIDER_VOTES)
        if (value != null) score[movie.key] = value
    }

    val leading = ArrayList<HomeRow>()
    val trailing = ArrayList<HomeRow>()
    // Bara så långt in i listan som raden behöver: titlarna jämförs en i taget
    // tills raden är full, inte alla tiotusentals i förväg.
    fun movieRow(title: String, list: List<Movie>, into: MutableList<HomeRow>) {
        // Filmens bästa version på kortet, oavsett vilken version som tog den hit.
        val items = list.asSequence().distinctBy { identity(it) }.take(ROW).map { MovieItem(versions.versionsOf(it).first()) }.toList()
        if (items.size >= 6) into += HomeRow(title, items)
    }
    fun seriesRow(title: String, list: List<Series>, into: MutableList<HomeRow>) {
        val items = list.asSequence().distinctBy { seriesIdentity(it) }.take(ROW).map { SeriesItem(versions.versionsOf(it).first()) }.toList()
        if (items.size >= 6) into += HomeRow(title, items)
    }

    // Trendar just nu: TMDB:s lista för i dag, med det som finns i biblioteket.
    if (trending.isNotEmpty()) {
        val movieById = HashMap<Int, Movie>()
        val movieByTitle = HashMap<String, Movie>()
        for (movie in movies) {
            movieTmdb(movie)?.id?.let { movieById.putIfAbsent(it, movie) }
            movieByTitle.putIfAbsent(HomeKeys.movie(movie), movie)
        }
        val seriesByTmdb = HashMap<Int, Series>()
        val seriesByTitle = HashMap<String, Series>()
        for (s in series) {
            seriesTmdb(s)?.id?.let { seriesByTmdb.putIfAbsent(it, s) }
            seriesByTitle.putIfAbsent(HomeKeys.series(s), s)
        }
        val ranked = ArrayList<HomeItem>()
        val taken = HashSet<String>()
        for (title in trending) {
            if (ranked.size >= TRENDING_ROW) break
            val item: HomeItem? = if (title.kind == "movie") {
                (movieById[title.id] ?: trendingMatch(movieByTitle, "movie", title))?.let { MovieItem(versions.versionsOf(it).first()) }
            } else {
                (seriesByTmdb[title.id] ?: trendingMatch(seriesByTitle, "tv", title))?.let { SeriesItem(versions.versionsOf(it).first()) }
            }
            val identity = when (item) {
                is MovieItem -> identity(item.movie)
                is SeriesItem -> seriesIdentity(item.series)
                else -> null
            }
            if (item != null && identity != null && taken.add(identity)) ranked += item
        }
        if (ranked.size >= 3) leading += HomeRow("Trendar just nu", ranked, ranked = true)
    }

    // Nytt sedan sist: filmer och serier som kommit till sedan förra besöket, nyast först.
    if (newSinceUtc > 0L) {
        val fresh = ArrayList<Pair<Long, HomeItem>>()
        movies.asSequence().filter { it.addedUtc >= newSinceUtc }.sortedByDescending { it.addedUtc }
            .distinctBy { identity(it) }.take(ROW)
            .forEach { fresh += it.addedUtc to MovieItem(versions.versionsOf(it).first()) }
        series.asSequence().filter { it.addedUtc >= newSinceUtc }.sortedByDescending { it.addedUtc }
            .distinctBy { seriesIdentity(it) }.take(ROW)
            .forEach { fresh += it.addedUtc to SeriesItem(versions.versionsOf(it).first()) }
        val items = fresh.sortedByDescending { it.first }.map { it.second }.distinctBy { it.key }.take(ROW)
        if (items.size >= 4) leading += HomeRow("Nytt sedan sist", items)
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
        list.filterNot { versions.isHidden(it) }.sortedByDescending { score[it.key] ?: 0.0 }.asSequence().distinctBy { identity(it) }.take(60).toList()
    }
    for (genre in homeGenres) movieRow(genre.label, bestByGenre[genre] ?: emptyList(), trailing)
    // Julfilmerna i november och december.
    val month = java.time.LocalDate.now().monthValue
    if (month >= 11) movieRow(Genre.CHRISTMAS.label, bestByGenre[Genre.CHRISTMAS] ?: emptyList(), leading)

    return HomeCatalog(
        movieByKey = movies.associateBy { it.key },
        seriesByKey = series.associateBy { it.key },
        channelByKey = channels.associateBy { it.key },
        channelByHistoryId = channels.associateBy { "${it.sourceId}|${it.streamId?.toString() ?: "namn:${it.name}"}" },
        movieByStream = movies.associateBy { "${it.sourceId}|${it.streamId}" },
        seriesById = series.associateBy { "${it.sourceId}|${it.seriesId}" },
        genresOfMovie = genresOfMovie,
        bestByGenre = bestByGenre,
        leading = leading,
        trailing = trailing,
        versions = versions,
    )
}

/** Som AppState: leverantörens betyg vägs som om få hade röstat. */
private const val PROVIDER_VOTES = 30

/** Topp tio, som på streamingtjänsterna. */
private const val TRENDING_ROW = 10

/** En trendande titel i biblioteket efter titel och år, när TMDB-id:t inte är känt ännu. */
private fun <T> trendingMatch(byKey: Map<String, T>, kind: String, title: TrendingTitle): T? =
    byKey[TmdbStore.keyOf(kind, title.title, title.year)]
        ?: byKey[TmdbStore.keyOf(kind, title.original, title.year)]
        ?: byKey[TmdbStore.keyOf(kind, title.title, null)]

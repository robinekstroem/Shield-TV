package se.norrsken.tv.feature.home

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import se.norrsken.tv.core.ui.component.FocusableSurface
import se.norrsken.tv.core.ui.component.SectionHeading
import se.norrsken.tv.feature.home.ChannelTileRow
import se.norrsken.tv.feature.home.ContinueRow
import se.norrsken.tv.feature.home.HomeTopBar
import se.norrsken.tv.feature.home.QuickTile
import se.norrsken.tv.feature.home.QuickTileRow
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collect
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import se.norrsken.tv.core.ui.theme.LocalPalette
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import se.norrsken.tv.core.ui.component.ContentRail
import se.norrsken.tv.core.ui.component.CinematicLightField
import se.norrsken.tv.core.ui.component.HeroBackdrop
import se.norrsken.tv.core.ui.component.HeroContent
import se.norrsken.tv.core.ui.component.HeroInfo
import se.norrsken.tv.core.ui.component.HeroScrim
import se.norrsken.tv.core.ui.component.metaLine
import se.norrsken.tv.core.ui.component.EmptyState
import se.norrsken.tv.core.ui.component.RailItem
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.device.LocalDeviceProfile
import se.norrsken.tv.data.db.dao.ChannelDao
import se.norrsken.tv.data.db.dao.LibraryDao
import se.norrsken.tv.data.db.entity.ContentKind
import se.norrsken.tv.data.metadata.MetadataRepository
import se.norrsken.tv.data.repository.CategoryVisibility
import se.norrsken.tv.data.repository.WatchHistoryRepository
import se.norrsken.tv.data.settings.ChannelSort
import se.norrsken.tv.data.settings.LibrarySort
import javax.inject.Inject

data class HomeState(
    val movies: List<RailItem> = emptyList(),
    val series: List<RailItem> = emptyList(),
    val channels: List<RailItem> = emptyList(),
) {
    val isEmpty: Boolean
        get() = movies.isEmpty() && series.isEmpty() && channels.isEmpty()
}

/** "35 min kvar". Sekunder intresserar ingen som ska fortsätta en film. */
private fun remaining(positionMs: Long, durationMs: Long): String {
    val left = ((durationMs - positionMs) / 60_000L).coerceAtLeast(1L)
    return "$left min kvar"
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    libraryDao: LibraryDao,
    channelDao: ChannelDao,
    private val metadata: MetadataRepository,
    visibility: CategoryVisibility,
    history: WatchHistoryRepository,
) : ViewModel() {

    /**
     * Påbörjat men inte slutfört, nyast först. Filmer och avsnitt i samma
     * rad — det är ordningen man lämnade dem i som betyder något, inte
     * vilken sort de är.
     */
    val continueWatching: StateFlow<List<RailItem>> = combine(
        history.observeMovies(12),
        history.observeEpisodes(12),
    ) { movies, episodes ->
        val fromMovies = movies.map { movie ->
            movie.lastPlayedUtc to RailItem(
                id = movie.id,
                title = movie.title,
                subtitle = remaining(movie.positionMs, movie.durationMs),
                imageUrl = movie.posterUrl,
                progress = movie.positionMs.toFloat() / movie.durationMs.coerceAtLeast(1),
            )
        }
        val fromEpisodes = episodes.map { episode ->
            episode.lastPlayedUtc to RailItem(
                id = episode.id,
                title = episode.seriesTitle ?: episode.title,
                subtitle = "S${episode.season} A${episode.episode} · " +
                    remaining(episode.positionMs, episode.durationMs),
                imageUrl = episode.thumbnailUrl ?: episode.seriesPosterUrl,
                progress = episode.positionMs.toFloat() / episode.durationMs.coerceAtLeast(1),
                isSeries = true,
            )
        }
        (fromMovies + fromEpisodes).sortedByDescending { it.first }.map { it.second }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Allt pa hemskarmen kommer ur biblioteket. Ingen rad heter "Populart"
     * eller "Rekommenderat" — appen vet inte vad som ar populart, och att
     * pasta det vore att hitta pa data at anvandaren.
     */
    val state: StateFlow<HomeState> = combine(
        visibility.hiddenIds(ContentKind.MOVIE),
        visibility.hiddenIds(ContentKind.SERIES),
        visibility.hiddenIds(ContentKind.LIVE),
    ) { movieHidden, seriesHidden, liveHidden ->
        Triple(movieHidden, seriesHidden, liveHidden)
    }.flatMapLatest { (movieHidden, seriesHidden, liveHidden) ->
        // Dolda kategorier galler hemskarmen ocksa. Annars hade det dolda
        // dykt upp i hero och rader direkt pa startsidan.
        combine(
            libraryDao.observeMovies(
                categoryId = null,
                sort = LibrarySort.RECENT.code,
                hiddenIds = movieHidden,
                filter = null,
                limit = 20,
            ),
            libraryDao.observeSeries(
                categoryId = null,
                sort = LibrarySort.RECENT.code,
                hiddenIds = seriesHidden,
                filter = null,
                limit = 20,
            ),
            channelDao.observeChannels(
                categoryId = null,
                sort = ChannelSort.PROVIDER.code,
                hiddenIds = liveHidden,
                limit = 20,
            ),
        ) { movies, series, channels -> Triple(movies, series, channels) }
    }.map { (movies, series, channels) ->
        HomeState(
            movies = movies.map {
                RailItem(
                    id = it.id,
                    title = it.title,
                    // "2024 · Action" under omslaget, som i mockupen — inte
                    // hela genrelistan.
                    subtitle = metaLine(it.year?.toString(), it.genres?.split(",")?.firstOrNull()?.trim()),
                    imageUrl = it.posterUrl,
                    backdropUrl = it.backdropUrl,
                    overview = it.overview,
                    meta = metaLine(
                        it.year?.toString(),
                        it.rating?.takeIf { value -> value > 0 }?.let { value -> "★ %.1f".format(value) },
                        it.genres,
                    ),
                )
            },
            series = series.map {
                RailItem(
                    id = it.id,
                    title = it.title,
                    subtitle = it.year?.toString(),
                    imageUrl = it.posterUrl,
                    backdropUrl = it.backdropUrl,
                    overview = it.overview,
                    meta = metaLine(
                        it.year?.toString(),
                        it.rating?.takeIf { value -> value > 0 }?.let { value -> "★ %.1f".format(value) },
                        it.genres,
                    ),
                    isSeries = true,
                )
            },
            channels = channels.map {
                RailItem(
                    id = it.id,
                    title = it.name,
                    subtitle = it.groupTitle,
                    imageUrl = it.logoUrl,
                    wide = true,
                    meta = metaLine(it.groupTitle),
                    isLive = true,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun fillMovieArtwork(id: Long) {
        viewModelScope.launch { metadata.resolveMovie(id) }
    }

    fun fillSeriesArtwork(id: Long) {
        viewModelScope.launch { metadata.resolveSeries(id) }
    }
}

@OptIn(FlowPreview::class)
@Composable
fun HomeScreen(
    onOpenMovie: (Long) -> Unit,
    onPlayMovie: (Long) -> Unit,
    onPlayEpisode: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onOpenChannel: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLiveTv: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeriesSection: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenFavorites: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val metrics = LocalMetrics.current
    val isTv = LocalDeviceProfile.current.isTv

    if (state.isEmpty) {
        EmptyState(
            modifier = modifier,
            title = "Inget bibliotek än",
            body = "Lägg till en M3U-länk eller ett Xtream-konto under Inställningar.",
            actionLabel = "Öppna Inställningar",
            onAction = onOpenSettings,
        )
        return
    }

    // Det markerade kortet styr heron, men först efter en paus. Håller man
    // HÖGER intryckt passerar tio titlar på en sekund — utan pausen hade
    // appen börjat hämta tio stora bakgrundsbilder i onödan.
    // Fokusvärdet läses aldrig i skärmens kropp — bara i hero-avsnittet.
    // Läste kroppen det skulle hela startsidan ritas om varje gång
    // markeringen flyttade ett steg i en rad.
    val focusedState = remember { mutableStateOf<RailItem?>(null) }
    val heroState = remember { mutableStateOf<HeroContent?>(null) }

    // Paus innan heron byts: håller man höger passerar tio titlar på en
    // sekund, och utan pausen hämtas tio stora bakgrundsbilder i onödan.
    LaunchedEffect(Unit) {
        snapshotFlow { focusedState.value }
            .debounce(250)
            .collect { item -> item?.let { heroState.value = it.toHero() } }
    }

    // Startbilden väljs en gång per laddning, inte per omritning: annars
    // hade heron bytts varje gång något annat på skärmen ändrades.
    val featured = remember(state.movies, state.series, continueWatching) {
        (continueWatching + state.movies + state.series).firstOrNull { it.backdropUrl != null }
            ?: (continueWatching + state.movies + state.series).firstOrNull()
    }
    LaunchedEffect(featured) {
        if (heroState.value == null) heroState.value = featured?.toHero()
    }

    val palette = LocalPalette.current
    val isTv = LocalDeviceProfile.current.isTv
    val quickTiles = remember(palette) {
        listOf(
            QuickTile("Live TV", "Direktsändning", Icons.Filled.PlayArrow, Color(0xFFD9534F)),
            QuickTile("Filmer", "Nya & klassiker", Icons.Filled.Star, Color(0xFF3B7DD8)),
            QuickTile("Serier", "Säsonger & avsnitt", Icons.AutoMirrored.Filled.List, Color(0xFF8A5BD6)),
            QuickTile("Tablå", "Vad som sänds nu", Icons.Filled.DateRange, Color(0xFF2FA97A)),
            QuickTile("Favoriter", "Din lista", Icons.Filled.Favorite, Color(0xFFD99A2B)),
        )
    }
    val onOpenSection: (QuickTile) -> Unit = { tile ->
        when (tile.title) {
            "Live TV" -> onOpenLiveTv()
            "Filmer" -> onOpenMovies()
            "Serier" -> onOpenSeriesSection()
            "Tablå" -> onOpenGuide()
            else -> onOpenFavorites()
        }
    }

    Box(modifier.fillMaxSize()) {
        // Lager 1 och 2: bilden och mörkningen. Båda ligger still medan
        // raderna rullar ovanpå, så ingen kant uppstår där heron "slutar".
        HeroSection(heroState)
        HeroScrim()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(metrics.spaceLg),
        ) {
            item { HomeTopBar(onSearch = onOpenSearch) }

            item {
                // Textblocket tar vänstra dryga tredjedelen och ligger nere
                // i bildens nedre halva — resten av skärmen är artwork.
                Box(
                    modifier = Modifier
                        .fillParentMaxHeight(if (isTv) 0.54f else 0.46f)
                        // Halva bredden är rätt på en TV, där resten är
                        // artwork. På en telefon blir det en smal spalt.
                        .fillMaxWidth(if (isTv) 0.52f else 1f),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    HeroText(
                        heroState = heroState,
                        onOpenMovie = onOpenMovie,
                        onPlayMovie = onPlayMovie,
                        onOpenSeries = onOpenSeries,
                        onOpenChannel = onOpenChannel,
                    )
                }
            }

            // Genvägarna: en färg per avdelning, så de skiljs åt på håll.
            item { QuickTileRow(tiles = quickTiles, onOpen = { onOpenSection(it) }) }

            if (continueWatching.isNotEmpty()) {
                item { RailTitle("Fortsätt titta") }
                item {
                    ContinueRow(continueWatching) { item ->
                        if (item.isSeries) onPlayEpisode(item.id) else onPlayMovie(item.id)
                    }
                }
            }

            if (state.channels.isNotEmpty()) {
                item { RailTitle("Populära livekanaler", onShowAll = onOpenLiveTv) }
                item { ChannelTileRow(state.channels) { onOpenChannel(it.id) } }
            }

            item { RailTitle("Nya filmer", onShowAll = onOpenMovies) }
            item {
                ContentRail(
                    title = "",
                    items = state.movies,
                    onOpen = { onOpenMovie(it.id) },
                    onMissingImage = { viewModel.fillMovieArtwork(it.id) },
                    onFocusItem = { focusedState.value = it },
                )
            }

            item { RailTitle("Serier", onShowAll = onOpenSeriesSection) }
            item {
                ContentRail(
                    title = "",
                    items = state.series,
                    onOpen = { onOpenSeries(it.id) },
                    onMissingImage = { viewModel.fillSeriesArtwork(it.id) },
                    onFocusItem = { focusedState.value = it },
                )
            }

            item { Spacer(Modifier.height(metrics.spaceXl)) }
        }
    }
}

/** Bara bilden. Läser hero-tillståndet så resten av sidan slipper. */
@Composable
private fun HeroSection(heroState: State<HeroContent?>) {
    HeroBackdrop(url = heroState.value?.backdropUrl)
}

/** Titel, fakta och knappar för det markerade. */
@Composable
private fun HeroText(
    heroState: State<HeroContent?>,
    onOpenMovie: (Long) -> Unit,
    onPlayMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onOpenChannel: (Long) -> Unit,
) {
    val current = heroState.value
    HeroInfo(
        content = current,
        onPlay = {
            val item = current ?: return@HeroInfo
            val id = item.key.substringAfter(':').toLong()
            when {
                item.isLive -> onOpenChannel(id)
                item.isSeries -> onOpenSeries(id)
                else -> onPlayMovie(id)
            }
        },
        onInfo = {
            val item = current ?: return@HeroInfo
            val id = item.key.substringAfter(':').toLong()
            if (item.isSeries) onOpenSeries(id) else onOpenMovie(id)
        },
    )
}

/** Rubrik över en rad, med "Visa alla" till höger när det finns mer. */
@Composable
private fun RailTitle(title: String, onShowAll: (() -> Unit)? = null) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = metrics.screenEdge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeading(title = title, modifier = Modifier.weight(1f))
        if (onShowAll != null) {
            FocusableSurface(onClick = onShowAll, focusScale = 1f) { focused ->
                Text(
                    text = "Visa alla ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (focused) palette.focus else palette.signal,
                    modifier = Modifier.padding(horizontal = metrics.spaceSm, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * Nyckeln bär både sort och id. Ett film-id och ett kanal-id kan vara samma
 * siffra, och heron måste kunna skilja dem åt.
 */
private fun RailItem.toHero() = HeroContent(
    key = (if (isLive) "LIVE" else if (isSeries) "SERIES" else "MOVIE") + ":" + id,
    title = title,
    meta = meta.orEmpty(),
    overview = overview,
    backdropUrl = backdropUrl,
    isSeries = isSeries,
    isLive = isLive,
)

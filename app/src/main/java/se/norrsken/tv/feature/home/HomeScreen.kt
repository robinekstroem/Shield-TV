package se.norrsken.tv.feature.home

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
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
                subtitle = movie.year?.toString(),
                imageUrl = movie.posterUrl,
                progress = movie.positionMs.toFloat() / movie.durationMs.coerceAtLeast(1),
            )
        }
        val fromEpisodes = episodes.map { episode ->
            episode.lastPlayedUtc to RailItem(
                id = episode.id,
                title = episode.seriesTitle ?: episode.title,
                subtitle = "S%02dE%02d".format(episode.season, episode.episode),
                imageUrl = episode.seriesPosterUrl ?: episode.thumbnailUrl,
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
                    subtitle = it.year?.toString(),
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

@Composable
fun HomeScreen(
    onOpenMovie: (Long) -> Unit,
    onPlayMovie: (Long) -> Unit,
    onPlayEpisode: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onOpenChannel: (Long) -> Unit,
    onOpenSettings: () -> Unit,
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
    var focused by remember { mutableStateOf<RailItem?>(null) }
    var hero by remember { mutableStateOf<HeroContent?>(null) }

    LaunchedEffect(focused) {
        val item = focused ?: return@LaunchedEffect
        delay(250)
        hero = item.toHero()
    }

    // Startbilden väljs en gång per laddning, inte per omritning: annars
    // hade heron bytts varje gång något annat på skärmen ändrades.
    val featured = remember(state.movies, state.series, continueWatching) {
        (continueWatching + state.movies + state.series).firstOrNull { it.backdropUrl != null }
            ?: (continueWatching + state.movies + state.series).firstOrNull()
    }
    LaunchedEffect(featured) {
        if (hero == null) hero = featured?.toHero()
    }

    val current = hero

    Box(modifier.fillMaxSize()) {
        // Lager 1 och 2: bilden och mörkningen. Båda ligger still medan
        // raderna rullar ovanpå, så ingen kant uppstår där heron "slutar".
        HeroBackdrop(url = current?.backdropUrl)
        CinematicLightField(Modifier.fillMaxSize())
        HeroScrim()

        LazyColumn(
            // Innehållet börjar innanför menyn; bilden bakom gör det inte.
            modifier = Modifier
                .fillMaxSize()
                .padding(start = metrics.railCollapsed),
            verticalArrangement = Arrangement.spacedBy(metrics.spaceLg),
        ) {
            item {
                // Textblocket tar vänstra dryga tredjedelen och ligger nere
                // i bildens nedre halva — resten av skärmen är artwork.
                Box(
                    modifier = Modifier
                        .fillParentMaxHeight(if (isTv) 0.72f else 0.62f)
                        .fillMaxWidth(if (isTv) 0.56f else 0.94f),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    HeroInfo(
                        content = current,
                        onPlay = {
                            val item = current ?: return@HeroInfo
                            when {
                                item.isLive -> onOpenChannel(item.key.substringAfter(':').toLong())
                                item.isSeries -> onOpenSeries(item.key.substringAfter(':').toLong())
                                else -> onPlayMovie(item.key.substringAfter(':').toLong())
                            }
                        },
                        onInfo = {
                            val item = current ?: return@HeroInfo
                            val id = item.key.substringAfter(':').toLong()
                            if (item.isSeries) onOpenSeries(id) else onOpenMovie(id)
                        },
                    )
                }
            }

            item {
                ContentRail(
                    title = "Fortsätt titta",
                    items = continueWatching,
                    onOpen = { item ->
                        if (item.isSeries) onPlayEpisode(item.id) else onPlayMovie(item.id)
                    },
                    onFocusItem = { focused = it },
                )
            }
            item {
                ContentRail(
                    title = "Kanaler",
                    items = state.channels,
                    onOpen = { onOpenChannel(it.id) },
                    onFocusItem = { focused = it },
                )
            }
            item {
                ContentRail(
                    title = "Nyligen tillagda filmer",
                    items = state.movies,
                    onOpen = { onOpenMovie(it.id) },
                    onMissingImage = { viewModel.fillMovieArtwork(it.id) },
                    onFocusItem = { focused = it },
                )
            }
            item {
                ContentRail(
                    title = "Serier",
                    items = state.series,
                    onOpen = { onOpenSeries(it.id) },
                    onMissingImage = { viewModel.fillSeriesArtwork(it.id) },
                    onFocusItem = { focused = it },
                )
            }
            item { Spacer(Modifier.height(metrics.spaceXl)) }
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

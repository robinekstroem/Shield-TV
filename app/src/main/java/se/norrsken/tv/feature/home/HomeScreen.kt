package se.norrsken.tv.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.norrsken.tv.core.ui.component.ContentRail
import se.norrsken.tv.core.ui.component.EmptyState
import se.norrsken.tv.core.ui.component.HeroBanner
import se.norrsken.tv.core.ui.component.HeroContent
import se.norrsken.tv.core.ui.component.RailItem
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.data.db.dao.ChannelDao
import se.norrsken.tv.data.db.dao.LibraryDao
import se.norrsken.tv.data.db.entity.ContentKind
import se.norrsken.tv.data.metadata.MetadataRepository
import se.norrsken.tv.data.repository.CategoryVisibility
import se.norrsken.tv.data.settings.ChannelSort
import se.norrsken.tv.data.settings.LibrarySort
import javax.inject.Inject

data class HomeState(
    val hero: HeroContent? = null,
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
) : ViewModel() {

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
                limit = 20,
            ),
            libraryDao.observeSeries(
                categoryId = null,
                sort = LibrarySort.RECENT.code,
                hiddenIds = seriesHidden,
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
        val hero = movies.firstOrNull { it.backdropUrl != null }
            ?.let { movie ->
                HeroContent(
                    id = movie.id,
                    title = movie.title,
                    facts = listOfNotNull(
                        movie.year?.toString(),
                        movie.genres,
                        movie.runtimeMinutes?.let { "$it min" },
                        movie.rating?.let { "%.1f".format(it) },
                    ),
                    overview = movie.overview,
                    imageUrl = movie.backdropUrl,
                    isSeries = false,
                )
            }
            ?: series.firstOrNull { it.backdropUrl != null }?.let { item ->
                HeroContent(
                    id = item.id,
                    title = item.title,
                    facts = listOfNotNull(item.year?.toString(), item.genres),
                    overview = item.overview,
                    imageUrl = item.backdropUrl,
                    isSeries = true,
                )
            }

        HomeState(
            hero = hero,
            movies = movies.map {
                RailItem(it.id, it.title, it.year?.toString(), it.posterUrl)
            },
            series = series.map {
                RailItem(it.id, it.title, it.year?.toString(), it.posterUrl)
            },
            channels = channels.map {
                RailItem(it.id, it.name, it.groupTitle, it.logoUrl, wide = true)
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
    onOpenSeries: (Long) -> Unit,
    onOpenChannel: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val metrics = LocalMetrics.current

    if (state.isEmpty) {
        EmptyState(
            modifier = modifier,
            title = "Inget bibliotek än",
            body = "Lägg till en M3U-länk eller ett Xtream-konto under Inställningar.",
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceLg),
    ) {
        state.hero?.let { hero ->
            item {
                HeroBanner(
                    content = hero,
                    onPlay = {
                        if (hero.isSeries) onOpenSeries(hero.id) else onOpenMovie(hero.id)
                    },
                )
            }
        }

        item {
            ContentRail(
                title = "Kanaler",
                items = state.channels,
                onOpen = { onOpenChannel(it.id) },
            )
        }
        item {
            ContentRail(
                title = "Nyligen tillagda filmer",
                items = state.movies,
                onOpen = { onOpenMovie(it.id) },
                onMissingImage = { viewModel.fillMovieArtwork(it.id) },
            )
        }
        item {
            ContentRail(
                title = "Serier",
                items = state.series,
                onOpen = { onOpenSeries(it.id) },
                onMissingImage = { viewModel.fillSeriesArtwork(it.id) },
            )
        }
        item {
            androidx.compose.foundation.layout.Spacer(
                Modifier.padding(bottom = metrics.spaceXl)
            )
        }
    }
}

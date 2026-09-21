package se.norrsken.tv.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.norrsken.tv.core.device.LocalDeviceProfile
import se.norrsken.tv.core.ui.component.ContentRail
import se.norrsken.tv.core.ui.component.RailItem
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.data.db.dao.ChannelDao
import se.norrsken.tv.data.db.dao.LibraryDao
import se.norrsken.tv.data.db.entity.ContentKind
import se.norrsken.tv.data.metadata.MetadataRepository
import se.norrsken.tv.data.repository.CategoryVisibility
import se.norrsken.tv.data.repository.likePattern
import javax.inject.Inject

/** Kortare än så ger för många träffar för att vara till nytta. */
private const val MIN_QUERY = 2

sealed interface SearchResults {
    data object Idle : SearchResults
    data class Ready(
        val query: String,
        val channels: List<RailItem>,
        val movies: List<RailItem>,
        val series: List<RailItem>,
    ) : SearchResults {
        val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
    }
}

private data class SearchRequest(
    val query: String,
    val hiddenLive: List<Long>,
    val hiddenMovies: List<Long>,
    val hiddenSeries: List<Long>,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val channelDao: ChannelDao,
    private val libraryDao: LibraryDao,
    private val metadata: MetadataRepository,
    visibility: CategoryVisibility,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun onQuery(value: String) {
        _query.value = value
    }

    /**
     * 300 ms paus innan sökningen körs. Utan den blir varje tangenttryck en
     * fråga mot databasen — "gladiator" blir nio sökningar, varav åtta är
     * bortkastade.
     *
     * mapLatest avbryter en pågående sökning när en ny kommer, så ett
     * långsamt svar på "gla" aldrig kan skriva över svaret på "gladiator".
     *
     * Dolda kategorier gäller här också. Annars kunde man söka fram precis
     * det man valt att dölja.
     */
    val results: StateFlow<SearchResults> = combine(
        _query.debounce(300).map { it.trim() }.distinctUntilChanged(),
        visibility.hiddenIds(ContentKind.LIVE),
        visibility.hiddenIds(ContentKind.MOVIE),
        visibility.hiddenIds(ContentKind.SERIES),
    ) { query, live, movies, series ->
        SearchRequest(query, live, movies, series)
    }.mapLatest { request ->
        if (request.query.length < MIN_QUERY) return@mapLatest SearchResults.Idle
        val pattern = likePattern(request.query)
        SearchResults.Ready(
            query = request.query,
            channels = channelDao.search(pattern, request.hiddenLive, 30).map {
                RailItem(it.id, it.name, it.groupTitle, it.logoUrl, wide = true)
            },
            movies = libraryDao.searchMovies(pattern, request.hiddenMovies, 60).map {
                RailItem(it.id, it.title, it.year?.toString(), it.posterUrl)
            },
            series = libraryDao.searchSeries(pattern, request.hiddenSeries, 60).map {
                RailItem(it.id, it.title, it.year?.toString(), it.posterUrl)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults.Idle)

    fun fillMovieArtwork(id: Long) {
        viewModelScope.launch { metadata.resolveMovie(id) }
    }

    fun fillSeriesArtwork(id: Long) {
        viewModelScope.launch { metadata.resolveSeries(id) }
    }
}

@Composable
fun SearchScreen(
    onOpenChannel: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val profile = LocalDeviceProfile.current
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()

    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // På telefonen öppnas tangentbordet direkt — man kom hit för att skriva.
    // På TV lämnas fokus kvar: annars dyker skärmtangentbordet upp i
    // samma stund man bläddrar förbi Sök i menyn.
    if (!profile.isTv) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { fieldFocus.requestFocus() }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceLg),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQuery,
                label = { Text("Sök kanaler, filmer och serier") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = metrics.screenEdge)
                    .padding(top = metrics.screenEdge)
                    .focusRequester(fieldFocus),
            )
        }

        when (val current = results) {
            is SearchResults.Idle -> item {
                Text(
                    text = if (query.isBlank()) {
                        "Skriv minst $MIN_QUERY tecken."
                    } else {
                        "Skriv minst $MIN_QUERY tecken för att söka."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textTertiary,
                    modifier = Modifier.padding(horizontal = metrics.screenEdge),
                )
            }

            is SearchResults.Ready -> if (current.isEmpty) {
                item {
                    Text(
                        text = "Inga träffar för \"${current.query}\".",
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(horizontal = metrics.screenEdge),
                    )
                }
            } else {
                item {
                    ContentRail(
                        title = "Kanaler",
                        items = current.channels,
                        onOpen = { onOpenChannel(it.id) },
                    )
                }
                item {
                    ContentRail(
                        title = "Filmer",
                        items = current.movies,
                        onOpen = { onOpenMovie(it.id) },
                        onMissingImage = { viewModel.fillMovieArtwork(it.id) },
                    )
                }
                item {
                    ContentRail(
                        title = "Serier",
                        items = current.series,
                        onOpen = { onOpenSeries(it.id) },
                        onMissingImage = { viewModel.fillSeriesArtwork(it.id) },
                    )
                }
            }
        }
    }
}

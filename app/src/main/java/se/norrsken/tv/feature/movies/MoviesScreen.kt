package se.norrsken.tv.feature.movies

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.norrsken.tv.core.device.LocalDeviceProfile
import se.norrsken.tv.core.ui.component.CategoryColumn
import se.norrsken.tv.core.ui.component.CategoryRow
import se.norrsken.tv.core.ui.component.EmptyState
import se.norrsken.tv.core.ui.component.PosterGrid
import se.norrsken.tv.core.ui.component.PosterItem
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.data.db.dao.LibraryDao
import se.norrsken.tv.data.db.dao.PlaylistDao
import se.norrsken.tv.data.db.entity.CategoryEntity
import se.norrsken.tv.data.db.entity.ContentKind
import se.norrsken.tv.data.metadata.MetadataRepository
import se.norrsken.tv.data.repository.CategoryVisibility
import se.norrsken.tv.data.repository.VodDetailsRepository
import se.norrsken.tv.data.repository.likePattern
import androidx.compose.ui.platform.LocalContext
import se.norrsken.tv.core.util.openTrailer
import se.norrsken.tv.feature.library.LibraryBrowser
import se.norrsken.tv.data.repository.isCategoryHidden
import se.norrsken.tv.data.settings.AppPreferences
import se.norrsken.tv.data.settings.CategorySort
import se.norrsken.tv.data.settings.LibrarySort
import se.norrsken.tv.data.settings.SettingsRepository
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MoviesViewModel @Inject constructor(
    private val libraryDao: LibraryDao,
    playlistDao: PlaylistDao,
    private val metadata: MetadataRepository,
    private val details: VodDetailsRepository,
    private val settings: SettingsRepository,
    visibility: CategoryVisibility,
) : ViewModel() {

    // Maste ligga fore categories: en egenskapsinitierare far inte
    // referera till nagot som deklareras langre ner i klassen.
    private val preferences = settings.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    /** Dolda kategoriers id:n. Deras innehall uteslut aven under "Alla". */
    private val hiddenIds = visibility.hiddenIds(ContentKind.MOVIE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> =
        preferences
            .flatMapLatest { prefs ->
                playlistDao.observeCategories(ContentKind.MOVIE, prefs.categorySort.code)
                    .map { list -> list.filterNot { isCategoryHidden(it, prefs) } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sortLabel: StateFlow<String> = preferences
        .map { it.librarySort.label }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val categorySortLabel: StateFlow<String> = preferences
        .map { it.categorySort.label }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun cycleSort() {
        val order = LibrarySort.entries
        val next = order[(order.indexOf(preferences.value.librarySort) + 1) % order.size]
        viewModelScope.launch { settings.setLibrarySort(next) }
    }

    fun cycleCategorySort() {
        val order = CategorySort.entries
        val next = order[(order.indexOf(preferences.value.categorySort) + 1) % order.size]
        viewModelScope.launch { settings.setCategorySort(next) }
    }

    private val _selected = MutableStateFlow<Long?>(null)

    /**
     * Doljs kategorin man star i blir valet "Alla" i stallet for en tom lista
     * utan forklaring.
     */
    val selected: StateFlow<Long?> =
        combine(_selected, hiddenIds) { id, hidden -> id?.takeIf { it !in hidden } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter.asStateFlow()

    /** Hur många titlar som hämtas just nu. Växer när man närmar sig slutet. */
    private val _limit = MutableStateFlow(PAGE)

    val movies: StateFlow<List<PosterItem>> =
        combine(
            _selected,
            preferences,
            hiddenIds,
            // Paus innan sökningen körs: utan den blir varje tangenttryck en
            // fråga mot databasen.
            _filter.debounce(250).map { it.trim().ifBlank { null }?.let(::likePattern) },
            _limit,
        ) { id, prefs, hidden, pattern, limit ->
            LibraryQuery(id?.takeIf { it !in hidden }, prefs.librarySort, hidden, pattern, limit)
        }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            libraryDao.observeMovies(
                categoryId = query.categoryId,
                sort = query.sort.code,
                hiddenIds = query.hidden,
                filter = query.filter,
                limit = query.limit,
            )
        }
        .map { list ->
            list.map { movie ->
                PosterItem(
                    id = movie.id,
                    title = movie.title,
                    subtitle = movie.year?.toString(),
                    posterUrl = movie.posterUrl,
                    backdropUrl = movie.backdropUrl,
                    rating = movie.rating,
                    overview = movie.overview,
                    trailerUrl = movie.trailerUrl,
                    cast = movie.cast,
                    director = movie.director,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Markeringen har stått still — hämta leverantörens beskrivning och
     * trailer för just den titeln. Utan det står huvudytan tom tills man
     * öppnat filmen en gång.
     */
    fun focusHold(id: Long) {
        viewModelScope.launch { details.ensureDetails(id) }
    }

    /** Antal i kategorin enligt databasen — inte antal inlästa. */
    val total: StateFlow<Int> =
        combine(_selected, hiddenIds, _filter.debounce(250)) { id, hidden, text ->
            Triple(id?.takeIf { it !in hidden }, hidden, text.trim().ifBlank { null }?.let(::likePattern))
        }
        .distinctUntilChanged()
        .flatMapLatest { (categoryId, hidden, pattern) ->
            libraryDao.countMovies(categoryId, hidden, pattern)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setFilter(value: String) {
        _filter.value = value
        _limit.value = PAGE
    }

    /**
     * Nästa sida — men bara om den nuvarande faktiskt var full. Annars hade
     * slutet av biblioteket bett om mer i all oändlighet.
     */
    fun loadMore() {
        if (movies.value.size >= _limit.value && _limit.value < MAX_ITEMS) {
            _limit.value = _limit.value + PAGE
        }
    }

    fun select(categoryId: Long?) {
        _selected.value = categoryId
        _limit.value = PAGE
    }

    fun fillMissingArtwork(id: Long) {
        viewModelScope.launch { metadata.resolveMovie(id) }
    }
}

@Composable
fun MoviesScreen(
    onOpenMovie: (Long) -> Unit,
    onPlayMovie: (Long) -> Unit,
    onManageCategories: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MoviesViewModel = hiltViewModel(),
) {
    val items by viewModel.movies.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val sortLabel by viewModel.sortLabel.collectAsStateWithLifecycle()
    val categorySortLabel by viewModel.categorySortLabel.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val total by viewModel.total.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LibraryBrowser(
        items = items,
        categories = categories,
        selectedCategory = selected,
        onSelectCategory = viewModel::select,
        sortLabel = sortLabel,
        onCycleSort = viewModel::cycleSort,
        categorySortLabel = categorySortLabel,
        onCycleCategorySort = viewModel::cycleCategorySort,
        onManageCategories = onManageCategories,
        filter = filter,
        onFilterChange = viewModel::setFilter,
        onOpen = { onOpenMovie(it.id) },
        onPlay = { onPlayMovie(it.id) },
        onTrailer = { item -> item.trailerUrl?.let { openTrailer(context, it) } },
        onFocusHold = viewModel::focusHold,
        total = total,
        onMissingPoster = { viewModel.fillMissingArtwork(it.id) },
        onNeedMore = viewModel::loadMore,
        allLabel = "Alla filmer",
        filterLabel = "Filtrera filmer",
        emptyTitle = "Inga filmer",
        emptyBody = "Filmer kommer från ett Xtream-konto. En vanlig M3U-fil innehåller sällan någon filmdel.",
        modifier = modifier,
    )
}

/** Allt som avgör vilken frågan blir. */
private data class LibraryQuery(
    val categoryId: Long?,
    val sort: LibrarySort,
    val hidden: List<Long>,
    val filter: String?,
    val limit: Int,
)

private const val PAGE = 200
/** Tak mot att hela biblioteket hamnar i minnet på en TV-box. */
private const val MAX_ITEMS = 4_000

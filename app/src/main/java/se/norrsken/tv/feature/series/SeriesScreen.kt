package se.norrsken.tv.feature.series

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
import se.norrsken.tv.data.repository.likePattern
import se.norrsken.tv.feature.library.LibraryBrowser
import se.norrsken.tv.data.repository.isCategoryHidden
import se.norrsken.tv.data.settings.AppPreferences
import se.norrsken.tv.data.settings.CategorySort
import se.norrsken.tv.data.settings.LibrarySort
import se.norrsken.tv.data.settings.SettingsRepository
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SeriesViewModel @Inject constructor(
    libraryDao: LibraryDao,
    playlistDao: PlaylistDao,
    private val metadata: MetadataRepository,
    private val settings: SettingsRepository,
    visibility: CategoryVisibility,
) : ViewModel() {

    // Maste ligga fore categories: en egenskapsinitierare far inte
    // referera till nagot som deklareras langre ner i klassen.
    private val preferences = settings.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    /** Dolda kategoriers id:n. Deras innehall uteslut aven under "Alla". */
    private val hiddenIds = visibility.hiddenIds(ContentKind.SERIES)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> =
        preferences
            .flatMapLatest { prefs ->
                playlistDao.observeCategories(ContentKind.SERIES, prefs.categorySort.code)
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

    val series: StateFlow<List<PosterItem>> =
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
            libraryDao.observeSeries(
                categoryId = query.categoryId,
                sort = query.sort.code,
                hiddenIds = query.hidden,
                filter = query.filter,
                limit = query.limit,
            )
        }
        .map { list ->
            list.map { item ->
                PosterItem(
                    id = item.id,
                    title = item.title,
                    subtitle = item.year?.toString(),
                    posterUrl = item.posterUrl,
                    backdropUrl = item.backdropUrl,
                    rating = item.rating,
                    overview = item.overview,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(value: String) {
        _filter.value = value
        _limit.value = PAGE
    }

    /**
     * Nästa sida — men bara om den nuvarande faktiskt var full. Annars hade
     * slutet av biblioteket bett om mer i all oändlighet.
     */
    fun loadMore() {
        if (series.value.size >= _limit.value && _limit.value < MAX_ITEMS) {
            _limit.value = _limit.value + PAGE
        }
    }

    fun select(categoryId: Long?) {
        _selected.value = categoryId
        _limit.value = PAGE
    }

    fun fillMissingArtwork(id: Long) {
        viewModelScope.launch { metadata.resolveSeries(id) }
    }
}

@Composable
fun SeriesScreen(
    onOpenSeries: (Long) -> Unit,
    onManageCategories: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeriesViewModel = hiltViewModel(),
) {
    val items by viewModel.series.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val sortLabel by viewModel.sortLabel.collectAsStateWithLifecycle()
    val categorySortLabel by viewModel.categorySortLabel.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()

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
        onOpen = { onOpenSeries(it.id) },
        onMissingPoster = { viewModel.fillMissingArtwork(it.id) },
        onNeedMore = viewModel::loadMore,
        allLabel = "Alla serier",
        filterLabel = "Filtrera serier",
        emptyTitle = "Inga serier",
        emptyBody = "Serier kommer från ett Xtream-konto. Avsnitten hämtas först när du öppnar en serie.",
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

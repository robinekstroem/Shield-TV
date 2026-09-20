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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
import se.norrsken.tv.data.settings.AppPreferences
import se.norrsken.tv.data.settings.CategorySort
import se.norrsken.tv.data.settings.LibrarySort
import se.norrsken.tv.data.settings.SettingsRepository
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SeriesViewModel @Inject constructor(
    libraryDao: LibraryDao,
    playlistDao: PlaylistDao,
    private val metadata: MetadataRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> =
        preferences
            .flatMapLatest { playlistDao.observeCategories(ContentKind.SERIES, it.categorySort.code) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val preferences = settings.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

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
    val selected: StateFlow<Long?> = _selected.asStateFlow()

    val series: StateFlow<List<PosterItem>> =
        combine(_selected, preferences) { id, prefs -> id to prefs.librarySort }
        .flatMapLatest { (categoryId, sort) ->
            libraryDao.observeSeries(categoryId = categoryId, sort = sort.code, limit = 300)
        }
        .map { list ->
            list.map { item ->
                PosterItem(
                    id = item.id,
                    title = item.title,
                    subtitle = item.year?.toString(),
                    posterUrl = item.posterUrl,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(categoryId: Long?) {
        _selected.value = categoryId
    }

    fun fillMissingArtwork(id: Long) {
        viewModelScope.launch { metadata.resolveSeries(id) }
    }
}

@Composable
fun SeriesScreen(
    onOpenSeries: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeriesViewModel = hiltViewModel(),
) {
    val series by viewModel.series.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val sortLabel by viewModel.sortLabel.collectAsStateWithLifecycle()
    val categorySortLabel by viewModel.categorySortLabel.collectAsStateWithLifecycle()
    val profile = LocalDeviceProfile.current
    val metrics = LocalMetrics.current

    if (series.isEmpty() && categories.isEmpty()) {
        EmptyState(
            modifier = modifier,
            title = "Inga serier",
            body = "Serier kommer fran ett Xtream-konto. Avsnitten hamtas " +
                "forst nar du oppnar en serie.",
        )
        return
    }

    val grid = @Composable { mod: Modifier ->
        PosterGrid(
            items = series,
            onOpen = { onOpenSeries(it.id) },
            onMissingPoster = { viewModel.fillMissingArtwork(it.id) },
            modifier = mod,
        )
    }

    if (profile.isTv) {
        Row(modifier = modifier.fillMaxSize().padding(start = metrics.screenEdge)) {
            CategoryColumn(
                categories = categories,
                selected = selected,
                onSelect = viewModel::select,
                allLabel = "Alla serier",
                sortLabel = sortLabel,
                onCycleSort = viewModel::cycleSort,
                categorySortLabel = categorySortLabel,
                onCycleCategorySort = viewModel::cycleCategorySort,
                modifier = Modifier.width(240.dp),
            )
            grid(Modifier)
        }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            CategoryRow(
                categories = categories,
                selected = selected,
                onSelect = viewModel::select,
                allLabel = "Alla",
                sortLabel = sortLabel,
                onCycleSort = viewModel::cycleSort,
                modifier = Modifier.padding(horizontal = metrics.screenEdge),
            )
            grid(Modifier)
        }
    }
}

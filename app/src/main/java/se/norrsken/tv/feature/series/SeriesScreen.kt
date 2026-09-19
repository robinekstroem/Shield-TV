package se.norrsken.tv.feature.series

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.norrsken.tv.data.metadata.MetadataRepository
import se.norrsken.tv.core.ui.component.EmptyState
import se.norrsken.tv.core.ui.component.PosterGrid
import se.norrsken.tv.core.ui.component.PosterItem
import se.norrsken.tv.data.db.dao.LibraryDao
import javax.inject.Inject

@HiltViewModel
class SeriesViewModel @Inject constructor(
    libraryDao: LibraryDao,
    private val metadata: MetadataRepository,
) : ViewModel() {

    /**
     * Anropas när ett kort utan omslag kommer in på skärmen. Repositoryt
     * struntar i dubbletter, så det gör inget att rutnätet rekomponeras.
     */
    fun fillMissingArtwork(id: Long) {
        viewModelScope.launch { metadata.resolveSeries(id) }
    }

    val series: StateFlow<List<PosterItem>> = libraryDao.observeSeries(limit = 300)
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
}

@Composable
fun SeriesScreen(
    modifier: Modifier = Modifier,
    viewModel: SeriesViewModel = hiltViewModel(),
) {
    val series by viewModel.series.collectAsStateWithLifecycle()

    if (series.isEmpty()) {
        EmptyState(
            modifier = modifier,
            title = "Inga serier",
            body = "Serier kommer från ett Xtream-konto. Avsnitten hämtas först när du öppnar en serie.",
        )
        return
    }

    PosterGrid(
        items = series,
        onOpen = { /* Fas 5: säsonger och avsnitt */ },
        onMissingPoster = { viewModel.fillMissingArtwork(it.id) },
        modifier = modifier,
    )
}

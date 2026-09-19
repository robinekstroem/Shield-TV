package se.norrsken.tv.feature.movies

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
class MoviesViewModel @Inject constructor(
    libraryDao: LibraryDao,
    private val metadata: MetadataRepository,
) : ViewModel() {

    /**
     * Anropas när ett kort utan omslag kommer in på skärmen. Repositoryt
     * struntar i dubbletter, så det gör inget att rutnätet rekomponeras.
     */
    fun fillMissingArtwork(id: Long) {
        viewModelScope.launch { metadata.resolveMovie(id) }
    }

    val movies: StateFlow<List<PosterItem>> = libraryDao.observeMovies(limit = 300)
        .map { list ->
            list.map { movie ->
                PosterItem(
                    id = movie.id,
                    title = movie.title,
                    subtitle = movie.year?.toString(),
                    posterUrl = movie.posterUrl,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun MoviesScreen(
    modifier: Modifier = Modifier,
    viewModel: MoviesViewModel = hiltViewModel(),
) {
    val movies by viewModel.movies.collectAsStateWithLifecycle()

    if (movies.isEmpty()) {
        EmptyState(
            modifier = modifier,
            title = "Inga filmer",
            body = "Filmer kommer från ett Xtream-konto. En vanlig M3U-fil innehåller sällan någon filmdel.",
        )
        return
    }

    PosterGrid(
        items = movies,
        onOpen = { /* Fas 5: detaljsidan */ },
        onMissingPoster = { viewModel.fillMissingArtwork(it.id) },
        modifier = modifier,
    )
}

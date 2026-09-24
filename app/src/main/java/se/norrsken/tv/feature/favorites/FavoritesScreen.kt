package se.norrsken.tv.feature.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.flow.stateIn
import se.norrsken.tv.core.ui.component.ContentRail
import se.norrsken.tv.core.ui.component.EmptyState
import se.norrsken.tv.core.ui.component.RailItem
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.data.repository.FavoritesRepository
import javax.inject.Inject

data class FavoritesState(
    val movies: List<RailItem> = emptyList(),
    val series: List<RailItem> = emptyList(),
    val channels: List<RailItem> = emptyList(),
) {
    val empty get() = movies.isEmpty() && series.isEmpty() && channels.isEmpty()
}

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    favorites: FavoritesRepository,
) : ViewModel() {
    val state: StateFlow<FavoritesState> = combine(
        favorites.movies(), favorites.series(), favorites.channels()
    ) { movies, series, channels ->
        FavoritesState(
            movies = movies.map { RailItem(it.id, it.title, it.year?.toString(), it.posterUrl) },
            series = series.map { RailItem(it.id, it.title, it.year?.toString(), it.posterUrl, isSeries = true) },
            channels = channels.map { RailItem(it.id, it.name, it.groupTitle, it.logoUrl, wide = true, isLive = true) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavoritesState())
}

@Composable
fun FavoritesScreen(
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onOpenChannel: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val metrics = LocalMetrics.current

    if (state.empty) {
        EmptyState(
            modifier = modifier,
            title = "Inga favoriter än",
            body = "Öppna en film eller serie och välj Lägg till favorit. Dina favoriter samlas här.",
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceLg),
    ) {
        item {
            ContentRail("Filmer", state.movies, onOpen = { onOpenMovie(it.id) })
        }
        item {
            ContentRail("Serier", state.series, onOpen = { onOpenSeries(it.id) })
        }
        item {
            ContentRail("Kanaler", state.channels, onOpen = { onOpenChannel(it.id) })
        }
    }
}

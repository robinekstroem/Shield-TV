package se.norrsken.tv.feature.seriesdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.norrsken.tv.core.ui.component.FocusableSurface
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.data.db.dao.LibraryDao
import se.norrsken.tv.data.db.entity.EpisodeEntity
import se.norrsken.tv.data.repository.EpisodeRepository
import javax.inject.Inject

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryDao: LibraryDao,
    private val episodes: EpisodeRepository,
) : ViewModel() {

    private val seriesId: Long = savedStateHandle.get<String>("seriesId")?.toLongOrNull() ?: 0L

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val episodeList: StateFlow<List<EpisodeEntity>> =
        libraryDao.observeEpisodes(seriesId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            _title.value = libraryDao.seriesById(seriesId)?.title.orEmpty()
            _error.value = episodes.ensureEpisodes(seriesId)
            _loading.value = false
        }
    }
}

@Composable
fun SeriesDetailScreen(
    onPlayEpisode: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeriesDetailViewModel = hiltViewModel(),
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val title by viewModel.title.collectAsStateWithLifecycle()
    val episodes by viewModel.episodeList.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(metrics.screenEdge),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
    ) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = palette.textPrimary,
                modifier = Modifier.padding(bottom = metrics.spaceMd),
            )
        }

        if (loading && episodes.isEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
                ) {
                    CircularProgressIndicator()
                    Text("Hamtar avsnitt...", color = palette.textSecondary)
                }
            }
        }

        error?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.alert,
                )
            }
        }

        // Sasongsrubrik nar sasongen byts. Enklare an en grupperad struktur
        // och ger samma lasbarhet i en lista.
        itemsIndexed(episodes, key = { _, item -> item.id }) { index, episode ->
            // Jamfor med foregaende avsnitt i listan i stallet for att halla
            // ett lopande varde: item-lambdan kors per synligt avsnitt, i
            // scrollordning, sa ett lopande varde ger rubriker som forsvinner
            // nar man scrollar tillbaka.
            val startsSeason = episodes.getOrNull(index - 1)?.season != episode.season
            Column {
                if (startsSeason) {
                    Text(
                        text = "Sasong ${episode.season}",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(
                            top = metrics.spaceMd,
                            bottom = metrics.spaceXs,
                        ),
                    )
                }
                EpisodeRow(episode = episode, onClick = { onPlayEpisode(episode.id) })
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: EpisodeEntity, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    FocusableSurface(onClick = onClick, modifier = Modifier.fillMaxWidth()) { _ ->
        Column(Modifier.padding(metrics.spaceMd)) {
            Text(
                text = "S%02dE%02d  %s".format(episode.season, episode.episode, episode.title),
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            episode.durationMinutes?.let {
                Text(
                    text = "$it min",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textTertiary,
                )
            }
        }
    }
}

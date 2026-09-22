package se.norrsken.tv.feature.seriesdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import se.norrsken.tv.core.ui.component.AuroraLoader
import se.norrsken.tv.core.ui.component.SecondaryAction
import se.norrsken.tv.core.ui.theme.LocalThemeStyle
import se.norrsken.tv.core.util.openTrailer
import se.norrsken.tv.data.db.entity.SeriesEntity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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

    /** Hela serieraden, levande — backdrop och beskrivning fylls på i efterhand. */
    val series: StateFlow<SeriesEntity?> =
        libraryDao.observeSeriesById(seriesId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    val series by viewModel.series.collectAsStateWithLifecycle()
    val style = LocalThemeStyle.current
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
    ) {
        item {
            // Seriens huvud: backdrop som tonar ut i atmosfären, titel,
            // fakta och beskrivning. Samma språk som filmsidan.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.heroHeight),
            ) {
                series?.backdropUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(palette.scrim, palette.scrimClear)))
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(palette.scrimClear, palette.background)))
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(metrics.screenEdge)
                        .widthIn(max = 640.dp),
                    verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
                ) {
                    val name = series?.title ?: title
                    Text(
                        text = if (style.uppercaseHeadings) name.uppercase() else name,
                        style = MaterialTheme.typography.displayLarge,
                        color = palette.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val facts = listOfNotNull(
                        series?.year?.toString(),
                        series?.rating?.let { "★ %.1f".format(it) },
                        series?.genres,
                    )
                    if (facts.isNotEmpty()) {
                        Text(
                            text = facts.joinToString("  ·  "),
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textSecondary,
                        )
                    }
                    series?.overview?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.textSecondary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Trailerknappen finns bara när leverantören faktiskt
                    // skickat en trailer.
                    series?.trailerUrl?.let { trailer ->
                        SecondaryAction(
                            label = "Trailer",
                            onClick = { openTrailer(context, trailer) },
                            modifier = Modifier.padding(top = metrics.spaceSm),
                        )
                    }
                }
            }
        }

        if (loading && episodes.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = metrics.screenEdge),
                    verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
                ) {
                    Text("Hämtar avsnitt …", color = palette.textSecondary)
                    AuroraLoader(modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth())
                }
            }
        }

        error?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.alert,
                    modifier = Modifier.padding(horizontal = metrics.screenEdge),
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
            Column(Modifier.padding(horizontal = metrics.screenEdge)) {
                if (startsSeason) {
                    Text(
                        text = "Säsong ${episode.season}",
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

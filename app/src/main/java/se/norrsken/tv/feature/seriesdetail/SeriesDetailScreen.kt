package se.norrsken.tv.feature.seriesdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import se.norrsken.tv.core.ui.component.AuroraLoader
import se.norrsken.tv.core.ui.component.CinematicLightField
import se.norrsken.tv.core.ui.component.MetaChip
import se.norrsken.tv.core.ui.component.PremiumBadge
import se.norrsken.tv.core.ui.component.PrimaryAction
import se.norrsken.tv.core.ui.component.SecondaryAction
import se.norrsken.tv.core.ui.theme.LocalThemeStyle
import se.norrsken.tv.core.ui.theme.LocalMotion
import se.norrsken.tv.core.util.openTrailer
import se.norrsken.tv.data.db.entity.SeriesEntity
import se.norrsken.tv.data.db.entity.MediaKind
import se.norrsken.tv.data.repository.FavoritesRepository
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
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
    private val favorites: FavoritesRepository,
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

    val isFavorite: StateFlow<Boolean> = favorites.isFavorite(MediaKind.SERIES, seriesId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleFavorite() {
        viewModelScope.launch { favorites.toggle(MediaKind.SERIES, seriesId, isFavorite.value) }
    }

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
    val motion = LocalMotion.current
    val style = LocalThemeStyle.current
    val context = LocalContext.current
    val title by viewModel.title.collectAsStateWithLifecycle()
    val episodes by viewModel.episodeList.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val series by viewModel.series.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()

    val firstEpisode = episodes.firstOrNull()
    val firstFocus = remember { FocusRequester() }
    var shown by remember { mutableStateOf(false) }
    val appear by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(motion.scale(480)),
        label = "seriesDetailAppear",
    )

    LaunchedEffect(series?.id, firstEpisode?.id) {
        shown = true
        if (firstEpisode != null) {
            withFrameNanos { }
            runCatching { firstFocus.requestFocus() }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.heroHeight),
            ) {
                when {
                    series?.backdropUrl != null -> AsyncImage(
                        model = series?.backdropUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    series?.posterUrl != null -> AsyncImage(
                        model = series?.posterUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .height(metrics.heroHeight)
                            .aspectRatio(2f / 3f),
                    )
                }

                CinematicLightField(Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            0f to palette.scrim,
                            0.48f to palette.scrim.copy(alpha = 0.72f),
                            0.76f to palette.scrimClear,
                        )
                    )
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.48f to palette.scrimClear,
                            1f to palette.background,
                        )
                    )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .safeDrawingPadding()
                        .padding(metrics.screenEdge)
                        .widthIn(max = 680.dp)
                        .graphicsLayer {
                            alpha = appear
                            translationY = (1f - appear) * 28.dp.toPx()
                        },
                    verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
                ) {
                    PremiumBadge("SERIE")
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
                        series?.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                        episodes.map { it.season }.distinct().size.takeIf { it > 0 }?.let { "$it säsonger" },
                        series?.genres,
                    )
                    if (facts.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm)) {
                            facts.take(4).forEach { MetaChip(it) }
                        }
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

                    Row(
                        modifier = Modifier.padding(top = metrics.spaceMd),
                        horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
                    ) {
                        if (firstEpisode != null) {
                            PrimaryAction(
                                label = "Spela S%02dE%02d".format(firstEpisode.season, firstEpisode.episode),
                                onClick = { onPlayEpisode(firstEpisode.id) },
                                modifier = Modifier.focusRequester(firstFocus),
                            )
                        }
                        series?.trailerUrl?.let { trailer ->
                            SecondaryAction(label = "Trailer", onClick = { openTrailer(context, trailer) })
                        }
                        SecondaryAction(
                            label = if (isFavorite) "Ta bort favorit" else "Lägg till favorit",
                            onClick = viewModel::toggleFavorite,
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

        itemsIndexed(episodes, key = { _, item -> item.id }) { index, episode ->
            val startsSeason = episodes.getOrNull(index - 1)?.season != episode.season
            Column(Modifier.padding(horizontal = metrics.screenEdge)) {
                if (startsSeason) {
                    Text(
                        text = "Säsong ${episode.season}",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = metrics.spaceMd, bottom = metrics.spaceXs),
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

    FocusableSurface(onClick = onClick, modifier = Modifier.fillMaxWidth()) { focused ->
        Row(
            modifier = Modifier.padding(metrics.spaceMd),
            horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 150.dp, max = 150.dp).height(84.dp)
                    .background(palette.surface, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                if (episode.thumbnailUrl != null) {
                    AsyncImage(
                        model = episode.thumbnailUrl,
                        contentDescription = episode.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = "S%02dE%02d".format(episode.season, episode.episode),
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textTertiary,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "S%02dE%02d  %s".format(episode.season, episode.episode, episode.title),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (focused) palette.focus else palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val details = listOfNotNull(
                    episode.durationMinutes?.let { "$it min" },
                    episode.overview,
                )
                if (details.isNotEmpty()) {
                    Text(
                        text = details.joinToString("  •  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

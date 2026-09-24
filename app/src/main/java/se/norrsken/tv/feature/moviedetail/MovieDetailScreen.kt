package se.norrsken.tv.feature.moviedetail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.norrsken.tv.core.ui.component.AuroraLoader
import se.norrsken.tv.core.ui.component.CinematicLightField
import se.norrsken.tv.core.ui.component.MetaChip
import se.norrsken.tv.core.ui.component.PremiumBadge
import se.norrsken.tv.core.ui.component.PrimaryAction
import se.norrsken.tv.core.ui.component.SecondaryAction
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalMotion
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.core.ui.theme.LocalThemeStyle
import se.norrsken.tv.core.util.openTrailer
import se.norrsken.tv.data.db.dao.LibraryDao
import se.norrsken.tv.data.db.entity.MovieEntity
import se.norrsken.tv.data.db.entity.MediaKind
import se.norrsken.tv.data.repository.FavoritesRepository
import se.norrsken.tv.data.metadata.MetadataRepository
import se.norrsken.tv.data.repository.VodDetailsRepository
import javax.inject.Inject

@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryDao: LibraryDao,
    metadata: MetadataRepository,
    vodDetails: VodDetailsRepository,
    private val favorites: FavoritesRepository,
) : ViewModel() {

    val movieId: Long = savedStateHandle.get<String>("id")?.toLongOrNull() ?: 0L

    val movie: StateFlow<MovieEntity?> = libraryDao.observeMovie(movieId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isFavorite: StateFlow<Boolean> = favorites.isFavorite(MediaKind.MOVIE, movieId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleFavorite() {
        viewModelScope.launch { favorites.toggle(MediaKind.MOVIE, movieId, isFavorite.value) }
    }

    init {
        viewModelScope.launch {
            // Leverantörens egen data först. Den slås upp på filmens eget
            // id hos leverantören och kan därför aldrig peka på fel film.
            val fromProvider = vodDetails.ensureDetails(movieId)

            // TMDB används bara när leverantören inte hade något. Det är en
            // matchning på titel, alltså en kvalificerad gissning — därför
            // sist, aldrig först.
            if (!fromProvider) metadata.resolveMovie(movieId)
        }
    }
}

/**
 * Filmens egen sida. Backdropen tar hela skärmen, texten ligger på en
 * gradient — aldrig direkt på en okontrollerad bild.
 *
 * Bara det som faktiskt är känt visas. Saknas speltid blir raden kortare,
 * inte "okänd speltid". Finns ingen trailer finns ingen trailerknapp.
 */
@Composable
fun MovieDetailScreen(
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MovieDetailViewModel = hiltViewModel(),
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val motion = LocalMotion.current
    val style = LocalThemeStyle.current
    val context = LocalContext.current
    val movie by viewModel.movie.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()

    val playFocus = remember { FocusRequester() }
    var shown by remember { mutableStateOf(false) }
    val appear by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(motion.scale(480)),
        label = "detailAppear",
    )

    val current = movie
    if (current == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AuroraLoader(modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth())
        }
        return
    }

    LaunchedEffect(current.id) {
        shown = true
        // Fokus på Spela, så OK direkt startar filmen på TV:n.
        withFrameNanos { }
        runCatching { playFocus.requestFocus() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            current.backdropUrl != null -> AsyncImage(
                model = current.backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Utan backdrop får omslaget ta högra sidan. Bättre än en tom
            // yta, och aldrig en annan films bild.
            current.posterUrl != null -> AsyncImage(
                model = current.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f),
            )
        }

        CinematicLightField(Modifier.fillMaxSize())

        // Två gradienter: vänster bär texten, botten syr ihop bilden med
        // atmosfären så ingen kant syns.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(palette.scrim, palette.scrimClear)))
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to palette.scrimClear,
                        1f to palette.background,
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .safeDrawingPadding()
                .padding(metrics.screenEdge)
                .widthIn(max = 640.dp)
                .graphicsLayer {
                    alpha = appear
                    translationY = (1f - appear) * 28.dp.toPx()
                },
            verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
        ) {
            PremiumBadge("FILM")

            Text(
                text = if (style.uppercaseHeadings) current.title.uppercase() else current.title,
                style = MaterialTheme.typography.displayLarge,
                color = palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val facts = listOfNotNull(
                current.year?.toString(),
                current.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                current.runtimeMinutes?.let { formatRuntime(it) },
                current.genres,
            )
            if (facts.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm)) {
                    facts.take(4).forEach { fact -> MetaChip(fact) }
                }
            }

            current.overview?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textSecondary,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.padding(top = metrics.spaceMd),
                horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
            ) {
                PrimaryAction(
                    label = "Spela",
                    onClick = { onPlay(current.id) },
                    modifier = Modifier.focusRequester(playFocus),
                )
                current.trailerUrl?.let { trailer ->
                    SecondaryAction(
                        label = "Trailer",
                        onClick = { openTrailer(context, trailer) },
                    )
                }
                SecondaryAction(
                    label = if (isFavorite) "Ta bort favorit" else "Lägg till favorit",
                    onClick = viewModel::toggleFavorite,
                )
            }
        }
    }
}

private fun formatRuntime(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}

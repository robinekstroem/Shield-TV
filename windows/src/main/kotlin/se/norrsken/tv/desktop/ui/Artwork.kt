package se.norrsken.tv.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.norrsken.tv.desktop.AppState

/**
 * Ett omslag med reserver: adresserna provas i tur och ordning tills en går
 * att visa — den visade versionens omslag, de andra versionernas, TMDB:s.
 * Ett trasigt omslag hos leverantören ger alltså inte en tom ruta så länge
 * något annat finns.
 *
 * Bilden tonas in när den kommer från disken eller nätet, men inte när den
 * redan finns i minnet: då ska den bara synas, utan att blinka.
 * [placeholder] visas när inget gick att hämta, och efter en stund medan
 * det fortfarande laddar.
 */
@Composable
fun Artwork(
    candidates: List<String>,
    modifier: Modifier = Modifier,
    maxSide: Int = 480,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    placeholder: @Composable () -> Unit = {},
) {
    val urls = remember(candidates) {
        candidates.map { it.trim() }.filter { it.startsWith("http", ignoreCase = true) }.distinct()
    }
    val initial = remember(urls, maxSide) { urls.firstNotNullOfOrNull { Images.cached(it, maxSide) } }
    var image by remember(urls, maxSide) { mutableStateOf(initial) }
    var showPlaceholder by remember(urls, maxSide) { mutableStateOf(urls.isEmpty()) }
    if (image == null && urls.isNotEmpty()) {
        LaunchedEffect(urls, maxSide) {
            // Tom ruta en kort stund, så att titeln inte blinkar förbi när omslaget kommer från disken.
            val patience = launch {
                delay(PLACEHOLDER_DELAY_MS)
                showPlaceholder = true
            }
            for (url in urls) {
                val loaded = Images.load(url, maxSide)
                if (loaded != null) {
                    patience.cancel()
                    image = loaded
                    return@LaunchedEffect
                }
            }
            patience.cancel()
            showPlaceholder = true
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (image != null) 1f else 0f,
        animationSpec = tween(if (initial != null) 0 else FADE_MS),
        label = "omslag",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        val current = image
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                alignment = alignment,
                contentScale = contentScale,
                alpha = alpha,
                filterQuality = FilterQuality.Medium,
            )
        } else if (showPlaceholder) {
            placeholder()
        }
    }
}

private const val PLACEHOLDER_DELAY_MS = 900L
/** Tv: kortare, så att omslagen känns snabba när man bläddrar. */
private val FADE_MS = if (tvLayout) 140 else 220

/**
 * Omslagen sparas i förväg, de senast tillagda filmerna först, medan inget
 * spelas. Det som redan är sparat hämtas aldrig igen, så efter första gången
 * är det bara nya filmer som får sina omslag hämtade. Högst [limit] omslag.
 */
@Composable
fun CoverPrefetch(state: AppState, limit: Int) {
    LaunchedEffect(state, limit) {
        // Först det som syns på skärmen, sedan resten i lugn takt.
        delay(6_000)
        snapshotFlow { state.versions }
            .filter { !it.isEmpty }
            .conflate()
            .collect {
                val urls = withContext(Dispatchers.Default) {
                    runCatching { state.coverPrefetchList(limit) }.getOrDefault(emptyList())
                }
                Images.prefetch(urls)
                // Medan TMDB söks igenom byggs versionerna om ofta; listan räcker att förnya då och då.
                delay(90_000)
            }
    }
    LaunchedEffect(state) {
        while (true) {
            Images.backgroundPaused = state.nowPlaying != null
            state.coverStatus = Images.status()
            delay(2_000)
        }
    }
}

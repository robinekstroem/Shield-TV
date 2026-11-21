package se.norrsken.tv.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.NextUp
import se.norrsken.tv.desktop.player.VideoPlayer

// --- Levande bakgrund -----------------------------------------------------------------
//
// Titeln man står på blir bakgrund: bilden tonas in mjukt och rör sig sedan
// långsamt, som i en filmtrailer (Ken Burns). Rörelsen är så stilla att den
// inte drar blicken till sig, och den står still medan något spelas över allt.

/**
 * En bakgrundsbild som tonar över till nästa och sakta zoomar och glider.
 * [running]: av medan spelaren täcker skärmen, så att inget ritas i onödan.
 */
@Composable
internal fun LivingBackdrop(
    url: String?,
    modifier: Modifier = Modifier,
    running: Boolean = true,
    alignment: Alignment = Alignment.TopCenter,
) {
    Crossfade(targetState = url, animationSpec = tween(BACKDROP_FADE_MS), modifier = modifier, label = "levande bakgrund") { current ->
        if (current != null) {
            KenBurns(running = running, modifier = Modifier.fillMaxSize()) {
                RemoteImage(
                    url = current,
                    modifier = Modifier.fillMaxSize(),
                    // TMDB:s bakgrunder är 1 280 bildpunkter breda: större gör bara minnet fullt.
                    maxSide = 1280,
                    contentScale = ContentScale.Crop,
                    alignment = alignment,
                )
            }
        }
    }
}

@Composable
internal fun KenBurns(running: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            progress.animateTo(1f, tween(KEN_BURNS_MS, easing = LinearEasing))
            progress.animateTo(0f, tween(KEN_BURNS_MS, easing = LinearEasing))
        }
    }
    Box(
        modifier
            .clipToBounds()
            .graphicsLayer {
                // Läses här och inte vid komponeringen: bara bilden ritas om, inget annat.
                val t = FastOutSlowInEasing.transform(progress.value)
                val scale = 1.03f + 0.07f * t
                scaleX = scale
                scaleY = scale
                translationX = -size.width * 0.025f * t
                translationY = size.height * 0.012f * t
            },
    ) {
        content()
    }
}

// --- Titeln som logga -----------------------------------------------------------------

/**
 * Filmens eller seriens logga från TMDB i stället för titeln i text, som på
 * streamingtjänsterna. Texten står kvar tills loggan finns, och för titlar
 * som saknar logga. [kind]: "movie" eller "tv".
 */
@Composable
internal fun TitleLogo(
    state: AppState,
    kind: String,
    tmdbId: Int?,
    title: String,
    style: TextStyle,
    maxHeight: Dp,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    if (tmdbId != null) {
        LaunchedEffect(kind, tmdbId) { state.ensureLogo(kind, tmdbId) }
    }
    val url = tmdbId?.let { state.logoUrl(kind, it) }
    val initial = remember(url) { url?.let { Images.cached(it, LOGO_SIDE) } }
    var logo by remember(url) { mutableStateOf(initial) }
    if (url != null && logo == null) {
        LaunchedEffect(url) { logo = Images.load(url, LOGO_SIDE) }
    }
    Crossfade(
        targetState = logo,
        animationSpec = tween(if (initial != null) 0 else 360),
        modifier = modifier,
        label = "logga",
    ) { image ->
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .heightIn(max = maxHeight)
                    .aspectRatio(image.width.toFloat() / image.height.coerceAtLeast(1)),
            )
        } else {
            Text(title, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

// --- I spelaren: hoppa över intro och nästa avsnitt -------------------------------------

/**
 * Nere till höger i spelaren: Hoppa över intro i början av ett avsnitt,
 * Nästa avsnitt mot slutet. Ovanför kontrollerna när de syns. På tv:n
 * väljer OK den som visas, och Tillbaka stänger Nästa avsnitt.
 */
@Composable
internal fun PlayerPrompts(state: AppState, status: VideoPlayer.Status, lifted: Boolean, modifier: Modifier = Modifier) {
    val intro = state.showsIntroSkip(status)
    val upcoming = state.nextUp
    val next = upcoming != null && state.showsNextUp(status)
    val bottom by animateDpAsState(if (lifted) 128.dp else 48.dp, tween(260), label = "rutor i spelaren")
    Box(modifier.padding(end = 40.dp, bottom = bottom), contentAlignment = Alignment.BottomEnd) {
        AnimatedVisibility(
            visible = intro,
            enter = fadeIn(tween(300)) + slideInHorizontally(tween(300)) { it / 3 },
            exit = fadeOut(tween(200)),
        ) {
            SkipIntroButton(onClick = { state.skipIntro() })
        }
        AnimatedVisibility(
            visible = next,
            enter = fadeIn(tween(420)) + slideInHorizontally(tween(420)) { it / 2 },
            exit = fadeOut(tween(220)),
        ) {
            if (upcoming != null) {
                NextUpCard(state, upcoming, ended = status.phase == VideoPlayer.Phase.ENDED)
            }
        }
    }
}

@Composable
private fun SkipIntroButton(onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    Pressable(onClick = onClick, shape = shape, hoverScale = 1.03f) { hovered ->
        Row(
            Modifier
                .background(Color.Black.copy(alpha = if (hovered) 0.78f else 0.58f), shape)
                .border(1.5.dp, if (tvLayout || hovered) palette.focus else Color.White.copy(alpha = 0.8f), shape)
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tvLayout) {
                OkHint()
                Spacer(Modifier.width(10.dp))
            }
            Text("Hoppa över intro", style = MaterialTheme.typography.titleSmall, color = Color.White)
            Spacer(Modifier.width(10.dp))
            Icon(Glyphs.Forward10, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun NextUpCard(state: AppState, next: NextUp, ended: Boolean) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    // Tio sekunder, sedan börjar nästa avsnitt. Tar avsnittet slut innan dess börjar det direkt.
    val countdown = remember(next) { Animatable(0f) }
    LaunchedEffect(next) {
        countdown.animateTo(1f, tween(NEXT_UP_COUNTDOWN_MS, easing = LinearEasing))
        state.playNextUp()
    }
    Column(
        Modifier
            .width(400.dp)
            .shadow(28.dp, shape)
            .clip(shape)
            .background(palette.surface.copy(alpha = 0.95f))
            .border(1.dp, palette.hairline, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(150.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.surfaceRaised),
            ) {
                Artwork(
                    candidates = listOfNotNull(next.episode.thumbnail, next.series.backdrop, next.series.cover),
                    modifier = Modifier.fillMaxSize(),
                    maxSide = 480,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("NÄSTA AVSNITT", style = MaterialTheme.typography.labelMedium, color = palette.focus)
                Text(next.heading, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (next.episode.title.isNotBlank()) {
                    Text(
                        next.episode.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CountdownButton(progress = { countdown.value }, onClick = { state.playNextUp() }, modifier = Modifier.weight(1f))
            SecondaryButton(
                if (ended) "Stäng" else "Titta klart",
                onClick = { if (ended) state.closePlayer() else state.dismissNextUp() },
            )
        }
        if (tvLayout) {
            Text(
                "OK spelar nu · Tillbaka tittar klart",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textTertiary,
            )
        }
    }
}

/** Knappen fylls medan nedräkningen går, som en tidslinje. */
@Composable
private fun CountdownButton(progress: () -> Float, onClick: () -> Unit, modifier: Modifier) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    Pressable(onClick = onClick, modifier = modifier, shape = shape) { _ ->
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(shape)
                .background(palette.focus.copy(alpha = 0.32f))
                .drawBehind { drawRect(palette.focus, size = Size(size.width * progress().coerceIn(0f, 1f), size.height)) },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Play, contentDescription = null, tint = palette.onFocus, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Spela nästa", style = MaterialTheme.typography.titleSmall, color = palette.onFocus)
            }
        }
    }
}

/** Tv: en liten "OK" som visar vilken knapp på fjärren som väljer. */
@Composable
private fun OkHint() {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(palette.focus)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text("OK", style = MaterialTheme.typography.labelSmall, color = palette.onFocus)
    }
}

private const val BACKDROP_FADE_MS = 700
private const val KEN_BURNS_MS = 22_000
private const val LOGO_SIDE = 600
private const val NEXT_UP_COUNTDOWN_MS = 10_000

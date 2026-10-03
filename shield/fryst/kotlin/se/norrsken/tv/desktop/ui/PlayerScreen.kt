package se.norrsken.tv.desktop.ui

import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.lerp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.NowPlaying
import se.norrsken.tv.desktop.player.VideoPlayer
import se.norrsken.tv.desktop.player.VideoPlayer.Phase
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
private val idleStatus = MutableStateFlow(VideoPlayer.Status())
private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun PlayerScreen(state: AppState, collapse: Float = 0f) {
    val palette = LocalPalette.current
    val playing = state.nowPlaying ?: return
    val player = state.videoPlayer
    val rawStatus by (player?.status ?: idleStatus).collectAsState()
    // Status från förra strömmen räknas inte: den kan säga ENDED eller ERROR
    // i ögonblicket innan den nya har hunnit öppnas.
    val status = if (rawStatus.session == state.playbackSession) rawStatus else VideoPlayer.Status(phase = Phase.OPENING)

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }

    // En film eller ett avsnitt som spelats klart stänger spelaren.
    LaunchedEffect(status.phase, playing) {
        if (status.phase == Phase.ENDED && !playing.live) state.closePlayer()
    }

    val problem = problemFor(state, playing, status)
    val waiting = problem == null && (player == null || status.phase == Phase.OPENING || status.phase == Phase.IDLE || status.stalled)
    val controlsVisible = problem != null || status.phase == Phase.PAUSED || state.trackPanelOpen ||
        now - state.lastActivity < 3_500
    val mini = collapse > 0.5f
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Blockerar också klick och scrollhjul till listan under spelaren.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        state.touchPlayer()
                    }
                }
            }
            .hoverable(hoverSource)
            .pointerHoverIcon(if (controlsVisible || mini) PointerIcon.Default else hiddenCursor),
    ) {
        if (player != null) {
            VideoSurface(
                player = player,
                sampleAspect = status.sampleAspect,
                sharp = state.sharpScaling,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(player) {
                        detectTapGestures(
                            onTap = {
                                when {
                                    state.miniPlayer -> state.expandPlayer()
                                    state.trackPanelOpen -> state.trackPanelOpen = false
                                    else -> state.togglePause()
                                }
                            },
                            onDoubleTap = { state.toggleFullscreen() },
                        )
                    },
            )
        }

        when {
            problem != null && mini -> Text(
                problem.title,
                style = MaterialTheme.typography.labelLarge,
                color = palette.textSecondary,
                modifier = Modifier.align(Alignment.Center).padding(12.dp),
            )
            problem != null -> ProblemPanel(
                problem = problem,
                onRetry = if (problem.canRetry) ({ state.retryPlayback() }) else null,
                onClose = { state.closePlayer() },
                modifier = Modifier.align(Alignment.Center),
            )
            waiting -> Spinner(Modifier.align(Alignment.Center), size = if (mini) 28.dp else 48.dp)
            status.phase == Phase.PLAYING && !status.hasVideo && status.timeMs > 3_000 -> Text(
                "Endast ljud",
                style = MaterialTheme.typography.titleMedium,
                color = palette.textSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && !mini,
            enter = fadeIn(),
            exit = fadeOut(),
            // Kontrollerna tonar bort redan i början av krympningen.
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = (1f - collapse * 3f).coerceIn(0f, 1f) },
        ) {
            Box(Modifier.fillMaxSize()) {
                TopBar(state, playing, status, now, Modifier.align(Alignment.TopCenter))
                BottomBar(state, playing, status, Modifier.align(Alignment.BottomCenter))
            }
        }

        if (state.trackPanelOpen && !mini) {
            TracksPanel(
                state = state,
                status = status,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 104.dp),
            )
        }

        if (mini) {
            MiniOverlay(state, playing, status, visible = hovered, now = now, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TopBar(state: AppState, playing: NowPlaying, status: VideoPlayer.Status, now: Long, modifier: Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)))
            .padding(start = 14.dp, end = 24.dp, top = 12.dp, bottom = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(Glyphs.ChevronDown, "Minimera och fortsätt bläddra", onClick = { state.minimizePlayer() })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (playing.live) Tag("LIVE", palette.signal)
                Text(playing.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                qualityLabel(status.videoHeight)?.let { Tag(it, palette.textSecondary) }
            }
            val channel = playing.channel
            val onAir = channel?.let { state.guide.current(it, now) }
            if (onAir != null && channel != null) {
                val next = state.guide.next(channel, now)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        clockText(onAir.start) + "–" + clockText(onAir.stop) + "  " + onAir.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Box(
                        Modifier.width(110.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(palette.focus.copy(alpha = 0.22f)),
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(onAir.progress(now)).background(palette.focus))
                    }
                }
                next?.let {
                    Text(
                        "Sedan " + clockText(it.start) + "  " + it.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                playing.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Ritas om varannan halvsekund eftersom `now` ändras — klockan följer med.
        Text(
            LocalTime.now().format(clockFormat),
            style = MaterialTheme.typography.titleMedium,
            color = palette.textSecondary,
        )
        Spacer(Modifier.width(12.dp))
        IconAction(Glyphs.Close, "Stäng spelaren", onClick = { state.closePlayer() })
    }
}

@Composable
private fun BottomBar(state: AppState, playing: NowPlaying, status: VideoPlayer.Status, modifier: Modifier) {
    val palette = LocalPalette.current
    val player = state.videoPlayer
    val paused = status.phase == Phase.PAUSED
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
            .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 14.dp),
    ) {
        if (!playing.live && status.lengthMs > 0) {
            SeekBar(status, onSeek = { fraction -> player?.seekTo(fraction) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val playPause = if (paused) Glyphs.Play else Glyphs.Pause
            val playPauseLabel = if (paused) "Spela" else "Paus"
            if (playing.live) {
                IconAction(Glyphs.SkipPrevious, "Föregående kanal", onClick = { state.switchChannel(-1) })
                IconAction(playPause, playPauseLabel, onClick = { state.togglePause() }, size = 52.dp, iconSize = 30.dp, emphasized = true)
                IconAction(Glyphs.SkipNext, "Nästa kanal", onClick = { state.switchChannel(1) })
            } else {
                IconAction(Glyphs.Replay10, "Bakåt 10 sekunder", onClick = { player?.seekBy(-10_000) })
                IconAction(playPause, playPauseLabel, onClick = { state.togglePause() }, size = 52.dp, iconSize = 30.dp, emphasized = true)
                IconAction(Glyphs.Forward10, "Framåt 10 sekunder", onClick = { player?.seekBy(10_000) })
                if (status.lengthMs > 0) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${formatTime(status.timeMs)} / ${formatTime(status.lengthMs)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.textSecondary,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            IconAction(
                Glyphs.Subtitles,
                "Ljud och undertext",
                onClick = { state.trackPanelOpen = !state.trackPanelOpen },
                emphasized = state.trackPanelOpen,
            )
            Spacer(Modifier.width(4.dp))
            IconAction(
                if (state.muted || state.volume == 0) Glyphs.VolumeOff else Glyphs.Volume,
                if (state.muted) "Slå på ljudet" else "Stäng av ljudet",
                onClick = { state.toggleMute() },
            )
            Slider(
                value = if (state.muted) 0f else state.volume / 100f,
                onValueChange = { state.setVolumeLevel((it * 100).roundToInt()) },
                modifier = Modifier.width(130.dp),
                colors = sliderColors(),
            )
            Spacer(Modifier.width(8.dp))
            IconAction(
                if (state.fullscreen) Glyphs.FullscreenExit else Glyphs.Fullscreen,
                if (state.fullscreen) "Lämna helskärm" else "Helskärm",
                onClick = { state.toggleFullscreen() },
            )
        }
    }
}

/** Ljud och undertext, bredvid knappen som öppnade panelen. */
@Composable
private fun TracksPanel(state: AppState, status: VideoPlayer.Status, modifier: Modifier) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    val audio = status.audioTracks.filter { it.id >= 0 }
    val subtitles = status.subtitleTracks.filter { it.id >= 0 }
    Row(
        modifier
            .widthIn(max = 640.dp)
            .clip(shape)
            .background(palette.surface.copy(alpha = 0.97f))
            .border(1.dp, palette.hairline, shape)
            // Klick i panelen ska inte pausa bilden under den.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TrackList(
            title = "Ljud",
            tracks = audio,
            selected = status.audioTrack,
            note = when {
                audio.isEmpty() -> "Väntar på strömmen…"
                audio.size == 1 -> "Strömmen har ett ljudspår."
                else -> null
            },
            onSelect = { state.selectAudio(it) },
            modifier = Modifier.weight(1f),
        )
        TrackList(
            title = "Undertext",
            tracks = listOf(VideoPlayer.Track(-1, "Av", null)) + subtitles,
            selected = if (subtitles.any { it.id == status.subtitleTrack }) status.subtitleTrack else -1,
            note = if (subtitles.isEmpty()) "Strömmen har inga undertexter." else null,
            onSelect = { state.selectSubtitle(it) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TrackList(
    title: String,
    tracks: List<VideoPlayer.Track>,
    selected: Int,
    note: String?,
    onSelect: (VideoPlayer.Track) -> Unit,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = palette.textTertiary,
            modifier = Modifier.padding(start = 10.dp, bottom = 6.dp),
        )
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            tracks.forEach { track ->
                val isSelected = track.id == selected
                Pressable(onClick = { onSelect(track) }, selected = isSelected, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                            if (isSelected) {
                                Icon(Glyphs.Check, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            track.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) palette.textPrimary else palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textTertiary,
                modifier = Modifier.padding(start = 10.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun SeekBar(status: VideoPlayer.Status, onSeek: (Float) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val position = dragging ?: (status.timeMs.toFloat() / status.lengthMs).coerceIn(0f, 1f)
    Slider(
        value = position,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let(onSeek)
            dragging = null
        },
        enabled = status.seekable,
        modifier = Modifier.fillMaxWidth(),
        colors = sliderColors(),
    )
}

@Composable
private fun sliderColors(): SliderColors {
    val palette = LocalPalette.current
    return SliderDefaults.colors(
        thumbColor = palette.focus,
        activeTrackColor = palette.focus,
        inactiveTrackColor = palette.focus.copy(alpha = 0.22f),
        disabledThumbColor = palette.textTertiary,
        disabledActiveTrackColor = palette.textTertiary,
        disabledInactiveTrackColor = palette.hairline,
    )
}

private fun qualityLabel(height: Int): String? = when {
    height >= 2000 -> "4K"
    height >= 1400 -> "1440p"
    height >= 1000 -> "1080p"
    height >= 700 -> "720p"
    height > 0 -> "SD"
    else -> null
}

private class Problem(
    val title: String,
    val message: String,
    val canRetry: Boolean,
    val showVlcLink: Boolean = false,
)

private fun problemFor(state: AppState, playing: NowPlaying, status: VideoPlayer.Status): Problem? {
    state.playerError?.let { message ->
        val vlcMissing = state.videoPlayer == null && message.contains("VLC")
        return Problem(
            title = if (vlcMissing) "Uppspelningen behöver VLC" else "Det gick inte att spela",
            message = message,
            canRetry = !vlcMissing,
            showVlcLink = vlcMissing,
        )
    }
    return when (status.phase) {
        Phase.ERROR -> Problem(
            title = "Det gick inte att spela",
            message = if (playing.live) {
                "Kanalen svarar inte just nu, eller så är max antal samtidiga anslutningar nått hos leverantören. " +
                    "Prova igen, byt kanal, eller byt Live-format under Inställningar."
            } else {
                "Filen gick inte att öppna. Leverantören kan ha tagit bort den, eller så nekade servern anslutningen."
            },
            canRetry = true,
        )
        Phase.ENDED -> if (playing.live) {
            Problem(
                title = "Sändningen avbröts",
                message = "Servern slutade skicka. Det brukar gå att starta om.",
                canRetry = true,
            )
        } else {
            null
        }
        else -> null
    }
}

@Composable
private fun ProblemPanel(problem: Problem, onRetry: (() -> Unit)?, onClose: () -> Unit, modifier: Modifier) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .padding(24.dp)
            .widthIn(max = 540.dp)
            .clip(shape)
            .background(palette.surface.copy(alpha = 0.96f))
            .border(1.dp, palette.hairline, shape)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(problem.title, style = MaterialTheme.typography.headlineSmall)
        Text(problem.message, style = MaterialTheme.typography.bodyLarge, color = palette.textSecondary)
        Spacer(Modifier.width(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onRetry != null) PrimaryButton("Försök igen", onRetry, icon = Glyphs.Refresh)
            if (problem.showVlcLink) {
                PrimaryButton("Öppna videolan.org", onClick = { openInBrowser("https://www.videolan.org/vlc/") })
            }
            SecondaryButton("Stäng", onClose, icon = Glyphs.Close)
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/**
 * Spelaren i huvudfönstret. Minimeras den glider bilden ner i hörnet och
 * spelar vidare, medan resten av appen går att använda som vanligt. Samma
 * PlayerScreen hela vägen: bilden byts aldrig ut, den bara krymper.
 */
@Composable
fun PlayerHost(state: AppState) {
    val palette = LocalPalette.current
    val collapse by animateFloatAsState(
        targetValue = if (state.miniPlayer) 1f else 0f,
        animationSpec = tween(durationMillis = 460, easing = FastOutSlowInEasing),
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val miniWidth = (maxWidth * 0.27f).coerceIn(300.dp, 480.dp)
        val miniHeight = miniWidth * (9f / 16f)
        val margin = 24.dp
        val shape = RoundedCornerShape(lerp(0.dp, 14.dp, collapse))
        Box(
            Modifier
                .offset(
                    x = lerp(0.dp, maxWidth - miniWidth - margin, collapse),
                    y = lerp(0.dp, maxHeight - miniHeight - margin, collapse),
                )
                .size(
                    width = lerp(maxWidth, miniWidth, collapse),
                    height = lerp(maxHeight, miniHeight, collapse),
                )
                .glow(palette.glowOf(0.55f * collapse), shape, 30.dp)
                .shadow(lerp(0.dp, 24.dp, collapse), shape)
                .clip(shape),
        ) {
            PlayerScreen(state, collapse)
        }
    }
}

/**
 * Minispelarens kontroller, när musen står på den: titel, stäng, paus och
 * förstora. Förloppet längst ned syns alltid — filmens tid, eller programmet
 * som sänds just nu på kanalen.
 */
@Composable
private fun MiniOverlay(
    state: AppState,
    playing: NowPlaying,
    status: VideoPlayer.Status,
    visible: Boolean,
    now: Long,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    val paused = status.phase == Phase.PAUSED
    val progress: Float? = when {
        !playing.live && status.lengthMs > 0 -> (status.timeMs.toFloat() / status.lengthMs).coerceIn(0f, 1f)
        else -> playing.channel?.let { channel -> state.guide.current(channel, now)?.progress(now) }
    }
    Box(modifier) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent, Color.Black.copy(alpha = 0.7f))),
                ),
            ) {
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (playing.live) {
                        Tag("LIVE", palette.signal)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        playing.title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconAction(Glyphs.Close, "Stäng", onClick = { state.closePlayer() }, size = 34.dp, iconSize = 18.dp)
                }
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconAction(
                        if (paused) Glyphs.Play else Glyphs.Pause,
                        if (paused) "Spela" else "Paus",
                        onClick = { state.togglePause() },
                        size = 44.dp,
                        iconSize = 26.dp,
                        emphasized = true,
                    )
                    IconAction(
                        Glyphs.OpenInFull,
                        "Förstora",
                        onClick = { state.expandPlayer() },
                        size = 44.dp,
                        iconSize = 20.dp,
                        emphasized = true,
                    )
                }
            }
        }
        if (progress != null) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(palette.focus.copy(alpha = 0.25f)),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(palette.focus))
            }
        }
    }
}

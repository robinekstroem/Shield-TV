package se.norrsken.tv.desktop.ui

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.lerp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.text.font.FontWeight
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
fun PlayerScreen(state: AppState, collapse: Float = 0f, highlighted: Boolean = false) {
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

    // En film eller ett avsnitt som spelats klart stänger spelaren. Finns ett nästa
    // avsnitt spelas det i stället, om man inte valt att titta klart.
    LaunchedEffect(status.phase, playing) {
        if (status.phase == Phase.ENDED && !playing.live) {
            if (!state.miniPlayer && state.nextUp != null && !state.nextUpDismissed) state.playNextUp() else state.closePlayer()
        }
    }

    val problem = problemFor(state, playing, status)
    val waiting = problem == null && (player == null || status.phase == Phase.OPENING || status.phase == Phase.IDLE || status.stalled)
    val controlsVisible = problem != null || status.phase == Phase.PAUSED || state.trackPanelOpen ||
        now - state.lastActivity < 3_500 ||
        // Tv: medan man står på knapparna står de kvar en stund längre.
        (tvLayout && state.playerControlsFocused && now - state.lastActivity < 8_000)
    // Tv: tonar knapparna bort medan en av dem har markeringen går den tillbaka till bilden.
    if (tvLayout) {
        LaunchedEffect(controlsVisible) { if (!controlsVisible) state.leavePlayerControls() }
    }
    val mini = collapse > 0.5f
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()

    // Tv: spelarens knappar och vart pilarna går mellan dem.
    val keys = remember { PlayerKeys() }
    // Tv: felrutans första knapp tar markeringen, så att OK försöker igen.
    val problemFocus = remember { FocusRequester() }
    if (tvLayout && problem != null && !mini) {
        LaunchedEffect(problem.title, problem.message) {
            repeat(2) { withFrameNanos { } }
            runCatching { problemFocus.requestFocus() }
        }
    }

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
            .pointerHoverIcon(
                when {
                    // Minispelaren förstoras med ett klick.
                    mini -> PointerIcon.Hand
                    controlsVisible -> PointerIcon.Default
                    else -> hiddenCursor
                },
            )
            // Tv: markeringen står på något i spelaren (knapparna, felrutan, panelerna),
            // inte på själva bilden. Då styr pilarna och OK det man står på.
            .onFocusChanged { if (tvLayout) state.playerControlsFocused = it.hasFocus },
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
                focus = problemFocus,
                keys = keys,
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
                TopBar(state, playing, status, now, keys, Modifier.align(Alignment.TopCenter))
                BottomBar(state, playing, status, keys, Modifier.align(Alignment.BottomCenter))
            }
        }

        if (state.trackPanelOpen && !mini) {
            TracksPanel(
                state = state,
                status = status,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 104.dp),
            )
        }

        // Hoppa över intro och Nästa avsnitt, nere till höger.
        if (!mini && problem == null && !state.trackPanelOpen) {
            PlayerPrompts(
                state = state,
                status = status,
                lifted = controlsVisible,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

        if (mini) {
            // Titel och knappar står i sidofältet under bilden; på bilden bara förstora.
            DockedOverlay(state, playing, status, visible = hovered || highlighted, now = now, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TopBar(state: AppState, playing: NowPlaying, status: VideoPlayer.Status, now: Long, keys: PlayerKeys, modifier: Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)))
            .padding(start = 14.dp, end = 24.dp, top = 12.dp, bottom = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            Glyphs.ChevronDown,
            "Minimera och fortsätt bläddra",
            onClick = { state.minimizePlayer() },
            modifier = Modifier.playerKey(keys.minimize, toDown = keys.play, toRight = keys.close),
        )
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
        IconAction(
            Glyphs.Close,
            "Stäng spelaren",
            onClick = { state.closePlayer() },
            modifier = Modifier.playerKey(keys.close, toDown = keys.tracks, toLeft = keys.minimize),
        )
    }
}

/**
 * Tv: spelarens knappar och vart pilarna går mellan dem. Under bilden står
 * skärmen man kom från kvar, och utan de här vägarna kunde markeringen hoppa
 * ner till något där bakom. Nu stannar den alltid bland knapparna.
 */
private class PlayerKeys {
    val minimize = FocusRequester()
    val close = FocusRequester()
    val first = FocusRequester()
    val play = FocusRequester()
    val third = FocusRequester()
    val tracks = FocusRequester()
}

/** En av spelarens knappar ([self]), med grannarna åt varje håll. Saknas en granne stannar markeringen. */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.playerKey(
    self: FocusRequester,
    toUp: FocusRequester? = null,
    toDown: FocusRequester? = null,
    toLeft: FocusRequester? = null,
    toRight: FocusRequester? = null,
): Modifier {
    val linked = focusRequester(self)
    if (!tvLayout) return linked
    return linked.focusProperties {
        up = toUp ?: FocusRequester.Cancel
        down = toDown ?: FocusRequester.Cancel
        left = toLeft ?: FocusRequester.Cancel
        right = toRight ?: FocusRequester.Cancel
    }
}

@Composable
private fun BottomBar(state: AppState, playing: NowPlaying, status: VideoPlayer.Status, keys: PlayerKeys, modifier: Modifier) {
    val palette = LocalPalette.current
    val player = state.videoPlayer
    val paused = status.phase == Phase.PAUSED
    // Tv: pil upp eller ner tar fram knapparna med markeringen på Spela/Paus.
    if (tvLayout) {
        LaunchedEffect(state.controlsFocusTick) {
            if (state.takeControlsFocus()) {
                repeat(2) { withFrameNanos { } }
                runCatching { keys.play.requestFocus() }
            }
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
            .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 14.dp),
    ) {
        if (!playing.live && status.lengthMs > 0) {
            // Tv: tidslinjen visar bara var man är. Man spolar med pil vänster och
            // höger på bilden, eller knapparna — ett reglage fångar markeringen.
            if (tvLayout) TvTimeline(status) else SeekBar(status, onSeek = { fraction -> state.seekTo(fraction) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val playPause = if (paused) Glyphs.Play else Glyphs.Pause
            val playPauseLabel = if (paused) "Spela" else "Paus"
            // Tv: vart pilarna går mellan knapparna (se PlayerKeys).
            val first = Modifier.playerKey(keys.first, toUp = keys.minimize, toRight = keys.play)
            val middle = Modifier.playerKey(keys.play, toUp = keys.minimize, toLeft = keys.first, toRight = keys.third)
            val third = Modifier.playerKey(keys.third, toUp = keys.minimize, toLeft = keys.play, toRight = keys.tracks)
            if (playing.live) {
                IconAction(Glyphs.SkipPrevious, "Föregående kanal", onClick = { state.switchChannel(-1) }, modifier = first)
                IconAction(playPause, playPauseLabel, onClick = { state.togglePause() }, size = 52.dp, iconSize = 30.dp, emphasized = true, modifier = middle)
                IconAction(Glyphs.SkipNext, "Nästa kanal", onClick = { state.switchChannel(1) }, modifier = third)
            } else {
                IconAction(Glyphs.Replay10, "Bakåt 10 sekunder", onClick = { state.seekBy(-10_000) }, modifier = first)
                IconAction(playPause, playPauseLabel, onClick = { state.togglePause() }, size = 52.dp, iconSize = 30.dp, emphasized = true, modifier = middle)
                IconAction(Glyphs.Forward10, "Framåt 10 sekunder", onClick = { state.seekBy(10_000) }, modifier = third)
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
                modifier = Modifier.playerKey(keys.tracks, toUp = keys.close, toLeft = keys.third),
            )
            // Tv: ljudet sköts med tv:ns egen fjärrkontroll, och bilden är alltid i helskärm.
            if (!tvLayout) {
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
}

/** Ljud och undertext, bredvid knappen som öppnade panelen. */
@Composable
private fun TracksPanel(state: AppState, status: VideoPlayer.Status, modifier: Modifier) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    val audio = status.audioTracks.filter { it.id >= 0 }
    val subtitles = status.subtitleTracks.filter { it.id >= 0 }
    // Tv: markeringen hamnar på ljudspåret som spelar när panelen öppnas.
    val entry = remember { FocusRequester() }
    if (tvLayout) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { entry.requestFocus() }
        }
    }
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
            focus = if (audio.isNotEmpty()) entry else null,
            closedLeft = true,
            closedRight = false,
        )
        TrackList(
            title = "Undertext",
            tracks = listOf(VideoPlayer.Track(-1, "Av", null)) + subtitles,
            selected = if (subtitles.any { it.id == status.subtitleTrack }) status.subtitleTrack else -1,
            note = if (subtitles.isEmpty()) "Strömmen har inga undertexter." else null,
            onSelect = { state.selectSubtitle(it) },
            modifier = Modifier.weight(1f),
            focus = if (audio.isEmpty()) entry else null,
            closedLeft = audio.isEmpty(),
            closedRight = true,
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
    /** Läggs på spåret som är valt (eller det första), så att markeringen kan hamna där. */
    focus: FocusRequester? = null,
    /** Tv: panelens kanter. Markeringen stannar i panelen i stället för att gå till bilden bakom. */
    closedLeft: Boolean = false,
    closedRight: Boolean = false,
) {
    val palette = LocalPalette.current
    val focusIndex = tracks.indexOfFirst { it.id == selected }.takeIf { it >= 0 } ?: 0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = palette.textTertiary,
            modifier = Modifier.padding(start = 10.dp, bottom = 6.dp),
        )
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            tracks.forEachIndexed { index, track ->
                val isSelected = track.id == selected
                val target = (if (focus != null && index == focusIndex) Modifier.focusRequester(focus) else Modifier)
                    .tvEdges(
                        blockUp = index == 0,
                        blockDown = index == tracks.lastIndex,
                        blockLeft = closedLeft,
                        blockRight = closedRight,
                    )
                Pressable(onClick = { onSelect(track) }, selected = isSelected, modifier = target.fillMaxWidth()) {
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

/** Tv: var i filmen man är, som en tunn linje utan reglage. */
@Composable
private fun TvTimeline(status: VideoPlayer.Status) {
    val palette = LocalPalette.current
    val position = (status.timeMs.toFloat() / status.lengthMs).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 16.dp)
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(palette.focus.copy(alpha = 0.22f)),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(position).background(palette.focus))
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
@OptIn(ExperimentalComposeUiApi::class)
private fun ProblemPanel(
    problem: Problem,
    onRetry: (() -> Unit)?,
    onClose: () -> Unit,
    /** Tv: första knappen, som tar markeringen när rutan visas. */
    focus: FocusRequester,
    keys: PlayerKeys,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    // Tv: pil upp och ner går till spelarens knappar, och markeringen stannar
    // vid radens ändar i stället för att hoppa till listan bakom bilden.
    fun button(first: Boolean, last: Boolean): Modifier {
        val target = if (first) Modifier.focusRequester(focus) else Modifier
        if (!tvLayout) return target
        return target.focusProperties {
            up = keys.minimize
            down = keys.play
            if (first) left = FocusRequester.Cancel
            if (last) right = FocusRequester.Cancel
        }
    }
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
            if (onRetry != null) {
                PrimaryButton("Försök igen", onRetry, icon = Glyphs.Refresh, modifier = button(first = true, last = false))
            }
            if (problem.showVlcLink) {
                PrimaryButton(
                    "Öppna videolan.org",
                    onClick = { openInBrowser("https://www.videolan.org/vlc/") },
                    modifier = button(first = onRetry == null, last = false),
                )
            }
            SecondaryButton(
                "Stäng",
                onClose,
                icon = Glyphs.Close,
                modifier = button(first = onRetry == null && !problem.showVlcLink, last = true),
            )
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
 * Spelaren i huvudfönstret. Minimeras den glider bilden upp i sidofältets
 * övre hörn och spelar vidare, medan resten av appen går att använda som
 * vanligt. Samma PlayerScreen hela vägen: bilden byts aldrig ut, den bara
 * krymper. [collapse] är hur långt: 0 över allt, 1 liten.
 */
@Composable
fun PlayerHost(state: AppState, collapse: () -> Float) {
    val palette = LocalPalette.current
    val progress = collapse()
    // Fjärrkontroll och tangentbord: rutan i hörnet går att markera, och OK
    // tar bilden till helskärm igen. I helskärm står markeringen på bilden,
    // så att fjärrkontrollen alltid svarar direkt.
    val focusSource = remember { MutableInteractionSource() }
    val focused by focusSource.collectIsFocusedAsState()
    val requester = remember { FocusRequester() }
    if (tvLayout) {
        LaunchedEffect(state.nowPlaying, state.miniPlayer, state.trackPanelOpen, state.playerFocusTick) {
            if (!state.miniPlayer && !state.trackPanelOpen) {
                withFrameNanos { }
                runCatching { requester.requestFocus() }
            }
        }
        // Den lilla bilden i hörnet ska få markeringen (pil höger eller ner förbi innehållets kant).
        LaunchedEffect(state.miniFocusTick) {
            if (state.miniFocusTick > 0 && state.miniPlayer) {
                withFrameNanos { }
                runCatching { requester.requestFocus() }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Liten: överst i sidofältet, i facket det håller fritt. Tv: nere i
        // högra hörnet, ovanpå det man bläddrar i — menyn är gömd där.
        val shape = RoundedCornerShape(lerp(0.dp, if (tvLayout) TV_PIP_RADIUS else MiniDock.radius, progress))
        val miniWidth = if (tvLayout) TV_PIP_WIDTH else MiniDock.videoWidth
        val miniHeight = if (tvLayout) TV_PIP_HEIGHT else MiniDock.videoHeight
        val miniX = if (tvLayout) maxWidth - TV_PIP_WIDTH - TV_PIP_MARGIN else MiniDock.inset
        val miniY = if (tvLayout) maxHeight - TV_PIP_HEIGHT - TV_PIP_MARGIN else MiniDock.top
        Box(
            Modifier
                .offset(
                    x = lerp(0.dp, miniX, progress),
                    y = lerp(0.dp, miniY, progress),
                )
                .size(
                    width = lerp(maxWidth, miniWidth, progress),
                    height = lerp(maxHeight, miniHeight, progress),
                )
                .glow(palette.glowOf((if (focused) 1f else 0.55f) * progress), shape, 22.dp)
                .shadow(lerp(0.dp, if (tvLayout) 22.dp else 14.dp, progress), shape)
                .clip(shape)
                .then(
                    when {
                        focused && progress > 0.5f -> Modifier.border(3.dp, if (tvLayout) menuFill(palette) else palette.focus, shape)
                        // Tv: en tunn ljus kant runt den lilla bilden, som en ram.
                        tvLayout && progress > 0.5f -> Modifier.border(1.5.dp, Color.White.copy(alpha = 0.22f * progress), shape)
                        else -> Modifier
                    },
                )
                .focusRequester(requester)
                .onKeyEvent { event ->
                    val ok = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter
                    if (ok && state.miniPlayer && event.type == KeyEventType.KeyDown) {
                        state.expandPlayer()
                        true
                    } else {
                        false
                    }
                }
                // Tv: Tillbaka stänger den lilla bilden när den är markerad (se MainActivity).
                .onFocusChanged { state.playerBoxFocused = it.isFocused }
                .focusable(interactionSource = focusSource),
        ) {
            PlayerScreen(state, progress, highlighted = focused)
        }
    }
}

/** Tv: minispelaren som en bild i hörnet, i 16:9. */
private val TV_PIP_WIDTH = 432.dp
private val TV_PIP_HEIGHT = 243.dp
private val TV_PIP_MARGIN = 40.dp
private val TV_PIP_RADIUS = 14.dp

/** Filmens tid, eller hur långt programmet som sänds just nu på kanalen kommit. */
private fun miniProgress(state: AppState, playing: NowPlaying, status: VideoPlayer.Status, now: Long): Float? = when {
    !playing.live && status.lengthMs > 0 -> (status.timeMs.toFloat() / status.lengthMs).coerceIn(0f, 1f)
    else -> playing.channel?.let { channel -> state.guide.current(channel, now)?.progress(now) }
}

@Composable
private fun ProgressLine(progress: Float, modifier: Modifier) {
    val palette = LocalPalette.current
    Box(modifier.fillMaxWidth().height(3.dp).background(palette.focus.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(palette.focus))
    }
}

/**
 * Bilden i sidofältet, när musen står på den: dämpad, med ett tecken för
 * att den förstoras med ett klick. Förloppet längst ned syns alltid.
 */
@Composable
private fun DockedOverlay(
    state: AppState,
    playing: NowPlaying,
    status: VideoPlayer.Status,
    visible: Boolean,
    now: Long,
    modifier: Modifier,
) {
    if (tvLayout) {
        TvMiniOverlay(state, playing, status, focused = visible, now = now, modifier = modifier)
        return
    }
    val progress = miniProgress(state, playing, status, now)
    Box(modifier) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            if (tvLayout) {
                // Tv: vad fjärrkontrollen gör med den markerade bilden.
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        KeyHint("OK", "förstora")
                        Text("·", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.6f))
                        KeyHint("Tillbaka", "stäng")
                    }
                }
            } else {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Glyphs.OpenInFull, contentDescription = "Förstora", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        if (progress != null) {
            ProgressLine(progress, Modifier.align(Alignment.BottomStart))
        }
    }
}

/**
 * Tv: den lilla bilden i hörnet. Nedtill vad som spelas — LIVE och kanalen,
 * programmet som sänds eller hur länge det är kvar — och hur långt det
 * kommit. Strax efter att bilden krympt står att Play förstorar den, och
 * Fotbollens förhandsvisning säger att den är utan ljud. Markerad: vad OK
 * och Tillbaka gör.
 */
@Composable
private fun TvMiniOverlay(
    state: AppState,
    playing: NowPlaying,
    status: VideoPlayer.Status,
    focused: Boolean,
    now: Long,
    modifier: Modifier,
) {
    val progress = miniProgress(state, playing, status, now)
    val preview = state.footballPreview != null
    val fresh = now - state.miniSince < MINI_HINT_MS
    val onAir = playing.channel?.let { state.guide.current(it, now) }
    val line = when {
        preview -> "Utan ljud · Play: titta med ljud"
        fresh -> "Play: helskärm"
        onAir != null -> onAir.title
        !playing.live && status.lengthMs > 0 -> remainingText(status.lengthMs - status.timeMs)
        else -> playing.subtitle?.takeIf { it.isNotBlank() }
    }
    Box(modifier) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.84f))))
                .padding(start = 12.dp, end = 12.dp, top = 26.dp, bottom = 11.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing.live) {
                    Box(
                        Modifier.clip(RoundedCornerShape(5.dp)).background(MINI_LIVE).padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            "LIVE",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    playing.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (preview) {
                    Spacer(Modifier.width(7.dp))
                    Icon(Glyphs.VolumeOff, contentDescription = "Utan ljud", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(15.dp))
                }
            }
            if (line != null) {
                Text(
                    line,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (preview || fresh) Color.White else Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (focused) {
            // Vad fjärrkontrollen gör med den markerade bilden.
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.74f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KeyHint("OK", "förstora")
                Text("·", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.6f))
                KeyHint("Tillbaka", "stäng")
            }
        }
        if (progress != null) {
            ProgressLine(progress, Modifier.align(Alignment.BottomStart))
        }
    }
}

/** Så länge den lilla bilden visar att Play förstorar den, efter att den krympt. */
private const val MINI_HINT_MS = 6_000L
private val MINI_LIVE = Color(0xFFE5322D)

/** "OK förstora": knappen i fetstil, vad den gör efter. */
@Composable
private fun KeyHint(key: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(key, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1)
        Text(action, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
    }
}

/**
 * "Spelas nu" i sidofältet, under minispelarens bild: titel, vad som sänds
 * eller hur länge det är kvar, och paus, förstora och stäng.
 */
@Composable
internal fun MiniPlayerCard(state: AppState, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    // Det som spelades står kvar medan raden glider undan efter Stäng.
    val last = remember { arrayOfNulls<NowPlaying>(1) }
    val playing = state.nowPlaying?.also { last[0] = it } ?: last[0] ?: return
    val player = state.videoPlayer
    val rawStatus by (player?.status ?: idleStatus).collectAsState()
    val status = if (rawStatus.session == state.playbackSession) rawStatus else VideoPlayer.Status(phase = Phase.OPENING)
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            now = System.currentTimeMillis()
        }
    }
    val paused = status.phase == Phase.PAUSED
    val problem = problemFor(state, playing, status)
    val onAir = playing.channel?.let { state.guide.current(it, now) }
    val detail = when {
        problem != null -> problem.title
        player == null || status.phase == Phase.OPENING || status.phase == Phase.IDLE -> "Startar…"
        onAir != null -> (if (paused) "Pausad · " else "") + clockText(onAir.start) + "–" + clockText(onAir.stop) + "  " + onAir.title
        !playing.live && status.lengthMs > 0 -> listOfNotNull(
            if (paused) "Pausad" else null,
            remainingText(status.lengthMs - status.timeMs),
            playing.subtitle?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        paused -> "Pausad"
        else -> playing.subtitle?.takeIf { it.isNotBlank() } ?: if (playing.live) "Direktsändning" else ""
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 2.dp, end = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing.live) {
                    Tag("LIVE", palette.signal)
                    Spacer(Modifier.width(7.dp))
                }
                Text(playing.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (problem != null) palette.signal else palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        IconAction(
            if (paused) Glyphs.Play else Glyphs.Pause,
            if (paused) "Spela" else "Paus",
            onClick = { state.togglePause() },
            size = 34.dp,
            iconSize = 18.dp,
        )
        IconAction(Glyphs.OpenInFull, "Förstora", onClick = { state.expandPlayer() }, size = 34.dp, iconSize = 16.dp)
        IconAction(Glyphs.Close, "Stäng", onClick = { state.closePlayer() }, size = 34.dp, iconSize = 16.dp)
    }
}

/** "1 tim 12 min kvar", "42 min kvar". */
private fun remainingText(ms: Long): String {
    val minutes = ((ms.coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$minutes min kvar"
        rest == 0 -> "$hours tim kvar"
        else -> "$hours tim $rest min kvar"
    }
}

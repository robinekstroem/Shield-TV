package se.norrsken.tv.desktop.ui

import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import se.norrsken.tv.desktop.data.LiveChannel
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import se.norrsken.tv.desktop.data.WatchKind
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
import androidx.compose.runtime.DisposableEffect
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

/**
 * Spelaren. [collapse]: hur långt den krympt (0 över allt, 1 liten),
 * [highlighted]: den lilla bilden är markerad, [staged]: den lilla bilden
 * står i Live TV:s stora ruta.
 */
@Composable
fun PlayerScreen(state: AppState, collapse: Float = 0f, highlighted: Boolean = false, staged: Boolean = false) =
    PlayerLayers(state, collapse, highlighted, staged, still = null)

/**
 * Förhandsbilder (windows/src/preview): spelaren över en stillbild, med ett
 * påhittat läge i stället för en spelare.
 */
internal class PlayerStill(val status: VideoPlayer.Status, val frame: ImageBitmap)

@Composable
internal fun PlayerLayers(state: AppState, collapse: Float, highlighted: Boolean, staged: Boolean, still: PlayerStill?) {
    val palette = LocalPalette.current
    val playing = state.nowPlaying ?: return
    val player = state.videoPlayer
    val rawStatus by (player?.status ?: idleStatus).collectAsState()
    // Status från förra strömmen räknas inte: den kan säga ENDED eller ERROR
    // i ögonblicket innan den nya har hunnit öppnas.
    val status = still?.status
        ?: if (rawStatus.session == state.playbackSession) rawStatus else VideoPlayer.Status(phase = Phase.OPENING)

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
    val waiting = problem == null &&
        ((player == null && still == null) || status.phase == Phase.OPENING || status.phase == Phase.IDLE || status.stalled)
    val controlsVisible = problem != null || status.phase == Phase.PAUSED || state.trackPanelOpen ||
        now - state.lastActivity < 3_500 ||
        // Tv: medan man står på knapparna står de kvar en stund längre.
        (tvLayout && state.playerControlsFocused && now - state.lastActivity < 8_000)
    // Tv: tonar knapparna bort medan en av dem har markeringen går den tillbaka till bilden.
    if (tvLayout) {
        LaunchedEffect(controlsVisible) { if (!controlsVisible) state.leavePlayerControls() }
    }
    val mini = collapse > 0.5f
    // Tv: titelns logga, handlingen och bakgrundsbilden, för rubriken, laddningen och pausbilden.
    val facts = if (tvLayout) remember(playing, state.library, state.tmdbFacts) { state.playingFacts(playing) } else null
    // Tv: pausbilden kommer när en film stått pausad en stund, och symbolen
    // syns stort i mitten när bilden pausas eller startar igen.
    var pausedSince by remember { mutableLongStateOf(0L) }
    var pulseTick by remember { mutableIntStateOf(0) }
    var lastPhase by remember { mutableStateOf(status.phase) }
    if (tvLayout) {
        LaunchedEffect(status.phase) {
            val before = lastPhase
            lastPhase = status.phase
            if (status.phase == Phase.PAUSED) pausedSince = System.currentTimeMillis()
            val toggled = (before == Phase.PLAYING && status.phase == Phase.PAUSED) ||
                (before == Phase.PAUSED && status.phase == Phase.PLAYING)
            if (toggled) pulseTick++
        }
    }
    val pauseScene = tvLayout && !mini && !playing.live && status.phase == Phase.PAUSED && problem == null &&
        !state.scrubbing && !state.trackPanelOpen && pausedSince > 0L && now - pausedSince >= PAUSE_SCENE_AFTER_MS
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()

    // Tv: felrutan försvann (kanalen kom igång efter Försök igen): markeringen
    // tillbaka till bilden, i stället för ingenstans.
    if (tvLayout && !mini) {
        val hasProblem = problem != null
        LaunchedEffect(hasProblem) { if (!hasProblem) state.focusPlayer() }
    }

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
        } else if (still != null) {
            Image(still.frame, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
            waiting && tvLayout && !mini && (status.phase == Phase.OPENING || status.phase == Phase.IDLE) ->
                LoadingScene(playing, facts, Modifier.align(Alignment.Center))
            waiting -> Spinner(Modifier.align(Alignment.Center), size = if (mini) 28.dp else 48.dp)
            status.phase == Phase.PLAYING && !status.hasVideo && status.timeMs > 3_000 -> Text(
                "Endast ljud",
                style = MaterialTheme.typography.titleMedium,
                color = palette.textSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (tvLayout && !mini) {
            AnimatedVisibility(
                visible = pauseScene,
                enter = fadeIn(tween(520)),
                exit = fadeOut(tween(240)),
                modifier = Modifier.fillMaxSize(),
            ) {
                PauseScene(state, playing, status, facts, Modifier.fillMaxSize())
            }
            PhasePulse(pulseTick, paused = status.phase == Phase.PAUSED, modifier = Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            // Tv: kanallistan ligger över bilden; då går knapparna undan.
            visible = controlsVisible && !mini && !(tvLayout && state.channelListOpen),
            enter = fadeIn(tween(CONTROLS_IN_MS)),
            exit = fadeOut(tween(CONTROLS_OUT_MS)),
            // Kontrollerna tonar bort redan i början av krympningen.
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = (1f - collapse * 3f).coerceIn(0f, 1f) },
        ) {
            Box(Modifier.fillMaxSize()) {
                if (tvLayout) {
                    TvTopBar(state, playing, status, facts, now, titleHidden = pauseScene, modifier = Modifier.align(Alignment.TopCenter))
                    TvBottomBar(state, playing, status, keys, Modifier.align(Alignment.BottomCenter))
                } else {
                    TopBar(state, playing, status, now, keys, Modifier.align(Alignment.TopCenter))
                    BottomBar(state, playing, status, keys, Modifier.align(Alignment.BottomCenter))
                }
            }
        }

        // Tv: kanalrutan nederst när en kanal startar eller byts med pil upp och ner.
        if (tvLayout && playing.live && !mini) {
            val zapping = state.zapTarget
            val banner = problem == null && !controlsVisible && !state.trackPanelOpen && !state.channelListOpen &&
                (zapping != null || now - state.zapShownAt < ZAP_BANNER_MS)
            AnimatedVisibility(
                visible = banner,
                enter = fadeIn(tween(CONTROLS_IN_MS)) + slideInVertically(tween(260)) { it / 4 },
                exit = fadeOut(tween(CONTROLS_OUT_MS)),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                val channel = zapping ?: playing.channel
                if (channel != null) ZapBanner(state, channel, now, pending = zapping != null)
            }
        }

        // Tv: tidslinjen och knapparna nederst är högre än på datorn.
        val timeline = !playing.live && status.lengthMs > 0L
        if (state.trackPanelOpen && !mini) {
            TracksPanel(
                state = state,
                status = status,
                modifier = Modifier.align(Alignment.BottomEnd).padding(
                    end = if (tvLayout) TV_EDGE else 20.dp,
                    bottom = when {
                        !tvLayout -> 104.dp
                        timeline -> 182.dp
                        else -> 118.dp
                    },
                ),
            )
        }

        // Hoppa över intro och Nästa avsnitt, nere till höger.
        if (!mini && problem == null && !state.trackPanelOpen) {
            PlayerPrompts(
                state = state,
                status = status,
                lifted = controlsVisible,
                liftedBottom = when {
                    !tvLayout -> 128.dp
                    timeline -> 236.dp
                    else -> 172.dp
                },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

        if (mini) {
            // Titel och knappar står i sidofältet under bilden; på bilden bara förstora.
            DockedOverlay(state, playing, status, visible = hovered || highlighted, now = now, staged = staged, modifier = Modifier.fillMaxSize())
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
    /** Tv: tidslinjen i en film, och kanallistan i direktsänt. */
    val timeline = FocusRequester()
    val channels = FocusRequester()
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
            SeekBar(status, onSeek = { fraction -> state.seekTo(fraction) })
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
            // Tv: knapparna står i en rad nederst; uppåt finns inget.
            up = FocusRequester.Cancel
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
            // Byts kanalen medan man står på spelarens knappar (Nästa kanal) stannar
            // markeringen där; den hoppar bara till bilden när den inte redan är i spelaren.
            if (!state.miniPlayer && !state.trackPanelOpen) {
                withFrameNanos { }
                if (!state.playerControlsFocused) runCatching { requester.requestFocus() }
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
    // Live TV: den lilla bilden står i den stora rutan till höger, med kanalerna
    // bredvid, och glider dit och tillbaka när man kommer till Live TV och går.
    val stage = LiveBox.bounds
    val place = remember { StagePlace() }
    if (stage != null) place.last = stage
    val toStage by animateFloatAsState(
        targetValue = if (stage != null) 1f else 0f,
        animationSpec = tween(STAGE_GLIDE_MS, easing = FastOutSlowInEasing),
        label = "liveruta",
    )
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val at = coordinates.positionInWindow()
                if (at != origin) origin = at
            },
    ) {
        // Liten: överst i sidofältet, i facket det håller fritt. Tv: nere i
        // högra hörnet, ovanpå det man bläddrar i — menyn är gömd där.
        var miniWidth = if (tvLayout) TV_PIP_WIDTH else MiniDock.videoWidth
        var miniHeight = if (tvLayout) TV_PIP_HEIGHT else MiniDock.videoHeight
        var miniX = if (tvLayout) maxWidth - TV_PIP_WIDTH - TV_PIP_MARGIN else MiniDock.inset
        var miniY = if (tvLayout) maxHeight - TV_PIP_HEIGHT - TV_PIP_MARGIN else MiniDock.top
        var radius = if (tvLayout) TV_PIP_RADIUS else MiniDock.radius
        val last = place.last
        if (last != null && toStage > 0f) {
            with(density) {
                miniX = lerp(miniX, (last.left - origin.x).toDp(), toStage)
                miniY = lerp(miniY, (last.top - origin.y).toDp(), toStage)
                miniWidth = lerp(miniWidth, last.width.toDp(), toStage)
                miniHeight = lerp(miniHeight, last.height.toDp(), toStage)
            }
            radius = lerp(radius, LIVE_STAGE_RADIUS, toStage)
        }
        val shape = RoundedCornerShape(lerp(0.dp, radius, progress))
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
                // I den stora rutan i Live TV lyser kanten svagare än i hörnet.
                .glow(palette.glowOf((if (focused) 1f else 0.55f - 0.3f * toStage) * progress), shape, 22.dp)
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
            PlayerScreen(state, progress, highlighted = focused, staged = toStage > 0.5f)
        }
    }
}

/** Var Live TV:s stora ruta senast stod, så att bilden kan glida därifrån när man lämnar Live TV. */
private class StagePlace {
    var last: Rect? = null
}

/** Tv: minispelaren som en bild i hörnet, i 16:9. */
private val TV_PIP_WIDTH = 432.dp
private val TV_PIP_HEIGHT = 243.dp
private val TV_PIP_MARGIN = 40.dp
private val TV_PIP_RADIUS = 14.dp

/** Hur fort bilden glider mellan hörnet och Live TV:s stora ruta. */
private const val STAGE_GLIDE_MS = 420

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
    /** I Live TV:s stora ruta: där finns inget kort med knappar under bilden, så stäng står på den. */
    staged: Boolean,
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
                    if (staged) {
                        Pressable(
                            onClick = { state.closePlayer() },
                            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(38.dp),
                            shape = RoundedCornerShape(19.dp),
                            glowing = false,
                        ) { hovered ->
                            Box(
                                Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (hovered) 0.8f else 0.55f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Glyphs.Close, contentDescription = "Stäng", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
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

// --- Tv: spelarens kontroller ------------------------------------------------------

/** Tv: knapparna tonar fram fort och bort lite långsammare. */
private const val CONTROLS_IN_MS = 160
private const val CONTROLS_OUT_MS = 280

/** Tv: så länge kanalrutan står kvar efter att kanalen startat. */
private const val ZAP_BANNER_MS = 4_000L

/** Tv: så länge en film stått pausad innan pausbilden kommer. */
private const val PAUSE_SCENE_AFTER_MS = 3_000L

/** Tv: luften vid bildens kanter, innanför det tv:n kan skära bort. */
private val TV_EDGE = 56.dp

/** Mörk text på det vita: markerad knapp, bubblan över tidslinjen. */
private val PLAYER_INK = Color(0xFF0B0D12)

/** Ljusare variant av en färg, för toningar. */
private fun Color.lift(amount: Float): Color = lerp(this, Color.White, amount)

/**
 * Tv: överst vad som spelas. En film: titelns logga (TMDB) eller namnet,
 * år, genrer och betyg, och för ett avsnitt vilket. En kanal: loggan,
 * namnet, programmet som sänds med hur långt det kommit, och vad som kommer
 * sedan. Klockan till höger, och när filmen slutar. Under ett kanalbyte:
 * kanalen man är på väg till. Medan pausbilden visas står bara klockan kvar.
 */
@Composable
private fun TvTopBar(
    state: AppState,
    playing: NowPlaying,
    status: VideoPlayer.Status,
    facts: AppState.PlayingFacts?,
    now: Long,
    titleHidden: Boolean,
    modifier: Modifier,
) {
    val accent = menuFill(LocalPalette.current)
    val channel = state.zapTarget ?: playing.channel
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.86f), Color.Black.copy(alpha = 0.38f), Color.Transparent)))
            .padding(start = TV_EDGE, end = TV_EDGE, top = 32.dp, bottom = 64.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.weight(1f)) {
            if (!titleHidden) {
                if (playing.live && channel != null) {
                    LiveHeading(state, channel, status, accent, now)
                } else {
                    FilmHeading(state, playing, status, facts)
                }
            }
        }
        Spacer(Modifier.width(24.dp))
        // `now` ändras varannan halvsekund: klockan följer med.
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                LocalTime.now().format(clockFormat),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Light),
                color = Color.White,
                maxLines = 1,
            )
            if (!playing.live && status.lengthMs > 0L) {
                val left = (status.lengthMs - status.timeMs).coerceAtLeast(0L)
                Text(
                    "Slutar " + LocalTime.now().plusSeconds(left / 1000L).format(clockFormat),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Filmens eller seriens namn — som logga när TMDB har en — med år, genrer och betyg. */
@Composable
private fun FilmHeading(state: AppState, playing: NowPlaying, status: VideoPlayer.Status, facts: AppState.PlayingFacts?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TitleLogo(
            state = state,
            kind = facts?.kind ?: "movie",
            tmdbId = facts?.tmdbId,
            title = playing.title,
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, color = Color.White),
            maxHeight = 64.dp,
            maxWidth = 480.dp,
            maxLines = 1,
        )
        val episode = playing.entry?.kind == WatchKind.EPISODE
        if (episode) {
            playing.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        MetaLine(facts, qualityLabel(status.videoHeight), fallback = if (episode) null else playing.subtitle)
    }
}

/** År · genrer · ★ betyg · 4K, i en rad. [fallback]: när inget av det är känt. */
@Composable
private fun MetaLine(facts: AppState.PlayingFacts?, quality: String?, fallback: String?) {
    val parts = listOfNotNull(
        facts?.year?.toString(),
        facts?.genres?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(2)?.joinToString(", ")?.takeIf { it.isNotEmpty() },
    )
    val rating = facts?.rating?.takeIf { it > 0.0 }
    if (parts.isEmpty() && rating == null && quality == null && fallback.isNullOrBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val text = parts.ifEmpty { listOfNotNull(fallback?.takeIf { it.isNotBlank() }) }.joinToString("  ·  ")
        if (text.isNotEmpty()) {
            Text(text, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (rating != null) {
            Text(
                "★ " + String.format(java.util.Locale.ROOT, "%.1f", rating).replace('.', ','),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFFFFD25A),
                maxLines = 1,
            )
        }
        quality?.let { QualityBadge(it) }
    }
}

/** Kanalens logga, namnet, LIVE, och programmet med hur långt det kommit och vad som kommer sedan. */
@Composable
private fun LiveHeading(state: AppState, channel: LiveChannel, status: VideoPlayer.Status, accent: Color, now: Long) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(channel.logo, channel.name, width = 100.dp, height = 54.dp, initialsStyle = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveBadge()
                Text(
                    channel.name,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                qualityLabel(status.videoHeight)?.let { QualityBadge(it) }
            }
            val onAir = state.guide.current(channel, now)
            if (onAir != null) {
                Text(
                    onAir.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        clockText(onAir.start) + "–" + clockText(onAir.stop),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                    ProgressPill(onAir.progress(now), accent, Modifier.width(180.dp))
                    Text(timeLeft(onAir, now), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                }
                state.guide.next(channel, now)?.let { next ->
                    Text(
                        "Sedan " + clockText(next.start) + "  " + next.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(channel.group, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            }
        }
    }
}

/**
 * Tv: nederst tidslinjen (i en film) och spelarens knappar i en rad: att
 * spela och spola till vänster, ljud och undertext, minimera och stäng till
 * höger. Den markerade knappen blir vit och växer ut till en pill med
 * namnet i, så att det syns från soffan var man är.
 */
@Composable
private fun TvBottomBar(state: AppState, playing: NowPlaying, status: VideoPlayer.Status, keys: PlayerKeys, modifier: Modifier) {
    val paused = status.phase == Phase.PAUSED
    val timeline = !playing.live && status.lengthMs > 0L
    // Pil upp eller ner i en film tar fram knapparna med markeringen på tidslinjen,
    // där pil vänster och höger spolar som förut; pil höger i direktsänt på Spela/Paus.
    LaunchedEffect(state.controlsFocusTick) {
        if (state.takeControlsFocus()) {
            repeat(2) { withFrameNanos { } }
            runCatching { (if (timeline) keys.timeline else keys.play).requestFocus() }
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.9f))))
            .padding(start = TV_EDGE, end = TV_EDGE, top = 64.dp, bottom = 26.dp),
    ) {
        if (timeline) {
            TvTimeline(state, status, keys)
            Spacer(Modifier.height(18.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val up = if (timeline) keys.timeline else null
            val playPause = if (paused) Glyphs.Play else Glyphs.Pause
            val playLabel = if (paused) "Spela" else "Paus"
            if (playing.live) {
                TvPlayerButton(
                    Glyphs.SkipPrevious, "Föregående kanal",
                    onClick = { state.zap(-1, keepControls = true) },
                    modifier = Modifier.playerKey(keys.first, toUp = up, toRight = keys.play),
                )
                TvPlayerButton(
                    playPause, playLabel, big = true,
                    onClick = { state.togglePause() },
                    modifier = Modifier.playerKey(keys.play, toUp = up, toLeft = keys.first, toRight = keys.third),
                )
                TvPlayerButton(
                    Glyphs.SkipNext, "Nästa kanal",
                    onClick = { state.zap(1, keepControls = true) },
                    modifier = Modifier.playerKey(keys.third, toUp = up, toLeft = keys.play, toRight = keys.channels),
                )
                TvPlayerButton(
                    Glyphs.Live, "Kanaler",
                    onClick = { state.openChannelList() },
                    modifier = Modifier.playerKey(keys.channels, toUp = up, toLeft = keys.third, toRight = keys.tracks),
                )
            } else {
                TvPlayerButton(
                    Glyphs.Replay10, "10 s bakåt",
                    onClick = { state.scrub(-1) },
                    modifier = Modifier.playerKey(keys.first, toUp = up, toRight = keys.play),
                )
                TvPlayerButton(
                    playPause, playLabel, big = true,
                    onClick = { if (!state.commitScrub()) state.togglePause() },
                    modifier = Modifier.playerKey(keys.play, toUp = up, toLeft = keys.first, toRight = keys.third),
                )
                TvPlayerButton(
                    Glyphs.Forward10, "10 s framåt",
                    onClick = { state.scrub(1) },
                    modifier = Modifier.playerKey(keys.third, toUp = up, toLeft = keys.play, toRight = keys.tracks),
                )
            }
            Spacer(Modifier.weight(1f))
            TvPlayerButton(
                Glyphs.Subtitles, "Ljud och undertext",
                active = state.trackPanelOpen,
                onClick = { state.trackPanelOpen = !state.trackPanelOpen },
                modifier = Modifier.playerKey(
                    keys.tracks,
                    toUp = up,
                    toLeft = if (playing.live) keys.channels else keys.third,
                    toRight = keys.minimize,
                ),
            )
            TvPlayerButton(
                Glyphs.ChevronDown, "Minimera",
                onClick = {
                    state.minimizePlayer()
                    TvNav.restoreFocus()
                },
                modifier = Modifier.playerKey(keys.minimize, toUp = up, toLeft = keys.tracks, toRight = keys.close),
            )
            TvPlayerButton(
                Glyphs.Close, "Stäng",
                onClick = {
                    state.closePlayer()
                    TvNav.restoreFocus()
                },
                modifier = Modifier.playerKey(keys.close, toUp = up, toLeft = keys.minimize),
            )
        }
    }
}

/**
 * Tv: tidslinjen i en film. Det spelade lyser i temats färg, ett känt intro
 * syns som en ljusare bit. Markerad blir linjen tjockare och får en tumme
 * med ett sken; där spolar pil vänster och höger (se MainActivity) och OK
 * pausar. Under spolningen står målets tid i en bubbla över tummen, med hur
 * långt hoppet blir, och sträckan dit lyser vitt.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun TvTimeline(state: AppState, status: VideoPlayer.Status, keys: PlayerKeys) {
    val accent = menuFill(LocalPalette.current)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val length = status.lengthMs.coerceAtLeast(1L)
    val target = state.scrubTarget
    val shown = if (target >= 0L) target else status.timeMs
    val position = (shown.toFloat() / length).coerceIn(0f, 1f)
    val played = (status.timeMs.toFloat() / length).coerceIn(0f, 1f)
    val lit = focused || target >= 0L
    val thickness by animateDpAsState(if (lit) 10.dp else 6.dp, tween(180), label = "tidslinje")
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "tumme")
    val intro = state.introMark?.takeIf { it.hasIntro }
    // Tonar knapparna bort medan tidslinjen är markerad gäller pilarna bilden igen.
    DisposableEffect(Unit) { onDispose { state.playerTimelineFocused = false } }
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .focusRequester(keys.timeline)
                .focusProperties {
                    up = FocusRequester.Cancel
                    down = keys.play
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                }
                .onFocusChanged { state.playerTimelineFocused = it.isFocused }
                .focusable(interactionSource = interaction),
            contentAlignment = Alignment.CenterStart,
        ) {
            val track = RoundedCornerShape(50)
            val width = maxWidth
            Box(Modifier.fillMaxWidth().height(thickness).clip(track).background(Color.White.copy(alpha = 0.22f)))
            if (intro != null) {
                val from = (intro.introFromMs.toFloat() / length).coerceIn(0f, 1f)
                val to = (intro.introToMs.toFloat() / length).coerceIn(from, 1f)
                Box(
                    Modifier
                        .offset(x = width * from)
                        .width(width * (to - from))
                        .height(thickness)
                        .background(Color.White.copy(alpha = 0.22f)),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth(played)
                    .height(thickness)
                    .clip(track)
                    .background(Brush.horizontalGradient(listOf(accent, accent.lift(0.35f)))),
            )
            if (target >= 0L) {
                // Sträckan bilden hoppar, fram eller tillbaka.
                val from = min(played, position)
                val to = max(played, position)
                Box(
                    Modifier
                        .offset(x = width * from)
                        .width(width * (to - from))
                        .height(thickness)
                        .clip(track)
                        .background(Color.White.copy(alpha = 0.7f)),
                )
            }
            if (lit) {
                val thumb = 24.dp
                val ring = 44.dp
                Box(
                    Modifier
                        .offset(x = (width * position - ring / 2).coerceIn(-ring / 4, width - ring * 3 / 4))
                        .size(ring)
                        .graphicsLayer { alpha = glow }
                        .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent)), CircleShape),
                )
                Box(
                    Modifier
                        .offset(x = (width * position - thumb / 2).coerceIn(0.dp, (width - thumb).coerceAtLeast(0.dp)))
                        .size(thumb)
                        .shadow(8.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
            if (target >= 0L) {
                ScrubBubble(target, state.scrubOrigin, accent, width, position)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTime(shown),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "−" + formatTime((length - shown).coerceAtLeast(0L)),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
            )
        }
    }
}

/**
 * Bubblan över tummen under en spolning: vart bilden hoppar, stort, och hur
 * långt i temats färg, med en liten spets ner mot tummen. Tar ingen plats:
 * den ritas ovanför tidslinjen.
 */
@Composable
private fun BoxScope.ScrubBubble(target: Long, origin: Long, accent: Color, width: Dp, position: Float) {
    val bubble = 176.dp
    val x = (width * position - bubble / 2).coerceIn(0.dp, (width - bubble).coerceAtLeast(0.dp))
    // Spetsen pekar på tummen också när bubblan stannat mot en kant.
    val tip = (width * position - x).coerceIn(18.dp, bubble - 18.dp)
    Column(
        Modifier
            .align(Alignment.TopStart)
            .offset(x = x, y = (-78).dp)
            .wrapContentHeight(unbounded = true)
            .width(bubble),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(14.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .padding(vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                formatTime(target),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = PLAYER_INK,
                maxLines = 1,
            )
            if (origin >= 0L) {
                Text(
                    signedTime(target - origin),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = accent,
                    maxLines = 1,
                )
            }
        }
        Spacer(
            Modifier
                .offset(x = tip - 9.dp)
                .size(width = 18.dp, height = 9.dp)
                .drawBehind {
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width / 2, size.height)
                        close()
                    }
                    drawPath(path, Color.White)
                },
        )
    }
}

/**
 * Tv: en av spelarens knappar. Rund och dämpad; markerad blir den vit och
 * växer ut till en pill med namnet bredvid symbolen. [active]: det knappen
 * öppnar är öppet.
 */
@Composable
private fun TvPlayerButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    active: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(160), label = "spelarknapp")
    val side = if (big) 62.dp else 52.dp
    Row(
        modifier
            .height(side)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (focused) 16.dp else 0.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> Color.White
                    active -> Color.White.copy(alpha = 0.32f)
                    else -> Color.White.copy(alpha = 0.13f)
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .animateContentSize(tween(200, easing = FastOutSlowInEasing))
            .widthIn(min = side)
            .padding(start = if (focused) 18.dp else 0.dp, end = if (focused) 22.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (focused) PLAYER_INK else Color.White,
            modifier = Modifier.size(if (big) 32.dp else 26.dp),
        )
        if (focused) {
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = PLAYER_INK,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Tv: kanalrutan nederst — numret, loggan och namnet, vad som sänds nu och
 * hur långt det kommit, och vad som kommer sedan. Under ett kanalbyte visar
 * den kanalen man är på väg till ([pending]), med en liten snurra.
 */
@Composable
private fun ZapBanner(state: AppState, channel: LiveChannel, now: Long, pending: Boolean) {
    val accent = menuFill(LocalPalette.current)
    val onAir = state.guide.current(channel, now)
    val next = state.guide.next(channel, now)
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .padding(start = TV_EDGE, end = TV_EDGE, bottom = 44.dp)
            .widthIn(min = 640.dp, max = 1080.dp)
            .shadow(28.dp, shape)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xF5171B24), Color(0xF50B0D12))))
            // Temats färg längs vänsterkanten.
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(listOf(accent.lift(0.3f), accent)),
                    size = androidx.compose.ui.geometry.Size(5.dp.toPx(), size.height),
                )
            }
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(25.dp))
        channel.number?.let { number ->
            Text(
                number.toString(),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                color = Color.White.copy(alpha = 0.42f),
                maxLines = 1,
                modifier = Modifier.widthIn(min = 56.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Box(
            Modifier.size(width = 136.dp, height = 76.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(channel.logo, channel.name, width = 120.dp, height = 64.dp, initialsStyle = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f).padding(top = 18.dp, bottom = 18.dp, end = 26.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    channel.name,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (pending) Spinner(size = 20.dp) else LiveBadge()
            }
            if (onAir != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "NU",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 1.2.sp),
                        color = accent.lift(0.3f),
                        maxLines = 1,
                    )
                    Text(
                        onAir.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        clockText(onAir.start) + "–" + clockText(onAir.stop) + "  ·  " + timeLeft(onAir, now),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                    )
                }
                ProgressPill(onAir.progress(now), accent, Modifier.fillMaxWidth())
                next?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "SEDAN",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 1.2.sp),
                            color = Color.White.copy(alpha = 0.45f),
                            maxLines = 1,
                        )
                        Text(
                            clockText(it.start) + "  " + it.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Text(
                    channel.group.takeIf { it.isNotBlank() } ?: "Direktsändning",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Tv: pausbilden. Står en film pausad en stund dämpas bilden och titeln
 * kommer fram stort till vänster — loggan, år och genrer, handlingen — som
 * på streamingtjänsterna. Knapparna står kvar nederst.
 */
@Composable
private fun PauseScene(state: AppState, playing: NowPlaying, status: VideoPlayer.Status, facts: AppState.PlayingFacts?, modifier: Modifier) {
    val accent = menuFill(LocalPalette.current)
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.82f),
                    0.55f to Color.Black.copy(alpha = 0.5f),
                    1f to Color.Black.copy(alpha = 0.22f),
                ),
            ),
    ) {
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = TV_EDGE, bottom = 120.dp).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "PAUSAD",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 3.sp),
                color = accent.lift(0.3f),
            )
            TitleLogo(
                state = state,
                kind = facts?.kind ?: "movie",
                tmdbId = facts?.tmdbId,
                title = playing.title,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, color = Color.White),
                maxHeight = 120.dp,
                maxWidth = 620.dp,
            )
            if (playing.entry?.kind == WatchKind.EPISODE) {
                playing.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            MetaLine(facts, qualityLabel(status.videoHeight), fallback = null)
            facts?.overview?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.84f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (status.lengthMs > 0L) {
                Text(
                    formatTime(status.timeMs) + " av " + formatTime(status.lengthMs) + "  ·  " +
                        remainingText((status.lengthMs - status.timeMs).coerceAtLeast(0L)),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * Tv: medan något startar. En film: bakgrundsbilden dämpad, och titeln med
 * en snurra. En kanal: loggan och namnet med en snurra. Ersätter den svarta
 * bilden tills första bildrutan kommer.
 */
@Composable
private fun LoadingScene(playing: NowPlaying, facts: AppState.PlayingFacts?, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val backdrop = facts?.backdrop
        if (!playing.live && backdrop != null) {
            RemoteImage(
                url = backdrop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.38f },
                maxSide = 1280,
                contentScale = ContentScale.Crop,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val channel = playing.channel
            if (playing.live && channel != null) {
                Box(
                    Modifier.size(width = 200.dp, height = 112.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    ChannelLogo(channel.logo, channel.name, width = 176.dp, height = 96.dp, initialsStyle = MaterialTheme.typography.headlineMedium)
                }
            }
            Text(
                playing.title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 900.dp),
            )
            Spinner(size = 40.dp)
        }
    }
}

/**
 * Tv: när bilden pausas eller startar igen syns symbolen stort mitt i bilden
 * en kort stund och tonar bort medan den växer — som på streamingtjänsterna.
 * [tick] ökar för varje byte; [paused]: bilden står nu still.
 */
@Composable
private fun PhasePulse(tick: Int, paused: Boolean, modifier: Modifier) {
    if (tick == 0) return
    val progress = remember { Animatable(1f) }
    LaunchedEffect(tick) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(720, easing = FastOutSlowInEasing))
    }
    Box(
        modifier
            .size(112.dp)
            .graphicsLayer {
                val t = progress.value
                alpha = (1f - t) * 0.9f
                val grow = 0.85f + 0.3f * t
                scaleX = grow
                scaleY = grow
            }
            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (paused) Glyphs.Pause else Glyphs.Play,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(52.dp),
        )
    }
}

/** Tv: LIVE i rött, som på tv-kanalerna. */
@Composable
private fun LiveBadge() {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(MINI_LIVE).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(
            "LIVE",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** Tv: bildens kvalitet, inom en tunn ram. */
@Composable
private fun QualityBadge(text: String) {
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), shape).padding(horizontal = 7.dp, vertical = 1.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
        )
    }
}

/** Hur långt programmet kommit, som en tunn linje. */
@Composable
private fun ProgressPill(progress: Float, color: Color, modifier: Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(color))
    }
}

/** "+2:30", "−0:40": hur långt spolningen hoppar. */
private fun signedTime(ms: Long): String = (if (ms < 0L) "−" else "+") + formatTime(abs(ms))

package se.norrsken.tv.feature.player

import android.content.pm.ActivityInfo
import android.media.audiofx.LoudnessEnhancer
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import se.norrsken.tv.core.device.LocalDeviceProfile
import se.norrsken.tv.core.ui.component.AuroraProgress
import se.norrsken.tv.core.ui.component.FocusableSurface
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.core.ui.theme.LocalPreferences
import se.norrsken.tv.core.ui.theme.LocalThemeStyle
import se.norrsken.tv.core.util.findActivity
import se.norrsken.tv.data.epg.NowNext
import se.norrsken.tv.data.settings.SubtitleBackground
import se.norrsken.tv.data.settings.VideoScale
import se.norrsken.tv.data.settings.VolumeBoost
import kotlin.math.max
import kotlin.math.min

private const val SEEK_STEP_MS = 10_000L

private enum class Panel { NONE, AUDIO, SUBTITLES }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val playable by viewModel.playable.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()
    val programme by viewModel.programme.collectAsStateWithLifecycle()
    val preferences = LocalPreferences.current
    val profile = LocalDeviceProfile.current
    val context = LocalContext.current
    val view = LocalView.current

    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    // Varje handling raknar upp den har, sa nedrakningen borjar om.
    var interactions by remember { mutableIntStateOf(0) }
    // playWhenReady, inte isPlaying: under buffring ar isPlaying falskt,
    // och da visade knappen "Spela" och startade om i stallet for att pausa.
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var panel by remember { mutableStateOf(Panel.NONE) }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }

    // Byggs om bara nar ett val som styr sjalva motorn andras. Byte av
    // accentfarg ska inte avbryta sandningen.
    val player = remember(
        preferences.buffer,
        preferences.decoder,
        preferences.matchFrameRate,
        preferences.userAgent,
        preferences.preferSurround,
        preferences.videoQuality,
        preferences.tunneling,
        preferences.audioLanguage,
        preferences.subtitleLanguage,
        preferences.autoSubtitles,
    ) {
        viewModel.playerFactory.create(preferences)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = explain(e)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) error = null
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playing = playWhenReady
            }

            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
            }
        }
        player.addListener(listener)
        playing = player.playWhenReady
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Volymförstärkning. Kopplas till spelarens ljudsession, som
    // PlayerFactory sätter direkt vid start. Fungerar bara när appen själv
    // avkodar ljudet: vid passthrough går Dolby-strömmen orörd till
    // förstärkaren, och vid tunnlad uppspelning byter ExoPlayer session.
    DisposableEffect(player, preferences.volumeBoost, preferences.tunneling) {
        val enhancer = if (
            preferences.volumeBoost == VolumeBoost.OFF || preferences.tunneling
        ) {
            null
        } else {
            // Vissa enheter saknar effekten helt och kastar då. Hellre ingen
            // förstärkning än en krasch vid uppspelningsstart.
            runCatching {
                LoudnessEnhancer(player.audioSessionId).apply {
                    setTargetGain(preferences.volumeBoost.millibels)
                    // setEnabled anropas som funktion: den returnerar en
                    // statuskod, och då godtar Kotlin inte "enabled = true".
                    setEnabled(true)
                }
            }.getOrNull()
        }
        onDispose { enhancer?.release() }
    }

    // Pausa nar appen hamnar i bakgrunden, annars fortsatter ljudet.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Helskarm. Utan den ritade Android sitt navigeringsfalt rakt ovanpa
    // knapparna — Pausa lag under Hem-knappen, sa ett tryck pa Pausa var ett
    // tryck pa Hem.
    DisposableEffect(view) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Liggande pa telefon. En film i portratt tar en tredjedel av skarmen.
    // Pa TV finns ingen orientering att byta.
    DisposableEffect(profile.isTv) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        if (!profile.isTv) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            if (!profile.isTv && activity != null && previous != null) {
                activity.requestedOrientation = previous
            }
        }
    }

    LaunchedEffect(player, playable?.url, attempt) {
        val url = playable?.url ?: return@LaunchedEffect
        error = null
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }

    val isLive = playable?.isLive != false
    LaunchedEffect(player, isLive, playable?.url) {
        if (isLive) return@LaunchedEffect
        while (true) {
            position = max(0L, player.currentPosition)
            duration = max(0L, player.duration)
            delay(500)
        }
    }

    // Panelen tonar bort — men inte medan ett sparval ar oppet.
    LaunchedEffect(controlsVisible, interactions, panel, preferences.infoPanelSeconds) {
        if (controlsVisible && panel == Panel.NONE) {
            delay(preferences.infoPanelSeconds * 1000L)
            controlsVisible = false
        }
    }

    // Fokus maste alltid ha ett hem. Nar panelen doljs forsvinner knappen som
    // hade fokus, och utan att ta tillbaka det gar fjarrkontrollen dod.
    LaunchedEffect(controlsVisible, panel) {
        withFrameNanos { }
        runCatching {
            when {
                panel != Panel.NONE -> panelFocus.requestFocus()
                controlsVisible && profile.isTv -> playFocus.requestFocus()
                !controlsVisible -> rootFocus.requestFocus()
            }
        }
    }

    // Back stanger ett oppet sparval innan det lamnar spelaren.
    BackHandler(enabled = panel != Panel.NONE) { panel = Panel.NONE }

    fun touched() {
        controlsVisible = true
        interactions++
    }

    fun togglePlayback() {
        if (player.playWhenReady) player.pause() else player.play()
        touched()
    }

    fun seekBy(deltaMs: Long) {
        if (isLive) return
        val end = max(0L, player.duration)
        val target = max(0L, player.currentPosition + deltaMs)
        player.seekTo(if (end > 0) min(target, end) else target)
        touched()
    }

    val audio = audioOptions(tracks)
    val subtitles = subtitleOptions(tracks)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Mediaknappar pa fjarren fungerar alltid, oavsett panel.
                when (event.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        togglePlayback(); return@onKeyEvent true
                    }
                    Key.MediaFastForward -> { seekBy(SEEK_STEP_MS); return@onKeyEvent true }
                    Key.MediaRewind -> { seekBy(-SEEK_STEP_MS); return@onKeyEvent true }
                }
                // Med panelen synlig styr D-paden knapparna, inte spolningen.
                // Annars gick det inte att flytta fokus mellan knapparna —
                // vanster och hoger spolade i stallet.
                if (panel != Panel.NONE) return@onKeyEvent false
                if (controlsVisible) {
                    touched()
                    return@onKeyEvent false
                }
                when (event.key) {
                    Key.DirectionLeft -> { seekBy(-SEEK_STEP_MS); true }
                    Key.DirectionRight -> { seekBy(SEEK_STEP_MS); true }
                    else -> { touched(); true }
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            // factory kors bara en gang. Utan update pekade vyn kvar pa den
            // slappta spelaren efter ett installningsbyte.
            update = { view ->
                view.player = player
                view.resizeMode = if (preferences.videoScale == VideoScale.ZOOM) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                view.subtitleView?.apply {
                    // Var stil vinner over strommens egen. Annars kan en fil
                    // med inbaddad gul text i Comic Sans kora over valet.
                    setApplyEmbeddedStyles(false)
                    setApplyEmbeddedFontSizes(false)
                    setFractionalTextSize(
                        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * preferences.subtitleSize.factor
                    )
                    setStyle(captionStyle(preferences.subtitleBackground))
                    // Undertexten lyfts nar kontrollerna syns, annars hamnar
                    // repliken bakom knapparna.
                    setBottomPaddingFraction(
                        if (controlsVisible) 0.30f else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
                    )
                }
            },
            onRelease = { it.player = null },
        )

        // Tryckyta ovanpa videon. PlayerView ar en Android-vy och kan sluka
        // beroringar innan Compose ser dem.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isLive) {
                    detectTapGestures(
                        onTap = {
                            if (panel != Panel.NONE) {
                                panel = Panel.NONE
                            } else if (controlsVisible) {
                                controlsVisible = false
                            } else {
                                touched()
                            }
                        },
                        onDoubleTap = { offset ->
                            if (!isLive) {
                                seekBy(if (offset.x < size.width / 2f) -SEEK_STEP_MS else SEEK_STEP_MS)
                            }
                        },
                    )
                },
        )

        AnimatedVisibility(
            visible = controlsVisible && error == null && !missing,
            // 180 ms: snabbt nog att kännas som svar på knapptrycket, långt
            // nog att inte blinka.
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
        ) {
            Controls(
                title = playable?.title.orEmpty(),
                subtitle = playable?.subtitle,
                isLive = isLive,
                playing = playing,
                position = position,
                duration = duration,
                showClock = preferences.showClock,
                hasAudioChoice = audio.size > 1,
                hasSubtitles = subtitles.size > 1,
                programme = programme,
                videoScaleLabel = preferences.videoScale.label,
                onVideoScale = {
                    viewModel.cycleVideoScale(preferences.videoScale)
                    touched()
                },
                playFocus = playFocus,
                onPlayPause = { togglePlayback() },
                onSeekBack = { seekBy(-SEEK_STEP_MS) },
                onSeekForward = { seekBy(SEEK_STEP_MS) },
                onAudio = { panel = Panel.AUDIO; touched() },
                onSubtitles = { panel = Panel.SUBTITLES; touched() },
                onBack = onBack,
            )
        }

        if (panel != Panel.NONE) {
            val isAudio = panel == Panel.AUDIO
            TrackPicker(
                title = if (isAudio) "Ljud" else "Undertext",
                options = if (isAudio) audio else subtitles,
                firstFocus = panelFocus,
                onPick = { option ->
                    selectTrack(
                        player = player,
                        type = if (isAudio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT,
                        option = option,
                    )
                    panel = Panel.NONE
                    touched()
                },
                onClose = { panel = Panel.NONE },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        if (missing) {
            PlayerMessage(
                title = "Hittades inte",
                message = "Innehållet finns inte kvar i biblioteket. Uppdatera spellistan.",
                onRetry = null,
                onBack = onBack,
            )
        }

        error?.let { message ->
            PlayerMessage(
                title = playable?.title.orEmpty().ifEmpty { "Uppspelning" },
                message = message,
                onRetry = { attempt++ },
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun Controls(
    title: String,
    subtitle: String?,
    isLive: Boolean,
    playing: Boolean,
    position: Long,
    duration: Long,
    showClock: Boolean,
    hasAudioChoice: Boolean,
    hasSubtitles: Boolean,
    programme: NowNext?,
    videoScaleLabel: String,
    onVideoScale: () -> Unit,
    playFocus: FocusRequester,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val minimal = LocalThemeStyle.current.minimal

    Box(Modifier.fillMaxSize()) {
        // Tva mjuka gradienter i stallet for en gra panel: texten blir
        // lasbar och resten av bilden syns.
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
        )

        // safeDrawingPadding haller knapparna fria fran systemfalt och
        // kamerahal — aven de ganger falten tillfalligt dras fram.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(metrics.screenEdge),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ControlButton(label = "Tillbaka", onClick = onBack)
                Spacer(Modifier.weight(1f))
                if (showClock) {
                    Clock(modifier = Modifier.padding(end = metrics.spaceMd))
                }
                if (hasAudioChoice) {
                    ControlButton(label = "Ljud", onClick = onAudio)
                    Spacer(Modifier.width(metrics.spaceSm))
                }
                if (hasSubtitles) {
                    ControlButton(
                        label = if (minimal) "Text" else "Undertext",
                        onClick = onSubtitles,
                    )
                    Spacer(Modifier.width(metrics.spaceSm))
                }
                ControlButton(
                    label = if (minimal) "Bild" else "Bild: $videoScaleLabel",
                    onClick = onVideoScale,
                )
            }

            Spacer(Modifier.weight(1f))

            if (isLive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm),
                ) {
                    Box(Modifier.size(10.dp).background(palette.signal, CircleShape))
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.signal,
                    )
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val current = programme?.current
            // programme != null behövs uttryckligen: att current inte är null
            // räcker inte för att Kotlin ska veta att programme inte är det.
            if (isLive && programme != null && current != null) {
                // Live med tablå: programmet är det intressanta, inte
                // kanalens grupp.
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = metrics.spaceXs),
                    horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = clockText(current.startUtc),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary,
                    )
                    AuroraProgress(
                        fraction = programme.progress(System.currentTimeMillis()) ?: 0f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = clockText(current.endUtc),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary,
                    )
                }
                programme.next?.let { next ->
                    Text(
                        text = "Sedan ${clockText(next.startUtc)}  ${next.title}",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = metrics.spaceXs),
                    )
                }
            } else if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textSecondary,
                )
            }

            // Tidslinjen finns bara nar det finns en tid. Live har ingen.
            if (!isLive && duration > 0) {
                Row(
                    modifier = Modifier.padding(top = metrics.spaceMd),
                    horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatTime(position),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary,
                    )
                    AuroraProgress(
                        fraction = position.toFloat() / duration,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatTime(duration),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = metrics.spaceMd),
                horizontalArrangement = Arrangement.spacedBy(
                    metrics.spaceMd,
                    Alignment.CenterHorizontally,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isLive) {
                    ControlButton(label = if (minimal) "-10" else "-10 s", onClick = onSeekBack)
                }
                ControlButton(
                    label = if (playing) "Pausa" else "Spela",
                    onClick = onPlayPause,
                    modifier = Modifier.focusRequester(playFocus),
                )
                if (!isLive) {
                    ControlButton(label = if (minimal) "+10" else "+10 s", onClick = onSeekForward)
                }
            }
        }
    }
}

@Composable
private fun Clock(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(10_000)
        }
    }
    val text = remember(now / 60_000) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = palette.textSecondary,
        modifier = modifier,
    )
}

/**
 * Samma knapp for finger och fjarrkontroll. Text i stallet for ikoner:
 * Materials karnuppsattning saknar en pausikon.
 */
@Composable
private fun ControlButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    FocusableSurface(
        onClick = onClick,
        focusScale = 1.06f,
        cornerRadius = metrics.radiusCard,
        modifier = modifier,
    ) { focused ->
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) palette.focus else palette.textPrimary,
            modifier = Modifier.padding(
                horizontal = metrics.spaceLg,
                vertical = metrics.spaceSm,
            ),
        )
    }
}

@Composable
private fun TrackPicker(
    title: String,
    options: List<TrackOption>,
    firstFocus: FocusRequester,
    onPick: (TrackOption) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(340.dp)
            .background(Color(0xF20A0C10))
            .safeDrawingPadding()
            .padding(metrics.spaceLg),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.textPrimary,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(metrics.spaceXs),
        ) {
            itemsIndexed(options) { index, option ->
                FocusableSurface(
                    onClick = { onPick(option) },
                    focusScale = 1f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                ) { focused ->
                    Row(
                        modifier = Modifier.padding(metrics.spaceMd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (option.selected) palette.signal else Color.Transparent,
                                    CircleShape,
                                )
                        )
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                focused -> palette.focus
                                option.selected -> palette.textPrimary
                                else -> palette.textSecondary
                            },
                        )
                    }
                }
            }
        }

        ControlButton(label = "Stäng", onClick = onClose)
    }
}

@Composable
private fun PlayerMessage(
    title: String,
    message: String,
    onRetry: (() -> Unit)?,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Box(
        modifier = Modifier.fillMaxSize().background(palette.scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(metrics.spaceMd),
            modifier = Modifier.safeDrawingPadding().padding(metrics.screenEdge),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = palette.textPrimary,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd)) {
                if (onRetry != null) ControlButton(label = "Försök igen", onClick = onRetry)
                ControlButton(label = "Tillbaka", onClick = onBack)
            }
        }
    }
}

/**
 * Undertextens utseende. Vit text alltid — den enda farg som syns pa
 * bade natt och snö. Det som skiljer ar hur texten skiljs fran bilden.
 */
@OptIn(UnstableApi::class)
private fun captionStyle(background: SubtitleBackground): CaptionStyleCompat {
    val white = 0xFFFFFFFF.toInt()
    val black = 0xFF000000.toInt()
    val clear = 0x00000000
    return when (background) {
        SubtitleBackground.NONE -> CaptionStyleCompat(
            white, clear, clear, CaptionStyleCompat.EDGE_TYPE_OUTLINE, black, null,
        )
        SubtitleBackground.SHADOW -> CaptionStyleCompat(
            white, clear, clear, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, black, null,
        )
        SubtitleBackground.BOX -> CaptionStyleCompat(
            white, 0xB3000000.toInt(), clear, CaptionStyleCompat.EDGE_TYPE_NONE, clear, null,
        )
    }
}

private fun clockText(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@OptIn(UnstableApi::class)
private fun explain(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    -> "Ingen kontakt med servern. Kontrollera nätverket."

    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "Servern nekade strömmen. Abonnemanget kan vara slut, eller så är " +
            "max antal samtidiga anslutningar nått."

    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    -> "Strömmen svarade med något som inte går att spela. Kanalen kan " +
        "vara nere hos leverantören."

    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    -> "Enheten klarar inte det här formatet."

    else -> "Gick inte att spela (kod ${e.errorCode})."
}

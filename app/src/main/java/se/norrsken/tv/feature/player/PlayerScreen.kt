package se.norrsken.tv.feature.player

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import kotlinx.coroutines.delay
import se.norrsken.tv.core.ui.component.FocusableSurface
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPreferences
import se.norrsken.tv.core.ui.theme.LocalPalette
import kotlin.math.max
import kotlin.math.min

private const val SEEK_STEP_MS = 10_000L

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val playable by viewModel.playable.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()

    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    // Varje tryck raknar upp den har. Utan den startar inte nedraakningen om
    // nar anvandaren gor nagot, sa panelen kan forsvinna mitt i ett tryck.
    var interactions by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    val focusRequester = remember { FocusRequester() }

    val preferences = LocalPreferences.current

    // Nycklad pa de varden som kraver en ny spelare. Andrar anvandaren
    // buffert eller avkodare byggs spelaren om — men bara da, sa ett byte av
    // accentfarg inte avbryter sandningen.
    val player = remember(
        preferences.buffer,
        preferences.decoder,
        preferences.matchFrameRate,
        preferences.userAgent,
        preferences.preferSurround,
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

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Utan det har fortsatter ljudet spelas nar anvandaren lamnar appen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // player maste vara nyckel: andras en uppspelningsinstallning byggs
    // spelaren om, och den nya har inget medium forran det har koret om.
    LaunchedEffect(player, playable?.url, attempt) {
        val url = playable?.url ?: return@LaunchedEffect
        error = null
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }

    LaunchedEffect(Unit) {
        // Samma falla som i sidomenyn: LaunchedEffect startar fore layouten,
        // och requestFocus mot en omonterad nod kastar. Har hade det blivit
        // en krasch i stallet for bara utebliven fokus.
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    // Positionen pollas bara for film och avsnitt. For live finns ingen
    // position att visa, och att fraga efter den varje halvsekund vore
    // arbete for ingenting.
    val isLive = playable?.isLive != false
    LaunchedEffect(player, isLive, playable?.url) {
        if (isLive) return@LaunchedEffect
        while (true) {
            position = max(0L, player.currentPosition)
            duration = max(0L, player.duration)
            delay(500)
        }
    }

    LaunchedEffect(controlsVisible, interactions, preferences.infoPanelSeconds) {
        if (controlsVisible) {
            delay(preferences.infoPanelSeconds * 1000L)
            controlsVisible = false
        }
    }

    fun touched() {
        controlsVisible = true
        interactions++
    }

    fun togglePlayback() {
        if (player.isPlaying) player.pause() else player.play()
        touched()
    }

    fun seekBy(deltaMs: Long) {
        if (isLive) return
        val target = player.currentPosition + deltaMs
        val end = max(0L, player.duration)
        player.seekTo(if (end > 0) min(max(0L, target), end) else max(0L, target))
        touched()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                        if (controlsVisible) togglePlayback() else touched()
                        true
                    }

                    Key.DirectionLeft -> {
                        seekBy(-SEEK_STEP_MS)
                        touched()
                        !isLive
                    }

                    Key.DirectionRight -> {
                        seekBy(SEEK_STEP_MS)
                        touched()
                        !isLive
                    }

                    else -> {
                        touched()
                        false
                    }
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    // Egna kontroller. PlayerViews standardpanel ritar en
                    // scrub-bar aven for live, dar den inte betyder nagot —
                    // ra TS-strommar har ingen kand langd, sa faltet hoppar.
                    useController = false
                    keepScreenOn = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            // factory kors bara en gang. Utan update-blocket pekade vyn kvar
            // pa den gamla spelaren efter varje installningsandring — alltsa
            // pa en instans som redan slappts.
            update = { view -> view.player = player },
            onRelease = { view -> view.player = null },
        )

        // Egen tryckyta ovanpa videon i stallet for pa rot-Boxen: PlayerView
        // ar en Android-vy och kan sluka beroringar innan Compose ser dem.
        // Knapparna nedan ligger senare i Boxen och far darfor tryck forst.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (controlsVisible) controlsVisible = false else touched()
                        },
                        onDoubleTap = { offset ->
                            // Dubbeltryck vanster/hoger halva spolar, som i de
                            // flesta spelare. Bara for film — live har ingen
                            // position att spola i.
                            if (!isLive) {
                                if (offset.x < size.width / 2f) {
                                    seekBy(-SEEK_STEP_MS)
                                } else {
                                    seekBy(SEEK_STEP_MS)
                                }
                            }
                        },
                    )
                }
        )

        AnimatedVisibility(
            visible = controlsVisible && error == null && !missing,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Controls(
                showClock = preferences.showClock,
                title = playable?.title.orEmpty(),
                subtitle = playable?.subtitle,
                isLive = isLive,
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                onPlayPause = { togglePlayback() },
                onSeekBack = { seekBy(-SEEK_STEP_MS) },
                onSeekForward = { seekBy(SEEK_STEP_MS) },
                onBack = onBack,
            )
        }

        if (missing) {
            PlayerMessage(
                title = "Hittades inte",
                message = "Innehallet finns inte kvar i biblioteket. Uppdatera spellistan.",
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
    showClock: Boolean,
    title: String,
    subtitle: String?,
    isLive: Boolean,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(metrics.screenEdge),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (showClock) {
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                // Panelen kan sta uppe i tio sekunder. En klocka som visar
                // tiden da den oppnades ar samre an ingen klocka.
                while (true) {
                    now = System.currentTimeMillis()
                    delay(10_000)
                }
            }
            Text(
                text = remember(now / 60_000) {
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(now))
                },
                style = MaterialTheme.typography.labelMedium,
                color = palette.textSecondary,
                modifier = Modifier.padding(bottom = metrics.spaceSm),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm),
        ) {
            if (isLive) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(palette.signal, CircleShape)
                )
                Text(
                    text = "LIVE",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.signal,
                )
            } else {
                Text(
                    text = if (isPlaying) "Spelar" else "Pausad",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary,
                )
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.textPrimary,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
            )
        }

        // Tidslinjen finns bara nar det finns en tid. For live ritas ingen
        // tom rand i stallet — den vore ren losning.
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
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(palette.hairline, RoundedCornerShape(2.dp))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(
                                (position.toFloat() / duration).coerceIn(0f, 1f)
                            )
                            .height(4.dp)
                            .background(palette.signal, RoundedCornerShape(2.dp))
                    )
                }
                Text(
                    text = formatTime(duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary,
                )
            }
        }

        // Knapparna finns for att panelen tidigare bara hade text. Pa en
        // telefon fanns det darfor ingen paus alls — och nar panelen tonat
        // bort ingen vag att fa tillbaka den.
        Row(
            modifier = Modifier.padding(top = metrics.spaceMd),
            horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(label = "Tillbaka", onClick = onBack)
            if (!isLive) {
                ControlButton(label = "−10 s", onClick = onSeekBack)
            }
            ControlButton(
                label = if (isPlaying) "Pausa" else "Spela",
                onClick = onPlayPause,
            )
            if (!isLive) {
                ControlButton(label = "+10 s", onClick = onSeekForward)
            }
        }
    }
}

/**
 * En knapp som bade gar att trycka pa och att na med D-pad.
 *
 * Text i stallet for ikoner: Materials karnuppsattning saknar en pausikon,
 * och en blandning av ikoner och text hade sett ut som ett misstag.
 */
@Composable
private fun ControlButton(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    FocusableSurface(
        onClick = onClick,
        focusScale = 1.04f,
        cornerRadius = metrics.radiusCard,
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
            modifier = Modifier.padding(metrics.screenEdge),
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
            if (onRetry != null) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.surfaceRaised,
                        contentColor = palette.textPrimary,
                    ),
                ) { Text("Forsok igen") }
            }
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.surface,
                    contentColor = palette.textSecondary,
                ),
            ) { Text("Tillbaka") }
        }
    }
}

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

/**
 * Ra ExoPlayer-fel sager ingenting for en anvandare.
 */
@OptIn(UnstableApi::class)
private fun explain(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    ->
        "Ingen kontakt med servern. Kontrollera natverket."

    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "Servern nekade strommen. Abonnemanget kan vara slut, eller sa ar " +
            "max antal samtidiga anslutningar natt."

    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    ->
        "Strommen svarade med nagot som inte gar att spela. Kanalen kan " +
            "vara nere hos leverantoren."

    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    ->
        "Enheten klarar inte det har formatet."

    else -> "Gick inte att spela (kod ${e.errorCode})."
}

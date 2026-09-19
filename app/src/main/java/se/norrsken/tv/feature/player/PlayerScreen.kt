package se.norrsken.tv.feature.player

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val channel by viewModel.channel.collectAsStateWithLifecycle()

    var error by remember { mutableStateOf<String?>(null) }
    // Ändras vid "Försök igen" och tvingar LaunchedEffect att köra om.
    var attempt by remember { mutableIntStateOf(0) }

    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(viewModel.streamSources.mediaSourceFactory())
            .build()
            .apply { playWhenReady = true }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = explain(e)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) error = null
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(channel?.id, attempt) {
        val url = channel?.streamUrl ?: return@LaunchedEffect
        error = null
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    // Annars slocknar skärmen mitt i en sändning.
                    keepScreenOn = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
        )

        error?.let { message ->
            PlaybackError(
                channelName = channel?.name.orEmpty(),
                message = message,
                onRetry = { attempt++ },
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun PlaybackError(
    channelName: String,
    message: String,
    onRetry: () -> Unit,
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
                text = channelName.ifEmpty { "Kanalen" },
                style = MaterialTheme.typography.headlineMedium,
                color = palette.textPrimary,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(metrics.spaceSm)) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.surfaceRaised,
                        contentColor = palette.textPrimary,
                    ),
                ) { Text("Försök igen") }
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
}

/**
 * Råa ExoPlayer-fel säger ingenting för en användare. Det här är minimum
 * tills diagnostikläget i Fas 10 finns — koden loggas, texten läses.
 */
@OptIn(UnstableApi::class)
private fun explain(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    ->
        "Ingen kontakt med servern. Kontrollera nätverket."

    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "Servern nekade strömmen. Abonnemanget kan vara slut, eller så är " +
            "max antal samtidiga anslutningar nått."

    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    ->
        "Strömmen svarade med något som inte går att spela. Kanalen kan " +
            "vara nere hos leverantören."

    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    ->
        "Enheten klarar inte den här kanalens format."

    else -> "Kanalen gick inte att spela (kod ${e.errorCode})."
}

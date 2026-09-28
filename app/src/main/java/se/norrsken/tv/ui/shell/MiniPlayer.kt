package se.norrsken.tv.ui.shell

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import se.norrsken.tv.core.device.LocalDeviceProfile
import se.norrsken.tv.core.ui.component.FocusableSurface
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.data.player.MiniPlayback

/**
 * Kanalen fortsätter i en liten ruta medan man bläddrar.
 *
 * Samma ström som nyss fyllde skärmen — ingen ny anslutning. Att i stället
 * förhandsvisa varje markerad kanal hade öppnat en uppkoppling per kanal,
 * och de flesta konton tillåter bara två eller tre samtidigt.
 *
 * OK eller ett tryck tar tillbaka den till helskärm. Knappen bredvid
 * stänger den.
 */
@OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(
    mini: MiniPlayback,
    player: ExoPlayer?,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onAppBackgrounded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val isTv = LocalDeviceProfile.current.isTv
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onAppBackgrounded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (player == null) return

    Column(
        modifier = modifier
            .padding(start = metrics.railCollapsed + metrics.spaceMd, top = metrics.spaceMd)
            .width(if (isTv) 280.dp else 168.dp),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceXs),
    ) {
        FocusableSurface(
            onClick = onOpen,
            cornerRadius = metrics.radiusCard,
            // fillMaxWidth, inte fillMaxSize: i en kolumn utan höjdgräns
            // svällde ytan ut över halva skärmen medan videon bara fyllde
            // sin sextondelsdel. Det var den grå lådan.
            modifier = Modifier.fillMaxWidth(),
        ) { _ ->
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        // Rutan är liten: kontroller i den går ändå inte att
                        // träffa, och de skulle täcka halva bilden.
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.aspectRatio(16f / 9f),
            )
        }

        Text(
            text = mini.title,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        FocusableSurface(onClick = onClose, focusScale = 1f) { focused ->
            Text(
                text = "Stäng",
                style = MaterialTheme.typography.labelMedium,
                color = if (focused) palette.focus else palette.textTertiary,
                modifier = Modifier
                    .background(palette.surface.copy(alpha = 0.6f))
                    .padding(horizontal = metrics.spaceSm, vertical = 2.dp),
            )
        }
    }
}

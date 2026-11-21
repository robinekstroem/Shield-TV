package se.norrsken.tv.shield

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.data.LiveChannel
import se.norrsken.tv.desktop.data.Programme
import se.norrsken.tv.desktop.ui.ChannelLogo
import se.norrsken.tv.desktop.ui.LocalPalette
import se.norrsken.tv.desktop.ui.tvEdges

/**
 * Kanallistan ovanpå bilden, som på en tv-box: OK eller vänster medan man
 * tittar tar fram den, pilarna bläddrar, OK byter kanal och Tillbaka
 * stänger. Visar kanalerna i samma lista som kanalen man tittar på, med vad
 * som sänds nu.
 */
@Composable
fun ChannelOverlay(state: AppState, onClose: () -> Unit) {
    val palette = LocalPalette.current
    val playing = state.nowPlaying ?: return
    val channels = playing.queue.ifEmpty { listOfNotNull(playing.channel) }
    if (channels.isEmpty()) return
    val currentIndex = channels.indexOfFirst { it.key == playing.channel?.key }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Vänta en bildruta, så att raden finns innan markeringen läggs på den.
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    val clock = state.clock
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(460.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(palette.background.copy(alpha = 0.97f), palette.background.copy(alpha = 0.90f)),
                    ),
                )
                .padding(top = 28.dp, bottom = 12.dp),
        ) {
            Text(
                playing.subtitle ?: "Kanaler",
                style = MaterialTheme.typography.titleLarge,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                "${channels.size} kanaler  ·  OK byter, Tillbaka stänger",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textTertiary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 10.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(channels) { index, channel ->
                    ChannelRow(
                        channel = channel,
                        current = index == currentIndex,
                        now = state.guide.current(channel, clock),
                        clock = clock,
                        // Markeringen stannar i listan: åt sidorna och förbi ändarna finns
                        // bara bilden och spelarens knappar, som inte ska nås härifrån.
                        modifier = (if (index == currentIndex) Modifier.focusRequester(requester) else Modifier)
                            .tvEdges(
                                blockUp = index == 0,
                                blockDown = index == channels.lastIndex,
                                blockLeft = true,
                                blockRight = true,
                            ),
                        onClick = {
                            if (index != currentIndex) state.playChannel(channel, channels)
                            onClose()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: LiveChannel,
    current: Boolean,
    now: Programme?,
    clock: Long,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                when {
                    focused -> palette.focus.copy(alpha = 0.20f)
                    current -> palette.focus.copy(alpha = 0.08f)
                    else -> Color.Transparent
                },
            )
            .then(if (focused) Modifier.border(2.dp, palette.focus, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            channel.number?.toString().orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = palette.textTertiary,
            maxLines = 1,
            modifier = Modifier.width(34.dp),
        )
        ChannelLogo(
            channel.logo,
            channel.name,
            width = 60.dp,
            height = 36.dp,
            initialsStyle = MaterialTheme.typography.labelLarge,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                channel.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (current) palette.signal else palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (now != null) {
                Text(
                    now.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val span = (now.stop - now.start).coerceAtLeast(1L)
                val done = ((clock - now.start).toFloat() / span).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.hairline),
                ) {
                    Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(palette.focus))
                }
            }
        }
    }
}

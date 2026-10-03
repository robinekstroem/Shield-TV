package se.norrsken.tv.desktop.ui

import se.norrsken.tv.desktop.data.ListKind
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.Screen
import se.norrsken.tv.desktop.data.LiveChannel
import se.norrsken.tv.desktop.data.Programme

@Composable
fun LiveTvScreen(state: AppState) {
    val library = state.library
    if (library.channels.isEmpty()) {
        NoLibrary(state)
        return
    }

    val group = state.liveGroup
    val query = state.liveQuery
    val channels = remember(library, group, query) { state.filteredChannels() }
    val listState = rememberLazyListState()
    val guide = state.guide
    val clock = state.clock
    LaunchedEffect(group, query) { listState.scrollToItem(0) }

    Row(Modifier.fillMaxSize()) {
        GroupColumn(
            allLabel = "Alla kanaler",
            total = library.channels.size,
            groups = library.liveGroups,
            counts = library.liveCounts,
            selected = group,
            onSelect = { state.liveGroup = it },
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ScreenHeader(
                title = group ?: "Alla kanaler",
                subtitle = "${channels.size.grouped()} kanaler",
                query = query,
                onQuery = { state.liveQuery = it },
                placeholder = "Sök kanal",
            )
            if (channels.isEmpty()) {
                EmptyState(
                    title = "Inga träffar",
                    body = if (query.isNotBlank()) "Ingen kanal heter något med \"${query.trim()}\"." else "Kategorin är tom.",
                )
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(start = 20.dp, end = 28.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(channels, key = { it.key }) { channel ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ChannelRow(
                                    channel = channel,
                                    showGroup = group == null,
                                    now = guide.current(channel, clock),
                                    next = guide.next(channel, clock),
                                    clock = clock,
                                    onClick = { state.playChannel(channel, channels) },
                                    modifier = Modifier.weight(1f),
                                )
                                FavoriteStar(
                                    saved = state.inMyList(channel.key),
                                    onClick = { state.toggleMyList(ListKind.CHANNEL, channel.key) },
                                )
                            }
                        }
                    }
                    Scrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 6.dp, bottom = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: LiveChannel,
    showGroup: Boolean,
    now: Programme?,
    next: Programme?,
    clock: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Pressable(onClick = onClick, modifier = modifier.fillMaxWidth()) { hovered ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                channel.number?.toString() ?: "",
                modifier = Modifier.width(44.dp),
                style = MaterialTheme.typography.labelMedium,
                color = palette.textTertiary,
                maxLines = 1,
            )
            // Loggor är ofta breda och på vit eller genomskinlig botten, så
            // de får en egen platta och skalas in — aldrig beskärs.
            Box(
                Modifier
                    .size(width = 72.dp, height = 42.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.surfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                RemoteImage(
                    url = channel.logo,
                    modifier = Modifier.fillMaxSize().padding(5.dp),
                    maxSide = 160,
                    contentScale = ContentScale.Fit,
                    placeholder = {
                        Text(
                            initials(channel.name),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textTertiary,
                        )
                    },
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (now != null) {
                    Text(
                        clockText(now.start) + "  " + now.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier
                                .width(96.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(palette.hairline),
                        ) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(now.progress(clock)).background(palette.textSecondary))
                        }
                        if (next != null) {
                            Text(
                                "Sedan " + clockText(next.start) + "  " + next.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else if (showGroup) {
                    Text(
                        channel.group,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (hovered) {
                Icon(Glyphs.Play, contentDescription = "Spela", tint = palette.textPrimary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Två bokstäver när loggan saknas: "SVT1 HD" blir "SV". */
internal fun initials(name: String): String =
    name.filter { it.isLetterOrDigit() }.take(2).uppercase().ifEmpty { "TV" }

@Composable
internal fun NoLibrary(state: AppState) {
    if (state.sources.isEmpty()) {
        EmptyState(
            title = "Inget bibliotek än",
            body = "Lägg till en spellista så hämtar appen kanaler, filmer och serier.",
            actionLabel = "Lägg till spellista",
            onAction = { state.screen = Screen.SETTINGS },
        )
    } else if (state.importingId != null) {
        EmptyState(title = "Hämtar biblioteket", body = state.importState.describe())
    } else {
        EmptyState(
            title = "Inga kanaler",
            body = "Källan gav inga kanaler. Uppdatera den under Inställningar, eller kontrollera adressen.",
            actionLabel = "Till Inställningar",
            actionIcon = Glyphs.Settings,
            onAction = { state.screen = Screen.SETTINGS },
        )
    }
}

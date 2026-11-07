package se.norrsken.tv.desktop.ui

import se.norrsken.tv.desktop.data.ListKind
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.data.LibraryFilter
import se.norrsken.tv.desktop.Screen
import se.norrsken.tv.desktop.data.LiveChannel
import se.norrsken.tv.desktop.data.Programme

/**
 * Live TV: kategorierna, kanalerna och — där det finns plats — en panel om
 * kanalen man står på: vad som går nu, hur länge det är kvar, vad det handlar
 * om och vad som kommer sedan. På tv:n glider kategorierna fram med pil
 * vänster, och kategorin byts medan man bläddrar bland dem.
 */
@Composable
fun LiveTvScreen(state: AppState) {
    val library = state.library
    if (library.channels.isEmpty()) {
        NoLibrary(state)
        return
    }

    val group = state.liveGroup
    val query = state.liveQuery
    val channels = remember(library, group, query, state.hiddenGroups) { state.filteredChannels() }
    val listState = rememberLazyListState()
    val guide = state.guide
    val clock = state.clock
    LaunchedEffect(group, query) { listState.scrollToItem(0) }
    // Tv: var markeringen landar från kategorierna.
    val entry = remember { TvEntry() }
    val scope = rememberCoroutineScope()
    // Kanalen panelen visar: den markerade på tv:n, den musen står på i Windows.
    // En kort paus först, så att panelen inte ritas om för varje rad man passerar.
    var activeKey by remember { mutableStateOf<String?>(null) }
    var shownKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeKey) {
        val key = activeKey ?: return@LaunchedEffect
        delay(140)
        shownKey = key
    }
    val shown = remember(channels, shownKey) { channels.firstOrNull { it.key == shownKey } ?: channels.firstOrNull() }

    Row(Modifier.fillMaxSize()) {
        GroupColumn(
            allLabel = "Alla kanaler",
            total = library.channels.size,
            groups = state.visibleGroups(LibraryFilter.LIVE, library.liveGroups),
            counts = library.liveCounts,
            selected = group,
            onSelect = { state.liveGroup = it },
            onEnterContent = {
                scope.launch { entry.enter { listState.layoutInfo.visibleItemsInfo.map { it.key } } }
            },
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val withPanel = maxWidth >= PANEL_FROM
            val listWidth = (maxWidth * (if (tvLayout) 0.46f else 0.52f)).coerceIn(480.dp, 720.dp)
            Row(Modifier.fillMaxSize()) {
                Column(
                    if (withPanel) Modifier.width(listWidth).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight(),
                ) {
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
                            TvScrolling(lead = 0.22f, trail = 0.22f) {
                                LazyColumn(
                                    state = listState,
                                    contentPadding = PaddingValues(start = if (tvLayout) 36.dp else 20.dp, end = if (withPanel) 12.dp else 28.dp, bottom = 24.dp),
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
                                                onActive = { if (!tvLayout) activeKey = channel.key },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .then(entry.mark(channel.key))
                                                    .onFocusChanged {
                                                        if (it.isFocused) {
                                                            activeKey = channel.key
                                                            entry.last = channel.key
                                                        }
                                                    },
                                            )
                                            FavoriteStar(
                                                saved = state.inMyList(channel.key),
                                                onClick = { state.toggleMyList(ListKind.CHANNEL, channel.key) },
                                            )
                                        }
                                    }
                                }
                            }
                            Scrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 6.dp, bottom = 12.dp))
                        }
                    }
                }
                if (withPanel && shown != null) {
                    ChannelInfo(state, shown, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/** Från den här bredden (utom kategorierna) får panelen om kanalen plats. */
private val PANEL_FROM = 960.dp

@Composable
private fun ChannelRow(
    channel: LiveChannel,
    showGroup: Boolean,
    now: Programme?,
    next: Programme?,
    clock: Long,
    onClick: () -> Unit,
    onActive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Pressable(onClick = onClick, modifier = modifier.fillMaxWidth()) { hovered ->
        LaunchedEffect(hovered) { if (hovered) onActive() }
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

/**
 * Panelen till höger: kanalen stort, programmet som går nu med tid kvar och
 * beskrivning, och de närmaste programmen efter det.
 */
@Composable
private fun ChannelInfo(state: AppState, channel: LiveChannel, modifier: Modifier) {
    val palette = LocalPalette.current
    val guide = state.guide
    val clock = state.clock
    val now = guide.current(channel, clock)
    val minute = clock / 60_000L
    val upcoming = remember(guide, channel, minute) {
        guide.schedule(channel).asSequence().filter { it.start >= clock }.take(5).toList()
    }
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .padding(start = 8.dp, end = 32.dp, top = 24.dp, bottom = 24.dp)
            .clip(shape)
            .background(palette.surface)
            .background(Brush.verticalGradient(listOf(palette.focus.copy(alpha = 0.06f), Color.Transparent)))
            .border(1.dp, palette.hairline, shape)
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 132.dp, height = 76.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.surfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                RemoteImage(
                    url = channel.logo,
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    maxSide = 320,
                    contentScale = ContentScale.Fit,
                    placeholder = {
                        Text(initials(channel.name), style = MaterialTheme.typography.headlineSmall, color = palette.textTertiary)
                    },
                )
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(channel.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(channel.number?.let { "Kanal $it" }, channel.group.ifBlank { null }).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (now == null) {
            Text(
                if (guide.hasSchedule(channel)) "Inget i tablån just nu." else "Ingen tablå för kanalen.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textTertiary,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag("NU", palette.signal)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        clockText(now.start) + "–" + clockText(now.stop),
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.textSecondary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(timeLeft(now, clock), style = MaterialTheme.typography.labelLarge, color = palette.textSecondary)
                }
                Text(now.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                now.subtitle?.ifBlank { null }?.let { subtitle ->
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.hairline),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(now.progress(clock)).background(palette.signal))
                }
            }
            now.description?.ifBlank { null }?.let { description ->
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                    maxLines = if (tvLayout) 5 else 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (upcoming.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("SEDAN", style = MaterialTheme.typography.labelSmall, color = palette.textTertiary)
                upcoming.forEach { programme ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            clockText(programme.start),
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.textTertiary,
                            modifier = Modifier.width(58.dp),
                        )
                        Text(
                            programme.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (tvLayout) {
            Text(
                "OK tittar  ·  pil höger: favorit  ·  pil vänster: kategorier",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "32 min kvar", "1 h 5 min kvar". */
private fun timeLeft(programme: Programme, clock: Long): String {
    val minutes = ((programme.stop - clock) / 60_000L).coerceAtLeast(0L)
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min kvar" else "$minutes min kvar"
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

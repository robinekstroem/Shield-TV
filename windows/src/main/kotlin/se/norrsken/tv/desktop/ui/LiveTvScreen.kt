package se.norrsken.tv.desktop.ui

import se.norrsken.tv.desktop.data.PersonalKeys

import se.norrsken.tv.desktop.data.ListKind
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.aspectRatio
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
 * Live TV: kategorierna, kanalerna med vad som sänds under varje, och — där
 * det finns plats — en stor ruta till höger. Spelas en kanal står bilden i
 * rutan medan man väljer bland kanalerna: Tillbaka från helskärm lägger den
 * där, med kanalerna till vänster. Annars visar rutan kanalen man står på.
 * På tv:n glider kategorierna fram med pil vänster, och kategorin byts
 * medan man bläddrar bland dem.
 *
 * På tv:n spelar rutan kanalen man står på, utan ljud, så fort markeringen
 * stannat en kort stund. OK väljer den — ljudet på, och den stannar i rutan
 * — och OK igen tar den till helskärm. Bläddrar man vidare följer rutan med.
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
    // Favoriter och Senast sedda följer listan och historiken.
    val personal = if (PersonalKeys.isPersonal(group)) state.history to state.myList else null
    val channels = remember(library, group, query, state.hiddenGroups, personal) { state.filteredChannels() }
    val listState = rememberLazyListState()
    val guide = state.guide
    val clock = state.clock
    LaunchedEffect(group, query) { listState.scrollToItem(0) }
    // Tv: var markeringen landar från kategorierna — och från helskärm: på kanalen som spelas.
    val entry = remember { TvEntry() }
    val scope = rememberCoroutineScope()
    val enterList: () -> Unit = {
        scope.launch {
            val playing = state.nowPlaying?.channel?.key
            val index = if (playing != null && state.miniPlayer) channels.indexOfFirst { it.key == playing } else -1
            if (index >= 0) {
                entry.last = playing
                if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                    listState.scrollToItem((index - 2).coerceAtLeast(0))
                }
            }
            entry.enter { listState.layoutInfo.visibleItemsInfo.map { it.key } }
        }
    }
    // Kanalen rutan visar: den markerade på tv:n, den musen står på i Windows.
    // En kort paus först, så att rutan inte ritas om för varje rad man passerar.
    var activeKey by remember { mutableStateOf<String?>(null) }
    var shownKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeKey) {
        val key = activeKey ?: return@LaunchedEffect
        delay(140)
        shownKey = key
    }
    val shown = remember(channels, shownKey) { channels.firstOrNull { it.key == shownKey } ?: channels.firstOrNull() }
    // Tv: lämnar man Live TV stängs en kanal som bara visades i rutan, utan att man valt den.
    if (tvLayout) {
        DisposableEffect(Unit) { onDispose { state.leaveStage() } }
    }

    Row(Modifier.fillMaxSize()) {
        // Tv: den första kanalens logga bredvid varje kategori.
        val covers = if (tvLayout) rememberInBackground(library) { state.categoryCovers(LibraryFilter.LIVE) } else null
        GroupColumn(
            allLabel = "Alla kanaler",
            total = library.channels.size,
            groups = state.visibleGroups(LibraryFilter.LIVE, library.liveGroups),
            counts = library.liveCounts,
            selected = group,
            onSelect = { state.liveGroup = it },
            pinned = personalPinned(state, LibraryFilter.LIVE),
            onEnterContent = enterList,
            look = GroupLook(MenuAccents.live, "Live TV", Glyphs.Live),
            covers = covers.orEmpty(),
            coverStyle = CoverStyle.LOGOS,
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val withStage = maxWidth >= STAGE_FROM
            val listWidth = (maxWidth * (if (tvLayout) 0.42f else 0.48f)).coerceIn(460.dp, 680.dp)
            // Tv: rutan spelar kanalen man står på när markeringen stannat en stund —
            // inte varje kanal man passerar när man bläddrar fort.
            if (tvLayout && withStage) {
                LaunchedEffect(activeKey) {
                    val key = activeKey ?: return@LaunchedEffect
                    delay(STAGE_PREVIEW_MS)
                    channels.firstOrNull { it.key == key }?.let { state.previewInStage(it, channels) }
                }
            }
            Row(Modifier.fillMaxSize()) {
                Column(
                    if (withStage) Modifier.width(listWidth).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight(),
                ) {
                    ScreenHeader(
                        title = PersonalKeys.titleOf(group) ?: group ?: "Alla kanaler",
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
                                    contentPadding = PaddingValues(start = if (tvLayout) 36.dp else 20.dp, end = if (withStage) 12.dp else 28.dp, bottom = 24.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    items(channels, key = { it.key }) { channel ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            ChannelRow(
                                                channel = channel,
                                                showGroup = group == null || PersonalKeys.isPersonal(group),
                                                now = guide.current(channel, clock),
                                                next = guide.next(channel, clock),
                                                clock = clock,
                                                onClick = {
                                                    if (tvLayout && withStage) {
                                                        // Tv: OK väljer kanalen i rutan, OK igen ger helskärm.
                                                        state.chooseInStage(channel, channels)
                                                    } else {
                                                        // Bilden står i den stora rutan: en annan kanal byts där, och
                                                        // kanalen som redan spelas går till helskärm.
                                                        val inStage = withStage && state.miniPlayer &&
                                                            state.nowPlaying?.live == true && state.footballPreview == null
                                                        state.playChannel(channel, channels, docked = inStage)
                                                    }
                                                },
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
                if (withStage) {
                    LiveStage(state, shown, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/** Från den här bredden (utom kategorierna) får den stora rutan plats. */
private val STAGE_FROM = 960.dp

/** Tv: så länge markeringen ska stå still på en kanal innan rutan börjar spela den. */
private const val STAGE_PREVIEW_MS = 450L

/**
 * Live TV:s stora ruta medan den visas, i fönstrets bildpunkter; null
 * annars. Spelaren lägger den lilla bilden här i stället för i hörnet
 * (se PlayerHost), och glider mellan dem när man kommer och går.
 */
internal object LiveBox {
    var bounds by mutableStateOf<Rect?>(null)
}

/** Rutans hörn, också för bilden som ligger i den. */
internal val LIVE_STAGE_RADIUS = 18.dp

/**
 * Den stora rutan till höger, i 16:9. Spelas något ligger bilden här (den
 * ritas av spelaren, ovanpå). Annars kanalen man står på: loggan stort och
 * vad som sänds nu — mer än så står redan under varje kanal i listan.
 */
@Composable
private fun LiveStage(state: AppState, channel: LiveChannel?, modifier: Modifier) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(LIVE_STAGE_RADIUS)
    DisposableEffect(Unit) { onDispose { LiveBox.bounds = null } }
    Column(
        modifier.padding(
            start = 12.dp,
            end = if (tvLayout) 44.dp else 28.dp,
            top = if (tvLayout) 34.dp else 28.dp,
            bottom = 24.dp,
        ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onGloballyPositioned { coordinates ->
                    val at = coordinates.positionInWindow()
                    val bounds = Rect(at.x, at.y, at.x + coordinates.size.width, at.y + coordinates.size.height)
                    if (LiveBox.bounds != bounds) LiveBox.bounds = bounds
                }
                .clip(shape)
                .background(palette.surface)
                .border(1.dp, palette.hairline, shape),
        ) {
            if (state.nowPlaying == null && channel != null) ChannelStage(state, channel)
        }
        if (tvLayout) {
            Spacer(Modifier.height(16.dp))
            val staged = state.stagedChannelKey()
            val chosen = staged != null && state.stageChosen == staged
            Text(
                if (chosen) {
                    "OK eller Play: helskärm  ·  pil upp och ner byter kanal i rutan"
                } else {
                    "OK väljer kanalen  ·  OK igen: helskärm  ·  pil höger: favorit"
                },
                style = MaterialTheme.typography.labelMedium,
                color = palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/**
 * Tv: rutan medan kanalen startar — samma som utan bild, med en liten
 * snurra, så att man ser vilken kanal det blir direkt (se PlayerLayers).
 */
@Composable
internal fun StageStarting(state: AppState, channel: LiveChannel) {
    // Täckande: bakom kan förra kanalens sista bild ligga kvar tills den nya kommer.
    Box(Modifier.fillMaxSize().background(LocalPalette.current.surface)) {
        ChannelStage(state, channel)
        Spinner(Modifier.align(Alignment.TopEnd).padding(18.dp), size = 26.dp)
    }
}

/** Rutan utan bild: kanalens logga stort, och vad som sänds nu med hur långt det kommit. */
@Composable
private fun ChannelStage(state: AppState, channel: LiveChannel) {
    val palette = LocalPalette.current
    val clock = state.clock
    val now = state.guide.current(channel, clock)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(palette.focus.copy(alpha = 0.14f), Color.Transparent))),
    ) {
        ChannelLogo(
            channel.logo,
            channel.name,
            width = if (tvLayout) 300.dp else 240.dp,
            height = if (tvLayout) 150.dp else 120.dp,
            initialsStyle = MaterialTheme.typography.displaySmall,
            modifier = Modifier.align(Alignment.Center).padding(bottom = 40.dp),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(channel.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag("NU", palette.signal)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        clockText(now.start) + "–" + clockText(now.stop) + "   " + now.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(timeLeft(now, clock), style = MaterialTheme.typography.labelLarge, color = palette.textTertiary, maxLines = 1)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.hairline),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(now.progress(clock)).background(palette.signal))
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
            // Loggorna lika stora och utan platta bakom (se ChannelLogo).
            ChannelLogo(channel.logo, channel.name, width = 72.dp, height = 40.dp)
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

/** "32 min kvar", "1 h 5 min kvar". */
internal fun timeLeft(programme: Programme, clock: Long): String {
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

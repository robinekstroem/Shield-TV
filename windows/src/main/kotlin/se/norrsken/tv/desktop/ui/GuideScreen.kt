package se.norrsken.tv.desktop.ui

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.data.LibraryFilter
import se.norrsken.tv.desktop.data.LiveChannel
import se.norrsken.tv.desktop.data.Programme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val HOUR = 3_600_000L
private val HourWidth = 280.dp
private val LabelWidth = 232.dp
private val RowHeight = 60.dp

private val swedishLocale: Locale = Locale.forLanguageTag("sv-SE")
private val clockPattern: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dayPattern: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", swedishLocale)
private val shortDayPattern: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", swedishLocale)

private fun zoned(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

/** "19:30" i datorns tidszon. */
internal fun clockText(ms: Long): String = zoned(ms).format(clockPattern)

/** "Onsdag 30 september". */
internal fun dayText(ms: Long): String = zoned(ms).format(dayPattern).replaceFirstChar { it.uppercase() }

private fun sameDay(a: Long, b: Long): Boolean = zoned(a).toLocalDate() == zoned(b).toLocalDate()

/** "19:30–20:00 · 30 min", med dag framför om det inte är i dag. */
internal fun programmeTimes(programme: Programme, now: Long): String = buildString {
    if (!sameDay(programme.start, now)) append(zoned(programme.start).format(shortDayPattern)).append("  ")
    append(clockText(programme.start)).append("–").append(clockText(programme.stop))
    append("  ·  ").append(programme.minutes).append(" min")
    if (programme.isOn(now)) {
        val left = ((programme.stop - now + 59_999L) / 60_000L).coerceAtLeast(1L)
        append("  ·  ").append(left).append(" min kvar")
    }
}

private class GuideSelection(val channel: LiveChannel, val programme: Programme)

private fun widthOf(ms: Long): Dp = (ms.toFloat() / HOUR * HourWidth.value).dp

@Composable
fun GuideScreen(state: AppState) {
    val library = state.library
    if (library.channels.isEmpty()) {
        NoLibrary(state)
        return
    }
    val palette = LocalPalette.current
    val guide = state.guide
    val group = state.liveGroup
    val query = state.liveQuery
    val onlyWithGuide = state.guideOnlyWithData
    val clock = state.clock
    val channels = remember(library, group, query, state.hiddenGroups) { state.filteredChannels() }
    val rows = remember(channels, guide, onlyWithGuide) {
        if (onlyWithGuide) channels.filter { guide.hasSchedule(it) } else channels
    }

    // Fönstret ligger still medan skärmen visas: två timmar bakåt, ett dygn och
    // lite till framåt. Flyttas det varje timme hoppar rutnätet under musen.
    val windowStart = remember { clock - clock % HOUR - 2 * HOUR }
    val windowEnd = windowStart + 32 * HOUR
    val scroll = rememberScrollState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var selected by remember { mutableStateOf<GuideSelection?>(null) }
    // Tv: var markeringen landar från kategorierna — programmet som sänds nu på
    // kanalen man senast stod på, eller på den första som syns.
    val entry = remember { TvEntry() }

    fun pixels(ms: Long): Int = with(density) { widthOf(ms).toPx() }.roundToInt()
    fun scrollToNow() {
        val target = pixels(System.currentTimeMillis() - windowStart - HOUR / 2).coerceAtLeast(0)
        scope.launch { scroll.animateScrollTo(target) }
    }
    fun moveHours(hours: Int) {
        scope.launch { scroll.animateScrollTo((scroll.value + pixels(hours * HOUR)).coerceAtLeast(0)) }
    }

    LaunchedEffect(Unit) {
        scroll.scrollTo(pixels(System.currentTimeMillis() - windowStart - HOUR / 2).coerceAtLeast(0))
    }
    LaunchedEffect(group, query, onlyWithGuide) { listState.scrollToItem(0) }

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
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ScreenHeader(
                title = group ?: "Tablå",
                subtitle = dayText(clock) + "  ·  " + rows.size.grouped() + " kanaler",
                query = query,
                onQuery = { state.liveQuery = it },
                placeholder = "Sök kanal",
            )
            if (guide.isEmpty) {
                GuideMissing(state)
                return@Column
            }

            GuideInfo(
                selection = selected,
                now = clock,
                onPlay = { channel -> state.playChannel(channel, rows) },
            )

            Row(
                Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("Nu", selected = false, onClick = { scrollToNow() })
                IconAction(Glyphs.SkipPrevious, "Tre timmar bakåt", onClick = { moveHours(-3) }, size = 36.dp, iconSize = 20.dp)
                IconAction(Glyphs.SkipNext, "Tre timmar framåt", onClick = { moveHours(3) }, size = 36.dp, iconSize = 20.dp)
                Spacer(Modifier.weight(1f))
                Chip(
                    "Bara kanaler med tablå",
                    selected = onlyWithGuide,
                    onClick = { state.guideOnlyWithData = !onlyWithGuide },
                )
            }

            TimeRuler(windowStart, windowEnd, scroll, clock)

            if (rows.isEmpty()) {
                EmptyState(
                    title = "Ingen tablå här",
                    body = "Inga kanaler i urvalet har tablå. Slå av \"Bara kanaler med tablå\" för att se alla.",
                )
                return@Column
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 20.dp, end = 28.dp, bottom = 8.dp),
                ) {
                    items(rows, key = { it.key }) { channel ->
                        GuideRow(
                            channel = channel,
                            programmes = guide.schedule(channel),
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            scroll = scroll,
                            now = clock,
                            // Tv: markeringen visar redan vilket program det är; raderna ritas
                            // då inte om för varje steg, bara rutan överst.
                            selected = if (tvLayout) null else selected,
                            onSelect = { programme -> selected = GuideSelection(channel, programme) },
                            onPlay = { state.playChannel(channel, rows) },
                            entryMark = entry.mark(channel.key),
                            onFocused = { entry.last = channel.key },
                        )
                    }
                }
                Scrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 6.dp))
            }
            HorizontalBar(scroll, modifier = Modifier.fillMaxWidth().padding(start = LabelWidth + 20.dp, end = 28.dp, top = 4.dp, bottom = 10.dp))
        }
    }
}

/** Det valda programmet i klartext, med knapp om det sänds nu. */
@Composable
private fun GuideInfo(selection: GuideSelection?, now: Long, onPlay: (LiveChannel) -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .height(132.dp)
            .clip(shape)
            .background(palette.surface)
            .border(1.dp, palette.hairline, shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (selection == null) {
            Text(
                if (tvLayout) {
                    "Gå runt i tablån med pilarna för att läsa om programmen. OK på det som sänds nu, eller på kanalen till vänster, börjar titta."
                } else {
                    "Klicka på ett program för att läsa mer om det. Klicka på en kanal till vänster för att titta."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textTertiary,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            return@Box
        }
        val programme = selection.programme
        val onAir = programme.isOn(now)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onAir) Tag("SÄNDS NU", palette.signal)
                    Text(selection.channel.name, style = MaterialTheme.typography.labelMedium, color = palette.textSecondary, maxLines = 1)
                    programme.category?.let {
                        Text("·  $it", style = MaterialTheme.typography.labelMedium, color = palette.textTertiary, maxLines = 1)
                    }
                }
                Text(
                    programme.title + (programme.subtitle?.let { "  —  $it" } ?: ""),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(programmeTimes(programme, now), style = MaterialTheme.typography.bodySmall, color = palette.textTertiary)
                programme.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onAir) {
                Spacer(Modifier.width(16.dp))
                PrimaryButton("Titta", onClick = { onPlay(selection.channel) }, icon = Glyphs.Play)
            }
        }
    }
}

@Composable
private fun TimeRuler(windowStart: Long, windowEnd: Long, scroll: ScrollState, now: Long) {
    val palette = LocalPalette.current
    val halfHours = ((windowEnd - windowStart) / (HOUR / 2)).toInt()
    Row(Modifier.fillMaxWidth().height(28.dp).padding(start = 20.dp, end = 28.dp)) {
        Spacer(Modifier.width(LabelWidth))
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
            Box(Modifier.width(widthOf(windowEnd - windowStart)).fillMaxHeight()) {
                for (i in 0 until halfHours) {
                    val time = windowStart + i * (HOUR / 2)
                    val fullHour = i % 2 == 0
                    val midnight = fullHour && zoned(time).hour == 0
                    Text(
                        if (midnight) zoned(time).format(shortDayPattern) else clockText(time),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fullHour) palette.textSecondary else palette.textTertiary,
                        modifier = Modifier.align(Alignment.CenterStart).offset(x = widthOf(time - windowStart) + 6.dp),
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = widthOf(time - windowStart))
                            .width(1.dp)
                            .height(if (fullHour) 10.dp else 5.dp)
                            .background(palette.hairline),
                    )
                }
                NowMarker(widthOf(now - windowStart))
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: LiveChannel,
    programmes: List<Programme>,
    windowStart: Long,
    windowEnd: Long,
    scroll: ScrollState,
    now: Long,
    selected: GuideSelection?,
    onSelect: (Programme) -> Unit,
    onPlay: () -> Unit,
    /** Tv: hit kan markeringen gå in från kategorierna (se TvEntry). */
    entryMark: Modifier,
    onFocused: () -> Unit,
) {
    val palette = LocalPalette.current
    // Tv: programmet som sänds nu tar emot markeringen från kategorierna; utan ett sådant kanalen.
    val onAir = programmes.firstOrNull { it.isOn(now) && it.stop > windowStart && it.start < windowEnd }
    Row(Modifier.fillMaxWidth().height(RowHeight).padding(vertical = 2.dp)) {
        ChannelCell(channel, onPlay, focus = (if (onAir == null) entryMark else Modifier).onFocusChanged { if (it.isFocused) onFocused() })
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
            Box(Modifier.width(widthOf(windowEnd - windowStart)).fillMaxHeight()) {
                if (programmes.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize().padding(end = 2.dp).clip(RoundedCornerShape(8.dp)).background(palette.surface),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            "Ingen tablå för kanalen",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textTertiary,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                } else {
                    for (programme in programmes) {
                        if (programme.stop <= windowStart || programme.start >= windowEnd) continue
                        val from = maxOf(programme.start, windowStart)
                        val to = minOf(programme.stop, windowEnd)
                        ProgrammeBlock(
                            programme = programme,
                            selected = selected?.programme === programme,
                            onAir = programme.isOn(now),
                            past = programme.stop <= now,
                            modifier = Modifier.offset(x = widthOf(from - windowStart)).width(widthOf(to - from)).fillMaxHeight(),
                            // Tv: rutan överst följer markeringen; OK på det som sänds nu börjar titta.
                            focus = (if (programme === onAir) entryMark else Modifier).onFocusChanged {
                                if (it.isFocused) {
                                    onFocused()
                                    if (tvLayout) onSelect(programme)
                                }
                            },
                            onClick = { if (tvLayout && programme.isOn(now)) onPlay() else onSelect(programme) },
                        )
                    }
                }
                NowMarker(widthOf(now - windowStart))
            }
        }
    }
}

@Composable
private fun ChannelCell(channel: LiveChannel, onPlay: () -> Unit, focus: Modifier = Modifier) {
    val palette = LocalPalette.current
    Pressable(onClick = onPlay, modifier = focus.width(LabelWidth).fillMaxHeight().padding(end = 6.dp)) { hovered ->
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(
                channel.logo,
                channel.name,
                width = 52.dp,
                height = 30.dp,
                initialsStyle = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                channel.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (hovered) {
                Icon(Glyphs.Play, contentDescription = "Titta", tint = palette.textPrimary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ProgrammeBlock(
    programme: Programme,
    selected: Boolean,
    onAir: Boolean,
    past: Boolean,
    modifier: Modifier,
    /** Läggs på själva rutan (markering och vart den kan gå in). */
    focus: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.padding(end = 2.dp)) {
        Pressable(
            onClick = onClick,
            selected = selected,
            shape = shape,
            modifier = focus
                .fillMaxSize()
                .background(if (onAir) palette.surfaceRaised else palette.surface, shape)
                .then(if (selected) Modifier.border(1.dp, palette.focus, shape) else Modifier),
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    programme.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (past) palette.textTertiary else palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    clockText(programme.start),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Linjen som visar nu, genom linjalen och alla rader. */
@Composable
private fun NowMarker(offset: Dp) {
    val palette = LocalPalette.current
    Box(Modifier.offset(x = offset).width(2.dp).fillMaxHeight().glow(palette.glowOf(0.9f), RectangleShape, 10.dp).background(palette.signal))
}

@Composable
private fun GuideMissing(state: AppState) {
    val lines = state.sources.mapNotNull { source -> state.guideStatus[source.id]?.let { "${source.name}: $it" } }
    EmptyState(
        title = if (state.guideLoading) "Hämtar tablån" else "Ingen tablå än",
        body = if (state.guideLoading) {
            "Första gången kan det ta en minut — tablåfilerna från leverantörer är stora."
        } else {
            (listOf("Tablån hämtas från din leverantör.") + lines).joinToString("\n")
        },
        actionLabel = if (state.guideLoading) null else "Hämta tablån",
        actionIcon = Glyphs.Refresh,
        onAction = { state.refreshGuides() },
    )
}

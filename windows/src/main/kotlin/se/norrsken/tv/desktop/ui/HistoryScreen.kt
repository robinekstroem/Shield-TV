package se.norrsken.tv.desktop.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.data.WatchEntry
import se.norrsken.tv.desktop.data.WatchKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val swedishDates: Locale = Locale.forLanguageTag("sv-SE")
private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", swedishDates)
private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", swedishDates)

/**
 * Historik: det man tittat på, senast först, uppdelat per dag. Filmer och
 * avsnitt visar hur långt man kom och fortsätter därifrån vid klick.
 */
@Composable
fun HistoryScreen(state: AppState) {
    val palette = LocalPalette.current
    var kindFilter by remember { mutableStateOf<WatchKind?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    // Rensa kräver två klick; det andra måste komma inom fyra sekunder.
    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            delay(4_000)
            confirmClear = false
        }
    }
    val entries = state.history.filterNot { it.upNext }
    val days = remember(entries, kindFilter) {
        entries.filter { kindFilter == null || it.kind == kindFilter }.groupBy { dayLabel(it.watchedAtUtc) }
    }
    val listState = rememberLazyListState()
    // Tv: tappas markeringen (till exempel när spelaren stängs) hamnar den på raden man senast stod på.
    val rowFocus = remember { TvEntry() }
    val scope = rememberCoroutineScope()
    TvFocusFallback {
        scope.launch {
            rowFocus.enter {
                listState.layoutInfo.visibleItemsInfo.mapNotNull { info -> (info.key as? String)?.takeIf { !it.startsWith("dag:") } }
            }
        }
        days.isNotEmpty()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Historik", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Det du har tittat på, senast först. Filmer och avsnitt fortsätter där du slutade.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textTertiary,
                )
            }
            if (entries.isNotEmpty()) {
                SecondaryButton(
                    text = if (confirmClear) "Bekräfta" else "Rensa historik",
                    icon = Glyphs.Delete,
                    color = if (confirmClear) palette.alert else palette.textPrimary,
                    onClick = {
                        if (confirmClear) {
                            state.clearHistory()
                            confirmClear = false
                        } else {
                            confirmClear = true
                        }
                    },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("Allt", selected = kindFilter == null, onClick = { kindFilter = null })
            Chip("Live TV", selected = kindFilter == WatchKind.LIVE, onClick = { kindFilter = WatchKind.LIVE })
            Chip("Filmer", selected = kindFilter == WatchKind.MOVIE, onClick = { kindFilter = WatchKind.MOVIE })
            Chip("Serier", selected = kindFilter == WatchKind.EPISODE, onClick = { kindFilter = WatchKind.EPISODE })
        }
        state.historyMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.alert,
                modifier = Modifier.padding(start = 28.dp, bottom = 8.dp),
            )
        }
        if (days.isEmpty()) {
            EmptyState(
                title = "Ingen historik än",
                body = "Det du tittar på hamnar här, så att du lätt hittar tillbaka. Filmer och avsnitt fortsätter där du slutade.",
            )
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 20.dp, end = 28.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    days.forEach { (day, watched) ->
                        item(key = "dag:$day") {
                            Text(
                                day.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textTertiary,
                                modifier = Modifier.padding(start = 10.dp, top = 16.dp, bottom = 6.dp),
                            )
                        }
                        items(watched, key = { it.identity }) { entry ->
                            HistoryRow(
                                entry = entry,
                                onPlay = { state.playFromHistory(entry) },
                                onRemove = { state.removeFromHistory(entry) },
                                focus = rowFocus.mark(entry.identity).onFocusChanged { if (it.isFocused) rowFocus.last = entry.identity },
                            )
                        }
                    }
                }
                Scrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 6.dp, bottom = 12.dp))
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: WatchEntry, onPlay: () -> Unit, onRemove: () -> Unit, focus: Modifier = Modifier) {
    if (!tvLayout) {
        HistoryRowContent(entry, onPlay, onRemove, focus)
        return
    }
    // Tv: Ta bort står bredvid raden och syns alltid, så att pil höger når den.
    // Inne i raden går den inte att markera.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { HistoryRowContent(entry, onPlay, onRemove = null, focus = focus) }
        Spacer(Modifier.width(10.dp))
        IconAction(Glyphs.Close, "Ta bort ur historiken", onClick = onRemove, size = 40.dp, iconSize = 20.dp)
    }
}

/** Raden: bild, titel och läge. [onRemove]: krysset i raden när musen står på den (datorn). */
@Composable
private fun HistoryRowContent(entry: WatchEntry, onPlay: () -> Unit, onRemove: (() -> Unit)?, focus: Modifier = Modifier) {
    val palette = LocalPalette.current
    val live = entry.kind == WatchKind.LIVE
    Pressable(onClick = onPlay, modifier = focus.fillMaxWidth()) { hovered ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Kanaler: loggan inpassad. Film och avsnitt: bilden beskuren, med förloppet under.
            Box(
                Modifier
                    .size(width = if (live) 96.dp else 128.dp, height = 72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.surfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                RemoteImage(
                    url = entry.image,
                    modifier = Modifier.fillMaxSize().padding(if (live) 8.dp else 0.dp),
                    maxSide = 320,
                    contentScale = if (live) ContentScale.Fit else ContentScale.Crop,
                    placeholder = {
                        Text(initials(entry.title), style = MaterialTheme.typography.labelMedium, color = palette.textTertiary)
                    },
                )
                if (!live && entry.durationMs > 0) {
                    Box(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.55f)),
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(entry.progress).background(palette.focus))
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live) {
                        Tag("LIVE", palette.signal)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                entry.subtitle?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(statusOf(entry), style = MaterialTheme.typography.labelSmall, color = palette.textTertiary)
            }
            if (hovered) {
                Text(
                    if (entry.resumable) "Fortsätt" else "Spela",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.focus,
                )
                if (onRemove != null) {
                    Spacer(Modifier.width(8.dp))
                    IconAction(Glyphs.Close, "Ta bort ur historiken", onClick = onRemove, size = 34.dp, iconSize = 18.dp)
                }
            }
        }
    }
}

/** "Idag", "Igår", annars veckodag och datum: "Tisdag 29 september". */
private fun dayLabel(utc: Long): String {
    val date = Instant.ofEpochMilli(utc).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "Idag"
        today.minusDays(1) -> "Igår"
        else -> date.format(dayFormat).replaceFirstChar { it.uppercase() }
    }
}

private fun statusOf(entry: WatchEntry): String {
    val time = Instant.ofEpochMilli(entry.watchedAtUtc).atZone(ZoneId.systemDefault()).format(clockFormat)
    val finished = entry.durationMs > 0 && entry.positionMs >= entry.durationMs * 0.95
    return when {
        entry.kind == WatchKind.LIVE -> "Kl. $time"
        entry.resumable && entry.durationMs > 0 ->
            "Kl. $time · ${((entry.durationMs - entry.positionMs) / 60_000).coerceAtLeast(1)} min kvar"
        finished -> "Kl. $time · Sedd"
        else -> "Kl. $time"
    }
}

package se.norrsken.tv.feature.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import se.norrsken.tv.core.device.LocalDeviceProfile
import se.norrsken.tv.core.ui.component.AuroraLoader
import se.norrsken.tv.core.ui.component.AuroraProgress
import se.norrsken.tv.core.ui.component.FocusableSurface
import se.norrsken.tv.core.ui.component.PrimaryAction
import se.norrsken.tv.core.ui.component.SectionHeading
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.data.db.dao.ChannelDao
import se.norrsken.tv.data.db.dao.EPG_CHUNK
import se.norrsken.tv.data.db.dao.EpgDao
import se.norrsken.tv.data.db.entity.ChannelEntity
import se.norrsken.tv.data.db.entity.ContentKind
import se.norrsken.tv.data.epg.EpgCoordinator
import se.norrsken.tv.data.epg.EpgState
import se.norrsken.tv.data.repository.CategoryVisibility
import se.norrsken.tv.data.settings.SettingsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
/** Tak för antal rader. Över det blir tablån en lista ingen scrollar igenom. */
private const val GUIDE_CHANNELS = 300

data class GuideProgramme(
    val id: Long,
    val title: String,
    val description: String?,
    val start: Long,
    val end: Long,
    val category: String?,
    val episode: String?,
)

data class GuideRow(val channel: ChannelEntity, val programmes: List<GuideProgramme>)

data class GuideState(
    val rows: List<GuideRow> = emptyList(),
    val windowStart: Long = 0L,
    val windowEnd: Long = 0L,
    val loading: Boolean = true,
) {
    val hasProgrammes: Boolean get() = rows.any { it.programmes.isNotEmpty() }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuideViewModel @Inject constructor(
    private val channelDao: ChannelDao,
    private val epgDao: EpgDao,
    settings: SettingsRepository,
    visibility: CategoryVisibility,
    private val epg: EpgCoordinator,
) : ViewModel() {

    /**
     * Fönstret börjar en timme före nuvarande timme och sträcker sig ett
     * dygn fram. Fast medan skärmen är öppen — flyttade det sig hade
     * rutnätet ryckt under användaren.
     */
    private val windowStart: Long = run {
        val now = System.currentTimeMillis()
        now - now % HOUR_MS - HOUR_MS
    }
    private val windowEnd: Long = windowStart + 25 * HOUR_MS

    val epgState: StateFlow<EpgState> = epg.state

    val now: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    val state: StateFlow<GuideState> = combine(
        settings.preferences,
        visibility.hiddenIds(ContentKind.LIVE),
        epg.version,
    ) { prefs, hidden, _ -> Triple(prefs, hidden, Unit) }
        .flatMapLatest { (prefs, hidden, _) ->
            channelDao.observeChannels(
                categoryId = null,
                sort = prefs.channelSort.code,
                hiddenIds = hidden,
                limit = GUIDE_CHANNELS,
            ).map { channels -> channels to prefs.epgOffsetMinutes }
        }
        .mapLatest { (channels, offsetMinutes) -> load(channels, offsetMinutes * MINUTE_MS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuideState())

    private suspend fun load(channels: List<ChannelEntity>, offset: Long): GuideState {
        val mappings = channels.map { it.id }.chunked(EPG_CHUNK).flatMap { epgDao.mappingsFor(it) }
        val epgFor = mappings.associate { it.channelId to it.epgChannelId }

        // Förskjutningen dras av vid frågan och läggs på vid visning. UTC i
        // databasen, lokal tid på skärmen.
        val programmes = mappings.map { it.epgChannelId }.distinct()
            .chunked(EPG_CHUNK)
            .flatMap { epgDao.window(it, windowStart - offset, windowEnd - offset) }
            .groupBy { it.epgChannelId }

        val rows = channels.map { channel ->
            val list = epgFor[channel.id]?.let { programmes[it] }.orEmpty()
                .sortedBy { it.startUtc }
                .map {
                    GuideProgramme(
                        id = it.id,
                        title = it.title,
                        description = it.description,
                        start = it.startUtc + offset,
                        end = it.endUtc + offset,
                        category = it.category,
                        episode = it.episodeNumber,
                    )
                }
            GuideRow(channel, list)
        }
        return GuideState(rows, windowStart, windowEnd, loading = false)
    }

    fun update() = epg.updateAll()
}

private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun time(ms: Long): String = clock.format(Date(ms))

@Composable
fun GuideScreen(
    onPlayChannel: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GuideViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Samma skäl som i kanallistan: läses klockan här ritas hela rutnätet
    // om varje halvminut.
    val nowState = viewModel.now.collectAsStateWithLifecycle()
    val epgState by viewModel.epgState.collectAsStateWithLifecycle()

    if (state.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AuroraLoader(Modifier.widthIn(max = 240.dp).fillMaxWidth())
        }
        return
    }
    if (!state.hasProgrammes) {
        GuideEmpty(epgState = epgState, onUpdate = viewModel::update, modifier = modifier)
        return
    }

    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val isTv = LocalDeviceProfile.current.isTv
    val density = LocalDensity.current

    // Mått anpassade för avstånd: en timme är bredare på TV:n, så en
    // halvtimmesprogram fortfarande rymmer sin titel.
    val hourWidth = if (isTv) 300.dp else 190.dp
    val labelWidth = if (isTv) 220.dp else 116.dp
    val rowHeight = if (isTv) 64.dp else 58.dp

    val msPerDp = HOUR_MS / hourWidth.value
    fun x(ms: Long): Dp = ((ms - state.windowStart) / msPerDp).dp
    val totalWidth = x(state.windowEnd)

    // En och samma rullning delas av tidslinjen och varje rad. Rullar en
    // rad — med finger eller för att fokus flyttat — följer alla med.
    val scroll = rememberScrollState()
    LaunchedEffect(state.windowStart) {
        // maxValue är noll tills rutnätet mätts; att rulla före det hamnar
        // på noll. Vänta på mätningen, ställ sedan "nu" en halvtimme in.
        snapshotFlow { scroll.maxValue }.first { it > 0 }
        val target = with(density) { x(nowState.value - 30 * MINUTE_MS).toPx() }.toInt()
        scroll.scrollTo(target.coerceIn(0, scroll.maxValue))
    }

    var focused by remember { mutableStateOf<Pair<GuideRow, GuideProgramme?>?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        GuideInfo(selection = focused, nowState = nowState)

        // Tidslinjen
        Row(Modifier.fillMaxWidth().height(32.dp)) {
            Spacer(Modifier.width(labelWidth))
            Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                Box(
                    Modifier
                        .width(totalWidth)
                        .fillMaxHeight()
                        .drawBehind {
                            val nowX = x(nowState.value).toPx()
                            drawLine(
                                color = palette.signal,
                                start = Offset(nowX, size.height * 0.4f),
                                end = Offset(nowX, size.height),
                                strokeWidth = 2.dp.toPx(),
                            )
                        },
                ) {
                    var tick = state.windowStart
                    while (tick < state.windowEnd) {
                        Text(
                            text = time(tick),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textTertiary,
                            modifier = Modifier.offset(x = x(tick) + 6.dp, y = 6.dp),
                        )
                        tick += 30 * MINUTE_MS
                    }
                }
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(state.rows, key = { it.channel.id }) { row ->
                Row(Modifier.fillMaxWidth().height(rowHeight)) {
                    ChannelLabel(row.channel, Modifier.width(labelWidth).fillMaxHeight())
                    Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                        Box(
                            Modifier
                                .width(totalWidth)
                                .fillMaxHeight()
                                .drawBehind {
                                    // Nu-linjen dras rad för rad och blir en
                                    // obruten linje genom hela rutnätet.
                                    val nowX = x(nowState.value).toPx()
                                    drawLine(
                                        color = palette.signal.copy(alpha = 0.8f),
                                        start = Offset(nowX, 0f),
                                        end = Offset(nowX, size.height),
                                        strokeWidth = 2.dp.toPx(),
                                    )
                                },
                        ) {
                            if (row.programmes.isEmpty()) {
                                // Kanalen ska gå att nå och starta även utan
                                // tablå — annars vore den en död rad.
                                ProgrammeBlock(
                                    title = "Ingen tablå",
                                    time = null,
                                    progress = null,
                                    past = false,
                                    onClick = { onPlayChannel(row.channel.id) },
                                    onFocus = { focused = row to null },
                                    modifier = Modifier
                                        .offset(x = x(nowState.value - 30 * MINUTE_MS))
                                        .width(hourWidth * 2)
                                        .fillMaxHeight(),
                                )
                            }
                            row.programmes.forEach { programme ->
                                val start = max(programme.start, state.windowStart)
                                val end = min(programme.end, state.windowEnd)
                                if (end > start) {
                                    // Läses per rad: bara synliga rader ritas om.
                                    val now = nowState.value
                                    val live = programme.start <= now && programme.end > now
                                    ProgrammeBlock(
                                        title = programme.title,
                                        time = "${time(programme.start)}–${time(programme.end)}",
                                        progress = if (live) {
                                            (now - programme.start).toFloat() / (programme.end - programme.start)
                                        } else {
                                            null
                                        },
                                        past = programme.end <= now,
                                        onClick = { onPlayChannel(row.channel.id) },
                                        onFocus = { focused = row to programme },
                                        modifier = Modifier
                                            .offset(x = x(start))
                                            .width(x(end) - x(start))
                                            .fillMaxHeight(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Informationsytan överst. Följer det markerade programmet — man ser vad
 * det handlar om utan att öppna något.
 */
@Composable
private fun GuideInfo(selection: Pair<GuideRow, GuideProgramme?>?, nowState: State<Long>) {
    val now = nowState.value
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp)
            .padding(horizontal = metrics.screenEdge, vertical = metrics.spaceMd),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceXs),
    ) {
        val (row, programme) = selection ?: run {
            SectionHeading(title = "Tablå")
            Text(
                text = "Markera ett program för att se mer. OK startar kanalen.",
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textTertiary,
            )
            return@Column
        }
        Text(
            text = programme?.title ?: row.channel.name,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val facts = listOfNotNull(
            row.channel.name.takeIf { programme != null },
            programme?.let { "${time(it.start)}–${time(it.end)}" },
            programme?.episode,
            programme?.category,
            programme?.takeIf { it.start <= now && it.end > now }?.let {
                "${((it.end - now) / MINUTE_MS).coerceAtLeast(1)} min kvar"
            },
        )
        if (facts.isNotEmpty()) {
            Text(
                text = facts.joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = palette.signal,
            )
        }
        programme?.description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChannelLabel(channel: ChannelEntity, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    Row(
        modifier = modifier
            .background(palette.background.copy(alpha = 0.7f))
            .padding(horizontal = metrics.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm),
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            if (channel.logoUrl != null) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = channel.name,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProgrammeBlock(
    title: String,
    time: String?,
    progress: Float?,
    past: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    val shape = RoundedCornerShape(6.dp)
    FocusableSurface(
        onClick = onClick,
        focusScale = 1f,
        cornerRadius = 6.dp,
        // Egen bakgrund nedan, med samma rundning som fokusringen.
        background = false,
        modifier = modifier
            .padding(2.dp)
            .onFocusChanged { if (it.isFocused) onFocus() },
    ) { focused ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Det som sänds nu lyfts fram; det som redan gått dämpas.
                .background(
                    color = when {
                        progress != null -> palette.surfaceRaised
                        else -> palette.surface.copy(alpha = if (past) 0.4f else 0.85f)
                    },
                    shape = shape,
                )
                .padding(horizontal = metrics.spaceSm, vertical = metrics.spaceXs),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    focused -> palette.focus
                    past -> palette.textTertiary
                    else -> palette.textPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (time != null) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textTertiary,
                    maxLines = 1,
                )
            }
            if (progress != null) {
                AuroraProgress(
                    fraction = progress,
                    height = 2.dp,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun GuideEmpty(epgState: EpgState, onUpdate: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Column(
        modifier = modifier.fillMaxSize().padding(metrics.screenEdge),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceMd, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Ingen tablå än",
            style = MaterialTheme.typography.headlineMedium,
            color = palette.textPrimary,
        )
        when (epgState) {
            is EpgState.Working -> {
                Text(
                    text = epgState.step + (epgState.programmes?.let { " · $it program" } ?: "") + " …",
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textSecondary,
                )
                AuroraLoader(Modifier.widthIn(max = 320.dp).fillMaxWidth())
            }
            is EpgState.Failed -> Text(
                text = epgState.message,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.alert,
            )
            else -> Text(
                text = "Tablån hämtas från din källa. Det tar oftast under en minut.",
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
            )
        }
        if (epgState !is EpgState.Working) {
            PrimaryAction(label = "Hämta tablån", onClick = onUpdate)
        }
    }
}

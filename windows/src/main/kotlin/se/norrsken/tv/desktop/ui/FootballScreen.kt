package se.norrsken.tv.desktop.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.data.EventKind
import se.norrsken.tv.desktop.data.FootballMatch
import se.norrsken.tv.desktop.data.FootballTeam
import se.norrsken.tv.desktop.data.League
import se.norrsken.tv.desktop.data.Lineup
import se.norrsken.tv.desktop.data.LineupPlayer
import se.norrsken.tv.desktop.data.LiveChannel
import se.norrsken.tv.desktop.data.MatchDetail
import se.norrsken.tv.desktop.data.MatchEvent
import se.norrsken.tv.desktop.data.MatchStat
import se.norrsken.tv.desktop.data.MatchState
import se.norrsken.tv.desktop.data.TableRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos

/**
 * Fliken Fotboll: Champions League, Premier League, LaLiga och Allsvenskan.
 *
 * Översikten visar det som pågår, dagens matcher, de senaste resultaten,
 * ligornas ledare och veckans matcher, med kanalen som visar matchen enligt
 * tablån. En liga visar tabellen och ligans matcher. En match visar lagen i
 * sina färger, startelvorna på en plan, händelserna och statistiken.
 */
@Composable
fun FootballScreen(state: AppState) {
    // Hämtar och uppdaterar så länge fliken syns, också när en match är öppen.
    LaunchedEffect(state) { state.footballLoop() }
    // "Visas på" räknas om när tablån, kanalerna eller de dolda kategorierna ändras.
    LaunchedEffect(state.guide, state.library, state.hiddenGroups) { state.refreshMatchChannels() }
    // Före förgreningen: listorna står kvar där de var när man backar ur en
    // match, och på tv:n hamnar markeringen på det man öppnade.
    val lists = remember { HashMap<String, LazyListState>() }
    val scrolls = remember { HashMap<String, ScrollState>() }
    val focus = remember { FootballFocus() }

    val selected = state.selectedMatch
    if (selected != null) {
        MatchPage(state, selected)
        return
    }
    if (tvLayout) {
        LaunchedEffect(Unit) {
            if (focus.openedKey != null) {
                withFrameNanos { }
                runCatching { focus.restore.requestFocus() }
            }
        }
    }
    val league = state.footballLeague
    if (tvLayout) {
        // Byts ligan (med fliken, en ledarruta eller Tillbaka) går markeringen till
        // ligans flik. Inte när man bara kommer tillbaka från en match.
        LaunchedEffect(league) {
            val changed = focus.started && focus.shownLeague != league
            focus.shownLeague = league
            focus.started = true
            if (changed) {
                withFrameNanos { }
                runCatching { focus.chip.requestFocus() }
            }
        }
    }
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize()) {
        FootballHeader(state)
        LeagueChips(state, league, focus)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.footballMatches.isEmpty() && state.footballError == null -> Spinner(Modifier.align(Alignment.Center))
                state.footballMatches.isEmpty() -> Text(
                    state.footballError.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                league == null -> Overview(state, focus, lists)
                else -> LeagueView(state, league, focus, lists, scrolls)
            }
        }
    }
}

/** Tv: var markeringen ska hamna när man backar ur en match eller byter liga. */
private class FootballFocus {
    /** Det man senast öppnade, som "rad:<match-id>". */
    var openedKey: String? = null
    val restore = FocusRequester()
    /** Den valda ligans flik. */
    val chip = FocusRequester()
    /** Ligan som visades senast, så att ett byte märks. */
    var shownLeague: League? = null
    var started = false
}

private fun Modifier.restoreFocus(focus: FootballFocus, key: String): Modifier =
    if (key == focus.openedKey) focusRequester(focus.restore) else this

private fun showMatch(state: AppState, focus: FootballFocus, match: FootballMatch, key: String) {
    focus.openedKey = key
    state.openMatch(match)
}

@Composable
private fun FootballHeader(state: AppState) {
    val palette = LocalPalette.current
    val live = state.footballMatches.count { it.state == MatchState.LIVE }
    Column(
        Modifier.fillMaxWidth().padding(start = SIDE, end = SIDE, top = 26.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Fotboll", style = MaterialTheme.typography.displaySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live > 0) {
                LiveDot()
                Spacer(Modifier.width(8.dp))
                Text(
                    if (live == 1) "1 match pågår" else "$live matcher pågår",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LIVE_RED,
                )
                Text("  ·  ", style = MaterialTheme.typography.bodyMedium, color = palette.textTertiary)
            }
            val updated = state.footballUpdatedAt
            Text(
                when {
                    updated <= 0L -> "Hämtar matcher…"
                    state.footballError != null -> "Uppdaterad ${clockOf(updated)} · nätet svarar inte just nu"
                    else -> "Uppdaterad ${clockOf(updated)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textTertiary,
            )
        }
    }
}

@Composable
private fun LeagueChips(state: AppState, selected: League?, focus: FootballFocus) {
    val liveBy = state.footballMatches.filter { it.state == MatchState.LIVE }.groupingBy { it.league }.eachCount()
    LazyRow(
        contentPadding = PaddingValues(horizontal = SIDE, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "översikt") {
            LeagueChip(
                text = "Översikt",
                accent = null,
                live = 0,
                selected = selected == null,
                onClick = { state.footballLeague = null },
                modifier = if (selected == null) Modifier.focusRequester(focus.chip) else Modifier,
            )
        }
        items(League.entries, key = { it.name }) { league ->
            LeagueChip(
                text = league.title,
                accent = Color(league.accent),
                logo = state.footballLogos[league],
                live = liveBy[league] ?: 0,
                selected = selected == league,
                onClick = { state.footballLeague = league },
                modifier = if (selected == league) Modifier.focusRequester(focus.chip) else Modifier,
            )
        }
    }
}

/** Som Chip, med ligans färg som en prick och antalet matcher som pågår. */
@Composable
private fun LeagueChip(
    text: String,
    accent: Color?,
    logo: String? = null,
    live: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Pressable(
        onClick = onClick,
        modifier = modifier.border(1.dp, if (selected) palette.focus else palette.hairline, shape),
        selected = selected,
        shape = shape,
    ) { _ ->
        Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (logo != null) {
                LeagueLogo(logo, 20.dp)
                Spacer(Modifier.width(9.dp))
            } else if (accent != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) palette.textPrimary else palette.textSecondary,
                maxLines = 1,
            )
            if (live > 0) {
                Spacer(Modifier.width(8.dp))
                LiveDot()
                Spacer(Modifier.width(4.dp))
                Text(live.toString(), style = MaterialTheme.typography.labelMedium, color = LIVE_RED)
            }
        }
    }
}

@Composable
private fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    trailing: String? = null,
    tag: String? = null,
) {
    val palette = LocalPalette.current
    Row(modifier.padding(top = 16.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (live) {
            LiveDot()
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleLarge)
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(trailing, style = MaterialTheme.typography.bodyMedium, color = palette.textTertiary)
        }
        if (tag != null) {
            Spacer(Modifier.width(12.dp))
            Tag(tag, WIN_GREEN)
        }
    }
}

// --- Översikten -----------------------------------------------------------------------

private class OverviewSections(
    val live: List<FootballMatch>,
    val today: List<FootballMatch>,
    val results: List<FootballMatch>,
    val upcoming: List<Pair<LocalDate, List<FootballMatch>>>,
) {
    val isEmpty: Boolean get() = live.isEmpty() && today.isEmpty() && results.isEmpty() && upcoming.isEmpty()
}

private fun overviewSections(matches: List<FootballMatch>, today: LocalDate): OverviewSections {
    val live = matches.filter { it.state == MatchState.LIVE }
    val rest = matches.filter { it.state != MatchState.LIVE }
    val todays = rest.filter { dayOf(it.kickoff) == today }
    val results = rest
        .filter { it.state == MatchState.FINISHED && dayOf(it.kickoff).let { day -> day < today && day >= today.minusDays(3) } }
        .sortedByDescending { it.kickoff }
        .take(24)
    val upcoming = rest
        .filter { dayOf(it.kickoff).let { day -> day > today && day <= today.plusDays(6) } }
        .groupBy { dayOf(it.kickoff) }
        .entries
        .sortedBy { it.key }
        .map { it.key to it.value }
    return OverviewSections(live, todays, results, upcoming)
}

@Composable
private fun Overview(state: AppState, focus: FootballFocus, lists: HashMap<String, LazyListState>) {
    val palette = LocalPalette.current
    val today = dayOf(state.clock)
    val matches = state.footballMatches
    val sections = remember(matches, today) { overviewSections(matches, today) }
    val tables = state.footballTables
    val leaders = remember(tables) {
        League.entries.mapNotNull { league -> tables[league]?.takeIf { it.size >= 2 }?.let { league to it } }
    }
    val side = Modifier.padding(horizontal = SIDE)
    LazyColumn(
        Modifier.fillMaxSize(),
        state = lists.getOrPut("översikt") { LazyListState() },
        contentPadding = PaddingValues(top = 2.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (sections.live.isNotEmpty()) {
            item(key = "live-titel") { SectionTitle("Live nu", side, live = true) }
            item(key = "live") {
                LazyRow(
                    state = lists.getOrPut("rad-live") { LazyListState() },
                    contentPadding = ROW_PADDING,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(sections.live, key = { it.id }) { match -> LiveCard(state, match, focus) }
                }
            }
        }
        if (sections.today.isNotEmpty()) {
            item(key = "idag-titel") { SectionTitle("Idag", side, trailing = matchCount(sections.today.size)) }
            items(sections.today, key = { "d" + it.id }) { match ->
                MatchRow(state, match, focus, key = "rad:${match.id}", showLeague = true, modifier = side)
            }
        }
        if (sections.results.isNotEmpty()) {
            item(key = "resultat-titel") { SectionTitle("Senaste resultaten", side) }
            item(key = "resultat") {
                LazyRow(
                    state = lists.getOrPut("rad-resultat") { LazyListState() },
                    contentPadding = ROW_PADDING,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(sections.results, key = { it.id }) { match -> ResultCard(state, match, focus, today) }
                }
            }
        }
        if (leaders.isNotEmpty()) {
            item(key = "ledare-titel") { SectionTitle("Ledarna", side) }
            item(key = "ledare") {
                LazyRow(
                    state = lists.getOrPut("rad-ledare") { LazyListState() },
                    contentPadding = ROW_PADDING,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(leaders, key = { it.first.name }) { (league, rows) -> LeaderCard(state, league, rows) }
                }
            }
        }
        for ((day, list) in sections.upcoming) {
            item(key = "dag-$day") { SectionTitle(dayLabel(day, today), side, trailing = matchCount(list.size)) }
            items(list, key = { "d" + it.id }) { match ->
                MatchRow(state, match, focus, key = "rad:${match.id}", showLeague = true, modifier = side)
            }
        }
        if (sections.isEmpty) {
            item(key = "tomt") {
                Text(
                    "Inga matcher den närmaste veckan.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textTertiary,
                    modifier = side.padding(top = 24.dp),
                )
            }
        }
    }
}

/** En match som pågår: lagen i sina färger, ställningen, minuten, senaste målet och kanalen. */
@Composable
private fun LiveCard(state: AppState, match: FootballMatch, focus: FootballFocus) {
    val palette = LocalPalette.current
    val accent = Color(match.league.accent)
    val homeColor = teamColor(match.home, accent)
    val awayColor = teamColor(match.away, accent)
    val channel = state.matchChannels[match.id]?.firstOrNull()
    val key = "live:${match.id}"
    Pressable(
        onClick = { showMatch(state, focus, match, key) },
        modifier = Modifier.width(384.dp).restoreFocus(focus, key),
        shape = RoundedCornerShape(20.dp),
        hoverScale = CARD_SCALE,
    ) { _ ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(palette.surface)
                .background(
                    Brush.horizontalGradient(
                        listOf(homeColor.copy(alpha = 0.30f), Color.Transparent, awayColor.copy(alpha = 0.30f)),
                    ),
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LeagueTag(match.league, state.footballLogos[match.league])
                Spacer(Modifier.weight(1f))
                LiveDot()
                Spacer(Modifier.width(6.dp))
                Text(match.status, style = MaterialTheme.typography.labelLarge, color = LIVE_RED)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamColumn(match.home, Modifier.weight(1f))
                Text(scoreText(match), style = scoreStyle(36.sp), color = palette.textPrimary)
                TeamColumn(match.away, Modifier.weight(1f))
            }
            // Samma höjd på alla rutor: raderna finns också när de är tomma.
            val goal = match.goals.lastOrNull()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Football, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (goal != null) "Senaste mål: " + goalLabel(goal.player, goal.minute, goal.penalty, goal.ownGoal) else "Inga mål ännu",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                channel == null -> Text(
                    "Ingen kanal i tablån visar matchen",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary,
                    modifier = Modifier.height(40.dp).padding(top = 11.dp),
                )
                // Tv: rutan öppnar matchen, och Titta är det första som markeras där.
                tvLayout -> Box(Modifier.height(40.dp), contentAlignment = Alignment.CenterStart) { ChannelLabel(channel) }
                else -> PrimaryButton("Titta · ${channel.name.take(28)}", onClick = { state.watchMatch(match) }, icon = Glyphs.Play)
            }
        }
    }
}

@Composable
private fun TeamColumn(team: FootballTeam, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TeamLogo(team, 56.dp)
        Text(
            team.short,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Ett resultat som på en resultattavla: lagen under varandra, vinnaren i vitt. */
@Composable
private fun ResultCard(state: AppState, match: FootballMatch, focus: FootballFocus, today: LocalDate) {
    val palette = LocalPalette.current
    val key = "resultat:${match.id}"
    val home = match.homeScore ?: 0
    val away = match.awayScore ?: 0
    Pressable(
        onClick = { showMatch(state, focus, match, key) },
        modifier = Modifier.width(260.dp).restoreFocus(focus, key),
        shape = RoundedCornerShape(16.dp),
        hoverScale = CARD_SCALE,
    ) { _ ->
        Column(
            Modifier.fillMaxWidth().background(palette.surface).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LeagueTag(match.league, state.footballLogos[match.league])
                Spacer(Modifier.weight(1f))
                Text(
                    shortDay(match.kickoff, today) + " · " + match.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textTertiary,
                    maxLines = 1,
                )
            }
            ScoreLine(match.home, match.homeScore, winner = home > away)
            ScoreLine(match.away, match.awayScore, winner = away > home)
        }
    }
}

@Composable
private fun ScoreLine(team: FootballTeam, score: Int?, winner: Boolean) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(team, 22.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            team.short,
            style = if (winner) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = if (winner) palette.textPrimary else palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            score?.toString() ?: "–",
            style = MaterialTheme.typography.titleMedium,
            color = if (winner) palette.textPrimary else palette.textSecondary,
        )
    }
}

/** Ligans ledare: "Leder med 3 poäng". Ett tryck öppnar ligan. */
@Composable
private fun LeaderCard(state: AppState, league: League, rows: List<TableRow>) {
    val palette = LocalPalette.current
    val accent = Color(league.accent)
    val first = rows[0]
    val gap = first.points - rows[1].points
    val line = when {
        first.played == 0 -> "Säsongen har inte börjat"
        gap > 0 -> "Leder med $gap poäng"
        else -> "Leder på målskillnad"
    }
    Pressable(
        onClick = { state.footballLeague = league },
        modifier = Modifier.width(320.dp),
        shape = RoundedCornerShape(18.dp),
        hoverScale = CARD_SCALE,
    ) { _ ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(palette.surface)
                .drawBehind { drawRect(accent, size = Size(4.dp.toPx(), size.height)) }
                .padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeamLogo(first.team, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    state.footballLogos[league]?.let { logo ->
                        LeagueLogo(logo, 14.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(league.title.uppercase(), style = MaterialTheme.typography.labelSmall, color = accent)
                }
                Text(first.team.short, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$line · ${first.points} p på ${first.played} matcher",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** En match på en rad: tid eller minut, lagen, ställningen, kanalen och ligan. */
@Composable
private fun MatchRow(
    state: AppState,
    match: FootballMatch,
    focus: FootballFocus,
    key: String,
    showLeague: Boolean,
    modifier: Modifier = Modifier,
    dayText: String? = null,
) {
    val palette = LocalPalette.current
    val channel = state.matchChannels[match.id]?.firstOrNull()?.takeIf { match.state != MatchState.FINISHED }
    val lineupReady = match.state == MatchState.UPCOMING && match.id in state.lineupsReady
    val played = match.homeScore != null && match.awayScore != null
    val finished = match.state == MatchState.FINISHED && played
    val homeWon = finished && (match.homeScore ?: 0) > (match.awayScore ?: 0)
    val awayWon = finished && (match.awayScore ?: 0) > (match.homeScore ?: 0)
    Pressable(
        onClick = { showMatch(state, focus, match, key) },
        modifier = modifier.fillMaxWidth().restoreFocus(focus, key),
        shape = RoundedCornerShape(14.dp),
        hoverScale = ROW_SCALE,
    ) { hovered ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(palette.surface.copy(alpha = if (hovered) 1f else 0.82f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(100.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when (match.state) {
                    MatchState.UPCOMING -> Text(clockOf(match.kickoff), style = MaterialTheme.typography.titleMedium)
                    MatchState.LIVE -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveDot()
                        Spacer(Modifier.width(6.dp))
                        Text(match.status, style = MaterialTheme.typography.labelLarge, color = LIVE_RED)
                    }
                    else -> Text(match.status, style = MaterialTheme.typography.labelMedium, color = palette.textTertiary)
                }
                when {
                    lineupReady -> Text("Startelvor klara", style = MaterialTheme.typography.labelSmall, color = WIN_GREEN)
                    dayText != null -> Text(dayText, style = MaterialTheme.typography.labelSmall, color = palette.textTertiary)
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    match.home.short,
                    style = if (homeWon) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                    color = if (finished && !homeWon) palette.textSecondary else palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
                TeamLogo(match.home, 28.dp)
            }
            Box(Modifier.width(88.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (played) scoreText(match) else "–",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (match.state == MatchState.LIVE) LIVE_RED else palette.textPrimary,
                )
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(match.away, 28.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    match.away.short,
                    style = if (awayWon) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                    color = if (finished && !awayWon) palette.textSecondary else palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Row(
                Modifier.width(if (showLeague) 290.dp else 200.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (channel != null) {
                    // Datorn: kanalen går att starta direkt när matchen pågår eller snart börjar.
                    if (!tvLayout && watchable(match, state.clock)) ChannelPill(state, match, channel) else ChannelLabel(channel)
                }
                if (showLeague) {
                    Spacer(Modifier.width(10.dp))
                    LeagueTag(match.league, state.footballLogos[match.league])
                }
            }
        }
    }
}

/** Kanalen som visar matchen. Ett tryck startar den. */
@Composable
private fun ChannelPill(state: AppState, match: FootballMatch, channel: LiveChannel) {
    val palette = LocalPalette.current
    Pressable(onClick = { state.watchMatch(match) }, shape = RoundedCornerShape(8.dp)) { hovered ->
        Row(
            Modifier
                .background(if (hovered) palette.focus else palette.focus.copy(alpha = 0.16f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyphs.Play, contentDescription = null, tint = if (hovered) palette.onFocus else palette.focus, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                channel.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (hovered) palette.onFocus else palette.focus,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 170.dp),
            )
        }
    }
}

/** "Visas på": kanalens namn, utan att gå att trycka på. */
@Composable
private fun ChannelLabel(channel: LiveChannel) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Glyphs.Live, contentDescription = "Visas på", tint = palette.textSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            channel.name,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
    }
}

// --- En liga: tabell och matcher -------------------------------------------------------

@Composable
private fun LeagueView(
    state: AppState,
    league: League,
    focus: FootballFocus,
    lists: HashMap<String, LazyListState>,
    scrolls: HashMap<String, ScrollState>,
) {
    val all = state.footballMatches
    val rows = state.footballTables[league].orEmpty()
    val today = dayOf(state.clock)
    val matches = remember(all, league) { all.filter { it.league == league } }
    val upcoming = remember(matches) { matches.filter { it.state != MatchState.FINISHED }.sortedBy { it.kickoff } }
    val finished = remember(matches) { matches.filter { it.state == MatchState.FINISHED }.sortedByDescending { it.kickoff } }
    val teams = remember(all) { teamInfo(all) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 1100.dp) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = SIDE),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(
                    Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .verticalScroll(scrolls.getOrPut("tabell-${league.name}") { ScrollState(0) })
                        .padding(top = 6.dp, bottom = 48.dp),
                ) {
                    TableCard(state, rows, teams, focus, Modifier.fillMaxWidth())
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    state = lists.getOrPut("matcher-${league.name}") { LazyListState() },
                    contentPadding = PaddingValues(top = 6.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    leagueMatches(state, focus, today, upcoming, finished)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = lists.getOrPut("liga-${league.name}") { LazyListState() },
                contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "tabell") { TableCard(state, rows, teams, focus, Modifier.fillMaxWidth()) }
                leagueMatches(state, focus, today, upcoming, finished)
            }
        }
    }
}

private fun LazyListScope.leagueMatches(
    state: AppState,
    focus: FootballFocus,
    today: LocalDate,
    upcoming: List<FootballMatch>,
    finished: List<FootballMatch>,
) {
    if (upcoming.isEmpty() && finished.isEmpty()) {
        item(key = "inga") { SectionTitle("Inga matcher just nu") }
        return
    }
    for ((day, list) in upcoming.groupBy { dayOf(it.kickoff) }) {
        item(key = "dag-$day") { SectionTitle(dayLabel(day, today), trailing = matchCount(list.size)) }
        items(list, key = { "k" + it.id }) { match ->
            MatchRow(state, match, focus, key = "liga:${match.id}", showLeague = false)
        }
    }
    if (finished.isNotEmpty()) {
        item(key = "spelade") { SectionTitle("Spelade") }
        items(finished.take(30), key = { "s" + it.id }) { match ->
            MatchRow(state, match, focus, key = "liga:${match.id}", showLeague = false, dayText = shortDay(match.kickoff, today))
        }
    }
}

/** Det tabellen visar om ett lag utöver siffrorna: formen och nästa match. */
private class TeamInfo(
    val form: String?,
    val live: FootballMatch?,
    val next: FootballMatch?,
    val latest: FootballMatch?,
) {
    /** Matchen ett tryck på laget öppnar: den som pågår, nästa, annars den senaste. */
    val target: FootballMatch? get() = live ?: next ?: latest
}

private fun teamInfo(matches: List<FootballMatch>): Map<String, TeamInfo> {
    val byTeam = HashMap<String, MutableList<FootballMatch>>()
    for (match in matches) {
        byTeam.getOrPut(match.home.id) { ArrayList() }.add(match)
        byTeam.getOrPut(match.away.id) { ArrayList() }.add(match)
    }
    return byTeam.mapValues { (id, list) ->
        val newestFirst = list.sortedByDescending { it.kickoff }
        val espnForm = newestFirst.firstNotNullOfOrNull { if (it.home.id == id) it.home.form else it.away.form }
        val finished = newestFirst.filter { it.state == MatchState.FINISHED && it.homeScore != null && it.awayScore != null }
        // Skickar ESPN ingen form räknas den fram ur matcherna appen har, äldst först.
        val ownForm = finished.take(5).reversed().joinToString("") { resultFor(it, id) }.ifEmpty { null }
        TeamInfo(
            form = espnForm ?: ownForm,
            live = list.firstOrNull { it.state == MatchState.LIVE },
            next = list.filter { it.state == MatchState.UPCOMING }.minByOrNull { it.kickoff },
            latest = finished.firstOrNull(),
        )
    }
}

private fun resultFor(match: FootballMatch, teamId: String): String {
    val home = match.home.id == teamId
    val own = (if (home) match.homeScore else match.awayScore) ?: return ""
    val other = (if (home) match.awayScore else match.homeScore) ?: return ""
    return when {
        own > other -> "W"
        own < other -> "L"
        else -> "D"
    }
}

/** Tabellen: zonerna som färgade streck, formen som prickar, nästa motståndare som logga. */
@Composable
private fun TableCard(
    state: AppState,
    rows: List<TableRow>,
    teams: Map<String, TeamInfo>,
    focus: FootballFocus,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(palette.surface).padding(horizontal = 6.dp, vertical = 10.dp),
    ) {
        if (rows.isEmpty()) {
            Text(
                when {
                    !state.footballTablesTried -> "Tabellen hämtas…"
                    state.footballTables.isEmpty() -> "Tabellen gick inte att hämta just nu. Appen försöker igen om en stund."
                    else -> "Tabellen finns inte just nu."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textTertiary,
                modifier = Modifier.padding(18.dp),
            )
        } else {
            TableHeader()
            rows.forEach { row ->
                val info = teams[row.team.id]
                val target = info?.target
                val key = "lag:${row.team.id}"
                TableLine(
                    row = row,
                    info = info,
                    onClick = if (target != null) ({ showMatch(state, focus, target, key) }) else null,
                    modifier = Modifier.restoreFocus(focus, key),
                )
            }
            ZoneLegend(rows)
            Text(
                "Välj ett lag för att se dess nästa match.",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textTertiary,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun TableHeader() {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(3.dp))
        HeaderCell("#", COL_RANK, TextAlign.Start, start = 8.dp)
        Text(
            "Lag",
            style = MaterialTheme.typography.labelSmall,
            color = palette.textTertiary,
            modifier = Modifier.weight(1f).padding(start = 32.dp),
        )
        HeaderCell("M", COL_NUM)
        HeaderCell("V", COL_NUM)
        HeaderCell("O", COL_NUM)
        HeaderCell("F", COL_NUM)
        HeaderCell("+/-", COL_DIFF)
        HeaderCell("P", COL_POINTS)
        HeaderCell("Form", COL_FORM, TextAlign.Start, start = 14.dp)
        HeaderCell("Nästa", COL_NEXT, TextAlign.Center)
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp, align: TextAlign = TextAlign.End, start: Dp = 0.dp) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = LocalPalette.current.textTertiary,
        textAlign = align,
        maxLines = 1,
        modifier = Modifier.width(width).padding(start = start),
    )
}

@Composable
private fun TableLine(row: TableRow, info: TeamInfo?, onClick: (() -> Unit)?, modifier: Modifier) {
    val palette = LocalPalette.current
    val zone = row.zoneColor?.let { Color(it) }
    val live = info?.live
    val coming = live ?: info?.next
    Pressable(
        onClick = { onClick?.invoke() },
        modifier = modifier.fillMaxWidth(),
        // Tv: varje rad går att markera, så att tabellen går att bläddra med pilarna.
        enabled = onClick != null || tvLayout,
        shape = RoundedCornerShape(10.dp),
        hoverScale = ROW_SCALE,
    ) { _ ->
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(zone ?: Color.Transparent))
            Text(
                row.rank.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = palette.textSecondary,
                modifier = Modifier.width(COL_RANK).padding(start = 8.dp),
            )
            TeamLogo(row.team, 22.dp)
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.team.short,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (live != null) {
                    Spacer(Modifier.width(8.dp))
                    LiveDot()
                }
            }
            NumberCell(row.played.toString(), COL_NUM)
            NumberCell(row.won.toString(), COL_NUM)
            NumberCell(row.drawn.toString(), COL_NUM)
            NumberCell(row.lost.toString(), COL_NUM)
            NumberCell(if (row.goalDiff > 0) "+${row.goalDiff}" else row.goalDiff.toString(), COL_DIFF)
            Text(
                row.points.toString(),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.End,
                modifier = Modifier.width(COL_POINTS),
            )
            FormDots(info?.form, Modifier.width(COL_FORM).padding(start = 14.dp))
            Box(Modifier.width(COL_NEXT), contentAlignment = Alignment.Center) {
                if (coming != null) TeamLogo(opponentOf(coming, row.team.id), 20.dp)
            }
        }
    }
}

@Composable
private fun NumberCell(text: String, width: Dp) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalPalette.current.textSecondary,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(width),
    )
}

/** De senaste fem matcherna, äldst till vänster: grön vinst, grå oavgjort, röd förlust. */
@Composable
private fun FormDots(form: String?, modifier: Modifier) {
    val palette = LocalPalette.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        form?.uppercase()?.filter { it == 'W' || it == 'D' || it == 'L' }?.takeLast(5)?.forEach { result ->
            val color = when (result) {
                'W' -> WIN_GREEN
                'L' -> LIVE_RED
                else -> palette.textTertiary
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun ZoneLegend(rows: List<TableRow>) {
    val palette = LocalPalette.current
    val zones = rows.mapNotNull { row ->
        val zone = row.zone
        val color = row.zoneColor
        if (zone != null && color != null) swedishZone(zone) to color else null
    }.distinctBy { it.first }
    if (zones.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        zones.take(6).chunked(3).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                line.forEach { (zone, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Color(color)))
                        Spacer(Modifier.width(6.dp))
                        Text(zone, style = MaterialTheme.typography.labelSmall, color = palette.textTertiary, maxLines = 1)
                    }
                }
            }
        }
    }
}

// --- En match -------------------------------------------------------------------------

@Composable
private fun MatchPage(state: AppState, match: FootballMatch) {
    val palette = LocalPalette.current
    val detail = state.matchDetail
    val channels = state.matchChannels[match.id].orEmpty()
    val accent = Color(match.league.accent)
    val homeColor = teamColor(match.home, accent)
    val awayColor = teamColor(match.away, accent)
    // Staplarna jämförs bredvid varandra: är färgerna för lika får bortalaget grått.
    val awayBar = if (similar(homeColor, awayColor)) palette.textTertiary else awayColor
    val canWatch = channels.isNotEmpty() && watchable(match, state.clock)
    // Tv: markeringen börjar på Titta när matchen går att se, annars på Tillbaka.
    val first = remember { FocusRequester() }
    if (tvLayout) {
        LaunchedEffect(match.id) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    val page = Modifier.widthIn(max = PAGE_MAX).fillMaxWidth()
    Box(Modifier.fillMaxSize()) {
        // Lagens färger som ett sken uppifrån, hemmalaget till vänster.
        Box(
            Modifier
                .fillMaxWidth()
                .height(560.dp)
                .drawBehind {
                    val radius = size.width * 0.55f
                    drawRect(
                        Brush.radialGradient(
                            listOf(homeColor.copy(alpha = 0.34f), Color.Transparent),
                            center = Offset(size.width * 0.2f, 0f),
                            radius = radius,
                        ),
                    )
                    drawRect(
                        Brush.radialGradient(
                            listOf(awayColor.copy(alpha = 0.34f), Color.Transparent),
                            center = Offset(size.width * 0.8f, 0f),
                            radius = radius,
                        ),
                    )
                },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 22.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "topp") { MatchTopBar(state, match, backFocus = if (canWatch) null else first, modifier = page) }
            item(key = "huvud") {
                MatchHeader(state, match, detail, channels, canWatch, watchFocus = if (canWatch) first else null, modifier = page)
            }
            item(key = "elvor") { LineupsSection(state, match, detail, homeColor, awayColor, page) }
            if (detail != null && detail.events.isNotEmpty()) {
                item(key = "händelser") { EventsSection(detail.events, page) }
            }
            if (detail != null && detail.stats.isNotEmpty()) {
                item(key = "statistik") { StatsSection(detail.stats, homeColor, awayBar, page) }
            }
        }
    }
}

@Composable
private fun MatchTopBar(state: AppState, match: FootballMatch, backFocus: FocusRequester?, modifier: Modifier) {
    val palette = LocalPalette.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconAction(
            Glyphs.Back,
            "Tillbaka",
            onClick = { state.closeMatch() },
            modifier = if (backFocus != null) Modifier.focusRequester(backFocus) else Modifier,
        )
        Spacer(Modifier.width(14.dp))
        LeagueTag(match.league, state.footballLogos[match.league])
        Spacer(Modifier.width(12.dp))
        Text(dayAndTime(match.kickoff, state.clock), style = MaterialTheme.typography.bodyMedium, color = palette.textTertiary)
    }
}

@Composable
private fun MatchHeader(
    state: AppState,
    match: FootballMatch,
    detail: MatchDetail?,
    channels: List<LiveChannel>,
    canWatch: Boolean,
    watchFocus: FocusRequester?,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    val table = state.footballTables[match.league].orEmpty()
    val all = state.footballMatches
    val teams = remember(all) { teamInfo(all) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BigTeam(match.home, table.firstOrNull { it.team.id == match.home.id }, teams[match.home.id]?.form, Modifier.weight(1f))
            Column(
                Modifier.width(260.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (match.state) {
                    MatchState.UPCOMING -> {
                        Text(clockOf(match.kickoff), style = scoreStyle(50.sp), color = palette.textPrimary)
                        Text(countdown(match.kickoff, state.clock), style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
                    }
                    MatchState.POSTPONED -> Text(match.status, style = MaterialTheme.typography.headlineMedium, color = palette.textSecondary)
                    else -> {
                        Text(scoreText(match), style = scoreStyle(64.sp), color = palette.textPrimary)
                        if (match.state == MatchState.LIVE) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LiveDot()
                                Spacer(Modifier.width(6.dp))
                                Text(match.status, style = MaterialTheme.typography.titleMedium, color = LIVE_RED)
                            }
                        } else {
                            Text(match.status, style = MaterialTheme.typography.titleSmall, color = palette.textSecondary)
                        }
                    }
                }
            }
            BigTeam(match.away, table.firstOrNull { it.team.id == match.away.id }, teams[match.away.id]?.form, Modifier.weight(1f))
        }
        if (match.goals.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    match.goals.filter { it.home }.forEach { GoalText(goalLabel(it.player, it.minute, it.penalty, it.ownGoal)) }
                }
                Spacer(Modifier.width(260.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    match.goals.filter { !it.home }.forEach { GoalText(goalLabel(it.player, it.minute, it.penalty, it.ownGoal)) }
                }
            }
        }
        val firstChannel = channels.firstOrNull()
        when {
            canWatch && firstChannel != null -> Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryButton(
                    "Titta · ${firstChannel.name.take(32)}",
                    onClick = { state.watchMatch(match) },
                    modifier = if (watchFocus != null) Modifier.focusRequester(watchFocus) else Modifier,
                    icon = Glyphs.Play,
                )
                channels.drop(1).forEach { channel ->
                    SecondaryButton(channel.name.take(28), onClick = { state.playChannel(channel, channels) }, icon = Glyphs.Live)
                }
            }
            firstChannel != null && match.state == MatchState.UPCOMING -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Live, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Visas på " + channels.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            match.state == MatchState.UPCOMING || match.state == MatchState.LIVE -> Text(
                "Ingen kanal i tablån visar matchen.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textTertiary,
            )
        }
        InfoLine(match, detail)
    }
}

@Composable
private fun BigTeam(team: FootballTeam, row: TableRow?, form: String?, modifier: Modifier) {
    val palette = LocalPalette.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TeamLogo(team, 96.dp)
        Text(
            team.name,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (row != null && row.played > 0) {
            Text(
                "${ordinal(row.rank)} i tabellen · ${row.points} p",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textTertiary,
            )
        }
        if (!form.isNullOrEmpty()) FormDots(form, Modifier)
    }
}

@Composable
private fun GoalText(text: String) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Glyphs.Football, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
    }
}

@Composable
private fun InfoLine(match: FootballMatch, detail: MatchDetail?) {
    val parts = listOfNotNull(
        (detail?.venue ?: match.venue)?.let { "Arena: $it" },
        detail?.referee?.let { "Domare: $it" },
        detail?.attendance?.let { "Publik: ${it.grouped()}" },
    )
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("   ·   "),
        style = MaterialTheme.typography.bodySmall,
        color = LocalPalette.current.textTertiary,
        textAlign = TextAlign.Center,
    )
}

// --- Startelvorna ---------------------------------------------------------------------

@Composable
private fun LineupsSection(
    state: AppState,
    match: FootballMatch,
    detail: MatchDetail?,
    homeColor: Color,
    awayColor: Color,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    val released = detail?.lineupsReleased == true
    val lineups = detail?.lineups.orEmpty()
    val home = if (released) {
        lineups.firstOrNull { it.teamId == match.home.id } ?: lineups.firstOrNull { it.home }
    } else {
        state.previousLineups[match.home.id]
    }
    val away = if (released) {
        lineups.firstOrNull { it.teamId == match.away.id } ?: lineups.firstOrNull { !it.home }
    } else {
        state.previousLineups[match.away.id]
    }
    // Mål och kort syns på spelarna, som i tv-grafiken.
    val marks = remember(detail) { if (released && detail != null) playerMarks(detail.events) else emptyMap() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Startelvor", tag = if (released && match.state == MatchState.UPCOMING) "Klara" else null)
        val message = when {
            detail == null && state.matchDetailLoading -> null
            detail == null -> "Matchens detaljer gick inte att hämta just nu. Sidan försöker igen av sig själv."
            !released && match.state == MatchState.UPCOMING -> {
                val release = match.kickoff - LINEUP_BEFORE_MS
                val first = if (state.clock >= release) {
                    "Startelvorna brukar släppas en timme före avspark och kan komma när som helst nu."
                } else {
                    "Startelvorna släpps oftast en timme före avspark, cirka kl ${clockOf(release)}."
                }
                first + if (home != null || away != null) {
                    " Sidan uppdateras av sig själv – så länge visas lagens elvor från förra matchen."
                } else {
                    " Sidan uppdateras av sig själv."
                }
            }
            !released -> "Startelvorna finns inte för den här matchen."
            else -> null
        }
        if (detail == null && state.matchDetailLoading) Spinner(size = 22.dp)
        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
        }
        if (home != null || away != null) {
            val label = if (released) null else "Förra matchen"
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 860.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        PitchCard(match.home, home, label, homeColor, marks, Modifier.weight(1f))
                        PitchCard(match.away, away, label, awayColor, marks, Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        PitchCard(match.home, home, label, homeColor, marks, Modifier.fillMaxWidth())
                        PitchCard(match.away, away, label, awayColor, marks, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun PitchCard(
    team: FootballTeam,
    lineup: Lineup?,
    label: String?,
    color: Color,
    marks: Map<String, PlayerMark>,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    TvFocusBox(modifier, shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier.fillMaxWidth().background(palette.surface).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(team, 28.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    team.short,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (label != null) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = palette.textTertiary)
                    Spacer(Modifier.width(10.dp))
                }
                lineup?.formation?.let { formation ->
                    Text(formation, style = MaterialTheme.typography.labelLarge, color = palette.textPrimary)
                }
            }
            if (lineup == null || lineup.starters.isEmpty()) {
                Text("Ingen startelva ännu.", style = MaterialTheme.typography.bodyMedium, color = palette.textTertiary)
            } else {
                Pitch(lineup, marks, color)
                val bench = lineup.bench
                if (bench.isNotEmpty()) {
                    Text(
                        "Avbytare: " + bench.joinToString(", ") { player ->
                            (if (player.number.isNotEmpty()) player.number + " " else "") + markerName(player) + if (player.subbedIn) " ↑" else ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Planhalvan: målvakten nederst, anfallarna överst, lagets vänsterkant till vänster. */
@Composable
private fun Pitch(lineup: Lineup, marks: Map<String, PlayerMark>, color: Color) {
    val lines = remember(lineup) { pitchLines(lineup) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(PITCH_ASPECT)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(PITCH_TOP, PITCH_BOTTOM)))
            .drawBehind { drawPitch() },
    ) {
        val pitchWidth = maxWidth
        val pitchHeight = maxHeight
        lines.forEachIndexed { index, players ->
            val y = if (lines.size == 1) 0.5f else 0.86f - 0.72f * index / (lines.size - 1)
            val markerWidth = (pitchWidth / (players.size + 1)).coerceIn(56.dp, 96.dp)
            players.forEachIndexed { slot, player ->
                val x = (slot + 1f) / (players.size + 1f)
                PlayerMarker(
                    player = player,
                    mark = marks[player.name.lowercase()],
                    chip = color,
                    modifier = Modifier
                        .offset(x = pitchWidth * x - markerWidth / 2, y = pitchHeight * y - 22.dp)
                        .width(markerWidth),
                )
            }
        }
    }
}

@Composable
private fun PlayerMarker(player: LineupPlayer, mark: PlayerMark?, chip: Color, modifier: Modifier) {
    val onChip = if (chip.luminance() > 0.5f) INK else Color.White
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp)) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(chip)
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    player.number.ifEmpty { "–" },
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = onChip,
                )
            }
            if (mark != null && mark.goals > 0) {
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Glyphs.Football, contentDescription = "Mål", tint = INK, modifier = Modifier.size(11.dp))
                    if (mark.goals > 1) Text(mark.goals.toString(), style = MaterialTheme.typography.labelSmall, color = INK)
                }
            }
            if (mark != null && (mark.red || mark.yellow)) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 3.dp, top = 2.dp)
                        .width(9.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (mark.red) LIVE_RED else YELLOW_CARD),
                )
            }
        }
        Text(
            markerName(player) + if (player.subbedOut) " ↓" else "",
            style = MaterialTheme.typography.labelSmall.copy(shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 6f)),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Linjerna på planhalvan, i verkliga mått: 68 meter bred, 52,5 meter till mittlinjen. */
private fun DrawScope.drawPitch() {
    val line = Color.White.copy(alpha = 0.30f)
    val stroke = Stroke(width = 1.5.dp.toPx())
    val inset = 10.dp.toPx()
    val w = size.width - inset * 2
    val h = size.height - inset * 2
    val goalLine = inset + h
    val cx = inset + w / 2
    // Ränder i gräset.
    val bands = 8
    for (band in 0 until bands step 2) {
        drawRect(
            Color.White.copy(alpha = 0.035f),
            topLeft = Offset(0f, size.height * band / bands),
            size = Size(size.width, size.height / bands),
        )
    }
    drawRect(line, topLeft = Offset(inset, inset), size = Size(w, h), style = stroke)
    val mx = w / 68f
    val my = h / 52.5f
    // Mittcirkeln vid mittlinjen överst, och avsparkspunkten.
    val circle = 9.15f * mx
    drawArc(
        line,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(cx - circle, inset - circle),
        size = Size(circle * 2, circle * 2),
        style = stroke,
    )
    drawCircle(line, radius = 2.dp.toPx(), center = Offset(cx, inset))
    // Straffområdet och målområdet.
    val boxW = 40.32f * mx
    val boxH = 16.5f * my
    drawRect(line, topLeft = Offset(cx - boxW / 2, goalLine - boxH), size = Size(boxW, boxH), style = stroke)
    val smallW = 18.32f * mx
    val smallH = 5.5f * my
    drawRect(line, topLeft = Offset(cx - smallW / 2, goalLine - smallH), size = Size(smallW, smallH), style = stroke)
    // Straffpunkten och bågen utanför straffområdet.
    val spot = Offset(cx, goalLine - 11f * my)
    drawCircle(line, radius = 2.dp.toPx(), center = spot)
    val arc = 9.15f * mx
    val rise = spot.y - (goalLine - boxH)
    if (rise > 0f && arc > rise) {
        val half = Math.toDegrees(acos((rise / arc).toDouble())).toFloat()
        drawArc(
            line,
            startAngle = 270f - half,
            sweepAngle = half * 2,
            useCenter = false,
            topLeft = Offset(spot.x - arc, spot.y - arc),
            size = Size(arc * 2, arc * 2),
            style = stroke,
        )
    }
    // Målet, utanför mållinjen.
    val goalW = 7.32f * mx
    drawRect(line, topLeft = Offset(cx - goalW / 2, goalLine), size = Size(goalW, (inset - 3.dp.toPx()).coerceAtLeast(1f)), style = stroke)
}

/**
 * Startelvan i linjer, bakifrån: målvakt, backar, defensiva mittfältare,
 * mittfält, offensiva mittfältare, anfall. Säger positionerna för lite
 * används formationen.
 */
private fun pitchLines(lineup: Lineup): List<List<LineupPlayer>> {
    val starters = lineup.starters
    if (starters.isEmpty()) return emptyList()
    fun role(position: String): Int {
        val p = position.uppercase()
        return when {
            p == "G" || p == "GK" -> 0
            p.startsWith("CD") || p.startsWith("CB") || p.startsWith("D-") || p in setOf("D", "LB", "RB", "LWB", "RWB", "SW") -> 1
            p.startsWith("DM") || p == "CDM" -> 2
            p.startsWith("AM") || p in setOf("CAM", "LAM", "RAM") -> 4
            p.startsWith("CF") || p.startsWith("ST") || p.startsWith("F-") || p in setOf("F", "LW", "RW", "LF", "RF", "SS") -> 5
            p.startsWith("CM") || p.startsWith("M-") || p in setOf("M", "LM", "RM") -> 3
            else -> -1
        }
    }
    fun side(position: String): Int {
        val p = position.uppercase()
        return when {
            p.endsWith("-L") || (p.length in 2..3 && p.startsWith("L")) -> 0
            p.endsWith("-R") || (p.length in 2..3 && p.startsWith("R")) -> 2
            else -> 1
        }
    }
    val roles = starters.map { role(it.position) }
    if (roles.none { it < 0 } && roles.count { it == 0 } == 1) {
        return (0..5).mapNotNull { r ->
            starters.filterIndexed { i, _ -> roles[i] == r }.sortedBy { side(it.position) }.takeIf { it.isNotEmpty() }
        }
    }
    val shape = lineup.formation?.split('-')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.sum() == starters.size - 1 }
        ?: listOf(4, 4, 2).takeIf { starters.size == 11 }
        ?: return listOf(starters)
    val ordered = if (starters.all { it.place > 0 }) starters.sortedBy { it.place } else starters
    val result = ArrayList<List<LineupPlayer>>()
    result += listOf(ordered.first())
    var index = 1
    for (count in shape) {
        if (index >= ordered.size) break
        result += ordered.subList(index, (index + count).coerceAtMost(ordered.size))
        index += count
    }
    return result
}

/** Mål och kort per spelare, efter namnet i små bokstäver. */
private class PlayerMark(val goals: Int, val yellow: Boolean, val red: Boolean)

private fun playerMarks(events: List<MatchEvent>): Map<String, PlayerMark> =
    events.filter { it.kind != EventKind.SUB && it.kind != EventKind.OWN_GOAL }
        .groupBy { it.player.lowercase() }
        .mapValues { (_, list) ->
            PlayerMark(
                goals = list.count { it.kind == EventKind.GOAL || it.kind == EventKind.PENALTY },
                yellow = list.any { it.kind == EventKind.YELLOW },
                red = list.any { it.kind == EventKind.RED },
            )
        }

/** "B. Saka" → "Saka": efternamnet, som i tv-grafiken. */
private fun markerName(player: LineupPlayer): String {
    val short = player.shortName.trim()
    return if (short.length > 3 && short[1] == '.' && short[2] == ' ') short.substring(3) else short
}

// --- Händelser och statistik ------------------------------------------------------------

@Composable
private fun EventsSection(events: List<MatchEvent>, modifier: Modifier) {
    val palette = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Händelser")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(palette.surface).padding(6.dp)) {
            events.forEach { event ->
                // Tv: varje händelse går att markera, så att listan går att bläddra.
                TvFocusBox(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            if (event.home == true) EventText(event, alignEnd = true)
                        }
                        Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                            Text(event.minute, style = MaterialTheme.typography.labelLarge, color = palette.textTertiary)
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (event.home != true) EventText(event, alignEnd = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventText(event: MatchEvent, alignEnd: Boolean) {
    val palette = LocalPalette.current
    val text = when (event.kind) {
        EventKind.GOAL -> event.player + (event.other?.let { " (passning $it)" } ?: "")
        EventKind.PENALTY -> event.player + " (straff)"
        EventKind.OWN_GOAL -> event.player + " (självmål)"
        EventKind.YELLOW, EventKind.RED -> event.player
        EventKind.SUB -> "↑ " + event.player + (event.other?.let { "   ↓ $it" } ?: "")
    }
    val goal = event.kind == EventKind.GOAL || event.kind == EventKind.PENALTY || event.kind == EventKind.OWN_GOAL
    val icon: @Composable () -> Unit = {
        when (event.kind) {
            EventKind.GOAL, EventKind.PENALTY, EventKind.OWN_GOAL ->
                Icon(Glyphs.Football, contentDescription = "Mål", tint = palette.textPrimary, modifier = Modifier.size(16.dp))
            EventKind.YELLOW -> CardMark(YELLOW_CARD)
            EventKind.RED -> CardMark(LIVE_RED)
            EventKind.SUB -> Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                Text("⇅", style = MaterialTheme.typography.labelLarge, color = palette.textSecondary)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!alignEnd) icon()
        Text(
            text,
            style = if (goal) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = if (goal) palette.textPrimary else palette.textSecondary,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (alignEnd) icon()
    }
}

@Composable
private fun CardMark(color: Color) {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.width(10.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(color))
    }
}

/** Statistiken som staplar i lagens färger: hemmalaget från vänster, bortalaget från höger. */
@Composable
private fun StatsSection(stats: List<MatchStat>, homeColor: Color, awayColor: Color, modifier: Modifier) {
    val palette = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Statistik")
        TvFocusBox(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(
                Modifier.fillMaxWidth().background(palette.surface).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                stats.forEach { stat ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stat.home, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(72.dp))
                            Text(
                                stat.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stat.away,
                                style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(72.dp),
                            )
                        }
                        val share = stat.homeShare.coerceIn(0.02f, 0.98f)
                        Row(Modifier.fillMaxWidth().height(6.dp)) {
                            Box(Modifier.weight(share).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(homeColor))
                            Spacer(Modifier.width(4.dp))
                            Box(Modifier.weight(1f - share).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(awayColor))
                        }
                    }
                }
            }
        }
    }
}

// --- Små delar ------------------------------------------------------------------------

/**
 * Tv: en ruta som markeringen kan stanna på, så att sidan går att bläddra
 * med pilarna också där inget går att välja. På datorn bara innehållet.
 */
@Composable
private fun TvFocusBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp), content: @Composable BoxScope.() -> Unit) {
    if (!tvLayout) {
        Box(modifier, content = content)
        return
    }
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .clip(shape)
            .then(
                if (focused) {
                    Modifier.background(palette.focus.copy(alpha = 0.08f)).focusRing(palette, 2.dp, shape, 0.8f)
                } else {
                    Modifier
                },
            )
            .focusable(interactionSource = interaction),
        content = content,
    )
}

@Composable
private fun TeamLogo(team: FootballTeam, size: Dp) {
    val palette = LocalPalette.current
    RemoteImage(
        url = team.logo,
        modifier = Modifier.size(size),
        maxSide = 192,
        contentScale = ContentScale.Fit,
        placeholder = {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(palette.surfaceRaised), contentAlignment = Alignment.Center) {
                Text(team.abbreviation.take(3), style = MaterialTheme.typography.labelSmall, color = palette.textTertiary, maxLines = 1)
            }
        },
    )
}

/** Ligans märke på en match: loggan och förkortningen. Utan logga ännu en prick i ligans färg. */
@Composable
private fun LeagueTag(league: League, logo: String?) {
    val color = Color(league.accent)
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (logo != null) {
            LeagueLogo(logo, 14.dp)
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(6.dp))
        Text(league.short, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** Ligans officiella logga från ESPN. */
@Composable
private fun LeagueLogo(url: String, size: Dp) {
    RemoteImage(url = url, modifier = Modifier.size(size), maxSide = 128, contentScale = ContentScale.Fit)
}

/** Den röda pricken som pulserar när något är live. */
@Composable
private fun LiveDot() {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "puls",
    )
    Box(Modifier.size(8.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(LIVE_RED))
}

/** Stort och fett i temats rubrikstil: ställningen och avsparkstiden. */
@Composable
private fun scoreStyle(size: TextUnit): TextStyle =
    MaterialTheme.typography.displaySmall.copy(fontSize = size, lineHeight = size * 1.12f, fontWeight = FontWeight.Bold)

/** Lagets färg om den syns mot den mörka bakgrunden, annars lagets andra färg, annars ligans. */
private fun teamColor(team: FootballTeam, fallback: Color): Color {
    val colors = listOfNotNull(team.color, team.alternateColor).map { Color(it) }
    return colors.firstOrNull { it.luminance() in 0.04f..0.85f }
        ?: colors.firstOrNull { it.luminance() > 0.85f }
        ?: fallback
}

private fun similar(a: Color, b: Color): Boolean = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) < 0.3f

/** Matchen pågår eller börjar inom en halvtimme: då är Titta det man vill. */
private fun watchable(match: FootballMatch, now: Long): Boolean =
    match.state == MatchState.LIVE || (match.state == MatchState.UPCOMING && match.kickoff - now <= SOON_MS)

private fun opponentOf(match: FootballMatch, teamId: String): FootballTeam = if (match.home.id == teamId) match.away else match.home

private fun scoreText(match: FootballMatch): String = "${match.homeScore ?: 0} – ${match.awayScore ?: 0}"

private fun matchCount(count: Int): String = if (count == 1) "1 match" else "$count matcher"

/** 1:a, 2:a, 3:e … 11:e, 12:e … 21:a, 22:a. */
private fun ordinal(n: Int): String {
    val last = n % 10
    val lastTwo = n % 100
    return if ((last == 1 || last == 2) && lastTwo != 11 && lastTwo != 12) "$n:a" else "$n:e"
}

private fun goalLabel(player: String, minute: String, penalty: Boolean, ownGoal: Boolean): String =
    "$player $minute" + when {
        penalty -> " (straff)"
        ownGoal -> " (självmål)"
        else -> ""
    }

/** ESPN:s zoner på svenska. */
private fun swedishZone(zone: String): String {
    val z = zone.lowercase()
    return when {
        "champions league" in z && "qualif" in z -> "Champions League-kval"
        "champions league" in z -> "Champions League"
        "europa league" in z && "qualif" in z -> "Europa League-kval"
        "europa league" in z -> "Europa League"
        "conference" in z && "qualif" in z -> "Conference League-kval"
        "conference" in z -> "Conference League"
        "relegation" in z && ("playoff" in z || "play-off" in z || "play off" in z) -> "Kvalspel"
        "relegation" in z -> "Nedflyttning"
        "round of 16" in z -> "Åttondelsfinal"
        "playoff" in z || "play-off" in z || "knockout" in z -> "Playoff"
        "promotion" in z -> "Uppflyttning"
        "eliminated" in z -> "Utslagen"
        else -> zone
    }
}

private val swedish: Locale = Locale.forLanguageTag("sv-SE")
private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", swedish)
private val shortDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", swedish)

private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

private fun clockOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(clockFormat)

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Idag"
    today.plusDays(1) -> "Imorgon"
    today.minusDays(1) -> "Igår"
    else -> day.format(dayFormat).replaceFirstChar { it.uppercase() }
}

/** "Igår", "lör 1 nov". */
private fun shortDay(ms: Long, today: LocalDate): String = when (val day = dayOf(ms)) {
    today -> "Idag"
    today.minusDays(1) -> "Igår"
    today.plusDays(1) -> "Imorgon"
    else -> day.format(shortDayFormat).replace(".", "")
}

private fun dayAndTime(ms: Long, now: Long): String = dayLabel(dayOf(ms), dayOf(now)) + " kl " + clockOf(ms)

/** "Avspark om 25 min", "om 3 tim 10 min", annars dag och tid. */
private fun countdown(kickoff: Long, now: Long): String {
    val minutes = (kickoff - now) / 60_000L
    return when {
        minutes <= 0L -> "Avspark nu"
        minutes < 60L -> "Avspark om $minutes min"
        minutes < 24 * 60L && minutes % 60 == 0L -> "Avspark om ${minutes / 60} tim"
        minutes < 24 * 60L -> "Avspark om ${minutes / 60} tim ${minutes % 60} min"
        else -> dayAndTime(kickoff, now)
    }
}

private val SIDE = 32.dp
private val PAGE_MAX = 1200.dp
private val ROW_PADDING = PaddingValues(horizontal = 32.dp, vertical = 8.dp)
private val COL_RANK = 34.dp
private val COL_NUM = 32.dp
private val COL_DIFF = 44.dp
private val COL_POINTS = 40.dp
private val COL_FORM = 84.dp
private val COL_NEXT = 52.dp
private const val CARD_SCALE = 1.03f
private const val ROW_SCALE = 1.01f
/** Planhalvans form: 68 meter bred, 52,5 meter lång. */
private const val PITCH_ASPECT = 1.3f
private const val SOON_MS = 30 * 60_000L
/** Startelvorna släpps oftast en timme före avspark. */
private const val LINEUP_BEFORE_MS = 60 * 60_000L
private val LIVE_RED = Color(0xFFFF453A)
private val WIN_GREEN = Color(0xFF34C759)
private val YELLOW_CARD = Color(0xFFFFD60A)
private val PITCH_TOP = Color(0xFF1F5C37)
private val PITCH_BOTTOM = Color(0xFF14432A)
private val INK = Color(0xFF101318)

package se.norrsken.tv.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
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
import se.norrsken.tv.desktop.data.competitionName
import se.norrsken.tv.desktop.data.isMalmoFF
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos

/**
 * Fliken Fotboll: Malmö FF och landslaget först, sedan Champions League,
 * Europa League, Conference League, Premier League, LaLiga och Allsvenskan.
 *
 * Översikten börjar med Malmö FF och Sverige: matchen som pågår, nästa
 * match med nedräkning, formen. Sedan det som pågår, dagens matcher, de
 * senaste resultaten, ligornas ledare och veckans matcher, med kanalen som
 * visar matchen enligt tablån. En liga visar tabellen och ligans matcher. En
 * match visar lagen i sina färger, startelvorna på en plan, händelserna och
 * statistiken. Allt står på en plan i kvällsljus.
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
    LivePreview(state)

    Box(Modifier.fillMaxSize()) {
        PitchBackdrop(Modifier.matchParentSize())
        val selected = state.selectedMatch
        if (selected != null) {
            MatchPage(state, selected)
        } else {
            FootballBrowse(state, focus, lists, scrolls)
        }
    }
}

/**
 * Den lilla tv-rutan i hörnet, bara i Fotboll: en stund efter att man kommit
 * hit visar den en match som pågår på en kanal man har — Malmö FF först,
 * sedan Sverige — utan ljud. Står man en stund på en annan match som pågår
 * byter den dit. Play eller OK på rutan ger helskärm med ljud. Spelas redan
 * något annat lämnas det i fred, och lämnar man Fotboll stängs rutan.
 */
@Composable
private fun LivePreview(state: AppState) {
    LaunchedEffect(state) {
        delay(PREVIEW_AFTER_MS)
        var offered = false
        snapshotFlow { previewCandidate(state) }.collect { match ->
            if (match == null) return@collect
            val current = state.footballPreview
            when {
                // En gång per besök, och bara om inget annat spelas.
                !offered -> {
                    offered = true
                    if (current == null && state.nowPlaying == null && !state.footballPreviewDismissed) state.previewMatch(match)
                }
                // Matchen i rutan är slut: nästa som pågår.
                current != null && current != match.id &&
                    state.footballMatches.none { it.id == current && it.state == MatchState.LIVE } -> state.previewMatch(match)
            }
        }
    }
    DisposableEffect(state) {
        onDispose { state.stopFootballPreview() }
    }
}

/** Matchen rutan helst visar: en som pågår på en kanal man har, Malmö FF och Sverige först. */
private fun previewCandidate(state: AppState): FootballMatch? =
    state.footballMatches
        .filter { it.state == MatchState.LIVE && !state.matchChannels[it.id].isNullOrEmpty() }
        .minByOrNull {
            when {
                it.isMalmoFF() -> 0
                it.league == League.SWEDEN -> 1
                else -> 2
            }
        }

/** Står markeringen en stund på en match som pågår: rutan byter dit (om den visas, eller inget spelas). */
@Composable
private fun PreviewOnFocus(state: AppState, match: FootballMatch, active: Boolean) {
    if (match.state != MatchState.LIVE) return
    LaunchedEffect(active, match.id) {
        if (!active) return@LaunchedEffect
        delay(PREVIEW_FOCUS_MS)
        if (canPreview(state, match)) state.previewMatch(match)
    }
}

/** Rutan får visa [match]: den visar något annat än den, eller inget spelas — och man har inte stängt den. */
private fun canPreview(state: AppState, match: FootballMatch): Boolean =
    !state.footballPreviewDismissed && state.footballPreview != match.id &&
        (state.footballPreview != null || state.nowPlaying == null)

/** Översikten eller en liga, med ligorna i kategorilistan på tv:n. */
@Composable
private fun FootballBrowse(
    state: AppState,
    focus: FootballFocus,
    lists: HashMap<String, LazyListState>,
    scrolls: HashMap<String, ScrollState>,
) {
    if (tvLayout) {
        LaunchedEffect(Unit) {
            if (focus.openedKey != null) {
                withFrameNanos { }
                runCatching { focus.restore.requestFocus() }
            }
        }
    }
    // Tv: tappas markeringen hamnar den på matchen man senast öppnade, annars i ligorna.
    TvFocusFallback {
        (focus.openedKey != null && runCatching { focus.restore.requestFocus() }.isSuccess) ||
            run {
                TvNav.openCategories()
                true
            }
    }
    val league = state.footballLeague
    if (tvLayout) {
        // Byts ligan (med en ledarruta eller Tillbaka) går markeringen till
        // ligans flik. Inte när man bara kommer tillbaka från en match, och inte
        // när ligan valdes i själva listan: då är man redan där, eller på väg in.
        LaunchedEffect(league) {
            val changed = focus.started && focus.shownLeague != league && !focus.fromList
            val enter = focus.enterPage
            focus.shownLeague = league
            focus.started = true
            focus.fromList = false
            focus.enterPage = false
            if (enter) {
                // Lagets ruta i översikten: rakt in på lagets sida, på lagets stora kort.
                repeat(2) { withFrameNanos { } }
                if (runCatching { focus.first.requestFocus() }.isFailure) runCatching { focus.content.requestFocus() }
            } else if (changed) {
                withFrameNanos { }
                // Tv: ligorna ligger i kategorilistan, som tar emot markeringen
                // (om man inte redan bläddrar i den).
                if (TvNav.drawer != TvDrawer.CATEGORIES) TvNav.openCategories()
            }
        }
    }
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    Row(Modifier.fillMaxSize()) {
        // Tv: ligorna är kategorierna, som i Filmer — de glider fram med pil
        // vänster, och ligan byts medan man bläddrar bland dem.
        if (tvLayout) {
            // Malmö FF räknar lagets matcher i alla tävlingar, också de som hör till en liga.
            val counts = remember(state.footballMatches) {
                val all = state.footballMatches
                League.entries.associate { entry ->
                    entry.title to if (entry == League.MALMO_FF) all.count { it.isMalmoFF() } else all.count { it.league == entry }
                }
            }
            // Ligornas loggor bredvid namnen; Malmö FF och landslaget med sina märken.
            val logos = remember(state.footballMatches, state.footballLogos) {
                League.entries.mapNotNull { entry ->
                    val url = if (entry.isTeam) {
                        teamCrest(state.footballMatches, entry) ?: entry.teamId?.let { "$ESPN_TEAM_LOGOS/$it.png" }
                    } else {
                        state.footballLogos[entry]
                    }
                    url?.let { entry.title to it }
                }.toMap()
            }
            GroupColumn(
                allLabel = "Översikt",
                total = state.footballMatches.size,
                groups = League.entries.map { it.title },
                counts = counts,
                selected = league?.title,
                logos = logos,
                allIcon = Glyphs.Football,
                look = GroupLook(MenuAccents.football, "Fotboll", Glyphs.Football),
                onSelect = { title ->
                    val next = League.entries.firstOrNull { it.title == title }
                    if (next != state.footballLeague) focus.fromList = true
                    state.footballLeague = next
                },
                title = "LIGOR",
                // Pil höger eller OK i ligalistan: in på sidan, till matchen man senast
                // öppnade om den syns, annars det första på sidan. Översikten likadant
                // som ligorna, som har annat innehåll och annars inte gick att nå.
                onEnterContent = {
                    scope.launch {
                        // Byttes ligan nyss: vänta in den nya sidan. Den kan behöva några
                        // bilder på sig (lagens sidor räknas fram först), så det prövas igen
                        // tills markeringen verkligen lämnat ligalistan.
                        repeat(2) { withFrameNanos { } }
                        for (attempt in 0 until ENTER_ATTEMPTS) {
                            val restored = focus.openedKey != null && runCatching { focus.restore.requestFocus() }.isSuccess
                            if (!restored && runCatching { focus.first.requestFocus() }.isFailure) {
                                runCatching { focus.content.requestFocus() }
                            }
                            withFrameNanos { }
                            if (TvNav.drawer != TvDrawer.CATEGORIES) return@launch
                            // Fanns inget att stå på: åt höger, som förut.
                            focusManager.moveFocus(FocusDirection.Right)
                            withFrameNanos { }
                            if (TvNav.drawer != TvDrawer.CATEGORIES) return@launch
                        }
                    }
                },
            )
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            FootballHeader(state)
            if (!tvLayout) LeagueChips(state, league, focus)
            Box(Modifier.weight(1f).fillMaxWidth().focusRequester(focus.content)) {
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
    /** Sidan under rubriken: pil höger från ligalistan går in hit. */
    val content = FocusRequester()
    /** Ligan valdes nyss i ligalistan (inte med en ledarruta eller Tillbaka). */
    var fromList = false
    /** Ett lags ruta i översikten valdes: markeringen går in på lagets sida. */
    var enterPage = false
    /** Det första på sidan: Malmö FF:s kort i översikten, lagets stora kort på dess sida. */
    val first = FocusRequester()
}

private fun Modifier.restoreFocus(focus: FootballFocus, key: String): Modifier =
    if (key == focus.openedKey) focusRequester(focus.restore) else this

private fun showMatch(state: AppState, focus: FootballFocus, match: FootballMatch, key: String) {
    focus.openedKey = key
    state.openMatch(match)
}

/**
 * Rubriken: bollen, som rullar medan något pågår, och till höger en
 * resultatrad som i tv-sändningarna, med ställningen i matcherna som pågår.
 */
@Composable
private fun FootballHeader(state: AppState) {
    val palette = LocalPalette.current
    val live = remember(state.footballMatches) {
        // Malmö FF och Sverige först i raden.
        state.footballMatches.filter { it.state == MatchState.LIVE }.sortedBy { if (highlightOf(it) != null) 0 else 1 }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = SIDE, end = SIDE, top = 22.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RollingBall(rolling = live.isNotEmpty())
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Fotboll",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
                color = palette.textPrimary,
                maxLines = 1,
            )
            val updated = state.footballUpdatedAt
            Text(
                when {
                    updated <= 0L -> "Hämtar matcher…"
                    state.footballError != null -> "Uppdaterad ${clockOf(updated)} · nätet svarar inte just nu"
                    live.size == 1 -> "1 match pågår · uppdaterad ${clockOf(updated)}"
                    live.size > 1 -> "${live.size} matcher pågår · uppdaterad ${clockOf(updated)}"
                    else -> "Uppdaterad ${clockOf(updated)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (live.isNotEmpty() && state.footballError == null) CHALK.copy(alpha = 0.78f) else palette.textTertiary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(32.dp))
        if (live.isNotEmpty()) {
            LiveTicker(live, Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Fotbollen i rubriken. Rullar sakta medan en match pågår. */
@Composable
private fun RollingBall(rolling: Boolean) {
    val size = 46.dp
    val ball = Modifier
        .size(size)
        .clip(CircleShape)
        .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.06f))))
        .border(1.5.dp, CHALK.copy(alpha = 0.55f), CircleShape)
    if (!rolling) {
        Box(ball, contentAlignment = Alignment.Center) {
            Icon(Glyphs.Football, contentDescription = null, tint = CHALK, modifier = Modifier.size(28.dp))
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "boll")
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing)),
        label = "varv",
    )
    Box(ball, contentAlignment = Alignment.Center) {
        // Bara lagret vrids: inget ritas om eller mäts om medan bollen rullar.
        Icon(
            Glyphs.Football,
            contentDescription = null,
            tint = CHALK,
            modifier = Modifier.size(28.dp).graphicsLayer { rotationZ = turn },
        )
    }
}

/**
 * Resultatraden: LIVE i rött och ställningen i varje match som pågår, som
 * rullar förbi när den inte får plats. Bara raden flyttas, i ritningen.
 */
@Composable
private fun LiveTicker(live: List<FootballMatch>, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(TICKER_BG)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.fillMaxHeight().background(LIVE_RED).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveDot(Color.White)
            Spacer(Modifier.width(7.dp))
            Text(
                "LIVE",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 1.4.sp),
                color = Color.White,
                maxLines = 1,
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds(), contentAlignment = Alignment.CenterStart) {
            Row(
                Modifier
                    .basicMarquee(
                        iterations = Int.MAX_VALUE,
                        repeatDelayMillis = 0,
                        initialDelayMillis = 1200,
                        spacing = MarqueeSpacing(56.dp),
                        velocity = 42.dp,
                    )
                    .padding(start = 18.dp, end = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                live.forEachIndexed { index, match ->
                    if (index > 0) {
                        Box(Modifier.padding(horizontal = 18.dp).size(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.30f)))
                    }
                    TickerScore(match)
                }
            }
            // Kanterna tonar ut i radens färg.
            Box(
                Modifier.align(Alignment.CenterStart).width(22.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(TICKER_BG, TICKER_BG.copy(alpha = 0f)))),
            )
            Box(
                Modifier.align(Alignment.CenterEnd).width(28.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(TICKER_BG.copy(alpha = 0f), TICKER_BG))),
            )
        }
    }
}

/** "MFF 2–1 AIK 67'" med lagens loggor, Malmö FF och Sverige i sina färger. */
@Composable
private fun TickerScore(match: FootballMatch) {
    val mark = highlightOf(match)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (mark != null) {
            Box(Modifier.size(width = 3.dp, height = 22.dp).clip(RoundedCornerShape(2.dp)).background(mark))
            Spacer(Modifier.width(9.dp))
        }
        TeamLogo(match.home, 22.dp)
        Spacer(Modifier.width(8.dp))
        Text(tickerName(match.home), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
        Text(
            "  ${match.homeScore ?: 0}–${match.awayScore ?: 0}  ",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
            color = Color.White,
            maxLines = 1,
        )
        Text(tickerName(match.away), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        TeamLogo(match.away, 22.dp)
        Spacer(Modifier.width(10.dp))
        Text(match.status, style = MaterialTheme.typography.labelLarge, color = LIVE_SOFT, maxLines = 1)
    }
}

/** Kort som på en resultattavla: "MFF", "ARS", "SWE". */
private fun tickerName(team: FootballTeam): String {
    if (team.isMalmoFF()) return "MFF"
    return team.abbreviation.ifBlank { team.short }.uppercase()
}

@Composable
private fun LeagueChips(state: AppState, selected: League?, focus: FootballFocus) {
    val liveBy = state.footballMatches.filter { it.state == MatchState.LIVE }.groupingBy { it.league }.eachCount()
    // Lagen har sina klubbmärken i stället för en ligalogga.
    val crests = remember(state.footballMatches) {
        League.entries.filter { it.isTeam }.mapNotNull { team -> teamCrest(state.footballMatches, team)?.let { team to it } }.toMap()
    }
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
                logo = if (league.isTeam) crests[league] else state.footballLogos[league],
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
            Spacer(Modifier.width(10.dp))
        } else {
            // Ett kritstreck, som linjerna på planen.
            Box(Modifier.size(width = 4.dp, height = 20.dp).clip(RoundedCornerShape(2.dp)).background(CHALK.copy(alpha = 0.85f)))
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
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
    val mff = remember(matches) { teamFocus(matches, League.MALMO_FF) }
    val sweden = remember(matches) { teamFocus(matches, League.SWEDEN) }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = lists.getOrPut("översikt") { LazyListState() },
        contentPadding = PaddingValues(top = 2.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Malmö FF och Sverige först: det som pågår, annars nästa match. Ett tryck
        // öppnar matchen som pågår, annars lagets egen sida.
        item(key = "fokus") {
            Row(
                side.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                for ((team, info) in listOf(League.MALMO_FF to mff, League.SWEDEN to sweden)) {
                    val key = "spot:${team.name}"
                    SpotlightCard(
                        state = state,
                        team = team,
                        info = info,
                        big = false,
                        onClick = {
                            val live = info.live
                            if (live != null) {
                                showMatch(state, focus, live, key)
                            } else {
                                focus.openedKey = key
                                focus.enterPage = true
                                state.footballLeague = team
                            }
                        },
                        modifier = Modifier
                            .weight(if (team == League.MALMO_FF) 1.3f else 1f)
                            .then(if (team == League.MALMO_FF) Modifier.focusRequester(focus.first) else Modifier)
                            .restoreFocus(focus, key),
                    )
                }
            }
        }
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

/**
 * En match som pågår: lagen i sina färger, ställningen, minuten som en
 * stapel, senaste målet och kanalen. Görs ett mål blinkar rutan i det
 * lagets färg: MÅL!
 */
@Composable
private fun LiveCard(state: AppState, match: FootballMatch, focus: FootballFocus) {
    val palette = LocalPalette.current
    val accent = Color(match.league.accent)
    val homeColor = teamColor(match.home, accent)
    val awayColor = teamColor(match.away, accent)
    val channel = state.matchChannels[match.id]?.firstOrNull()
    val key = "live:${match.id}"
    val shape = RoundedCornerShape(20.dp)
    val mark = highlightOf(match)
    // Målen räknas mot det rutan senast visade. Kommer den nyss fram blinkar inget.
    val homeGoals = match.homeScore ?: 0
    val awayGoals = match.awayScore ?: 0
    val seen = remember(match.id) { intArrayOf(homeGoals, awayGoals) }
    val flash = remember(match.id) { Animatable(0f) }
    var scorer by remember(match.id) { mutableStateOf<Color?>(null) }
    LaunchedEffect(match.id, homeGoals, awayGoals) {
        val home = homeGoals > seen[0]
        val away = awayGoals > seen[1]
        seen[0] = homeGoals
        seen[1] = awayGoals
        if (home || away) {
            scorer = if (home) homeColor else awayColor
            flash.snapTo(1f)
            flash.animateTo(0f, tween(GOAL_FLASH_MS, easing = LinearEasing))
        }
    }
    Pressable(
        onClick = { showMatch(state, focus, match, key) },
        modifier = Modifier.width(384.dp).restoreFocus(focus, key),
        shape = shape,
        hoverScale = CARD_SCALE,
    ) { active ->
        PreviewOnFocus(state, match, active)
        Box {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .background(
                        Brush.horizontalGradient(
                            listOf(homeColor.copy(alpha = 0.34f), Color.Transparent, awayColor.copy(alpha = 0.34f)),
                        ),
                    )
                    .then(if (mark != null) Modifier.border(2.dp, mark.copy(alpha = 0.9f), shape) else Modifier)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MatchTag(state, match)
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
                MinuteBar(match, homeColor, awayColor)
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
            val color = scorer
            if (color != null) GoalFlash(color, { flash.value }, Modifier.matchParentSize())
        }
    }
}

/**
 * Hur långt matchen kommit, som en stapel i lagens färger med ett streck
 * vid halvtid. Står inte minuten att läsa (förlängning, straffar) syns ingen.
 */
@Composable
private fun MinuteBar(match: FootballMatch, homeColor: Color, awayColor: Color) {
    val progress = matchProgress(match)
    Box(Modifier.fillMaxWidth().height(4.dp)) {
        if (progress != null) {
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.10f)))
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.horizontalGradient(listOf(homeColor, awayColor))),
            )
            Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.45f)))
        }
    }
}

/** 0–1 för minuten i en match som pågår: "67'" → 0,74, "Halvtid" → 0,5. */
private fun matchProgress(match: FootballMatch): Float? {
    if (match.state != MatchState.LIVE) return null
    val status = match.status.trim()
    if (status.startsWith("Halvtid", ignoreCase = true) || status.equals("HT", ignoreCase = true)) return 0.5f
    val minute = MINUTE.find(status)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return (minute / 90f).coerceIn(0.02f, 1f)
}

private val MINUTE = Regex("""^(\d{1,3})""")

/**
 * MÅL! över rutan i det lagets färg, som blinkar och tonar bort. Bara
 * lagrets genomskinlighet ändras medan det pågår: inget mäts eller ritas om.
 */
@Composable
private fun GoalFlash(color: Color, flash: () -> Float, modifier: Modifier) {
    Box(
        modifier
            .graphicsLayer {
                val f = flash()
                alpha = if (f <= 0f) 0f else (f * (0.62f + 0.38f * cos(f * 18.85f))).coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .background(Brush.radialGradient(listOf(color.copy(alpha = 0.92f), color.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "MÅL!",
            style = scoreStyle(64.sp).copy(
                letterSpacing = 4.sp,
                shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 4f), 16f),
            ),
            color = Color.White,
            maxLines = 1,
        )
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
    val mark = highlightOf(match)
    Pressable(
        onClick = { showMatch(state, focus, match, key) },
        modifier = Modifier.width(260.dp).restoreFocus(focus, key),
        shape = RoundedCornerShape(16.dp),
        hoverScale = CARD_SCALE,
    ) { _ ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(palette.surface)
                .then(if (mark != null) Modifier.background(mark.copy(alpha = 0.12f)) else Modifier)
                .drawBehind {
                    // Ligans färg som en kant överst, som på en resultattavla.
                    drawRect(mark ?: Color(match.league.accent), size = Size(size.width, 3.dp.toPx()))
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MatchTag(state, match)
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
    /** Tävlingen under tiden i stället för till höger: lagens sidor, där matcherna spelas i flera. */
    tagUnderTime: Boolean = false,
) {
    val palette = LocalPalette.current
    val mark = highlightOf(match)
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
                .then(
                    if (mark != null) {
                        // Malmö FF och Sverige: en ton och en kant i lagets färg.
                        Modifier
                            .background(Brush.horizontalGradient(listOf(mark.copy(alpha = 0.22f), mark.copy(alpha = 0.05f))))
                            .drawBehind { drawRect(mark, size = Size(4.dp.toPx(), size.height)) }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(if (tagUnderTime) 124.dp else 100.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                if (tagUnderTime) {
                    Spacer(Modifier.height(2.dp))
                    MatchTag(state, match)
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
                    MatchTag(state, match)
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

// --- Malmö FF och Sverige -------------------------------------------------------------

/** Ett lags matcher i korthet: den som pågår, nästa, den senaste, formen och märket. */
private class TeamFocus(
    val crest: FootballTeam?,
    val live: FootballMatch?,
    val next: FootballMatch?,
    val last: FootballMatch?,
    /** De senaste fem, äldst först: W, D, L. */
    val form: String,
) {
    /** Matchen kortet handlar om: den som pågår, annars nästa, annars den senaste. */
    val main: FootballMatch? get() = live ?: next ?: last
}

/** Laget [team] är det här laget (Malmö FF eller landslaget). */
private fun League.owns(team: FootballTeam): Boolean = when {
    team.id == teamId -> true
    this == League.MALMO_FF -> team.isMalmoFF()
    else -> teamName != null && team.name.equals(teamName, ignoreCase = true)
}

private fun teamFocus(matches: List<FootballMatch>, team: League): TeamFocus {
    val mine = matches.filter { team.owns(it.home) || team.owns(it.away) }
    val played = mine
        .filter { it.state == MatchState.FINISHED && it.homeScore != null && it.awayScore != null }
        .sortedByDescending { it.kickoff }
    val own = mine.flatMap { listOf(it.home, it.away) }.filter { team.owns(it) }
    val form = played.take(5).reversed().joinToString("") { match ->
        resultFor(match, if (team.owns(match.home)) match.home.id else match.away.id)
    }
    return TeamFocus(
        crest = own.firstOrNull { it.logo != null } ?: own.firstOrNull(),
        live = mine.firstOrNull { it.state == MatchState.LIVE },
        next = mine.filter { it.state == MatchState.UPCOMING }.minByOrNull { it.kickoff },
        last = played.firstOrNull(),
        form = form,
    )
}

/** Lagets klubbmärke (landslaget: flaggan), ur dess matcher. */
private fun teamCrest(matches: List<FootballMatch>, team: League): String? =
    matches.asSequence().flatMap { sequenceOf(it.home, it.away) }.firstOrNull { team.owns(it) && it.logo != null }?.logo

/** Malmö FF:s och landslagets matcher sticker ut i lagets färg. Andra: null. */
private fun highlightOf(match: FootballMatch): Color? = when {
    match.isMalmoFF() -> MFF_SKY
    match.league == League.SWEDEN || League.SWEDEN.owns(match.home) || League.SWEDEN.owns(match.away) -> SWE_YELLOW
    else -> null
}

/**
 * Malmö FF:s och landslagets kort: lagets färger och mönster — tunna ränder
 * i himmelsblått, korset i blågult — och matchen som pågår, annars nästa
 * match med nedräkning, annars det senaste resultatet. Underst formen.
 * Markeras kortet sveper ett ljus över det, en gång.
 */
@Composable
private fun SpotlightCard(
    state: AppState,
    team: League,
    info: TeamFocus,
    big: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val mff = team == League.MALMO_FF
    val shape = RoundedCornerShape(if (big) 28.dp else 24.dp)
    val sheen = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val crest = info.crest?.logo?.takeIf { mff }
    Pressable(
        onClick = onClick,
        modifier = modifier.onFocusChanged {
            if (it.isFocused) {
                scope.launch {
                    sheen.snapTo(0f)
                    sheen.animateTo(1f, tween(SHEEN_MS, easing = FastOutSlowInEasing))
                }
            }
        },
        shape = shape,
        hoverScale = if (big) 1.012f else CARD_SCALE,
        glowing = false,
    ) { active ->
        info.live?.let { PreviewOnFocus(state, it, active) }
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (big) 292.dp else 250.dp)
                .drawBehind { if (mff) drawMalmoFF() else drawSweden() }
                .drawWithContent {
                    drawContent()
                    // Ljussvepet läses först här: bara ritningen görs om medan det går.
                    val t = sheen.value
                    if (t > 0f && t < 1f) drawSheen(t)
                },
        ) {
            // Märket stort och svagt till höger, delvis utanför kanten.
            if (crest != null) {
                RemoteImage(
                    url = crest,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = 54.dp)
                        .size(if (big) 320.dp else 270.dp)
                        .graphicsLayer {
                            alpha = 0.15f
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                    maxSide = 512,
                    contentScale = ContentScale.Fit,
                )
            }
            Column(
                Modifier.fillMaxSize().padding(horizontal = if (big) 30.dp else 24.dp, vertical = if (big) 24.dp else 20.dp),
            ) {
                SpotHeader(state, team, info, big)
                Spacer(Modifier.weight(1f))
                SpotBody(state, team, info, big)
                Spacer(Modifier.height(if (big) 16.dp else 12.dp))
                SpotFooter(state, info, big)
            }
        }
    }
}

/** Lagets namn och märke, och för Malmö FF placeringen i Allsvenskan. */
@Composable
private fun SpotHeader(state: AppState, team: League, info: TeamFocus, big: Boolean) {
    val mff = team == League.MALMO_FF
    Row(verticalAlignment = Alignment.CenterVertically) {
        info.crest?.let {
            TeamLogo(it, if (big) 54.dp else 44.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (mff) "MALMÖ FF" else "SVERIGE",
                style = TextStyle(
                    fontWeight = FontWeight.Black,
                    fontSize = if (big) 34.sp else 28.sp,
                    lineHeight = if (big) 38.sp else 32.sp,
                    letterSpacing = 2.sp,
                    shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 8f),
                ),
                color = Color.White,
                maxLines = 1,
            )
            Text(
                if (mff) "Di blåe" else "Herrlandslaget · Blågult",
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.8.sp),
                color = Color.White.copy(alpha = 0.74f),
                maxLines = 1,
            )
        }
        if (mff) {
            val row = state.footballTables[League.ALLSVENSKAN]?.firstOrNull { it.team.isMalmoFF() }
            if (row != null && row.played > 0) {
                SpotChip("${ordinal(row.rank)} i Allsvenskan · ${row.points} p")
            }
        }
        if (info.live != null) {
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(LIVE_RED).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiveDot(Color.White)
                Spacer(Modifier.width(6.dp))
                Text(
                    "LIVE",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, letterSpacing = 1.2.sp),
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun SpotChip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
    }
}

/** Matchen som pågår, nästa match eller det senaste resultatet. */
@Composable
private fun SpotBody(state: AppState, team: League, info: TeamFocus, big: Boolean) {
    val live = info.live
    val next = info.next
    val last = info.last
    when {
        live != null -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(live.home, if (big) 52.dp else 42.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    live.home.short,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${live.homeScore ?: 0} – ${live.awayScore ?: 0}",
                    style = scoreStyle(if (big) 54.sp else 44.sp),
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    live.away.short,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                TeamLogo(live.away, if (big) 52.dp else 42.dp)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot(LIVE_SOFT)
                Spacer(Modifier.width(7.dp))
                val goal = live.goals.lastOrNull()
                Text(
                    listOfNotNull(
                        live.status,
                        competitionName(live.slug),
                        goal?.let { "mål: " + goalLabel(it.player, it.minute, it.penalty, it.ownGoal) },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.86f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        next != null -> {
            val home = team.owns(next.home)
            val opponent = if (home) next.away else next.home
            Text(
                "NÄSTA MATCH",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp),
                color = Color.White.copy(alpha = 0.70f),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(opponent, if (big) 62.dp else 50.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        (if (home) "Hemma mot " else "Borta mot ") + opponent.short,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(competitionName(next.slug), next.venue).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        spotDay(next.kickoff, state.clock),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                    )
                    Text(clockOf(next.kickoff), style = scoreStyle(if (big) 48.sp else 40.sp), color = Color.White, maxLines = 1)
                    Text(
                        spotCountdown(next.kickoff, state.clock),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                    )
                }
            }
        }
        last != null -> {
            Text(
                "SENASTE MATCHEN",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp),
                color = Color.White.copy(alpha = 0.70f),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(last.home, 40.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "${last.home.short}  ${last.homeScore ?: 0} – ${last.awayScore ?: 0}  ${last.away.short}",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
                TeamLogo(last.away, 40.dp)
                Spacer(Modifier.width(14.dp))
                ResultChip(resultFor(last, if (team.owns(last.home)) last.home.id else last.away.id))
            }
        }
        else -> Text(
            if (state.footballUpdatedAt <= 0L) "Hämtar matcher…" else "Inga matcher hämtade ännu. Appen frågar igen om en stund.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.80f),
        )
    }
}

/** Formen och kanalen, eller förra resultatet när kortet visar nästa match. */
@Composable
private fun SpotFooter(state: AppState, info: TeamFocus, big: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (info.form.isNotEmpty()) {
            Text(
                "FORM",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
                color = Color.White.copy(alpha = 0.62f),
            )
            Spacer(Modifier.width(10.dp))
            FormPills(info.form)
        }
        Spacer(Modifier.weight(1f))
        val shown = info.live ?: info.next
        val channel = shown?.let { state.matchChannels[it.id]?.firstOrNull() }
        val last = info.last
        when {
            channel != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Live, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    channel.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = if (big) 320.dp else 220.dp),
                )
            }
            shown != null && last != null -> Text(
                "Senast: ${last.home.short} ${last.homeScore ?: 0}–${last.awayScore ?: 0} ${last.away.short}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (big) 380.dp else 260.dp),
            )
        }
    }
}

/** Formen som brickor: V, O, F i grönt, grått och rött, äldst till vänster. */
@Composable
private fun FormPills(form: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        form.uppercase().filter { it == 'W' || it == 'D' || it == 'L' }.takeLast(5).forEach { result ->
            val (letter, color) = when (result) {
                'W' -> "V" to WIN_GREEN
                'L' -> "F" to LIVE_RED
                else -> "O" to Color.White.copy(alpha = 0.34f)
            }
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

/** VINST, OAVGJORT eller FÖRLUST efter en match. */
@Composable
private fun ResultChip(result: String) {
    val (text, color) = when (result) {
        "W" -> "VINST" to WIN_GREEN
        "L" -> "FÖRLUST" to LIVE_RED
        "D" -> "OAVGJORT" to Color.White.copy(alpha = 0.34f)
        else -> return
    }
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp),
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** "Idag", "Imorgon", "lör 29 nov". */
private fun spotDay(kickoff: Long, now: Long): String {
    val today = dayOf(now)
    return when (val day = dayOf(kickoff)) {
        today -> "Idag"
        today.plusDays(1) -> "Imorgon"
        else -> day.format(shortDayFormat).replace(".", "")
    }
}

/** "Avspark om 25 min", "om 3 tim", "om 4 dagar", "om 6 veckor". */
private fun spotCountdown(kickoff: Long, now: Long): String {
    val minutes = (kickoff - now) / 60_000L
    val days = java.time.temporal.ChronoUnit.DAYS.between(dayOf(now), dayOf(kickoff))
    return when {
        minutes <= 0L -> "Avspark nu"
        minutes < 60L -> "Avspark om $minutes min"
        minutes < 12 * 60L -> "Avspark om ${minutes / 60} tim"
        days <= 1L -> "Avspark om ${minutes / 60} tim"
        days < 14L -> "om $days dagar"
        else -> "om ${days / 7} veckor"
    }
}

/** Malmö FF: himmelsblått i snett ljus, med tunna ränder som i matchtröjan. */
private fun DrawScope.drawMalmoFF() {
    drawRect(
        Brush.linearGradient(
            listOf(MFF_NIGHT, MFF_DEEP, MFF_SKY),
            start = Offset(0f, size.height),
            end = Offset(size.width, 0f),
        ),
    )
    val gap = 18.dp.toPx()
    val stroke = 1.2.dp.toPx()
    val stripe = Color.White.copy(alpha = 0.06f)
    var x = -size.height
    while (x < size.width) {
        drawLine(stripe, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = stroke)
        x += gap
    }
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent), endY = size.height * 0.45f))
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f)), startY = size.height * 0.5f))
}

/** Sverige: blått, och flaggans kors svagt i gult till höger om texten. */
private fun DrawScope.drawSweden() {
    drawRect(
        Brush.linearGradient(
            listOf(SWE_NIGHT, SWE_DEEP, SWE_BLUE),
            start = Offset(0f, size.height),
            end = Offset(size.width, 0f),
        ),
    )
    val bar = size.height * 0.15f
    val crossX = size.width * 0.74f
    val crossY = size.height * 0.5f - bar / 2
    val yellow = SWE_YELLOW.copy(alpha = 0.22f)
    val fadeFrom = size.width * 0.42f
    drawRect(yellow, topLeft = Offset(crossX, 0f), size = Size(bar, size.height))
    // Den liggande balken i två delar, så att korsningen inte blir dubbelt så ljus.
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, yellow), startX = fadeFrom, endX = crossX),
        topLeft = Offset(fadeFrom, crossY),
        size = Size(crossX - fadeFrom, bar),
    )
    drawRect(yellow, topLeft = Offset(crossX + bar, crossY), size = Size(size.width - crossX - bar, bar))
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), endY = size.height * 0.45f))
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f)), startY = size.height * 0.5f))
}

/** Ljussvepet över ett markerat kort: ett snett band som går från vänster till höger. */
private fun DrawScope.drawSheen(t: Float) {
    val band = size.width * 0.32f
    val x = -band + (size.width + band * 2) * t
    drawRect(
        Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
            start = Offset(x - band / 2, 0f),
            end = Offset(x + band / 2, size.height * 0.55f),
        ),
    )
}

// --- Planen bakom allt ----------------------------------------------------------------

/**
 * Fotbollssidans bakgrund: en plan i kvällsljus sedd uppifrån — mörkt gräs
 * med klippta ränder, svaga kritlinjer, strålkastarsken från hörnen och
 * mörkare kanter. Står still och ritas en gång i ett eget lager, så att den
 * inte kostar något när man bläddrar.
 */
@Composable
private fun PitchBackdrop(modifier: Modifier) {
    Spacer(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind { drawStadium() },
    )
}

private fun DrawScope.drawStadium() {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    drawRect(Brush.verticalGradient(listOf(GRASS_TOP, GRASS_BOTTOM)))
    // Klippta ränder i gräset.
    val stripes = 12
    val stripe = w / stripes
    for (i in 0 until stripes step 2) {
        drawRect(Color.White.copy(alpha = 0.022f), topLeft = Offset(i * stripe, 0f), size = Size(stripe, h))
    }
    // Planen liggande, 105 × 68 meter, nästan lika bred som skärmen.
    val fieldW = w * 0.9f
    val fieldH = fieldW * 68f / 105f
    val left = (w - fieldW) / 2
    val top = (h - fieldH) / 2
    val m = fieldW / 105f
    val line = Color.White.copy(alpha = 0.07f)
    val width = 2.dp.toPx()
    val stroke = Stroke(width = width)
    val cx = left + fieldW / 2
    val cy = top + fieldH / 2
    drawRect(line, topLeft = Offset(left, top), size = Size(fieldW, fieldH), style = stroke)
    drawLine(line, Offset(cx, top), Offset(cx, top + fieldH), strokeWidth = width)
    drawCircle(line, radius = 9.15f * m, center = Offset(cx, cy), style = stroke)
    drawCircle(line, radius = 3.dp.toPx(), center = Offset(cx, cy))
    val arc = 9.15f * m
    val half = Math.toDegrees(acos((5.5f / 9.15f).toDouble())).toFloat()
    for (side in 0..1) {
        val goalX = if (side == 0) left else left + fieldW
        val inward = if (side == 0) 1f else -1f
        val boxD = 16.5f * m
        val boxW = 40.32f * m
        val smallD = 5.5f * m
        val smallW = 18.32f * m
        drawRect(line, topLeft = Offset(if (side == 0) goalX else goalX - boxD, cy - boxW / 2), size = Size(boxD, boxW), style = stroke)
        drawRect(line, topLeft = Offset(if (side == 0) goalX else goalX - smallD, cy - smallW / 2), size = Size(smallD, smallW), style = stroke)
        // Straffpunkten och bågen utanför straffområdet.
        val spot = Offset(goalX + inward * 11f * m, cy)
        drawCircle(line, radius = 3.dp.toPx(), center = spot)
        drawArc(
            line,
            startAngle = (if (side == 0) 0f else 180f) - half,
            sweepAngle = half * 2,
            useCenter = false,
            topLeft = Offset(spot.x - arc, spot.y - arc),
            size = Size(arc * 2, arc * 2),
            style = stroke,
        )
        // Målet, utanför mållinjen.
        val goalW = 7.32f * m
        val depth = 2f * m
        drawRect(
            line,
            topLeft = Offset(if (side == 0) goalX - depth else goalX, cy - goalW / 2),
            size = Size(depth, goalW),
            style = stroke,
        )
    }
    // Strålkastarna: kallt ljus från hörnen uppe.
    val flood = Color(0x26E6F2FF)
    drawRect(Brush.radialGradient(listOf(flood, Color.Transparent), center = Offset(0f, 0f), radius = w * 0.55f))
    drawRect(Brush.radialGradient(listOf(flood, Color.Transparent), center = Offset(w, 0f), radius = w * 0.55f))
    // Mörkare kanter, så att det man läser står i ljuset.
    drawRect(
        Brush.radialGradient(
            0f to Color.Transparent,
            0.5f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.6f),
            center = Offset(w / 2, h * 0.45f),
            radius = maxOf(w, h) * 0.78f,
        ),
    )
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
    if (league.isTeam) {
        TeamView(state, league, focus, lists, scrolls)
        return
    }
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
    /** Lagens sidor: tävlingen vid varje match, och dagen också på kommande som ligger långt fram. */
    team: Boolean = false,
) {
    if (upcoming.isEmpty() && finished.isEmpty()) {
        item(key = "inga") { SectionTitle(if (team) "Inga matcher hämtade ännu" else "Inga matcher just nu") }
        return
    }
    for ((day, list) in upcoming.groupBy { dayOf(it.kickoff) }) {
        item(key = "dag-$day") { SectionTitle(dayLabel(day, today), trailing = matchCount(list.size)) }
        items(list, key = { "k" + it.id }) { match ->
            MatchRow(state, match, focus, key = "liga:${match.id}", showLeague = false, tagUnderTime = team)
        }
    }
    if (finished.isNotEmpty()) {
        item(key = "spelade") { SectionTitle("Spelade") }
        items(finished.take(30), key = { "s" + it.id }) { match ->
            MatchRow(
                state, match, focus,
                key = "liga:${match.id}",
                showLeague = false,
                dayText = shortDay(match.kickoff, today),
                tagUnderTime = team,
            )
        }
    }
}

/**
 * Malmö FF:s och landslagets sida: det stora kortet med matchen som pågår
 * eller nästa match, Allsvenskans tabell för Malmö FF, och lagets matcher
 * i alla tävlingar.
 */
@Composable
private fun TeamView(
    state: AppState,
    team: League,
    focus: FootballFocus,
    lists: HashMap<String, LazyListState>,
    scrolls: HashMap<String, ScrollState>,
) {
    val all = state.footballMatches
    val today = dayOf(state.clock)
    val matches = remember(all, team) { all.filter { team.owns(it.home) || team.owns(it.away) } }
    val info = remember(matches) { teamFocus(matches, team) }
    val upcoming = remember(matches) { matches.filter { it.state != MatchState.FINISHED }.sortedBy { it.kickoff } }
    val finished = remember(matches) { matches.filter { it.state == MatchState.FINISHED }.sortedByDescending { it.kickoff } }
    val table = if (team == League.MALMO_FF) state.footballTables[League.ALLSVENSKAN].orEmpty() else emptyList()
    val teams = remember(all) { teamInfo(all) }
    val key = "spot:${team.name}"
    val spotlight: @Composable () -> Unit = {
        SpotlightCard(
            state = state,
            team = team,
            info = info,
            big = true,
            onClick = { info.main?.let { showMatch(state, focus, it, key) } },
            modifier = Modifier.fillMaxWidth().focusRequester(focus.first).restoreFocus(focus, key),
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 1100.dp && table.isNotEmpty()) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = SIDE),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(
                    Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .verticalScroll(scrolls.getOrPut("lag-${team.name}") { ScrollState(0) })
                        .padding(top = 10.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    spotlight()
                    SectionTitle("Allsvenskan", trailing = "tabellen")
                    TableCard(state, table, teams, focus, Modifier.fillMaxWidth())
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    state = lists.getOrPut("lagmatcher-${team.name}") { LazyListState() },
                    contentPadding = PaddingValues(top = 6.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    leagueMatches(state, focus, today, upcoming, finished, team = true)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = lists.getOrPut("lag-${team.name}") { LazyListState() },
                contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 10.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "fokus") { spotlight() }
                if (table.isNotEmpty()) {
                    item(key = "tabell-titel") { SectionTitle("Allsvenskan", trailing = "tabellen") }
                    item(key = "tabell") { TableCard(state, table, teams, focus, Modifier.fillMaxWidth()) }
                }
                leagueMatches(state, focus, today, upcoming, finished, team = true)
            }
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
        // Malmö FF lyser i himmelsblått, var laget än står.
        val mff = row.team.isMalmoFF()
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .then(if (mff) Modifier.background(Brush.horizontalGradient(listOf(MFF_SKY.copy(alpha = 0.30f), MFF_SKY.copy(alpha = 0.08f)))) else Modifier)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(zone ?: Color.Transparent))
            Text(
                row.rank.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (mff) Color.White else palette.textSecondary,
                modifier = Modifier.width(COL_RANK).padding(start = 8.dp),
            )
            TeamLogo(row.team, 22.dp)
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.team.short,
                    style = if (mff) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
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
    // En match som pågår: rutan i hörnet visar den, om den visas eller inget annat spelas.
    if (match.state == MatchState.LIVE && channels.isNotEmpty()) {
        LaunchedEffect(match.id) {
            delay(PREVIEW_FOCUS_MS)
            if (canPreview(state, match)) state.previewMatch(match)
        }
    }
    // Tv: markeringen börjar på Titta när matchen går att se, annars på Tillbaka.
    val first = remember { FocusRequester() }
    if (tvLayout) {
        LaunchedEffect(match.id) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    TvFocusFallback { runCatching { first.requestFocus() }.isSuccess }
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
        MatchTag(state, match)
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
    val table = state.footballTables[competitionOf(match)].orEmpty()
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

/**
 * Tävlingen på en match. Lagens egna matcher (Malmö FF, landslaget) visar
 * tävlingen de spelas i: ligans märke om det är en av ligorna, annars
 * namnet, som "EL-kval" eller "Nations League".
 */
@Composable
private fun MatchTag(state: AppState, match: FootballMatch) {
    val league = competitionOf(match)
    if (league.isTeam) {
        LeagueTag(league, null, competitionName(match.slug))
    } else {
        LeagueTag(league, state.footballLogos[league])
    }
}

/** Ligan en match hör till: för lagens egna matcher den av ligorna tävlingen är, om någon. */
private fun competitionOf(match: FootballMatch): League =
    if (!match.league.isTeam) match.league else League.entries.firstOrNull { !it.isTeam && it.slug == match.slug } ?: match.league

/** Ligans märke på en match: loggan och förkortningen. Utan logga ännu en prick i ligans färg. */
@Composable
private fun LeagueTag(league: League, logo: String?, text: String = league.short) {
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
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** Ligans officiella logga från ESPN. */
@Composable
private fun LeagueLogo(url: String, size: Dp) {
    RemoteImage(url = url, modifier = Modifier.size(size), maxSide = 128, contentScale = ContentScale.Fit)
}

/** Den röda pricken som pulserar när något är live. */
@Composable
private fun LiveDot(color: Color = LIVE_RED) {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "puls",
    )
    Box(Modifier.size(8.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(color))
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
/** Minuten och LIVE på mörk och färgad botten: ljusare röd. */
private val LIVE_SOFT = Color(0xFFFF8A80)
/** Malmö FF: himmelsblått, och djupare blått för skuggorna. */
private val MFF_SKY = Color(0xFF6CB4EE)
private val MFF_DEEP = Color(0xFF1D5C9C)
private val MFF_NIGHT = Color(0xFF071E3A)
/** Sverige: flaggans blå och gula. */
private val SWE_BLUE = Color(0xFF0A6FC2)
private val SWE_DEEP = Color(0xFF064A8A)
private val SWE_NIGHT = Color(0xFF031D3A)
private val SWE_YELLOW = Color(0xFFFECC02)
/** Kritvitt, som linjerna på planen. */
private val CHALK = Color(0xFFF1F6EE)
private val TICKER_BG = Color(0xFF07140D)
/** Gräset i kvällsljus. */
private val GRASS_TOP = Color(0xFF0F3321)
private val GRASS_BOTTOM = Color(0xFF05130B)
private const val GOAL_FLASH_MS = 3400
/** Så många gånger prövas det att gå från ligalistan in på sidan. */
private const val ENTER_ATTEMPTS = 6
/** Den lilla rutan: en stund efter att man kommit till Fotboll, och efter en stund på en match. */
private const val PREVIEW_AFTER_MS = 1_800L
private const val PREVIEW_FOCUS_MS = 1_400L
private const val SHEEN_MS = 950
private val WIN_GREEN = Color(0xFF34C759)
private val YELLOW_CARD = Color(0xFFFFD60A)
private val PITCH_TOP = Color(0xFF1F5C37)
private val PITCH_BOTTOM = Color(0xFF14432A)
private val INK = Color(0xFF101318)

/** ESPN:s lagmärken, efter lagets id: Malmö FF 2720, Sverige 466. */
private const val ESPN_TEAM_LOGOS = "https://a.espncdn.com/i/teamlogos/soccer/500"

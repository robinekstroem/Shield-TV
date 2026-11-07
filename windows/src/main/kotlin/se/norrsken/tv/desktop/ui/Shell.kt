package se.norrsken.tv.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.norrsken.tv.data.playlist.ImportState
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.Screen
import se.norrsken.tv.desktop.data.MatchState

/**
 * Var minispelaren står: överst i sidofältet, som ett "spelas nu" med
 * bilden överst. Spelaren och sidofältet räknar med samma mått, så att
 * bilden landar precis i facket sidofältet håller fritt. På tv:n når
 * fjärrkontrollen den med pil upp från menyn.
 */
internal object MiniDock {
    /** Sidofältet blir lite bredare medan minispelaren står där. */
    val sidebarWidth = 316.dp
    val inset = 14.dp
    val top = 14.dp
    /** Sidofältets bredd minus marginalerna, i 16:9. */
    val videoWidth = 288.dp
    val videoHeight = 162.dp
    val radius = 12.dp
}

private val SIDEBAR_WIDTH = 236.dp

/**
 * Markeringen står i menyn. Kategorikanten på tv:n går då inte att landa i,
 * så att pil höger från menyn går rakt till innehållet.
 */
internal val LocalMenuFocused = compositionLocalOf { false }
/** Tv: menyn infälld till bara ikonerna, medan markeringen är i innehållet. */
private val RAIL_WIDTH = 74.dp
/** Luft över och under menyn, inne i det som rullar. */
private val NAV_AIR = 14.dp
private const val DOCK_MS = 420

private val dockEnter: EnterTransition =
    expandVertically(animationSpec = tween(DOCK_MS, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
        fadeIn(animationSpec = tween(DOCK_MS))
private val dockExit: ExitTransition =
    shrinkVertically(animationSpec = tween(DOCK_MS, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) +
        fadeOut(animationSpec = tween(DOCK_MS / 2))

@Composable
fun AppRoot(state: AppState) {
    val palette = LocalPalette.current
    // Hur långt spelaren krympt: 0 över allt, 1 som minispelare.
    val collapse = animateFloatAsState(
        targetValue = if (state.miniPlayer) 1f else 0f,
        animationSpec = tween(durationMillis = 460, easing = FastOutSlowInEasing),
        label = "minispelare",
    )
    // Minispelaren står överst i sidofältet. Fältet ställer om sig medan
    // bilden ännu täcker allt, och tillbaka först när den åter gör det;
    // listorna bakom behöver då bara ordna om sig en gång, i skydd av bilden.
    val docked by remember(state, collapse) {
        derivedStateOf { state.nowPlaying != null && (state.miniPlayer || collapse.value > 0f) }
    }
    var menuFocused by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(palette.background)) {
        // Temats dekor rör sig bara när spelaren inte täcker allt.
        val decorated = state.nowPlaying == null || state.miniPlayer
        CustomBackdrop(state.backgrounds, running = decorated, modifier = Modifier.fillMaxSize())
        ThemeBackdrop(palette.decoration, running = decorated, modifier = Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize()) {
            if (tvLayout && !docked) {
                // Tv: menyn tar bara ikonernas bredd. Utfälld lägger den sig över
                // innehållet, så att omslagen inte flyttar sig.
                Box(Modifier.width(RAIL_WIDTH).fillMaxHeight().zIndex(2f)) {
                    Sidebar(state, docked = false, rail = true, onFocus = { menuFocused = it })
                }
            } else {
                Sidebar(state, docked, rail = false, onFocus = { menuFocused = it })
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CompositionLocalProvider(LocalMenuFocused provides menuFocused) {
                    if (state.loading) {
                        Spinner(Modifier.align(Alignment.Center))
                    } else {
                        when (state.screen) {
                            Screen.HOME -> HomeScreen(state)
                            Screen.LIVE -> LiveTvScreen(state)
                            Screen.GUIDE -> GuideScreen(state)
                            Screen.FOOTBALL -> FootballScreen(state)
                            Screen.MOVIES -> MoviesScreen(state)
                            Screen.SERIES -> SeriesScreen(state)
                            Screen.GENRES -> GenreScreen(state)
                            Screen.HISTORY -> HistoryScreen(state)
                            Screen.SETTINGS -> SettingsScreen(state)
                        }
                    }
                }
            }
        }
        ThemeOverlay(palette.decoration, running = decorated, modifier = Modifier.fillMaxSize())
        // Spelaren ligger ovanpå allt. Skärmen under står kvar — med sin
        // scrollposition — så man kommer tillbaka dit man var.
        if (state.nowPlaying != null) {
            if (state.fullscreen && separateFullscreenWindow) {
                // Bilden visas i helskärmsfönstret. Här blir det bara svart, och
                // klick går inte igenom till listan bakom.
                Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } })
            } else {
                PlayerHost(state, collapse = { collapse.value })
            }
        }
    }
}

/**
 * Sidomenyn. På tv:n ([rail]) är den infälld till ikonerna och fälls ut när
 * markeringen kommer in i den, och in igen när den går tillbaka till innehållet.
 */
@Composable
private fun Sidebar(state: AppState, docked: Boolean, rail: Boolean, onFocus: (Boolean) -> Unit = {}) {
    val palette = LocalPalette.current
    val library = state.library
    var focused by remember { mutableStateOf(false) }
    // Försvinner menyn medan den har markeringen (minispelaren tar plats) räknas den som lämnad.
    DisposableEffect(Unit) { onDispose { onFocus(false) } }
    val folded = rail && !focused
    // Texten tonar ut när menyn fälls in, och in när den fälls ut.
    val labelAlpha = animateFloatAsState(if (folded) 0f else 1f, tween(if (folded) 120 else 260), label = "menytext")
    val labels = remember(labelAlpha) { { labelAlpha.value } }
    // Medan bilden täcker allt byter fältet bredd direkt. Stängs spelaren
    // medan den står i fältet glider fältet tillbaka till sin vanliga bredd.
    val widthSpec: AnimationSpec<Dp> = when {
        rail -> tween(240, easing = FastOutSlowInEasing)
        state.nowPlaying == null -> tween(320, easing = FastOutSlowInEasing)
        else -> snap()
    }
    val width by animateDpAsState(
        targetValue = when {
            docked -> MiniDock.sidebarWidth
            folded -> RAIL_WIDTH
            else -> SIDEBAR_WIDTH
        },
        animationSpec = widthSpec,
        label = "sidofält",
    )
    Column(
        Modifier
            .then(if (rail) Modifier.wrapContentWidth(Alignment.Start, unbounded = true) else Modifier)
            .width(width)
            .fillMaxHeight()
            // Utfälld över innehållet: täckande, med en skugga mot det som ligger under.
            .then(if (rail && !folded) Modifier.shadow(24.dp).background(palette.background) else Modifier)
            .background(palette.ambient)
            .onFocusChanged {
                focused = it.hasFocus
                onFocus(it.hasFocus)
            }
            .focusGroup()
            .padding(start = MiniDock.inset, end = MiniDock.inset, top = MiniDock.top, bottom = 22.dp),
    ) {
        AnimatedVisibility(visible = !docked, enter = dockEnter, exit = dockExit) {
            Row(
                Modifier.padding(start = 10.dp, top = 8.dp, bottom = 26.dp - NAV_AIR),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(rememberVectorPainter(Glyphs.AppMark), contentDescription = null, modifier = Modifier.size(36.dp).glow(palette.glowOf(0.55f), RoundedCornerShape(9.dp), 22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.graphicsLayer { alpha = labels() }) {
                    Text("Tv", style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false)
                    Text(
                        "för $platformName",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textTertiary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        // Minispelaren: facket för bilden, som spelaren själv ritar ovanpå, och
        // under det vad som spelas, med paus, förstora och stäng.
        AnimatedVisibility(visible = docked, enter = dockEnter, exit = dockExit) {
            Column {
                Spacer(Modifier.size(MiniDock.videoWidth, MiniDock.videoHeight))
                MiniPlayerCard(state, Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hairline))
            }
        }

        // Rullar bara om fönstret är för lågt för allt. Luften inuti ger
        // markeringens glöd plats, så att den inte klipps av i kanten.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = NAV_AIR),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NavItem(Glyphs.Home, "Hem", "Fortsätt titta & tips", null, state.screen == Screen.HOME, docked, labels = labels) {
                state.screen = Screen.HOME
            }
            NavItem(Glyphs.Live, "Live TV", "Direktsändning", library.channels.size, state.screen == Screen.LIVE, docked, labels = labels) {
                state.screen = Screen.LIVE
            }
            NavItem(Glyphs.Guide, "Tablå", "Vad som sänds", state.guide.channelCount, state.screen == Screen.GUIDE, docked, labels = labels) {
                state.screen = Screen.GUIDE
            }
            // Antalet matcher som pågår just nu.
            val liveMatches = state.footballMatches.count { it.state == MatchState.LIVE }
            NavItem(Glyphs.Football, "Fotboll", "Matcher & tabeller", liveMatches.takeIf { it > 0 }, state.screen == Screen.FOOTBALL, docked, live = true, labels = labels) {
                state.screen = Screen.FOOTBALL
            }
            // Varje film och serie en gång, hur många versioner den än finns i.
            val versions = state.versions
            val movieCount = if (versions.isEmpty) library.movies.size else versions.movieCount
            val seriesCount = if (versions.isEmpty) library.series.size else versions.seriesCount
            NavItem(Glyphs.Movies, "Filmer", "Filmbiblioteket", movieCount, state.screen == Screen.MOVIES, docked, labels = labels) {
                state.screen = Screen.MOVIES
            }
            NavItem(Glyphs.Series, "Serier", "Säsonger & avsnitt", seriesCount, state.screen == Screen.SERIES, docked, labels = labels) {
                state.screen = Screen.SERIES
            }
            NavItem(Glyphs.Genres, "Genrer", "Topplistor efter betyg", null, state.screen == Screen.GENRES, docked, labels = labels) {
                state.screen = Screen.GENRES
            }
            NavItem(Glyphs.History, "Historik", "Fortsätt där du slutade", null, state.screen == Screen.HISTORY, docked, labels = labels) {
                state.screen = Screen.HISTORY
            }
        }

        if (state.importingId != null) {
            if (folded) Spinner(Modifier.padding(start = 12.dp), size = 20.dp) else ImportIndicator(state.importState)
            Spacer(Modifier.height(8.dp))
        }

        NavItem(Glyphs.Settings, "Inställningar", "Källor & uppspelning", null, state.screen == Screen.SETTINGS, docked, labels = labels) {
            state.screen = Screen.SETTINGS
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    subtitle: String,
    count: Int?,
    selected: Boolean,
    /** Utan undertext, när minispelaren tar plats i sidofältet. */
    compact: Boolean,
    /** Siffran räknar det som pågår just nu: röd, med en prick framför. */
    live: Boolean = false,
    /** Hur synlig texten är: 0 när tv-menyn är infälld till ikonerna. Läses när det ritas. */
    labels: () -> Float = { 1f },
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val counted = count != null && count > 0
    Pressable(onClick = onClick, selected = selected, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (selected) palette.focus else palette.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
                // Infälld meny: en röd prick på ikonen när något pågår.
                if (live && counted) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 3.dp, y = (-2).dp)
                            .size(7.dp)
                            .graphicsLayer { alpha = 1f - labels() }
                            .clip(CircleShape)
                            .background(LIVE_COUNT),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            // En rad var, utan radbrytning: medan menyn fälls ut växer texten fram i stället för att hoppa.
            Column(Modifier.weight(1f).graphicsLayer { alpha = labels() }) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) palette.textPrimary else palette.textSecondary,
                    maxLines = 1,
                    softWrap = false,
                )
                AnimatedVisibility(visible = !compact, enter = dockEnter, exit = dockExit) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textTertiary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            if (count != null && count > 0) {
                Row(Modifier.graphicsLayer { alpha = labels() }, verticalAlignment = Alignment.CenterVertically) {
                    if (live) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(LIVE_COUNT))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        count.grouped(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (live) LIVE_COUNT else palette.textTertiary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportIndicator(importState: ImportState) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.surfaceRaised)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            importState.describe().ifEmpty { "Hämtar…" },
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = palette.signal,
            trackColor = palette.hairline,
        )
    }
}

/** Importläget i ord. Siffrorna kommer från arbetet självt, som på Android. */
fun ImportState.describe(): String = when (this) {
    ImportState.Idle -> ""
    is ImportState.Fetching -> "Hämtar $what…"
    is ImportState.Parsing -> "Läser $what: ${count.grouped()}"
    is ImportState.Saving -> "Sparar $what: ${saved.grouped()} av ${total.grouped()}"
    is ImportState.Done -> buildList {
        add("${channels.grouped()} kanaler")
        if (movies > 0) add("${movies.grouped()} filmer")
        if (series > 0) add("${series.grouped()} serier")
    }.joinToString(prefix = "Klart: ", separator = ", ", postfix = ".")
    is ImportState.Failed -> message
}

/** Samma röda som LIVE i fliken Fotboll. */
private val LIVE_COUNT = Color(0xFFFF453A)

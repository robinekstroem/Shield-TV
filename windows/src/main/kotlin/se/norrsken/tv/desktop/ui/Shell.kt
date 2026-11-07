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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Brush
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

/** Vad som syns till vänster om innehållet på tv:n. */
internal enum class TvDrawer { NONE, CATEGORIES, MENU }

/**
 * Tv: menyn och kategorierna syns bara när man är i dem. I innehållet tar
 * omslagen och kanalerna hela bredden; pil vänster vid kanten tar fram
 * kategorierna, och en gång till menyn. Pil höger eller OK går samma väg
 * tillbaka: menyn → kategorierna → innehållet, ett steg i taget.
 */
internal object TvNav {
    var drawer by mutableStateOf(TvDrawer.NONE)
    /** Hur många kategorilistor som står på skärmen just nu (en per skärm). */
    var categories by mutableIntStateOf(0)
    /** Ökas när kategorierna ska ta emot markeringen. */
    var categoryCalls by mutableIntStateOf(0)
        private set
    /** Ökas när menyn ska ta emot markeringen. */
    var menuCalls by mutableIntStateOf(0)
        private set

    fun openCategories() {
        drawer = TvDrawer.CATEGORIES
        categoryCalls++
    }

    fun openMenu() {
        drawer = TvDrawer.MENU
        menuCalls++
    }

    /** Medan kategorierna syns: tillbaka till innehållet, som pil höger. Tillbaka-knappen på Shield. */
    var leaveCategories: (() -> Unit)? = null

    /** Pil vänster vid innehållets kant: kategorierna om skärmen har några, annars menyn. */
    fun openLeft() {
        if (categories > 0) openCategories() else openMenu()
    }
}

/**
 * Tv: minispelaren står i sidomenyn, som då syns hela tiden. Kategorierna
 * står då som en smal kant bredvid, i stället för att gömmas helt.
 */
internal val LocalTvDocked = compositionLocalOf { false }

/** Tv: skuggan som tonar innehållet bakom menyn och kategorierna. */
internal val DRAWER_SCRIM = 260.dp
internal const val DRAWER_MS = 220

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
    val focusManager = LocalFocusManager.current
    // Tv: menyn och kategorierna göms medan man är i innehållet — också när
    // minispelaren går, som då ligger som en liten bild i hörnet.
    val drawers = tvLayout
    Box(Modifier.fillMaxSize().background(palette.background)) {
        // Temats dekor rör sig bara när spelaren inte täcker allt.
        val decorated = state.nowPlaying == null || state.miniPlayer
        CustomBackdrop(state.backgrounds, running = decorated, modifier = Modifier.fillMaxSize())
        ThemeBackdrop(palette.decoration, running = decorated, modifier = Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize()) {
            if (!drawers) Sidebar(state, docked, tv = false)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (drawers) {
                            // Pil vänster: går det inte längre åt vänster i innehållet
                            // kommer kategorierna fram (eller menyn, om skärmen inte har några).
                            Modifier.onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                    if (!focusManager.moveFocus(FocusDirection.Left)) TvNav.openLeft()
                                    true
                                } else {
                                    false
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                CompositionLocalProvider(LocalTvDocked provides (!drawers && tvLayout && docked)) {
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
        // Tv: menyn glider in över innehållet när man går dit, och ut igen när man lämnar den.
        if (drawers) {
            AnimatedVisibility(
                visible = TvNav.drawer == TvDrawer.MENU,
                enter = slideInHorizontally(tween(DRAWER_MS, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(DRAWER_MS)),
                exit = slideOutHorizontally(tween(DRAWER_MS, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(DRAWER_MS / 2)),
                modifier = Modifier.fillMaxHeight(),
            ) {
                Row(Modifier.fillMaxHeight()) {
                    Sidebar(state, docked = false, tv = true)
                    Box(
                        Modifier
                            .width(DRAWER_SCRIM)
                            .fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))),
                    )
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
 * Sidomenyn. På tv:n ([tv]) glider den in över innehållet när man går dit
 * med pil vänster, med markeringen på fliken man är i, och ut igen när man
 * lämnar den. OK eller pil höger byter flik och går vidare in.
 */
@Composable
private fun Sidebar(state: AppState, docked: Boolean, tv: Boolean) {
    val palette = LocalPalette.current
    val library = state.library
    var focused by remember { mutableStateOf(false) }
    // Den valda fliken tar emot markeringen när menyn kommer fram.
    val current = remember { FocusRequester() }
    if (tv) {
        LaunchedEffect(TvNav.menuCalls) {
            repeat(2) { withFrameNanos { } }
            runCatching { current.requestFocus() }
        }
    }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    // Byter flik. På tv:n går markeringen sedan vidare in: till kategorierna
    // om skärmen har några, annars till innehållet.
    fun go(screen: Screen) {
        state.screen = screen
        if (!tv) return
        scope.launch {
            // Vänta in den nya skärmen, så att dess kategorier hunnit visas.
            repeat(3) { withFrameNanos { } }
            if (TvNav.categories > 0) {
                TvNav.openCategories()
            } else if (focused) {
                focusManager.moveFocus(FocusDirection.Right)
            }
        }
    }
    // Den valda fliken tar emot markeringen; pil höger väljer fliken man står på.
    fun navModifier(screen: Screen): Modifier {
        var modifier: Modifier = Modifier
        if (state.screen == screen) modifier = modifier.focusRequester(current)
        if (tv) {
            modifier = modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    go(screen)
                    true
                } else {
                    false
                }
            }
        }
        return modifier
    }
    // Medan bilden täcker allt byter fältet bredd direkt. Stängs spelaren
    // medan den står i fältet glider fältet tillbaka till sin vanliga bredd.
    val widthSpec: AnimationSpec<Dp> = if (state.nowPlaying == null) tween(320, easing = FastOutSlowInEasing) else snap()
    val width by animateDpAsState(
        targetValue = if (docked) MiniDock.sidebarWidth else SIDEBAR_WIDTH,
        animationSpec = widthSpec,
        label = "sidofält",
    )
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            // Tv: täckande över innehållet, med en skugga mot det som ligger under.
            .then(if (tv) Modifier.shadow(24.dp).background(palette.background) else Modifier)
            .background(palette.ambient)
            .onFocusChanged {
                // Markeringen gick vidare: menyn glider undan.
                if (tv && focused && !it.hasFocus && TvNav.drawer == TvDrawer.MENU) TvNav.drawer = TvDrawer.NONE
                focused = it.hasFocus
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
                Column {
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
        // Tv: bilden ligger i hörnet medan man bläddrar. Menyn har dess knappar
        // överst: vad som spelas, paus, förstora och stäng.
        if (tv && state.nowPlaying != null && state.miniPlayer) {
            MiniPlayerCard(state, Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hairline))
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
            NavItem(Glyphs.Home, "Hem", "Fortsätt titta & tips", null, state.screen == Screen.HOME, docked, modifier = navModifier(Screen.HOME)) {
                go(Screen.HOME)
            }
            NavItem(Glyphs.Live, "Live TV", "Direktsändning", library.channels.size, state.screen == Screen.LIVE, docked, modifier = navModifier(Screen.LIVE)) {
                go(Screen.LIVE)
            }
            NavItem(Glyphs.Guide, "Tablå", "Vad som sänds", state.guide.channelCount, state.screen == Screen.GUIDE, docked, modifier = navModifier(Screen.GUIDE)) {
                go(Screen.GUIDE)
            }
            // Antalet matcher som pågår just nu.
            val liveMatches = state.footballMatches.count { it.state == MatchState.LIVE }
            NavItem(Glyphs.Football, "Fotboll", "Matcher & tabeller", liveMatches.takeIf { it > 0 }, state.screen == Screen.FOOTBALL, docked, live = true, modifier = navModifier(Screen.FOOTBALL)) {
                go(Screen.FOOTBALL)
            }
            // Varje film och serie en gång, hur många versioner den än finns i.
            val versions = state.versions
            val movieCount = if (versions.isEmpty) library.movies.size else versions.movieCount
            val seriesCount = if (versions.isEmpty) library.series.size else versions.seriesCount
            NavItem(Glyphs.Movies, "Filmer", "Filmbiblioteket", movieCount, state.screen == Screen.MOVIES, docked, modifier = navModifier(Screen.MOVIES)) {
                go(Screen.MOVIES)
            }
            NavItem(Glyphs.Series, "Serier", "Säsonger & avsnitt", seriesCount, state.screen == Screen.SERIES, docked, modifier = navModifier(Screen.SERIES)) {
                go(Screen.SERIES)
            }
            NavItem(Glyphs.Genres, "Genrer", "Topplistor efter betyg", null, state.screen == Screen.GENRES, docked, modifier = navModifier(Screen.GENRES)) {
                go(Screen.GENRES)
            }
            NavItem(Glyphs.History, "Historik", "Fortsätt där du slutade", null, state.screen == Screen.HISTORY, docked, modifier = navModifier(Screen.HISTORY)) {
                go(Screen.HISTORY)
            }
        }

        if (state.importingId != null) {
            ImportIndicator(state.importState)
            Spacer(Modifier.height(8.dp))
        }

        NavItem(Glyphs.Settings, "Inställningar", "Källor & uppspelning", null, state.screen == Screen.SETTINGS, docked, modifier = navModifier(Screen.SETTINGS)) {
            go(Screen.SETTINGS)
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val counted = count != null && count > 0
    Pressable(onClick = onClick, selected = selected, modifier = modifier.fillMaxWidth()) {
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

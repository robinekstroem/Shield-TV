package se.norrsken.tv.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
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

/** Tv: menyn som glider fram är bredare, med luft för loggan och klockan. */
private val TV_MENU_WIDTH = 300.dp

/** Hur fort markeringen i menyn tonar mellan raderna. */
private const val MENU_MS = 170

/** Tv: hur fort markeringen glider från en rad till nästa. */
private const val PILL_MS = 150

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

    /** Medan menyn syns: in i skärmen man är i igen, som pil höger. Tillbaka-knappen på Shield. */
    var leaveMenu: (() -> Unit)? = null

    /**
     * Tillbaka i menyn bytte till startsidan: menyn behåller markeringen i
     * stället för att startsidan tar den. Släpps när man lämnar menyn.
     */
    var holdMenu = false

    /**
     * Var markeringen ska hamna om den tappats, till exempel när det man stod
     * på stängdes: där skärmen själv säger (det man senast stod på). Sätts med
     * [TvFocusFallback]; null betyder att skärmen inte vet, och då tar menyn den.
     */
    var restoreContent: (() -> Boolean)? = null

    /** Pil vänster vid innehållets kant: kategorierna om skärmen har några, annars menyn. */
    fun openLeft() {
        if (categories > 0) openCategories() else openMenu()
    }

    /** Markeringen tillbaka in i innehållet, eller till menyn om skärmen inte vet var. */
    fun restoreFocus() {
        val restored = restoreContent?.let { action -> runCatching { action() }.getOrDefault(false) } ?: false
        if (!restored) openMenu()
    }
}

/**
 * Tv: var markeringen ska hamna om den tappas medan det här visas (se
 * [TvNav.restoreContent]). Sant från [action] om den hittade något.
 */
@Composable
internal fun TvFocusFallback(action: () -> Boolean) {
    if (!tvLayout) return
    val current by rememberUpdatedState(action)
    DisposableEffect(Unit) {
        val registered: () -> Boolean = { current() }
        TvNav.restoreContent = registered
        onDispose { if (TvNav.restoreContent === registered) TvNav.restoreContent = null }
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
    var introSkip by remember { mutableStateOf(false) }
    // Filmväggen: starten visar den, och sedan blir den kvar som bakgrund bakom sidorna.
    val wall = remember { PosterWall() }
    PosterWallDriver(state, wall)
    // Byter man sida vänder väggen bakom blad, medan sidans omslag vänds fram.
    LaunchedEffect(state.screen) { if (state.introDone) wall.turnPage() }
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .onPreviewKeyEvent { event ->
                // Väggen står still medan man trycker på fjärren.
                wall.lastInput = nowMs()
                // Medan starten visas hoppar en knapptryckning över den, och går inte vidare till sidan bakom.
                if (state.introDone) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) introSkip = true
                true
            }
            // Och medan musen rör sig. Bara läser händelserna, tar inga.
            .pointerInput(wall) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        wall.lastInput = nowMs()
                    }
                }
            },
    ) {
        // Temats dekor rör sig bara när spelaren inte täcker allt.
        val decorated = state.nowPlaying == null || state.miniPlayer
        // Egna lager: det som rör sig i bakgrunden ritas om för sig, inte hela skärmen
        // med listor och text varje gång snön faller en bit.
        CustomBackdrop(state.backgrounds, running = decorated, modifier = Modifier.fillMaxSize().graphicsLayer())
        ThemeBackdrop(palette.decoration, running = decorated, modifier = Modifier.fillMaxSize().graphicsLayer())
        // Filmväggen bakom sidorna, mörk och dämpad. Egna bakgrundsbilder går före.
        if (state.backgrounds.isEmpty()) {
            PosterWallBackdrop(
                wall,
                shown = state.introDone || state.introRevealing,
                quiet = state.screen == Screen.GUIDE || state.screen == Screen.LIVE || state.screen == Screen.SETTINGS,
                modifier = Modifier.fillMaxSize().graphicsLayer(),
            )
        }
        Row(Modifier.fillMaxSize()) {
            if (!drawers) Sidebar(state, docked, tv = false)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (drawers) {
                            Modifier.onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (event.key) {
                                    // Pil vänster: går det inte längre åt vänster i innehållet
                                    // kommer kategorierna fram (eller menyn, om skärmen inte har några).
                                    Key.DirectionLeft -> {
                                        if (!focusManager.moveFocus(FocusDirection.Left)) TvNav.openLeft()
                                        true
                                    }
                                    // Den lilla bilden står nere i högra hörnet: går det inte längre
                                    // åt höger eller nedåt i innehållet hamnar markeringen på den.
                                    Key.DirectionRight, Key.DirectionDown -> {
                                        if (state.nowPlaying == null || !state.miniPlayer) return@onKeyEvent false
                                        val direction = if (event.key == Key.DirectionRight) FocusDirection.Right else FocusDirection.Down
                                        if (!focusManager.moveFocus(direction)) state.focusMiniPlayer()
                                        true
                                    }
                                    else -> false
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                // Omslagen vänds fram när man kommer till en sida, och när starten tonar bort.
                val entrance = remember(state.screen, state.introRevealing) { CoverEntrance() }
                CompositionLocalProvider(
                    LocalTvDocked provides (!drawers && tvLayout && docked),
                    LocalCoverEntrance provides entrance,
                ) {
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
        ThemeOverlay(palette.decoration, running = decorated, modifier = Modifier.fillMaxSize().graphicsLayer())
        // Spelaren ligger ovanpå allt. Skärmen under står kvar — med sin
        // scrollposition — så man kommer tillbaka dit man var.
        if (state.nowPlaying != null) {
            if (state.fullscreen && separateFullscreenWindow) {
                // Bilden visas i helskärmsfönstret. Här blir det bara svart, och
                // klick går inte igenom till listan bakom.
                Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } })
            } else {
                // Fotbollens förhandsvisning börjar i hörnet, utan att först fylla skärmen.
                PlayerHost(state, collapse = { if (state.footballPreview != null && state.miniPlayer) 1f else collapse.value })
            }
        }
        // Starten: tv:n slås på medan biblioteket laddas och startsidan ordnas
        // bakom. Klar när startsidan har sina rader, eller när något annat visas
        // i stället (inget bibliotek, inställningarna vid första starten).
        if (!state.introDone) {
            val library = state.library
            val nothing = library.movies.isEmpty() && library.series.isEmpty() && library.channels.isEmpty()
            val ready = state.homeShown || (!state.loading && (state.screen != Screen.HOME || nothing)) || state.nowPlaying != null
            StartIntro(state = state, wall = wall, ready = ready, skip = introSkip, onFinished = { state.introDone = true })
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
    // Tv: markeringen glider från raden den lämnar till nästa (se NavItem).
    val trail = remember(tv) { if (tv) MenuTrail() else null }
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
        TvNav.holdMenu = false
        state.screen = screen
        if (!tv) return
        scope.launch {
            // Vänta in den nya skärmen, så att dess kategorier hunnit visas.
            repeat(3) { withFrameNanos { } }
            if (TvNav.categories > 0) {
                TvNav.openCategories()
            } else if (focused) {
                // Dit skärmen själv säger (Spela på startsidan, det man senast stod
                // på), annars det som ligger närmast till höger om menyn.
                val restored = TvNav.restoreContent?.let { action -> runCatching { action() }.getOrDefault(false) } ?: false
                if (!restored) focusManager.moveFocus(FocusDirection.Right)
            }
        }
    }
    // Tillbaka-knappen i menyn: in i skärmen man är i igen.
    if (tv) {
        DisposableEffect(Unit) {
            val leave: () -> Unit = { go(state.screen) }
            TvNav.leaveMenu = leave
            onDispose { if (TvNav.leaveMenu === leave) TvNav.leaveMenu = null }
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
        targetValue = when {
            docked -> MiniDock.sidebarWidth
            tv -> TV_MENU_WIDTH
            else -> SIDEBAR_WIDTH
        },
        animationSpec = widthSpec,
        label = "sidofält",
    )
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            // Tv: täckande över innehållet, med en skugga mot det som ligger under.
            .then(
                if (tv) {
                    Modifier.shadow(24.dp).background(palette.background).drawBehind { drawerBackdrop(palette) }
                } else {
                    Modifier.background(palette.ambient)
                },
            )
            .onFocusChanged {
                // Markeringen gick vidare: menyn glider undan.
                if (tv && focused && !it.hasFocus && TvNav.drawer == TvDrawer.MENU) {
                    TvNav.drawer = TvDrawer.NONE
                    TvNav.holdMenu = false
                }
                focused = it.hasFocus
            }
            .focusGroup()
            .padding(start = MiniDock.inset, end = MiniDock.inset, top = MiniDock.top, bottom = 22.dp),
    ) {
        AnimatedVisibility(visible = !docked, enter = dockEnter, exit = dockExit) {
            MenuBrand(state, tv)
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
            verticalArrangement = Arrangement.spacedBy(if (tv) 6.dp else 4.dp),
        ) {
            NavItem(Glyphs.Home, "Hem", "Fortsätt titta & tips", null, state.screen == Screen.HOME, docked, tv = tv, accent = MenuAccents.home, trail = trail, index = 0, modifier = navModifier(Screen.HOME)) {
                go(Screen.HOME)
            }
            NavItem(Glyphs.Live, "Live TV", "Direktsändning", library.channels.size, state.screen == Screen.LIVE, docked, tv = tv, accent = MenuAccents.live, trail = trail, index = 1, modifier = navModifier(Screen.LIVE)) {
                go(Screen.LIVE)
            }
            NavItem(Glyphs.Guide, "Tablå", "Vad som sänds", state.guide.channelCount, state.screen == Screen.GUIDE, docked, tv = tv, accent = MenuAccents.guide, trail = trail, index = 2, modifier = navModifier(Screen.GUIDE)) {
                go(Screen.GUIDE)
            }
            // Antalet matcher som pågår just nu.
            val liveMatches = state.footballMatches.count { it.state == MatchState.LIVE }
            NavItem(Glyphs.Football, "Fotboll", "Matcher & tabeller", liveMatches.takeIf { it > 0 }, state.screen == Screen.FOOTBALL, docked, tv = tv, accent = MenuAccents.football, live = true, trail = trail, index = 3, modifier = navModifier(Screen.FOOTBALL)) {
                go(Screen.FOOTBALL)
            }
            NavDivider(tv)
            // Varje film och serie en gång, hur många versioner den än finns i.
            val versions = state.versions
            val movieCount = if (versions.isEmpty) library.movies.size else versions.movieCount
            val seriesCount = if (versions.isEmpty) library.series.size else versions.seriesCount
            NavItem(Glyphs.Movies, "Filmer", "Filmbiblioteket", movieCount, state.screen == Screen.MOVIES, docked, tv = tv, accent = MenuAccents.movies, trail = trail, index = 4, modifier = navModifier(Screen.MOVIES)) {
                go(Screen.MOVIES)
            }
            NavItem(Glyphs.Series, "Serier", "Säsonger & avsnitt", seriesCount, state.screen == Screen.SERIES, docked, tv = tv, accent = MenuAccents.series, trail = trail, index = 5, modifier = navModifier(Screen.SERIES)) {
                go(Screen.SERIES)
            }
            NavItem(Glyphs.Genres, "Genrer", "Topplistor efter betyg", null, state.screen == Screen.GENRES, docked, tv = tv, accent = MenuAccents.genres, trail = trail, index = 6, modifier = navModifier(Screen.GENRES)) {
                go(Screen.GENRES)
            }
            NavItem(Glyphs.History, "Historik", "Fortsätt där du slutade", null, state.screen == Screen.HISTORY, docked, tv = tv, accent = MenuAccents.history, trail = trail, index = 7, modifier = navModifier(Screen.HISTORY)) {
                go(Screen.HISTORY)
            }
        }

        if (state.importingId != null) {
            ImportIndicator(state.importState)
            Spacer(Modifier.height(8.dp))
        }

        NavItem(Glyphs.Settings, "Inställningar", "Källor & uppspelning", null, state.screen == Screen.SETTINGS, docked, tv = tv, accent = MenuAccents.settings, trail = trail, index = 8, modifier = navModifier(Screen.SETTINGS)) {
            go(Screen.SETTINGS)
        }
    }
}

/**
 * Menyns överdel: märket och namnet, och på tv:n klockan under, som i
 * tv-boxarnas egna menyer.
 */
@Composable
private fun MenuBrand(state: AppState, tv: Boolean) {
    val palette = LocalPalette.current
    Column(Modifier.padding(start = if (tv) 8.dp else 10.dp, top = 8.dp, bottom = if (tv) 24.dp else 26.dp - NAV_AIR)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // En platt symbol i en färg: inga färgövergångar, inget sken.
            Icon(
                Glyphs.AppSymbol,
                contentDescription = null,
                tint = menuFill(palette),
                modifier = Modifier.size(if (tv) 44.dp else 36.dp),
            )
            Spacer(Modifier.width(if (tv) 14.dp else 12.dp))
            Column {
                Text(
                    "Tv",
                    style = TextStyle(
                        fontWeight = FontWeight.Black,
                        fontSize = if (tv) 30.sp else 24.sp,
                        lineHeight = if (tv) 32.sp else 26.sp,
                        letterSpacing = (-0.6).sp,
                    ),
                    color = palette.textPrimary,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    "FÖR ${platformName.uppercase()}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp),
                    color = palette.textTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (tv) {
            // Klockan stor och lätt, som på tv-boxarnas startsidor, och dagen under.
            val time = Instant.ofEpochMilli(state.clock).atZone(ZoneId.systemDefault())
            Text(
                time.format(MENU_TIME),
                style = TextStyle(
                    fontWeight = FontWeight.Light,
                    fontSize = 46.sp,
                    lineHeight = 50.sp,
                    letterSpacing = (-1).sp,
                ),
                color = palette.textPrimary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 6.dp, top = 22.dp),
            )
            Text(
                time.format(MENU_DATE).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.3.sp),
                color = palette.textTertiary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

private val MENU_TIME = DateTimeFormatter.ofPattern("HH:mm")
private val MENU_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.forLanguageTag("sv-SE"))

/** Mellan det som sänds och biblioteket: en liten rubrik på tv:n, lite luft på datorn. */
@Composable
private fun NavDivider(tv: Boolean) {
    val palette = LocalPalette.current
    if (!tv) {
        Spacer(Modifier.height(6.dp))
        return
    }
    Text(
        "BIBLIOTEK",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
        color = palette.textTertiary,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp),
    )
}

/**
 * Tv-menyns och kategoriernas bakgrund: mörk, med ett svagt sken av
 * markeringens färg upptill som tonar bort, mörkare nedtill, och en tunn
 * ljus kant mot innehållet som tonar ut i båda ändar. Inga färgfläckar.
 */
internal fun DrawScope.drawerBackdrop(palette: Palette) {
    val tint = menuFill(palette)
    drawRect(palette.surface.copy(alpha = palette.surface.alpha * 0.5f))
    drawRect(
        Brush.verticalGradient(
            listOf(tint.copy(alpha = 0.13f), tint.copy(alpha = 0.03f), Color.Transparent),
            endY = size.height * 0.6f,
        ),
    )
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.32f)), startY = size.height * 0.55f))
    val x = size.width - 0.75.dp.toPx()
    drawLine(
        Brush.verticalGradient(listOf(Color.Transparent, EDGE, EDGE, Color.Transparent)),
        Offset(x, 0f),
        Offset(x, size.height),
        strokeWidth = 1.5.dp.toPx(),
    )
}

private val EDGE = Color.White.copy(alpha = 0.12f)

/**
 * Symbolernas färger i menyn: varje del sin egen, fast i alla teman, så att
 * man känner igen den. Bara symbolen och strecket vid fliken man är i är
 * färgade — inga plattor, inget sken.
 */
private object MenuAccents {
    val home = Color(0xFF2EE6C5)
    val live = Color(0xFFFF5A72)
    val guide = Color(0xFFFFA24A)
    val football = Color(0xFF3DD47A)
    val movies = Color(0xFF5B8CFF)
    val series = Color(0xFFA27CFF)
    val genres = Color(0xFFFF6FA8)
    val history = Color(0xFF4CC3F5)
    val settings = Color(0xFF9AA6BC)
}

/** Temats markering är vit eller nästan vit (som Grafit): då har den ingen färg att fylla med. */
private fun Color.isPale(): Boolean {
    val most = maxOf(red, green, blue)
    val least = minOf(red, green, blue)
    val saturation = if (most <= 0f) 0f else (most - least) / most
    return luminance() > 0.6f && saturation < 0.25f
}

/** Den markerade raden i menyn: temats färg, eller märkets blå när temats markering är vit. */
internal fun menuFill(palette: Palette): Color = if (palette.focus.isPale()) Brand.blue else palette.focus

/** Texten på den markerade raden. */
internal fun menuInk(palette: Palette): Color = if (palette.focus.isPale()) Color.White else palette.onFocus

/**
 * Tv: var markeringen i menyn senast stod och står nu, som radernas
 * ordning. Så vet raden som får markeringen varifrån den kommer, och raden
 * som lämnar den vart den går.
 */
private class MenuTrail {
    var from = -1
    var to = -1
}

/**
 * En rad i menyn: symbolen i radens egen färg, och namnet. Markerad fylls
 * raden med temats färg och texten blir vit; fliken man är i har ett streck
 * i kanten. Inget sken och inget som skalas — det gör texten suddig på tv:n.
 * På datorn står också en rad förklaring och antalet; på tv:n bara namnet,
 * och LIVE när något pågår.
 *
 * Tv ([trail]): fyllningen glider ut ur raden man lämnar och in i nästa,
 * från det hållet, så att markeringen ser ut att glida mellan raderna. Bara
 * ritningen görs om medan den glider.
 */
@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    subtitle: String,
    count: Int?,
    selected: Boolean,
    /** Utan undertext, när minispelaren tar plats i sidofältet. */
    compact: Boolean,
    tv: Boolean,
    accent: Color,
    /** Siffran räknar det som pågår just nu: röd, som LIVE. */
    live: Boolean = false,
    trail: MenuTrail? = null,
    /** Radens plats i menyn, uppifrån. */
    index: Int = 0,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(if (tv) 14.dp else 10.dp)
    val fill = menuFill(palette)
    val ink = menuInk(palette)
    // Markerad: färgen tonar in. Musen över: en svag ton av samma färg.
    val lit by animateFloatAsState(if (focused && trail == null) 1f else 0f, tween(MENU_MS), label = "menyrad")
    val hint by animateFloatAsState(if (hovered && !focused) 0.12f else 0f, tween(MENU_MS), label = "menyhovring")
    // Tv: fyllningens läge i radhöjder (-1 ovanför, 1 under) och hur mycket den syns.
    val slide = remember { Animatable(0f) }
    val shown = remember { Animatable(0f) }
    if (trail != null) {
        LaunchedEffect(focused) {
            if (focused) {
                val from = trail.from
                if (from < 0 || from == index) {
                    slide.snapTo(0f)
                    shown.animateTo(1f, tween(MENU_MS))
                } else {
                    shown.snapTo(1f)
                    slide.snapTo(if (from < index) -1f else 1f)
                    slide.animateTo(0f, tween(PILL_MS, easing = FastOutSlowInEasing))
                }
            } else if (shown.value > 0f) {
                val to = trail.to
                if (to >= 0 && to != index) {
                    slide.animateTo(if (to > index) 1f else -1f, tween(PILL_MS, easing = FastOutSlowInEasing))
                } else {
                    shown.animateTo(0f, tween(MENU_MS))
                }
                shown.snapTo(0f)
                slide.snapTo(0f)
            }
        }
    }
    // Symbolen kliver lite åt höger på raden som är markerad.
    val nudge by animateFloatAsState(if (focused && tv) 1f else 0f, tween(MENU_MS), label = "menysymbol")
    val counted = count != null && count > 0
    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (trail != null) {
                    Modifier.onFocusChanged {
                        if (it.isFocused && trail.to != index) {
                            trail.from = trail.to
                            trail.to = index
                        }
                    }
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .drawBehind {
                if (hint > 0.001f) drawRect(fill, alpha = hint)
                if (lit > 0.001f) drawRect(fill, alpha = lit)
                val alpha = shown.value
                if (alpha > 0.001f) {
                    val dy = slide.value * size.height
                    drawRect(fill, topLeft = Offset(0f, dy), size = size, alpha = alpha)
                    // Ett svagt ljus i plattans överkant.
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                            startY = dy,
                            endY = dy + size.height * 0.7f,
                        ),
                        topLeft = Offset(0f, dy),
                        size = size,
                        alpha = alpha,
                    )
                }
            }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        // Fliken man är i.
        if (selected && !focused) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 3.dp)
                    .size(width = 3.dp, height = if (tv) 26.dp else 20.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (tv) 16.dp else 12.dp, vertical = if (tv) 13.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (focused) ink else accent,
                modifier = Modifier
                    .size(if (tv) 26.dp else 22.dp)
                    .graphicsLayer { translationX = nudge * 4.dp.toPx() },
            )
            Spacer(Modifier.width(if (tv) 16.dp else 14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = when {
                        selected || focused -> FontWeight.Bold
                        tv -> FontWeight.SemiBold
                        else -> FontWeight.Medium
                    },
                    color = when {
                        focused -> ink
                        selected -> palette.textPrimary
                        else -> palette.textSecondary
                    },
                    maxLines = 1,
                    softWrap = false,
                )
                if (!tv) {
                    AnimatedVisibility(visible = !compact, enter = dockEnter, exit = dockExit) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (focused) ink.copy(alpha = 0.78f) else palette.textTertiary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            when {
                live && counted -> LiveBadge(count ?: 0)
                !tv && counted -> Text(
                    (count ?: 0).grouped(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (focused) ink.copy(alpha = 0.78f) else palette.textTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** "● 2 LIVE": det som pågår just nu, i rött, med en prick som pulserar. */
@Composable
private fun LiveBadge(count: Int) {
    val transition = rememberInfiniteTransition(label = "live")
    val pulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "puls",
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(LIVE_COUNT)
            .padding(start = 7.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Bara lagrets genomskinlighet ändras: inget ritas om.
        Box(Modifier.size(6.dp).graphicsLayer { alpha = pulse }.clip(CircleShape).background(Color.White))
        Spacer(Modifier.width(5.dp))
        Text(
            "$count LIVE",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
            color = Color.White,
            maxLines = 1,
            softWrap = false,
        )
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

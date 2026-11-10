package se.norrsken.tv.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
            .then(if (tv) Modifier.shadow(24.dp).background(palette.background) else Modifier)
            .background(palette.ambient)
            .then(if (tv) Modifier.drawBehind { menuGlow(palette.hairline) } else Modifier)
            .onFocusChanged {
                // Markeringen gick vidare: menyn glider undan.
                if (tv && focused && !it.hasFocus && TvNav.drawer == TvDrawer.MENU) TvNav.drawer = TvDrawer.NONE
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
            NavItem(Glyphs.Home, "Hem", "Fortsätt titta & tips", null, state.screen == Screen.HOME, docked, tv = tv, modifier = navModifier(Screen.HOME)) {
                go(Screen.HOME)
            }
            NavItem(Glyphs.Live, "Live TV", "Direktsändning", library.channels.size, state.screen == Screen.LIVE, docked, tv = tv, modifier = navModifier(Screen.LIVE)) {
                go(Screen.LIVE)
            }
            NavItem(Glyphs.Guide, "Tablå", "Vad som sänds", state.guide.channelCount, state.screen == Screen.GUIDE, docked, tv = tv, modifier = navModifier(Screen.GUIDE)) {
                go(Screen.GUIDE)
            }
            // Antalet matcher som pågår just nu.
            val liveMatches = state.footballMatches.count { it.state == MatchState.LIVE }
            NavItem(Glyphs.Football, "Fotboll", "Matcher & tabeller", liveMatches.takeIf { it > 0 }, state.screen == Screen.FOOTBALL, docked, tv = tv, live = true, modifier = navModifier(Screen.FOOTBALL)) {
                go(Screen.FOOTBALL)
            }
            NavDivider(tv)
            // Varje film och serie en gång, hur många versioner den än finns i.
            val versions = state.versions
            val movieCount = if (versions.isEmpty) library.movies.size else versions.movieCount
            val seriesCount = if (versions.isEmpty) library.series.size else versions.seriesCount
            NavItem(Glyphs.Movies, "Filmer", "Filmbiblioteket", movieCount, state.screen == Screen.MOVIES, docked, tv = tv, modifier = navModifier(Screen.MOVIES)) {
                go(Screen.MOVIES)
            }
            NavItem(Glyphs.Series, "Serier", "Säsonger & avsnitt", seriesCount, state.screen == Screen.SERIES, docked, tv = tv, modifier = navModifier(Screen.SERIES)) {
                go(Screen.SERIES)
            }
            NavItem(Glyphs.Genres, "Genrer", "Topplistor efter betyg", null, state.screen == Screen.GENRES, docked, tv = tv, modifier = navModifier(Screen.GENRES)) {
                go(Screen.GENRES)
            }
            NavItem(Glyphs.History, "Historik", "Fortsätt där du slutade", null, state.screen == Screen.HISTORY, docked, tv = tv, modifier = navModifier(Screen.HISTORY)) {
                go(Screen.HISTORY)
            }
        }

        if (state.importingId != null) {
            ImportIndicator(state.importState)
            Spacer(Modifier.height(8.dp))
        }

        NavItem(Glyphs.Settings, "Inställningar", "Källor & uppspelning", null, state.screen == Screen.SETTINGS, docked, tv = tv, modifier = navModifier(Screen.SETTINGS)) {
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
            Image(
                rememberVectorPainter(Glyphs.AppMark),
                contentDescription = null,
                modifier = Modifier
                    .size(if (tv) 50.dp else 40.dp)
                    .glow(Brand.blue.copy(alpha = 0.32f), RoundedCornerShape(16.dp), 26.dp),
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
            Text(
                clockLine(state.clock),
                style = MaterialTheme.typography.labelLarge,
                color = palette.textSecondary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 4.dp, top = 20.dp),
            )
        }
    }
}

/** "21.40  ·  tisdag 10 november". */
private fun clockLine(ms: Long): String {
    val time = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return time.format(MENU_TIME) + "  ·  " + time.format(MENU_DATE)
}

private val MENU_TIME = DateTimeFormatter.ofPattern("HH:mm")
private val MENU_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.forLanguageTag("sv-SE"))

/** Mellan det som sänds och biblioteket: en tunn linje på tv:n, lite luft på datorn. */
@Composable
private fun NavDivider(tv: Boolean) {
    val palette = LocalPalette.current
    if (!tv) {
        Spacer(Modifier.height(6.dp))
        return
    }
    Box(
        Modifier
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.hairline.copy(alpha = palette.hairline.alpha * 0.7f)),
    )
}

/**
 * Tv-menyns bakgrund: ett svagt sken i märkets färger uppe till vänster, där
 * loggan står, och en tunn kant mot innehållet.
 */
private fun DrawScope.menuGlow(edge: Color) {
    drawRect(
        Brush.radialGradient(
            listOf(Brand.blue.copy(alpha = 0.16f), Brand.violet.copy(alpha = 0.06f), Color.Transparent),
            center = Offset(size.width * 0.18f, size.height * 0.06f),
            radius = size.width * 1.25f,
        ),
    )
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, Brand.violet.copy(alpha = 0.05f))),
    )
    drawLine(edge, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), strokeWidth = 1f)
}

/**
 * En rad i menyn. Markerad blir raden en fylld platta i temats färg, med
 * texten i kontrast — som knapparna — och växer lite på tv:n. Fliken man är
 * i har ett streck i kanten i märkets färger. På datorn står också en rad
 * förklaring och antalet; på tv:n bara namnet, och LIVE när något pågår.
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
    /** Siffran räknar det som pågår just nu: röd, som LIVE. */
    live: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(if (tv) 14.dp else 10.dp)
    val fill by animateColorAsState(
        targetValue = when {
            focused -> palette.focus
            hovered -> palette.focus.copy(alpha = 0.09f)
            selected -> palette.focus.copy(alpha = 0.07f)
            else -> Color.Transparent
        },
        animationSpec = tween(MENU_MS),
        label = "menyrad",
    )
    val ink by animateColorAsState(
        targetValue = when {
            focused -> palette.onFocus
            selected || hovered -> palette.textPrimary
            else -> palette.textSecondary
        },
        animationSpec = tween(MENU_MS),
        label = "menytext",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused && tv) 1.035f else 1f,
        animationSpec = tween(MENU_MS, easing = FastOutSlowInEasing),
        label = "menyskala",
    )
    val counted = count != null && count > 0
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focused) Modifier.glow(palette.glowOf(0.6f), shape, 20.dp) else Modifier)
            .clip(shape)
            .background(fill)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        // Fliken man är i.
        if (selected && !focused) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(width = 3.dp, height = if (tv) 24.dp else 20.dp)
                    .clip(CircleShape)
                    .background(Brand.stripe),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (tv) 16.dp else 12.dp, vertical = if (tv) 13.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(if (tv) 24.dp else 22.dp))
            Spacer(Modifier.width(if (tv) 16.dp else 14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
                    color = ink,
                    maxLines = 1,
                    softWrap = false,
                )
                if (!tv) {
                    AnimatedVisibility(visible = !compact, enter = dockEnter, exit = dockExit) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (focused) palette.onFocus.copy(alpha = 0.7f) else palette.textTertiary,
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
                    color = if (focused) palette.onFocus.copy(alpha = 0.7f) else palette.textTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** "2 LIVE": det som pågår just nu, i rött. */
@Composable
private fun LiveBadge(count: Int) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(LIVE_COUNT)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
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

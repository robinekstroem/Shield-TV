package se.norrsken.tv.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.Screen
import se.norrsken.tv.desktop.data.MatchState
import se.norrsken.tv.desktop.data.WatchKind
import se.norrsken.tv.desktop.data.isMalmoFF

/**
 * Menyns utseende, ett val i Inställningar. Original är menyn som den alltid
 * sett ut. De andra ritar samma rader på sitt eget sätt men styrs likadant:
 * pil upp och ner mellan raderna, pil höger eller OK in i delen, tillbaka ut.
 */
enum class MenuStyle(val label: String, val description: String, val fresh: Boolean) {
    ORIGINAL("Original", "Symboler i delarnas färger, som menyn alltid sett ut.", false),
    MINIMAL("Minimal", "Bara text, en stor klocka och ett rött streck som glider.", true),
    MULTIVERSE("Multiversum", "Ett hjul av omslag i 3D som snurrar fram, som Marvels intro.", true),
    STARK("Stark", "Ett hologram i 3D med ringar som snurrar, som i Iron Mans hjälm.", true),
}

/** En rad i de nya menyerna: samma delar, i samma ordning, som i Original. */
internal class MenuRow(val screen: Screen, val label: String, val icon: ImageVector)

internal val MENU_ROWS = listOf(
    MenuRow(Screen.HOME, "Hem", Glyphs.Home),
    MenuRow(Screen.LIVE, "Live TV", Glyphs.Live),
    MenuRow(Screen.GUIDE, "Tablå", Glyphs.Guide),
    MenuRow(Screen.FOOTBALL, "Fotboll", Glyphs.Football),
    MenuRow(Screen.MOVIES, "Filmer", Glyphs.Movies),
    MenuRow(Screen.SERIES, "Serier", Glyphs.Series),
    MenuRow(Screen.GENRES, "Genrer", Glyphs.Genres),
    MenuRow(Screen.HISTORY, "Historik", Glyphs.History),
    MenuRow(Screen.TOP10, "Topp 10", Glyphs.Star),
    MenuRow(Screen.MUSIC, "Musik", Glyphs.Music),
    MenuRow(Screen.SETTINGS, "Inställningar", Glyphs.Settings),
)

private const val FOOTBALL_ROW = 3

/** Veckans topp 10: den gula knappen (TopTen.kt), ritad likadant i alla menyer. */
private const val TOP10_ROW = 8
/** Musik: Spotify (MusicScreen.kt), under den gula knappen. */
private const val MUSIC_ROW = 9
private const val SETTINGS_ROW = 10

private val MARVEL_RED = Color(0xFFE62429)
private val MINIMAL_RED = Color(0xFFFF2A2F)
private val GLITCH_CYAN = Color(0xFF2DE2FF)
private val GLITCH_MAGENTA = Color(0xFFFF2BD6)
private val STARK_CYAN = Color(0xFF6FE6FF)
private val STARK_GOLD = Color(0xFFFFC24A)
private val LIVE_RED = Color(0xFFFF453A)

private val STYLED_TIME = DateTimeFormatter.ofPattern("HH:mm")
private val STYLED_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.forLanguageTag("sv-SE"))
private val SWEDISH_UPPER: Locale = Locale.forLanguageTag("sv-SE")

/**
 * Var markeringen står i de nya menyerna. [pos] är raden som ett flyttal,
 * för hjulet som snurrar; [markerY] är radens mitt i bildpunkter, för
 * strecket och ringarna som glider. Båda studsar lite när de stannar.
 */
@Stable
internal class StyledNav(private val scope: CoroutineScope) {
    /** Raden som har markeringen, eller -1. */
    var focused by mutableIntStateOf(-1)

    /** Raden markören och hjulet är på väg till. */
    var target = -1
        private set
    val pos = Animatable(0f)
    val markerY = Animatable(0f)

    /** Ett OK-tryck: 1 direkt, och sedan ned mot 0. */
    val press = Animatable(0f)

    /** Menyns överkant i fönstret, och radernas mitt räknat därifrån. */
    var top = 0f
    val centers = FloatArray(MENU_ROWS.size) { Float.NaN }

    fun goTo(index: Int) {
        val first = target < 0
        target = index
        scope.launch {
            if (first) pos.snapTo(index.toFloat()) else pos.animateTo(index.toFloat(), spring(dampingRatio = 0.62f, stiffness = 380f))
        }
        val y = centers[index]
        if (!y.isNaN()) {
            scope.launch {
                if (first) markerY.snapTo(y) else markerY.animateTo(y, spring(dampingRatio = 0.55f, stiffness = 650f))
            }
        }
    }

    /** Raden [index] har sin mitt på [center]: markören hoppar dit om den står där och inte är på väg. */
    fun placed(index: Int, center: Float) {
        centers[index] = center
        if (index == target && !markerY.isRunning && abs(markerY.value - center) > 0.5f) {
            scope.launch { markerY.snapTo(center) }
        }
    }

    fun pressed() {
        scope.launch {
            press.snapTo(1f)
            press.animateTo(0f, tween(420))
        }
    }
}

/** Det de nya menyerna behöver veta om läget, för varje ritning. */
internal class MenuContext(
    val state: AppState,
    val nav: StyledNav,
    val tv: Boolean,
    /** Delen man är i. */
    val current: Int,
    /** Raden markeringen står på, annars delen man är i. */
    val active: Int,
    /** Menyn har markeringen. */
    val hasFocus: Boolean,
    /** Det som gör en rad valbar: markeringen, pilarna och OK. */
    val row: (Int, MutableInteractionSource) -> Modifier,
)

/**
 * Sidomenyn i de nya utseendena. Samma beteende som Original (se Sidebar i
 * Shell.kt): på tv:n glider den fram med markeringen på delen man är i, pil
 * höger eller OK går in i delen, och tillbaka går tillbaka in. Pil upp och
 * ner går alltid till raden ovanför eller under, i ordning, oavsett hur
 * raderna ritas — också när hjulet har snurrat dem ur sina platser.
 */
@Composable
internal fun StyledSidebar(state: AppState, style: MenuStyle, tv: Boolean) {
    val scope = rememberCoroutineScope()
    val nav = remember(scope) { StyledNav(scope) }
    val requesters = remember { List(MENU_ROWS.size) { FocusRequester() } }
    val current = MENU_ROWS.indexOfFirst { it.screen == state.screen }.coerceAtLeast(0)
    var hasFocus by remember { mutableStateOf(false) }
    if (tv) {
        LaunchedEffect(TvNav.menuCalls) {
            repeat(2) { withFrameNanos { } }
            runCatching { requesters[current].requestFocus() }
        }
    }
    val focusManager = LocalFocusManager.current
    fun go(screen: Screen) {
        TvNav.holdMenu = false
        state.screen = screen
        if (!tv) return
        // Valt med fingret (telefon): ingen markering att flytta, menyn går undan.
        if (TvNav.touched) {
            TvNav.drawer = TvDrawer.NONE
            return
        }
        scope.launch {
            repeat(3) { withFrameNanos { } }
            if (TvNav.categories > 0) {
                TvNav.openCategories()
            } else if (hasFocus) {
                val restored = TvNav.restoreContent?.let { action -> runCatching { action() }.getOrDefault(false) } ?: false
                if (!restored) focusManager.moveFocus(FocusDirection.Right)
            }
        }
    }
    if (tv) {
        DisposableEffect(Unit) {
            val leave: () -> Unit = { go(state.screen) }
            TvNav.leaveMenu = leave
            onDispose { if (TvNav.leaveMenu === leave) TvNav.leaveMenu = null }
        }
    }
    val active = if (hasFocus && nav.focused >= 0) nav.focused else current
    LaunchedEffect(active) { nav.goTo(active) }

    fun row(index: Int, interaction: MutableInteractionSource): Modifier =
        Modifier
            .focusRequester(requesters[index])
            .onFocusChanged { if (it.isFocused) nav.focused = index }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> if (tv) {
                        nav.pressed()
                        go(MENU_ROWS[index].screen)
                        true
                    } else {
                        false
                    }
                    Key.DirectionDown -> if (index < MENU_ROWS.lastIndex) {
                        runCatching { requesters[index + 1].requestFocus() }
                        true
                    } else {
                        false
                    }
                    // Från Hem: vidare upp till minispelarens knappar, om den står överst.
                    Key.DirectionUp -> if (index > 0) {
                        runCatching { requesters[index - 1].requestFocus() }
                        true
                    } else {
                        false
                    }
                    else -> false
                }
            }
            .clickable(interactionSource = interaction, indication = null) {
                nav.pressed()
                go(MENU_ROWS[index].screen)
            }

    val ctx = MenuContext(state, nav, tv, current, active, hasFocus, ::row)
    val widthSpec: AnimationSpec<Dp> = if (state.nowPlaying == null) tween(320, easing = FastOutSlowInEasing) else snap()
    val width by animateDpAsState(
        targetValue = if (tv) style.tvWidth() else style.deskWidth(),
        animationSpec = widthSpec,
        label = "sidofält",
    )
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .onGloballyPositioned { nav.top = it.positionInRoot().y }
            .drawBehind { styledBackdrop(style) }
            .onFocusChanged {
                // Markeringen gick vidare: menyn glider undan.
                if (tv && hasFocus && !it.hasFocus && TvNav.drawer == TvDrawer.MENU) {
                    TvNav.drawer = TvDrawer.NONE
                    TvNav.holdMenu = false
                }
                hasFocus = it.hasFocus
            }
            .focusGroup(),
    ) {
        when (style) {
            MenuStyle.MINIMAL -> MinimalMenu(ctx)
            MenuStyle.MULTIVERSE -> MultiverseMenu(ctx)
            MenuStyle.STARK -> StarkMenu(ctx)
            MenuStyle.ORIGINAL -> Unit
        }
    }
}

internal fun MenuStyle.tvWidth() = when (this) {
    MenuStyle.MULTIVERSE -> 440.dp
    MenuStyle.STARK -> 400.dp
    else -> 340.dp
}

private fun MenuStyle.deskWidth() = when (this) {
    MenuStyle.MULTIVERSE -> 300.dp
    MenuStyle.STARK -> 270.dp
    else -> 250.dp
}

/** Menyns bakgrund: täckande och mörk, så att innehållet bakom inte stör texten. */
private fun DrawScope.styledBackdrop(style: MenuStyle) {
    when (style) {
        MenuStyle.MINIMAL -> {
            drawRect(Brush.horizontalGradient(0f to Color(0xFF040506), 1f to Color(0xFF08090B)))
            drawRect(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f)))
        }
        MenuStyle.MULTIVERSE -> {
            drawRect(Brush.horizontalGradient(0f to Color(0xFF060304), 1f to Color(0xFF0B0506)))
            drawRect(
                Brush.radialGradient(
                    0f to MARVEL_RED.copy(alpha = 0.26f),
                    0.55f to MARVEL_RED.copy(alpha = 0.07f),
                    1f to Color.Transparent,
                    center = Offset(size.width * 0.38f, size.height * 0.56f),
                    radius = size.width * 0.95f,
                ),
            )
        }
        MenuStyle.STARK -> {
            drawRect(Brush.horizontalGradient(0f to Color(0xFF01070C), 1f to Color(0xFF031019)))
            // Ett svagt rutnät, som på en hjälmskärm.
            val step = 32.dp.toPx()
            val grid = STARK_CYAN.copy(alpha = 0.035f)
            var x = step
            while (x < size.width) {
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += step
            }
            var y = step
            while (y < size.height) {
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
        }
        MenuStyle.ORIGINAL -> Unit
    }
    // En tunn kant mot innehållet.
    val edge = when (style) {
        MenuStyle.MULTIVERSE -> MARVEL_RED.copy(alpha = 0.35f)
        MenuStyle.STARK -> STARK_CYAN.copy(alpha = 0.30f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val x = size.width - 0.75.dp.toPx()
    drawLine(
        Brush.verticalGradient(listOf(Color.Transparent, edge, edge, Color.Transparent)),
        Offset(x, 0f),
        Offset(x, size.height),
        strokeWidth = 1.5.dp.toPx(),
    )
}

/** Tv: minispelarens knappar överst när en film eller kanal spelas i hörnet, som i Original. */
@Composable
private fun StyledMiniPlayer(ctx: MenuContext) {
    if (ctx.tv && ctx.state.nowPlaying != null && ctx.state.miniPlayer) {
        MiniPlayerCard(ctx.state, Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 12.dp))
    }
}

// --- Glimten: vad som finns bakom varje rad --------------------------------------------

/** En rad i glimten: en rubrik, titeln, en rad till och en bild. */
internal class PeekInfo(
    val kicker: String,
    val title: String,
    val line: String?,
    val image: String?,
    val poster: Boolean,
    val progress: Float? = null,
)

/**
 * Glimten för varje rad, samma som i Original (se MenuPeek): det man kan
 * fortsätta titta på, kanalen man senast såg, Malmö FF:s match, det nyaste
 * i Filmer och Serier och det man såg sist.
 */
@Composable
internal fun rememberMenuPeeks(state: AppState): List<PeekInfo?> {
    val history = state.history
    val library = state.library
    val lastChannel = remember(history, library) {
        history.firstOrNull { it.kind == WatchKind.LIVE }?.let { entry ->
            library.channels.firstOrNull {
                it.sourceId == entry.sourceId && (it.streamId?.toString() ?: "namn:${it.name}") == entry.itemId
            }
        }
    }
    val newestMovie = remember(library) { library.movies.maxByOrNull { it.addedUtc } }
    val newestSeries = remember(library) { library.series.maxByOrNull { it.addedUtc } }
    // Genrer: ett omslag ur biblioteket, så att raden har en bild.
    val genreMovie = remember(library) { library.movies.filter { it.poster != null }.let { it.getOrNull(it.size / 3) } }
    val match = remember(state.footballMatches) {
        state.footballMatches.firstOrNull { it.isMalmoFF() && it.state == MatchState.LIVE }
            ?: state.footballMatches.firstOrNull { it.isMalmoFF() && it.state == MatchState.UPCOMING }
    }
    val liveMatches = state.footballMatches.count { it.state == MatchState.LIVE }
    val onAir = lastChannel?.let { state.guide.current(it, state.clock) }
    val resume = history.firstOrNull { it.resumable }
    val last = history.firstOrNull()
    return listOf(
        resume?.let { PeekInfo("FORTSÄTT TITTA", it.title, it.subtitle, it.image, poster = true, progress = it.progress) },
        lastChannel?.let { PeekInfo("SENAST SEDD", it.name, onAir?.title, it.logo, poster = false, progress = onAir?.progress(state.clock)) },
        lastChannel?.let { PeekInfo("SÄNDS NU", onAir?.title ?: it.name, it.name, it.logo, poster = false, progress = onAir?.progress(state.clock)) },
        match?.let {
            val live = it.state == MatchState.LIVE
            val tag = { team: se.norrsken.tv.desktop.data.FootballTeam -> team.abbreviation.ifBlank { team.short.take(3).uppercase() } }
            PeekInfo(
                if (live) "MALMÖ FF · ${it.status}" else if (liveMatches > 0) "$liveMatches MATCHER LIVE" else "MALMÖ FF · NÄSTA MATCH",
                "${tag(it.home)} – ${tag(it.away)}",
                if (live) "${it.homeScore ?: 0}–${it.awayScore ?: 0}" else STYLED_KICKOFF.format(Instant.ofEpochMilli(it.kickoff).atZone(ZoneId.systemDefault())),
                null,
                poster = false,
            )
        } ?: if (liveMatches > 0) PeekInfo("$liveMatches MATCHER LIVE", "Fotboll", "Matcher & tabeller", null, poster = false) else null,
        newestMovie?.let { movie ->
            PeekInfo(
                "NYTT I FILMER",
                state.titleOf(movie),
                listOfNotNull(movie.year?.toString(), movie.group.takeIf { it.isNotBlank() }).joinToString(" · "),
                movie.poster,
                poster = true,
            )
        },
        newestSeries?.let { series ->
            PeekInfo(
                "NYTT I SERIER",
                state.titleOf(series),
                listOfNotNull(series.year, series.genre?.takeIf { it.isNotBlank() }).joinToString(" · "),
                series.cover,
                poster = true,
            )
        },
        genreMovie?.let { PeekInfo("GENRER", "Topplistor efter betyg", null, it.poster, poster = true) },
        last?.let {
            PeekInfo(
                "SÅG SIST",
                it.title,
                it.subtitle,
                it.image,
                poster = it.kind != WatchKind.LIVE,
                progress = it.progress.takeIf { p -> it.kind != WatchKind.LIVE && p > 0f },
            )
        },
        state.weeklyTop.firstOrNull()?.let { first ->
            PeekInfo(
                "VECKANS TOPP 10",
                "1. ${first.title}",
                state.weeklyTop.getOrNull(1)?.let { "2. ${it.title}" },
                first.poster,
                poster = true,
            )
        } ?: PeekInfo("VECKANS TOPP 10", "Populärast just nu", "Enligt TMDB", null, poster = false),
        PeekInfo("MUSIK", "Spotify", "Musik medan du tittar runt", null, poster = false),
        PeekInfo("INSTÄLLNINGAR", "Källor & uppspelning", "Utseende, menyn och omslagsväggen", null, poster = false),
    )
}

/**
 * Veckans topp 10 i de nya menyerna: den gula knappen, styrd som raderna
 * (pilarna, OK, pil höger). Strecket och ringarna glider dit som till en rad.
 */
@Composable
private fun StyledTopTen(ctx: MenuContext, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val isActive = ctx.active == TOP10_ROW
    TopTenButton(
        interaction = interaction,
        active = isActive || (!ctx.tv && hovered),
        current = ctx.current == TOP10_ROW,
        tv = ctx.tv,
        kick = { if (isActive) ctx.nav.press.value else 0f },
        modifier = modifier
            .onGloballyPositioned { ctx.nav.placed(TOP10_ROW, it.positionInRoot().y + it.size.height / 2f - ctx.nav.top) }
            .hoverable(interaction)
            .then(ctx.row(TOP10_ROW, interaction))
            .pointerHoverIcon(PointerIcon.Hand),
    )
}

private val STYLED_KICKOFF = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.forLanguageTag("sv-SE"))

// --- Minimal --------------------------------------------------------------------------

/**
 * Minimal: en stor klocka, raderna som smal vit text i versaler och ett rött
 * snedstreck som glider till raden man står på och studsar lite när det
 * stannar. Raden blir vit och fetare, och en kort färgglitch blinkar till när
 * man landar. OK: strecket blinkar vitt och en röd linje skjuter ut.
 */
@Composable
private fun MinimalMenu(ctx: MenuContext) {
    val nav = ctx.nav
    val liveMatches = ctx.state.footballMatches.count { it.state == MatchState.LIVE }
    Column(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                minimalMarker(nav, x = (if (ctx.tv) 30.dp else 20.dp).toPx() - 13.dp.toPx())
            }
            .padding(start = if (ctx.tv) 30.dp else 20.dp, end = 16.dp, top = if (ctx.tv) 30.dp else 20.dp, bottom = 20.dp),
    ) {
        StyledMiniPlayer(ctx)
        MinimalHeader(ctx)
        Spacer(Modifier.height(if (ctx.tv) 30.dp else 22.dp))
        for (i in 0..FOOTBALL_ROW) MinimalRow(ctx, i, if (i == FOOTBALL_ROW && liveMatches > 0) liveMatches else null)
        Spacer(Modifier.height(if (ctx.tv) 22.dp else 14.dp))
        for (i in FOOTBALL_ROW + 1 until TOP10_ROW) MinimalRow(ctx, i, null)
        Spacer(Modifier.height(if (ctx.tv) 14.dp else 10.dp))
        StyledTopTen(ctx, Modifier.padding(end = if (ctx.tv) 26.dp else 10.dp))
        Spacer(Modifier.height(if (ctx.tv) 10.dp else 6.dp))
        MinimalRow(ctx, MUSIC_ROW, null)
        Spacer(Modifier.weight(1f))
        if (ctx.state.importingId != null) {
            ImportIndicator(ctx.state.importState)
            Spacer(Modifier.height(8.dp))
        }
        if (ctx.tv && ctx.hasFocus) {
            val peeks = rememberMenuPeeks(ctx.state)
            MinimalPeek(peeks, ctx.active)
            Spacer(Modifier.height(14.dp))
        }
        MinimalRow(ctx, SETTINGS_ROW, null)
    }
}

@Composable
private fun MinimalHeader(ctx: MenuContext) {
    if (ctx.tv) {
        val time = Instant.ofEpochMilli(ctx.state.clock).atZone(ZoneId.systemDefault())
        Text(
            time.format(STYLED_TIME),
            style = TextStyle(fontFamily = menuDisplayFont, fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = 1.sp),
            color = Color.White,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            time.format(STYLED_DATE).uppercase(SWEDISH_UPPER),
            style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 3.5.sp),
            color = Color.White.copy(alpha = 0.42f),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 2.dp, top = 2.dp),
        )
    } else {
        Text(
            "TV",
            style = TextStyle(fontFamily = menuDisplayFont, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = 1.sp),
            color = Color.White,
            maxLines = 1,
        )
        Text(
            "FÖR ${platformName.uppercase()}",
            style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 3.sp),
            color = Color.White.copy(alpha = 0.42f),
            maxLines = 1,
        )
    }
}

@Composable
private fun MinimalRow(ctx: MenuContext, index: Int, liveCount: Int?) {
    val row = MENU_ROWS[index]
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val isActive = ctx.active == index
    val lit by animateFloatAsState(if (isActive) 1f else if (hovered) 0.5f else 0f, tween(160), label = "rad")
    val shift by animateDpAsState(if (isActive) 10.dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "skjut")
    // Färgglitchen när man landar på raden.
    val glitch = remember { Animatable(0f) }
    LaunchedEffect(isActive, ctx.hasFocus) {
        if (isActive && ctx.hasFocus) {
            glitch.snapTo(1f)
            glitch.animateTo(0f, tween(120))
        }
    }
    val label = row.label.uppercase(SWEDISH_UPPER)
    val style = TextStyle(
        fontFamily = menuCondensedFont,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
        fontSize = if (ctx.tv) 20.sp else 16.sp,
        letterSpacing = if (ctx.tv) 1.6.sp else 1.2.sp,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (ctx.tv) 40.dp else 34.dp)
            .onGloballyPositioned { ctx.nav.placed(index, it.positionInRoot().y + it.size.height / 2f - ctx.nav.top) }
            .hoverable(interaction)
            .then(ctx.row(index, interaction))
            .pointerHoverIcon(PointerIcon.Hand),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.graphicsLayer { translationX = shift.toPx() }) {
            Text(
                label,
                style = style,
                color = GLITCH_CYAN,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.graphicsLayer {
                    alpha = glitch.value * 0.85f
                    translationX = -3.dp.toPx() * glitch.value
                },
            )
            Text(
                label,
                style = style,
                color = GLITCH_MAGENTA,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.graphicsLayer {
                    alpha = glitch.value * 0.85f
                    translationX = 3.dp.toPx() * glitch.value
                },
            )
            Text(label, style = style, color = Color.White.copy(alpha = 0.40f + 0.60f * lit), maxLines = 1, softWrap = false)
        }
        if (liveCount != null) {
            Spacer(Modifier.width(14.dp + shift))
            Box(Modifier.size(6.dp).clip(CircleShape).background(MINIMAL_RED))
            Spacer(Modifier.width(6.dp))
            Text(
                "$liveCount LIVE",
                style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 2.sp),
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
            )
        }
    }
}

/** Det röda snedstrecket: sträcks medan det glider, blinkar vitt vid OK och skickar en linje åt höger. */
private fun DrawScope.minimalMarker(nav: StyledNav, x: Float) {
    if (nav.target < 0 || nav.centers[nav.target].isNaN()) return
    val y = nav.markerY.value
    val stretch = 1f + (abs(nav.markerY.velocity) / 5000f).coerceAtMost(0.6f)
    val half = 12.dp.toPx() * stretch
    val lean = 3.5.dp.toPx()
    val flash = nav.press.value
    val color = lerp(MINIMAL_RED, Color.White, (flash * 2.2f).coerceAtMost(1f))
    drawLine(color, Offset(x + lean, y - half), Offset(x - lean, y + half), strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Butt)
    if (flash > 0.01f) {
        val travel = (1f - flash) * 300.dp.toPx()
        val start = x + 24.dp.toPx() + travel
        val length = 130.dp.toPx()
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, MINIMAL_RED, Color.Transparent), startX = start, endX = start + length),
            Offset(start, y),
            Offset(start + length, y),
            strokeWidth = 2.dp.toPx(),
            alpha = flash,
        )
    }
}

@Composable
private fun MinimalPeek(peeks: List<PeekInfo?>, index: Int) {
    AnimatedContent(
        targetState = index,
        transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally(tween(240)) { it / 14 }) togetherWith fadeOut(tween(120)) },
        label = "minimal-glimt",
    ) { at ->
        val info = peeks.getOrNull(at)
        if (info == null || at == SETTINGS_ROW) {
            Spacer(Modifier.height(0.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (info.image != null) {
                    Box(
                        Modifier
                            .size(width = if (info.poster) 40.dp else 64.dp, height = if (info.poster) 60.dp else 36.dp)
                            .background(Color.White.copy(alpha = 0.06f)),
                    ) {
                        RemoteImage(
                            url = info.image,
                            modifier = Modifier.fillMaxSize(),
                            maxSide = 200,
                            contentScale = if (info.poster) ContentScale.Crop else ContentScale.Fit,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                }
                Column {
                    Text(
                        info.kicker,
                        style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 2.5.sp),
                        color = Color.White.copy(alpha = 0.45f),
                        maxLines = 1,
                    )
                    Text(
                        info.title.uppercase(SWEDISH_UPPER),
                        style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.8.sp),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    info.line?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.5.sp),
                            color = Color.White.copy(alpha = 0.45f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// --- Multiversum ----------------------------------------------------------------------

private const val WHEEL_STEP = 24f

/**
 * Multiversum: raderna är serierutor på ett hjul i 3D, var och en med ett
 * omslag bakom (det nyaste i Filmer, det man kan fortsätta titta på i Hem …)
 * i Marvel-rött. Hjulet snurrar så att raden man står på står rakt fram, och
 * studsar lite när det stannar. Den rutan får namnet i ett rött Marvel-block
 * som stämplas fram, och ett metallsken som sveper över. När menyn kommer
 * fram snurrar hjulet in.
 */
@Composable
private fun MultiverseMenu(ctx: MenuContext) {
    val nav = ctx.nav
    val peeks = rememberMenuPeeks(ctx.state)
    val liveMatches = ctx.state.footballMatches.count { it.state == MatchState.LIVE }
    if (ctx.tv) {
        LaunchedEffect(Unit) {
            delay(16)
            val to = nav.target.coerceAtLeast(0).toFloat()
            nav.pos.snapTo(to + 3.4f)
            nav.pos.animateTo(to, spring(dampingRatio = 0.55f, stiffness = 120f))
        }
    }
    Box(Modifier.fillMaxSize()) {
        MultiverseBackPanels(nav, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(top = if (ctx.tv) 30.dp else 18.dp, bottom = 18.dp)) {
            Column(Modifier.padding(start = if (ctx.tv) 30.dp else 16.dp, end = 16.dp)) {
                StyledMiniPlayer(ctx)
                MultiverseHeader(ctx)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                for (i in MENU_ROWS.indices) {
                    WheelPanel(
                        ctx,
                        i,
                        peeks.getOrNull(i),
                        if (i == FOOTBALL_ROW && liveMatches > 0) liveMatches else null,
                        Modifier.align(Alignment.CenterStart).zIndex(if (i == ctx.active) 1f else 0f),
                    )
                }
            }
            if (ctx.state.importingId != null) {
                Box(Modifier.padding(horizontal = 16.dp)) { ImportIndicator(ctx.state.importState) }
            }
        }
    }
}

@Composable
private fun MultiverseHeader(ctx: MenuContext) {
    if (ctx.tv) {
        val time = Instant.ofEpochMilli(ctx.state.clock).atZone(ZoneId.systemDefault())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                time.format(STYLED_TIME),
                style = TextStyle(fontFamily = menuDisplayFont, fontSize = 46.sp, lineHeight = 50.sp, letterSpacing = 1.sp),
                color = Color.White,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.width(14.dp))
            Box(Modifier.background(MARVEL_RED).padding(horizontal = 7.dp, vertical = 2.dp)) {
                Text(
                    time.format(STYLED_DATE).uppercase(SWEDISH_UPPER),
                    style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 2.sp),
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    } else {
        Box(Modifier.background(MARVEL_RED).padding(horizontal = 9.dp, vertical = 1.dp)) {
            Text("TV", style = TextStyle(fontFamily = menuDisplayFont, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = 1.sp), color = Color.White)
        }
    }
}

/** Stora tomma serierutor långt bak, som glider åt andra hållet när hjulet snurrar. */
@Composable
private fun MultiverseBackPanels(nav: StyledNav, modifier: Modifier) {
    Canvas(
        modifier.graphicsLayer {
            translationY = -(nav.pos.value * 26.dp.toPx())
            alpha = 0.9f
        },
    ) {
        val line = Color.White.copy(alpha = 0.045f)
        val red = MARVEL_RED.copy(alpha = 0.06f)
        val w = size.width
        val h = 150.dp.toPx()
        var y = -h * 3f
        var k = 0
        while (y < size.height + h * 9f) {
            val split = w * (0.35f + 0.3f * ((k * 37) % 10) / 10f)
            val stroke = Stroke(width = 1.5.dp.toPx())
            drawRect(line, topLeft = Offset(10.dp.toPx(), y), size = Size(split - 16.dp.toPx(), h - 12.dp.toPx()), style = stroke)
            drawRect(if (k % 3 == 0) red else line, topLeft = Offset(split, y), size = Size(w - split - 10.dp.toPx(), h - 12.dp.toPx()), style = stroke)
            y += h
            k++
        }
    }
}

@Composable
private fun WheelPanel(ctx: MenuContext, index: Int, info: PeekInfo?, liveCount: Int?, modifier: Modifier) {
    val row = MENU_ROWS[index]
    val nav = ctx.nav
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val isActive = ctx.active == index
    // Namnet stämplas fram när raden kommer fram.
    val stamp = remember { Animatable(1f) }
    LaunchedEffect(isActive) {
        if (isActive) {
            stamp.snapTo(1.32f)
            stamp.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 600f))
        }
    }
    // Metallskenet, när raden kommer fram och sedan då och då.
    val sweep = remember { Animatable(-1f) }
    LaunchedEffect(isActive) {
        if (!isActive) {
            sweep.snapTo(-1f)
            return@LaunchedEffect
        }
        delay(120)
        while (true) {
            sweep.snapTo(-0.35f)
            sweep.animateTo(1.35f, tween(900, easing = FastOutSlowInEasing))
            delay(3_400)
        }
    }
    val density = LocalDensity.current
    val panelWidth = if (ctx.tv) 372.dp else 248.dp
    val panelHeight = if (ctx.tv) 82.dp else 58.dp
    val radius = with(density) { (if (ctx.tv) 198.dp else 140.dp).toPx() }
    val label = row.label.uppercase(SWEDISH_UPPER)
    Box(
        modifier
            .padding(start = if (ctx.tv) 28.dp else 14.dp)
            .size(panelWidth, panelHeight)
            .graphicsLayer {
                val d = index - nav.pos.value
                val angle = d * WHEEL_STEP
                val rad = Math.toRadians(angle.toDouble())
                val c = cos(rad).toFloat()
                val seen = ((c * 1.35f) - 0.12f).coerceIn(0f, 1f)
                alpha = seen
                // Bakom hjulet: flyttas undan, så att den inte tar musklick från de som syns.
                translationX = if (seen <= 0f) -10_000f else 0f
                translationY = radius * sin(rad).toFloat()
                rotationX = -angle
                val s = 0.72f + 0.28f * c.coerceAtLeast(0f)
                scaleX = s
                scaleY = s
                cameraDistance = 6.5f * this.density
                transformOrigin = TransformOrigin(0.3f, 0.5f)
            }
            .hoverable(interaction)
            .then(ctx.row(index, interaction))
            .pointerHoverIcon(PointerIcon.Hand)
            .drawWithContent {
                drawContent()
                // Serierutans kant.
                drawRect(
                    Color.White.copy(alpha = if (isActive) 0.9f else if (hovered) 0.45f else 0.18f),
                    style = Stroke(width = if (isActive) 2.dp.toPx() else 1.dp.toPx()),
                )
                // Metallskenet: ett snett ljusband som sveper över.
                val p = sweep.value
                if (isActive && p > -0.5f) {
                    val x = size.width * p
                    val band = 90.dp.toPx()
                    drawRect(
                        Brush.linearGradient(
                            0f to Color.Transparent,
                            0.45f to Color.White.copy(alpha = 0.0f),
                            0.5f to Color.White.copy(alpha = 0.30f),
                            0.55f to Color.White.copy(alpha = 0.0f),
                            1f to Color.Transparent,
                            start = Offset(x - band, 0f),
                            end = Offset(x + band, size.height),
                        ),
                    )
                }
            }
            .clip(androidx.compose.ui.graphics.RectangleShape)
            .background(Color(0xFF0B0809)),
    ) {
        // Veckans topp 10: en gul ruta, som knappen i de andra menyerna.
        if (row.screen == Screen.TOP10) {
            TopTenWheelFace(isActive, ctx.tv)
        } else {
            if (info?.image != null) {
                RemoteImage(
                    url = info.image,
                    modifier = Modifier.fillMaxSize(),
                    maxSide = 600,
                    contentScale = if (info.poster) ContentScale.Crop else ContentScale.Fit,
                    alignment = Alignment.Center,
                )
            }
            // Mörkt till vänster där namnet står, och Marvel-rött över bilden.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.86f),
                            0.5f to Color.Black.copy(alpha = if (isActive) 0.30f else 0.50f),
                            1f to Color.Black.copy(alpha = if (isActive) 0.0f else 0.30f),
                        ),
                    )
                    .background(MARVEL_RED.copy(alpha = if (isActive) 0.14f else 0.08f)),
            )
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = if (ctx.tv) 16.dp else 10.dp, end = 12.dp),
            ) {
                if (isActive) {
                    Box(
                        Modifier
                            .graphicsLayer {
                                scaleX = stamp.value
                                scaleY = stamp.value
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            }
                            .background(MARVEL_RED)
                            .padding(horizontal = 9.dp, vertical = 1.dp),
                    ) {
                        Text(
                            label,
                            style = TextStyle(fontFamily = menuDisplayFont, fontSize = if (ctx.tv) 32.sp else 22.sp, lineHeight = if (ctx.tv) 36.sp else 25.sp, letterSpacing = 1.sp),
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    if (info != null && ctx.tv) {
                        Text(
                            "${info.kicker}  ·  ${info.title}".uppercase(SWEDISH_UPPER),
                            style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.4.sp),
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                } else {
                    Text(
                        label,
                        style = TextStyle(fontFamily = menuDisplayFont, fontSize = if (ctx.tv) 28.sp else 20.sp, lineHeight = if (ctx.tv) 32.sp else 23.sp, letterSpacing = 1.sp),
                        color = Color.White.copy(alpha = if (hovered) 0.85f else 0.62f),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(start = 9.dp),
                    )
                }
            }
            if (liveCount != null) {
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(LIVE_RED)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "$liveCount LIVE",
                        style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.sp),
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// --- Stark ----------------------------------------------------------------------------

/** Hur mycket hologrammet lutar, i grader. */
private const val STARK_TILT = 10f

/**
 * Stark: menyn är ett hologram som lutar in i rummet. Raden man står på får
 * hörn som snäpper fast, ett gyroskop av ringar i 3D som snurrar, och namnet
 * avkodas fram. En stor omloppsring i 3D följer markeringen bakom raderna.
 * När menyn kommer fram svänger hologrammet in och ringarna växer fram.
 */
@Composable
private fun StarkMenu(ctx: MenuContext) {
    val nav = ctx.nav
    val liveMatches = ctx.state.footballMatches.count { it.state == MatchState.LIVE }
    val boot = remember { Animatable(0f) }
    LaunchedEffect(Unit) { boot.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    val spin = rememberInfiniteTransition(label = "stark")
    val angle = spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8_000, easing = LinearEasing), RepeatMode.Restart),
        label = "ringar",
    )
    Box(Modifier.fillMaxSize()) {
        StarkOrbit(nav, angle, boot, ctx.tv)
        StarkOrbit(nav, angle, boot, ctx.tv, scale = 0.7f, tiltX = 66f, tiltY = 24f, speed = -0.6f)
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationY = STARK_TILT + (1f - boot.value) * 38f
                    alpha = (boot.value * 1.6f).coerceAtMost(1f)
                    cameraDistance = 10f * density
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .padding(start = if (ctx.tv) 26.dp else 14.dp, end = 18.dp, top = if (ctx.tv) 28.dp else 18.dp, bottom = 20.dp),
        ) {
            StyledMiniPlayer(ctx)
            StarkHeader(ctx)
            Spacer(Modifier.height(if (ctx.tv) 24.dp else 16.dp))
            for (i in 0..FOOTBALL_ROW) StarkRow(ctx, i, if (i == FOOTBALL_ROW && liveMatches > 0) liveMatches else null, angle)
            Text(
                "// BIBLIOTEK",
                style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 3.sp),
                color = STARK_CYAN.copy(alpha = 0.6f),
                maxLines = 1,
                modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 6.dp),
            )
            for (i in FOOTBALL_ROW + 1 until TOP10_ROW) StarkRow(ctx, i, null, angle)
            Spacer(Modifier.height(if (ctx.tv) 12.dp else 8.dp))
            StyledTopTen(ctx, Modifier.padding(start = 4.dp, end = if (ctx.tv) 34.dp else 12.dp))
            Spacer(Modifier.height(if (ctx.tv) 8.dp else 6.dp))
            StarkRow(ctx, MUSIC_ROW, null, angle)
            Spacer(Modifier.weight(1f))
            if (ctx.state.importingId != null) {
                ImportIndicator(ctx.state.importState)
                Spacer(Modifier.height(8.dp))
            }
            if (ctx.tv && ctx.hasFocus) {
                val peeks = rememberMenuPeeks(ctx.state)
                StarkPeek(peeks, ctx.active)
                Spacer(Modifier.height(12.dp))
            }
            StarkRow(ctx, SETTINGS_ROW, null, angle)
        }
    }
}

@Composable
private fun StarkHeader(ctx: MenuContext) {
    val time = Instant.ofEpochMilli(ctx.state.clock).atZone(ZoneId.systemDefault())
    if (ctx.tv) {
        Text(
            "LOKAL TID  ·  STOCKHOLM",
            style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 2.5.sp),
            color = STARK_CYAN.copy(alpha = 0.75f),
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp),
        )
        Text(
            time.format(STYLED_TIME),
            style = TextStyle(fontWeight = FontWeight.Light, fontSize = 54.sp, lineHeight = 60.sp, letterSpacing = 2.sp),
            color = Color.White,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            time.format(STYLED_DATE).uppercase(SWEDISH_UPPER),
            style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 2.5.sp),
            color = STARK_CYAN.copy(alpha = 0.9f),
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp),
        )
    } else {
        Text(
            "TV  ·  ${platformName.uppercase()}",
            style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 3.sp),
            color = Color.White,
            maxLines = 1,
        )
    }
    Box(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .height(4.dp)
            .drawBehind {
                drawLine(
                    Brush.horizontalGradient(listOf(STARK_CYAN.copy(alpha = 0.6f), Color.Transparent)),
                    Offset(0f, size.height / 2f),
                    Offset(size.width, size.height / 2f),
                    strokeWidth = 1.dp.toPx(),
                )
                drawRect(STARK_CYAN, size = Size(42.dp.toPx(), size.height))
            },
    )
}

private const val SCRAMBLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZÅÄÖ0123456789#%&<>/"

@Composable
private fun StarkRow(ctx: MenuContext, index: Int, liveCount: Int?, angle: State<Float>) {
    val row = MENU_ROWS[index]
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val isActive = ctx.active == index
    val label = row.label.uppercase(SWEDISH_UPPER)
    // Hörnen snäpper fast när raden kommer fram.
    val snapIn = remember { Animatable(1f) }
    LaunchedEffect(isActive) {
        if (isActive) {
            snapIn.snapTo(0f)
            snapIn.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 520f))
        }
    }
    // Namnet avkodas fram: slumpade tecken som faller på plats från vänster.
    var shown by remember(label) { mutableStateOf(label) }
    LaunchedEffect(isActive, ctx.hasFocus) {
        if (isActive && ctx.hasFocus) {
            val steps = 8
            for (s in 0..steps) {
                val reveal = label.length * s / steps
                shown = buildString {
                    label.forEachIndexed { k, ch -> append(if (k < reveal || ch == ' ') ch else SCRAMBLE.random()) }
                }
                delay(28)
            }
        }
        shown = label
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (ctx.tv) 46.dp else 36.dp)
            .onGloballyPositioned { ctx.nav.placed(index, it.positionInRoot().y + it.size.height / 2f - ctx.nav.top) }
            .hoverable(interaction)
            .then(ctx.row(index, interaction))
            .pointerHoverIcon(PointerIcon.Hand)
            .drawWithContent {
                if (isActive) starkSelection(snapIn.value)
                drawContent()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(44.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (isActive) {
                Gyroscope(angle, Modifier.size(30.dp))
            } else {
                Text(
                    (index + 1).toString().padStart(2, '0'),
                    style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.sp),
                    color = STARK_CYAN.copy(alpha = if (hovered) 0.9f else 0.5f),
                    maxLines = 1,
                )
            }
        }
        Text(
            shown,
            style = TextStyle(
                fontFamily = menuCondensedFont,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = if (ctx.tv) 20.sp else 16.sp,
                letterSpacing = 2.sp,
            ),
            color = if (isActive) Color.White else Color.White.copy(alpha = if (hovered) 0.85f else 0.62f),
            maxLines = 1,
            softWrap = false,
        )
        if (liveCount != null) {
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier
                    .drawBehind { drawRect(LIVE_RED.copy(alpha = 0.7f), style = Stroke(width = 1.dp.toPx())) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(LIVE_RED))
                Spacer(Modifier.width(5.dp))
                Text(
                    "$liveCount LIVE",
                    style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.5.sp),
                    color = LIVE_RED,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Den markerade raden: ett genomskinligt fält med svepande linjer, och hörn som snäpper fast. */
private fun DrawScope.starkSelection(snap: Float) {
    val pad = (1f - snap) * 14.dp.toPx()
    val left = -pad
    val top = -pad * 0.5f
    val right = size.width - 26.dp.toPx() + pad
    val bottom = size.height + pad * 0.5f
    drawRect(
        Brush.horizontalGradient(
            0f to STARK_CYAN.copy(alpha = 0.20f),
            0.8f to STARK_CYAN.copy(alpha = 0.03f),
            1f to Color.Transparent,
            startX = left,
            endX = right,
        ),
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        alpha = snap.coerceIn(0f, 1f),
    )
    // Linjer som på en bildskärm.
    var y = top + 2.dp.toPx()
    while (y < bottom) {
        drawLine(STARK_CYAN.copy(alpha = 0.05f), Offset(left, y), Offset(right, y), strokeWidth = 1f)
        y += 3.dp.toPx()
    }
    val arm = 13.dp.toPx()
    val stroke = 2.dp.toPx()
    val c = STARK_CYAN.copy(alpha = (0.4f + 0.6f * snap).coerceIn(0f, 1f))
    // Fyra hörn.
    drawLine(c, Offset(left, top), Offset(left + arm, top), stroke)
    drawLine(c, Offset(left, top), Offset(left, top + arm), stroke)
    drawLine(c, Offset(right, top), Offset(right - arm, top), stroke)
    drawLine(c, Offset(right, top), Offset(right, top + arm), stroke)
    drawLine(c, Offset(left, bottom), Offset(left + arm, bottom), stroke)
    drawLine(c, Offset(left, bottom), Offset(left, bottom - arm), stroke)
    drawLine(c, Offset(right, bottom), Offset(right - arm, bottom), stroke)
    drawLine(c, Offset(right, bottom), Offset(right, bottom - arm), stroke)
}

/**
 * Ett gyroskop: tre ringar som lutar åt olika håll och snurrar runt en
 * lysande prick. Ringarna ritas en gång; bara lagrens vinklar ändras.
 */
@Composable
private fun Gyroscope(angle: State<Float>, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Ring(Modifier.fillMaxSize().graphicsLayer {
            rotationX = 64f
            rotationZ = angle.value
            cameraDistance = 6f * density
        }, STARK_CYAN, 300f)
        Ring(Modifier.fillMaxSize().graphicsLayer {
            rotationY = 62f
            rotationZ = -angle.value * 1.4f
            cameraDistance = 6f * density
        }, STARK_CYAN.copy(alpha = 0.8f), 270f)
        Ring(Modifier.fillMaxSize().padding(5.dp).graphicsLayer {
            rotationX = 20f
            rotationZ = angle.value * 2.2f
        }, STARK_GOLD, 220f)
        Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun Ring(modifier: Modifier, color: Color, sweep: Float) {
    Canvas(modifier) {
        drawArc(color, startAngle = 0f, sweepAngle = sweep, useCenter = false, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * Den stora omloppsringen bakom raderna: två ellipser i 3D som snurrar och
 * följer markeringen upp och ner. Ritas en gång; bara lagret flyttas och vrids.
 */
@Composable
private fun StarkOrbit(
    nav: StyledNav,
    angle: State<Float>,
    boot: Animatable<Float, *>,
    tv: Boolean,
    scale: Float = 1f,
    tiltX: Float = 76f,
    tiltY: Float = -14f,
    speed: Float = 0.35f,
) {
    val size = (if (tv) 470.dp else 300.dp) * scale
    val density = LocalDensity.current
    val half = with(density) { size.toPx() / 2f }
    Box(
        Modifier
            .size(size)
            .offset {
                IntOffset(
                    ((if (tv) 195.dp else 120.dp).toPx() - half).roundToInt(),
                    (nav.markerY.value - half).roundToInt(),
                )
            }
            .graphicsLayer {
                val b = boot.value
                scaleX = b
                scaleY = b
                alpha = 0.8f * b
                rotationX = tiltX
                rotationY = tiltY
                rotationZ = angle.value * speed
                cameraDistance = 12f * this.density
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val dash = Stroke(width = 1.5.dp.toPx())
            // Ytterringen i bitar, innerringen hel, och små streck runt om.
            for (k in 0 until 8) {
                drawArc(
                    STARK_CYAN.copy(alpha = if (k % 3 == 0) 0.9f else 0.45f),
                    startAngle = k * 45f + 4f,
                    sweepAngle = 32f,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2f, r * 2f),
                    style = dash,
                )
            }
            val inner = r * 0.74f
            drawCircle(STARK_CYAN.copy(alpha = 0.35f), radius = inner, center = center, style = Stroke(width = 1.dp.toPx()))
            for (k in 0 until 36) {
                val a = Math.toRadians(k * 10.0)
                val from = inner * 0.92f
                val to = if (k % 3 == 0) inner * 0.82f else inner * 0.88f
                drawLine(
                    STARK_GOLD.copy(alpha = 0.5f),
                    Offset(center.x + cos(a).toFloat() * from, center.y + sin(a).toFloat() * from),
                    Offset(center.x + cos(a).toFloat() * to, center.y + sin(a).toFloat() * to),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
    }
}

@Composable
private fun StarkPeek(peeks: List<PeekInfo?>, index: Int) {
    val info = peeks.getOrNull(index) ?: return
    if (index == SETTINGS_ROW) return
    key(index) {
        val flip = remember { Animatable(80f) }
        LaunchedEffect(Unit) { flip.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 260f)) }
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    rotationY = flip.value
                    cameraDistance = 10f * density
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .drawBehind {
                    drawRect(STARK_CYAN.copy(alpha = 0.06f))
                    val arm = 12.dp.toPx()
                    val s = 1.5.dp.toPx()
                    val c = STARK_CYAN.copy(alpha = 0.8f)
                    drawLine(c, Offset(0f, 0f), Offset(arm, 0f), s)
                    drawLine(c, Offset(0f, 0f), Offset(0f, arm), s)
                    drawLine(c, Offset(size.width, 0f), Offset(size.width - arm, 0f), s)
                    drawLine(c, Offset(size.width, 0f), Offset(size.width, arm), s)
                    drawLine(c, Offset(0f, size.height), Offset(arm, size.height), s)
                    drawLine(c, Offset(0f, size.height), Offset(0f, size.height - arm), s)
                    drawLine(c, Offset(size.width, size.height), Offset(size.width - arm, size.height), s)
                    drawLine(c, Offset(size.width, size.height), Offset(size.width, size.height - arm), s)
                }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (info.image != null) {
                Box(
                    Modifier
                        .size(width = if (info.poster) 40.dp else 64.dp, height = if (info.poster) 60.dp else 36.dp)
                        .drawWithContent {
                            drawContent()
                            drawRect(STARK_CYAN.copy(alpha = 0.6f), style = Stroke(width = 1.dp.toPx()))
                        },
                ) {
                    RemoteImage(
                        url = info.image,
                        modifier = Modifier.fillMaxSize(),
                        maxSide = 200,
                        contentScale = if (info.poster) ContentScale.Crop else ContentScale.Fit,
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(
                    "▸ ${info.kicker}",
                    style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 2.sp),
                    color = STARK_CYAN,
                    maxLines = 1,
                )
                Text(
                    info.title.uppercase(SWEDISH_UPPER),
                    style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
                info.line?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.5.sp),
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// --- Inställningarnas små bilder --------------------------------------------------------

/** En liten bild av varje meny, i valet i Inställningar. */
internal fun DrawScope.menuMiniature(style: MenuStyle) {
    val w = size.width
    val h = size.height
    when (style) {
        MenuStyle.ORIGINAL -> {
            drawRect(Color(0xFF0B0D10))
            val colors = listOf(MenuAccents.home, MenuAccents.live, MenuAccents.guide, MenuAccents.football, MenuAccents.movies, MenuAccents.series)
            colors.forEachIndexed { k, color ->
                val y = h * 0.12f + k * h * 0.14f
                if (k == 4) drawRoundRect(color, topLeft = Offset(w * 0.06f, y - h * 0.02f), size = Size(w * 0.6f, h * 0.12f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f))
                drawRoundRect(if (k == 4) Color.White.copy(alpha = 0.3f) else color, topLeft = Offset(w * 0.09f, y), size = Size(h * 0.08f, h * 0.08f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f))
                drawRect(Color.White.copy(alpha = if (k == 4) 0.9f else 0.45f), topLeft = Offset(w * 0.09f + h * 0.13f, y + h * 0.025f), size = Size(w * 0.28f, h * 0.035f))
            }
        }
        MenuStyle.MINIMAL -> {
            drawRect(Color(0xFF050608))
            drawRect(Color.White, topLeft = Offset(w * 0.1f, h * 0.1f), size = Size(w * 0.3f, h * 0.13f))
            for (k in 0 until 6) {
                val y = h * 0.36f + k * h * 0.1f + if (k >= 3) h * 0.05f else 0f
                val lit = k == 3
                drawRect(Color.White.copy(alpha = if (lit) 1f else 0.4f), topLeft = Offset(w * (if (lit) 0.14f else 0.1f), y), size = Size(w * 0.24f, h * 0.035f))
                if (lit) drawLine(MINIMAL_RED, Offset(w * 0.08f, y - h * 0.03f), Offset(w * 0.065f, y + h * 0.065f), strokeWidth = 3f)
            }
        }
        MenuStyle.MULTIVERSE -> {
            drawRect(Color(0xFF070405))
            drawRect(Brush.radialGradient(listOf(MARVEL_RED.copy(alpha = 0.35f), Color.Transparent), center = Offset(w * 0.4f, h * 0.5f), radius = w * 0.7f))
            for (d in -2..2) {
                val y = h * 0.5f + d * h * 0.19f
                val s = 1f - abs(d) * 0.16f
                val pw = w * 0.74f * s
                val ph = h * 0.15f * (1f - abs(d) * 0.22f)
                val left = w * 0.1f
                drawRect(Color.White.copy(alpha = if (d == 0) 0.9f else 0.22f), topLeft = Offset(left, y - ph / 2f), size = Size(pw, ph), style = Stroke(width = if (d == 0) 2f else 1f))
                if (d == 0) drawRect(MARVEL_RED, topLeft = Offset(left + w * 0.04f, y - ph * 0.3f), size = Size(w * 0.3f, ph * 0.6f))
            }
        }
        MenuStyle.STARK -> {
            drawRect(Color(0xFF020910))
            val center = Offset(w * 0.55f, h * 0.52f)
            drawOval(STARK_CYAN.copy(alpha = 0.6f), topLeft = Offset(center.x - w * 0.4f, center.y - h * 0.12f), size = Size(w * 0.8f, h * 0.24f), style = Stroke(width = 1.5f))
            drawOval(STARK_GOLD.copy(alpha = 0.5f), topLeft = Offset(center.x - w * 0.3f, center.y - h * 0.08f), size = Size(w * 0.6f, h * 0.16f), style = Stroke(width = 1f))
            for (k in 0 until 6) {
                val y = h * 0.2f + k * h * 0.12f
                val lit = k == 3
                if (lit) {
                    drawRect(STARK_CYAN.copy(alpha = 0.18f), topLeft = Offset(w * 0.06f, y - h * 0.04f), size = Size(w * 0.6f, h * 0.11f))
                    drawCircle(STARK_CYAN, radius = h * 0.035f, center = Offset(w * 0.1f, y + h * 0.015f), style = Stroke(width = 1.5f))
                }
                drawRect(Color.White.copy(alpha = if (lit) 1f else 0.5f), topLeft = Offset(w * 0.17f, y), size = Size(w * 0.26f, h * 0.035f))
            }
        }
    }
}

package se.norrsken.tv.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.Screen
import se.norrsken.tv.desktop.data.Movie
import se.norrsken.tv.desktop.data.WeeklyTitle

/*
 * Veckans topp 10: en gul knapp i menyn, och sidan den öppnar med veckans
 * tio populäraste filmer enligt TMDB. Knappen är appens egen, i samma anda
 * som de gula skyltarna på 90-talets hyrfilmer: gul, med tjock svart text
 * som lutar framåt, en stjärna i en svart bricka och tre svarta streck.
 */

/** Knappens gula, i två toner uppifrån och ner. */
internal val TOP_YELLOW = Color(0xFFFFD21A)
private val TOP_YELLOW_DEEP = Color(0xFFFFAC00)

/** Kanten knappen står på, och skuggan bakom den markerade siffran. */
private val TOP_SIDE = Color(0xFF8F5300)
private val TOP_INK = Color(0xFF15110A)

/** Texten lutar framåt, som på skyltarna. */
private val TOP_SLANT = TextGeometricTransform(skewX = -0.16f)
private val TOP_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val TOP_LOCALE: Locale = Locale.forLanguageTag("sv-SE")

/** Antons siffror är så här höga, i delar av teckenstorleken. */
private const val ANTON_CAP = 0.859f

// --- Knappen ------------------------------------------------------------------------------

/**
 * Den gula knappen i menyn. Den står på en mörkare kant och trycks ner när
 * man väljer den; markerad lyfter den lite och får en vit ring runt sig.
 * [interaction]: knappens tryck (OK, musen, fingret). [active]: markeringen,
 * eller musen, står på den. [current]: veckans topp 10 är sidan som visas.
 * [kick]: ett tryck som menyn själv visar (de nya menyernas OK), 1 och ned mot 0.
 */
@Composable
internal fun TopTenButton(
    interaction: MutableInteractionSource,
    active: Boolean,
    current: Boolean,
    tv: Boolean,
    modifier: Modifier = Modifier,
    kick: () -> Float = { 0f },
) {
    val pressed by interaction.collectIsPressedAsState()
    val lift = animateFloatAsState(
        if (active && !pressed) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = 520f),
        label = "topp-lyft",
    )
    // Ner fort, upp med en liten studs.
    val pushSpec: AnimationSpec<Float> = if (pressed) tween(50) else spring(dampingRatio = 0.45f, stiffness = 700f)
    val push = animateFloatAsState(if (pressed) 1f else 0f, pushSpec, label = "topp-tryck")
    val ring = animateFloatAsState(if (active) 1f else if (current) 0.45f else 0f, tween(160), label = "topp-ring")
    TopTenArt(
        tv = tv,
        lift = { lift.value },
        push = { maxOf(push.value, kick()) },
        ring = { ring.value },
        modifier = modifier.widthIn(max = if (tv) 276.dp else 236.dp).fillMaxWidth(),
    )
}

/**
 * Knappen som bild: kanten den står på, den gula ytan med stjärnan, texten
 * och strecken, och ringen. [lift], [push] och [ring] läses först när den
 * ritas, så att bara ritningen görs om medan de rör sig.
 */
@Composable
private fun TopTenArt(
    tv: Boolean,
    lift: () -> Float,
    push: () -> Float,
    ring: () -> Float,
    modifier: Modifier = Modifier,
    /** Större, som rubrik på sidan. */
    large: Boolean = false,
) {
    val bodyHeight = when {
        large && tv -> 66.dp
        large -> 56.dp
        tv -> 56.dp
        else -> 46.dp
    }
    val side = if (tv) 6.dp else 5.dp
    val room = 7.dp
    val corner = if (tv) 14.dp else 12.dp
    Box(
        modifier
            .height(bodyHeight + side + room * 2)
            .drawBehind {
                val inset = room.toPx()
                val w = size.width - inset * 2
                val h = bodyHeight.toPx()
                val s = side.toPx()
                val r = CornerRadius(corner.toPx())
                val top = inset - lift() * 2.dp.toPx() + push() * s
                // Kanten knappen står på.
                drawRoundRect(TOP_SIDE, topLeft = Offset(inset, inset + s), size = Size(w, h), cornerRadius = r)
                // Den gula ytan.
                drawRoundRect(
                    Brush.verticalGradient(listOf(TOP_YELLOW, TOP_YELLOW_DEEP), startY = top, endY = top + h),
                    topLeft = Offset(inset, top),
                    size = Size(w, h),
                    cornerRadius = r,
                )
                clipPath(Path().apply { addRoundRect(RoundRect(inset, top, inset + w, top + h, r)) }) {
                    topTenStripes(right = inset + w - (if (tv) 20.dp else 16.dp).toPx(), top = top, bottom = top + h, tv = tv)
                }
                drawRoundRect(
                    TOP_INK.copy(alpha = 0.6f),
                    topLeft = Offset(inset, top),
                    size = Size(w, h),
                    cornerRadius = r,
                    style = Stroke(1.5.dp.toPx()),
                )
                // Markerad: en vit ring runt hela knappen, kanten med.
                val glow = ring()
                if (glow > 0.01f) {
                    val g = 3.5.dp.toPx()
                    drawRoundRect(
                        Color.White.copy(alpha = glow),
                        topLeft = Offset(inset - g, top - g),
                        size = Size(w + g * 2, inset + s + h - top + g * 2),
                        cornerRadius = CornerRadius(r.x + g),
                        style = Stroke((if (tv) 2.5.dp else 2.dp).toPx()),
                    )
                }
            },
    ) {
        Row(
            Modifier
                .padding(start = room + (if (tv) 10.dp else 8.dp), end = room)
                .height(bodyHeight)
                .graphicsLayer { translationY = room.toPx() - lift() * 2.dp.toPx() + push() * side.toPx() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopTenStar(
                when {
                    large && tv -> 44.dp
                    large -> 38.dp
                    tv -> 36.dp
                    else -> 30.dp
                },
            )
            Spacer(Modifier.width(if (tv) 11.dp else 9.dp))
            TopTenWords(tv, large)
        }
    }
}

/** Tre svarta streck som lutar, med det sista strax innanför [right]. */
private fun DrawScope.topTenStripes(right: Float, top: Float, bottom: Float, tv: Boolean) {
    val bar = (if (tv) 7.dp else 6.dp).toPx()
    val gap = (if (tv) 5.dp else 4.dp).toPx()
    val lean = (bottom - top) * 0.34f
    var x = right - lean - 3 * bar - 2 * gap
    repeat(3) {
        val stripe = Path().apply {
            moveTo(x + lean, top)
            lineTo(x + lean + bar, top)
            lineTo(x + bar, bottom)
            lineTo(x, bottom)
            close()
        }
        drawPath(stripe, TOP_INK)
        x += bar + gap
    }
}

/** En gul stjärna i en svart rund bricka. */
@Composable
private fun TopTenStar(size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(TOP_INK), contentAlignment = Alignment.Center) {
        Icon(Glyphs.Star, contentDescription = null, tint = TOP_YELLOW, modifier = Modifier.size(size * 0.64f))
    }
}

/** "VECKANS" smått och "TOPP 10" stort, svart och lutande. */
@Composable
private fun TopTenWords(tv: Boolean, large: Boolean) {
    val big = when {
        large && tv -> 31.sp
        large -> 26.sp
        tv -> 25.sp
        else -> 20.sp
    }
    Column {
        Text(
            "VECKANS",
            style = TextStyle(
                fontFamily = menuCondensedFont,
                fontWeight = FontWeight.Bold,
                fontSize = if (large) 11.sp else 9.5.sp,
                lineHeight = if (large) 12.sp else 10.5.sp,
                letterSpacing = 2.6.sp,
                textGeometricTransform = TOP_SLANT,
            ),
            color = TOP_INK,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            "TOPP 10",
            style = TextStyle(
                fontFamily = menuDisplayFont,
                fontSize = big,
                lineHeight = big * 1.06f,
                letterSpacing = 1.sp,
                textGeometricTransform = TOP_SLANT,
            ),
            color = TOP_INK,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Multiversums hjul: rutan för veckans topp 10 är gul, med stjärnan och
 * texten som på knappen. Hjulet ritar ramen och skenet runt den.
 */
@Composable
internal fun TopTenWheelFace(active: Boolean, tv: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(TOP_YELLOW, TOP_YELLOW_DEEP)))
                topTenStripes(right = size.width - (if (tv) 22.dp else 14.dp).toPx(), top = 0f, bottom = size.height, tv = tv)
            },
    ) {
        Row(
            Modifier.align(Alignment.CenterStart).padding(start = if (tv) 16.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopTenStar(if (tv) 42.dp else 30.dp)
            Spacer(Modifier.width(if (tv) 12.dp else 9.dp))
            TopTenWords(tv, large = active && tv)
        }
    }
}

// --- Sidan ----------------------------------------------------------------------------------

/** Måtten på sidan: tv:n och datorn. */
private class TopTenSizes(
    /** Marginalen till vänster och höger. */
    val side: Dp,
    val posterWidth: Dp,
    val posterHeight: Dp,
    /** De stora siffrornas höjd. */
    val numberHeight: Dp,
    val gap: Dp,
    val title: TextUnit,
    val heroWidth: Dp,
)

private val TV_SIZES = TopTenSizes(
    side = 64.dp,
    posterWidth = 152.dp,
    posterHeight = 228.dp,
    numberHeight = 172.dp,
    gap = 14.dp,
    title = 50.sp,
    heroWidth = 780.dp,
)

private val DESK_SIZES = TopTenSizes(
    side = 32.dp,
    posterWidth = 120.dp,
    posterHeight = 180.dp,
    numberHeight = 136.dp,
    gap = 10.dp,
    title = 36.sp,
    heroWidth = 600.dp,
)

/**
 * Veckans topp 10: de tio populäraste filmerna just nu enligt TMDB, med
 * stora siffror bredvid omslagen. Den markerade filmens bild fyller
 * bakgrunden, med titel, år, genrer, betyg och handling. Det som finns i
 * biblioteket öppnas med OK (eller ett klick); det andra visas bara.
 */
@Composable
internal fun TopTenScreen(state: AppState) {
    // Före förgreningarna: tillbaka från en film ska landa där man var.
    val rowState = rememberLazyListState()
    val entry = remember { TvEntry() }
    var shown by remember { mutableIntStateOf(0) }
    var pending by remember { mutableIntStateOf(0) }
    var entering by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        state.refreshWeeklyTop()
        delay(1_200)
        entering = false
    }
    // Paus: snabbt bläddrande byter inte bakgrund för varje film man passerar.
    LaunchedEffect(pending) {
        if (pending == shown) return@LaunchedEffect
        delay(if (tvLayout) 160L else 90L)
        shown = pending
    }
    val titles = state.weeklyTop
    val library = state.library
    val versions = state.versions
    val tmdb = state.tmdbFacts
    // Vilka av filmerna som finns i biblioteket. Räknas i bakgrunden, och
    // under TMDB-hämtningen bara en gång när den tagit en paus.
    val owned by produceState(emptyList<Movie?>(), titles, library, versions, tmdb) {
        if (value.isNotEmpty()) delay(1_500)
        value = withContext(Dispatchers.Default) { runCatching { state.weeklyInLibrary(titles) }.getOrDefault(emptyList()) }
    }

    val openMovie = state.selectedMovie
    if (openMovie != null) {
        MovieDetail(state, openMovie)
        return
    }
    if (titles.isEmpty()) {
        when {
            !state.tmdbConnected -> EmptyState(
                title = "Veckans topp 10 kommer från TMDB",
                body = "Lägg in din TMDB-nyckel i Inställningar, så kommer veckans populäraste filmer upp här.",
                actionLabel = "Till Inställningar",
                actionIcon = Glyphs.Settings,
                onAction = { state.screen = Screen.SETTINGS },
            )
            state.weeklyTopFailed && !state.weeklyTopLoading -> EmptyState(
                title = "Listan gick inte att hämta",
                body = "TMDB svarade inte just nu. Försök igen om en stund.",
                actionLabel = "Försök igen",
                actionIcon = Glyphs.Refresh,
                onAction = { state.refreshWeeklyTop(force = true) },
            )
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
        }
        return
    }

    // Tv: markeringen landar på filmen man senast stod på, annars på ettan.
    val scope = rememberCoroutineScope()
    TvFocusFallback {
        scope.launch { entry.enter { rowState.layoutInfo.visibleItemsInfo.map { it.key } } }
        true
    }
    // Tillbaka från en film, eller listan kom medan man stod här: in i listan,
    // om inte menyn är framme (då går menyn dit själv när man väljer).
    if (tvLayout) {
        LaunchedEffect(Unit) {
            if (TvNav.drawer == TvDrawer.NONE) entry.enter { rowState.layoutInfo.visibleItemsInfo.map { it.key } }
        }
    }
    TopTenBoard(
        state = state,
        titles = titles,
        owned = owned,
        shown = shown.coerceIn(0, titles.lastIndex),
        onActive = { pending = it },
        onShow = {
            pending = it
            shown = it
        },
        rowState = rowState,
        entry = entry,
        entering = entering,
    )
}

@Composable
private fun TopTenBoard(
    state: AppState,
    titles: List<WeeklyTitle>,
    owned: List<Movie?>,
    shown: Int,
    onActive: (Int) -> Unit,
    onShow: (Int) -> Unit,
    rowState: LazyListState,
    entry: TvEntry,
    entering: Boolean,
) {
    val tv = tvLayout
    val sizes = if (tv) TV_SIZES else DESK_SIZES
    val palette = LocalPalette.current
    val title = titles[shown]
    Box(Modifier.fillMaxSize().background(palette.background)) {
        TopTenBackdrop(title.backdrop ?: title.poster, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            TopTenHeader(state, titles, owned, sizes)
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(start = sizes.side, end = sizes.side, top = 12.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                TopTenHero(titles, owned, shown, sizes)
            }
            TvScrolling(lead = 0.05f, trail = 0.1f) {
                LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(start = sizes.side, end = sizes.side, top = 18.dp, bottom = if (tv) 40.dp else 26.dp),
                    horizontalArrangement = Arrangement.spacedBy(sizes.gap),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    itemsIndexed(titles, key = { _, item -> "topp:${item.id}" }) { index, item ->
                        val key = "topp:${item.id}"
                        val movie = owned.getOrNull(index)
                        TopTenCard(
                            rank = index + 1,
                            title = item,
                            movie = movie,
                            sizes = sizes,
                            entering = entering,
                            focus = entry.mark(key).onFocusChanged { if (it.isFocused) entry.last = key },
                            onActive = { onActive(index) },
                            onClick = {
                                if (movie != null) {
                                    state.showMovie(movie)
                                } else {
                                    onShow(index)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Den visade filmens bild över hela sidan, mörk där texten och omslagen står. */
@Composable
private fun TopTenBackdrop(url: String?, modifier: Modifier) {
    val palette = LocalPalette.current
    Box(modifier) {
        Crossfade(targetState = url, animationSpec = tween(520), label = "topp-bild") { image ->
            if (image != null) {
                RemoteImage(
                    url = image,
                    modifier = Modifier.fillMaxSize(),
                    maxSide = 1280,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            } else {
                Spacer(Modifier.fillMaxSize())
            }
        }
        val base = palette.background
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.horizontalGradient(
                        0f to base.copy(alpha = 0.97f),
                        0.36f to base.copy(alpha = 0.80f),
                        0.68f to base.copy(alpha = 0.22f),
                        1f to base.copy(alpha = 0.06f),
                    ),
                )
                drawRect(
                    Brush.verticalGradient(
                        0f to base.copy(alpha = 0.6f),
                        0.2f to Color.Transparent,
                        0.5f to Color.Transparent,
                        0.76f to base.copy(alpha = 0.84f),
                        1f to base.copy(alpha = 0.97f),
                    ),
                )
            },
        )
    }
}

/** Överst: den gula skylten, lite på sned, och vilken vecka listan gäller. */
@Composable
private fun TopTenHeader(state: AppState, titles: List<WeeklyTitle>, owned: List<Movie?>, sizes: TopTenSizes) {
    val tv = tvLayout
    val palette = LocalPalette.current
    val week = remember { LocalDate.now().get(WeekFields.ISO.weekOfWeekBasedYear()) }
    val count = owned.count { it != null }
    val updated = state.weeklyTopAt.takeIf { it > 0L }?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(TOP_CLOCK)
    }
    Row(
        Modifier.fillMaxWidth().padding(start = sizes.side - 7.dp, end = sizes.side, top = if (tv) 32.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopTenArt(
            tv = tv,
            lift = { 0f },
            push = { 0f },
            ring = { 0f },
            large = true,
            modifier = Modifier.width(if (tv) 300.dp else 252.dp).graphicsLayer { rotationZ = -2.5f },
        )
        Spacer(Modifier.width(if (tv) 22.dp else 16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "POPULÄRAST JUST NU  ·  VECKA $week",
                style = TextStyle(
                    fontFamily = menuCondensedFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (tv) 15.sp else 13.sp,
                    letterSpacing = 2.4.sp,
                ),
                color = palette.textPrimary,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                listOfNotNull(
                    "Enligt TMDB",
                    updated?.let { "uppdaterad kl. $it" },
                    if (owned.isNotEmpty()) "$count av ${titles.size} finns i biblioteket" else null,
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelLarge,
                color = palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Den visade filmen: placeringen, år, genrer och betyg, titeln stort, handlingen och om den finns. */
@Composable
private fun TopTenHero(titles: List<WeeklyTitle>, owned: List<Movie?>, shown: Int, sizes: TopTenSizes) {
    val tv = tvLayout
    val palette = LocalPalette.current
    AnimatedContent(
        targetState = shown,
        transitionSpec = { (fadeIn(tween(240)) + slideInVertically(tween(260)) { it / 14 }) togetherWith fadeOut(tween(120)) },
        label = "topp-film",
    ) { at ->
        val title = titles.getOrNull(at)
        if (title == null) {
            Spacer(Modifier.height(0.dp))
        } else {
            Column(Modifier.widthIn(max = sizes.heroWidth), verticalArrangement = Arrangement.spacedBy(if (tv) 10.dp else 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(TOP_YELLOW)
                            .padding(horizontal = 9.dp, vertical = 1.dp),
                    ) {
                        Text(
                            "NR ${at + 1}",
                            style = TextStyle(
                                fontFamily = menuDisplayFont,
                                fontSize = if (tv) 19.sp else 16.sp,
                                letterSpacing = 1.sp,
                                textGeometricTransform = TOP_SLANT,
                            ),
                            color = TOP_INK,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        topTenMeta(title),
                        style = TextStyle(
                            fontFamily = menuCondensedFont,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = if (tv) 15.sp else 13.sp,
                            letterSpacing = 1.4.sp,
                        ),
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    title.title.uppercase(TOP_LOCALE),
                    style = TextStyle(
                        fontFamily = menuDisplayFont,
                        fontSize = sizes.title,
                        lineHeight = sizes.title * 1.08f,
                        letterSpacing = 0.5.sp,
                    ),
                    color = palette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                title.overview?.let { text ->
                    Text(
                        text,
                        style = if (tv) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TopTenAvailability(owned.getOrNull(at) != null, tv)
            }
        }
    }
}

/** "2025  ·  ACTION, THRILLER  ·  ★ 7,8" */
private fun topTenMeta(title: WeeklyTitle): String = listOfNotNull(
    title.year?.toString(),
    title.genres.takeIf { it.isNotBlank() },
    title.rating?.let { "★ " + String.format(TOP_LOCALE, "%.1f", it) },
).joinToString("  ·  ").uppercase(TOP_LOCALE)

/** Finns filmen i biblioteket: gul, med vad man gör för att öppna den. Annars en tunn ram. */
@Composable
private fun TopTenAvailability(available: Boolean, tv: Boolean) {
    val palette = LocalPalette.current
    val label = TextStyle(fontFamily = menuCondensedFont, fontWeight = FontWeight.Bold, fontSize = if (tv) 13.sp else 12.sp, letterSpacing = 1.6.sp)
    val pill = RoundedCornerShape(50)
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (available) {
            Row(
                Modifier.clip(pill).background(TOP_YELLOW).padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Glyphs.Play, contentDescription = null, tint = TOP_INK, modifier = Modifier.size(if (tv) 18.dp else 16.dp))
                Spacer(Modifier.width(6.dp))
                Text("FINNS I BIBLIOTEKET", style = label, color = TOP_INK, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (tv) "Tryck OK för att öppna" else "Klicka för att öppna",
                style = MaterialTheme.typography.labelLarge,
                color = palette.textTertiary,
                maxLines = 1,
            )
        } else {
            Box(Modifier.clip(pill).border(1.5.dp, palette.textTertiary, pill).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text("FINNS INTE I BIBLIOTEKET", style = label, color = palette.textTertiary, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * En film i listan: placeringen som en stor siffra och omslaget delvis över
 * den. Markerad växer omslaget och får en gul ram, och siffran blir gul med
 * en mörk skugga snett bakom. Det som inte finns i biblioteket är mörkare,
 * och skakar till om man väljer det.
 */
@Composable
private fun TopTenCard(
    rank: Int,
    title: WeeklyTitle,
    movie: Movie?,
    sizes: TopTenSizes,
    entering: Boolean,
    focus: Modifier,
    onActive: () -> Unit,
    onClick: () -> Unit,
) {
    val tv = tvLayout
    val palette = LocalPalette.current
    val local = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val active = focused || (!tv && hovered)
    LaunchedEffect(active) { if (active) onActive() }
    val lit = animateFloatAsState(if (active) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 420f), label = "topp-kort")
    val shake = remember { Animatable(0f) }
    // Omslagen kommer upp ett i taget när sidan öppnas.
    val rise = remember { Animatable(if (entering) 0f else 1f) }
    if (entering) {
        LaunchedEffect(Unit) {
            delay(80L + rank * 55L)
            rise.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 260f))
        }
    }
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val number = remember(rank, sizes, local) {
        measurer.measure(
            rank.toString(),
            TextStyle(
                fontFamily = menuDisplayFont,
                fontSize = with(local) { (sizes.numberHeight / ANTON_CAP).toSp() },
                letterSpacing = with(local) { (-4).dp.toSp() },
            ),
        )
    }
    val numberWidth = with(local) { number.size.width.toDp() }
    val overlap = sizes.posterWidth * 0.16f
    val corner = if (tv) 10.dp else 8.dp
    Box(
        focus
            .graphicsLayer {
                alpha = rise.value.coerceIn(0f, 1f)
                translationY = (1f - rise.value) * 46.dp.toPx()
                translationX = shake.value.dp.toPx()
            }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) {
                onClick()
                if (movie == null) {
                    scope.launch {
                        for (x in listOf(9f, -7f, 5f, -3f, 0f)) shake.animateTo(x, tween(45))
                    }
                }
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .width(numberWidth + sizes.posterWidth - overlap)
            .height(sizes.posterHeight + 16.dp),
    ) {
        // Siffran, med foten där omslaget slutar.
        Canvas(Modifier.align(Alignment.BottomStart).padding(bottom = 8.dp).width(numberWidth).height(sizes.posterHeight)) {
            val y = size.height - number.firstBaseline
            val l = lit.value
            if (l > 0.01f) {
                val d = 5.dp.toPx() * l
                drawText(number, color = TOP_SIDE, topLeft = Offset(d, y + d), alpha = l)
                drawText(number, brush = Brush.verticalGradient(listOf(TOP_YELLOW, TOP_YELLOW_DEEP)), topLeft = Offset(0f, y), alpha = l)
            }
            if (l < 0.99f) drawText(number, color = Color.Black, topLeft = Offset(0f, y), alpha = 0.55f * (1f - l))
            drawText(
                number,
                color = lerp(Color.White.copy(alpha = 0.5f), TOP_INK, l),
                topLeft = Offset(0f, y),
                drawStyle = Stroke(width = (if (tv) 2.5.dp else 2.dp).toPx(), join = StrokeJoin.Round),
            )
        }
        // Omslaget, över siffrans högra del.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 8.dp)
                .size(sizes.posterWidth, sizes.posterHeight)
                .graphicsLayer {
                    val s = 1f + (if (tv) 0.07f else 0.04f) * lit.value
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .drawWithContent {
                    drawContent()
                    val l = lit.value
                    if (l > 0.01f) {
                        drawRoundRect(
                            TOP_YELLOW,
                            alpha = l,
                            cornerRadius = CornerRadius(corner.toPx()),
                            style = Stroke((if (tv) 3.dp else 2.dp).toPx()),
                        )
                    }
                }
                .clip(RoundedCornerShape(corner))
                .background(palette.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            RemoteImage(
                url = title.poster,
                modifier = Modifier.fillMaxSize(),
                maxSide = 360,
                contentScale = ContentScale.Crop,
                placeholder = {
                    Text(initials(title.title), style = MaterialTheme.typography.titleMedium, color = palette.textTertiary)
                },
            )
            if (movie == null) {
                Spacer(Modifier.fillMaxSize().graphicsLayer { alpha = 0.5f * (1f - lit.value) }.background(Color.Black))
            } else {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(if (tv) 30.dp else 26.dp)
                        .clip(CircleShape)
                        .background(TOP_YELLOW),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Glyphs.Play, contentDescription = "Finns i biblioteket", tint = TOP_INK, modifier = Modifier.size(if (tv) 18.dp else 16.dp))
                }
            }
        }
    }
}

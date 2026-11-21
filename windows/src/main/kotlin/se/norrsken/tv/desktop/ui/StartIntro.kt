package se.norrsken.tv.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.data.IntroReelItem
import se.norrsken.tv.desktop.data.IntroReelStore
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * Starten, som inledningen på en film. En vägg av omslag från de häftigaste
 * filmerna man har — det som trendar, Marvel och de andra filmserierna, nya
 * filmer med högt betyg — glider förbi snett i djupet, rad för rad åt olika
 * håll, i ljuset från en projektor med stoft som svävar. Över väggen driver
 * filmernas titlar förbi i jättestora, genomskinliga bokstäver, ibland bara
 * konturen. När startsidan är klar rusar kameran in i väggen, symbolen
 * blixtrar till med ett liggande linsljus, och allt tonar bort mot
 * startsidan bakom.
 *
 * Omslagen är de appen redan har på disken, valda förra gången
 * (startfilm.json). Finns inga än — första gången, innan biblioteket hunnit
 * laddas — sveper strålkastare över en svart duk och symbolen slår in, tills
 * väggen kan ta över.
 *
 * Allt som rör sig läses i ritningen eller i lagren: inget mäts om och inget
 * sätts ihop på nytt medan det spelas, så att det går jämnt på Shield också
 * medan appen laddar som mest. Ingen oskärpa.
 */
@Composable
internal fun StartIntro(state: AppState, ready: Boolean, onFinished: () -> Unit) {
    val palette = LocalPalette.current
    val accent = menuFill(palette)
    // Millisekunder sedan starten. Läses bara i ritningen och i lagren.
    val clock = remember { mutableLongStateOf(0L) }
    val exit = remember { Animatable(0f) }
    // Var symbolen står på skärmen: linsljuset går genom den.
    val logoY = remember { mutableFloatStateOf(-1f) }
    val finished = rememberUpdatedState(onFinished)

    // Filmväggen: den sparade från förra gången direkt, annars den som räknas fram nu.
    val saved by produceState(emptyList<IntroReelItem>()) {
        value = withContext(Dispatchers.IO) { IntroReelStore.load() }
    }
    var reel by remember { mutableStateOf<List<IntroReelItem>?>(null) }
    val reelAt = remember { mutableLongStateOf(-1L) }
    val candidate = saved.ifEmpty { state.introReel }
    LaunchedEffect(candidate) {
        // Väggen tar över en gång, och bara medan starten ännu har tid att visa den.
        if (reel == null && candidate.size >= REEL_MIN && exit.value == 0f && clock.longValue < REEL_LATEST_MS) {
            reelAt.longValue = clock.longValue
            reel = candidate
        }
    }

    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { clock.longValue = it - start }
        }
    }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        // Spelas klart först: väggen en stund, annars symbolen.
        while (clock.longValue < minimumShow(reelAt.longValue)) delay(40)
        exit.animateTo(1f, tween(EXIT_MS, easing = FastOutSlowInEasing))
        finished.value()
    }
    LaunchedEffect(Unit) {
        delay(MAX_SHOW_MS)
        exit.animateTo(1f, tween(EXIT_MS))
        finished.value()
    }

    val wall = reel
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Allt tonar bort mot startsidan bakom, sent, så att symbolen hinner blixtra.
                val e = exit.value
                alpha = (1f - e * e * 1.1f).coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .background(INTRO_BLACK),
        contentAlignment = Alignment.Center,
    ) {
        // Strålkastare som sveper över den svarta duken, stoft och skenet bakom symbolen.
        // Tonar bort när väggen tänds.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val t = clock.longValue
                    val hide = progress(t, reelAt.longValue, WALL_FADE_MS)
                    drawSearchlights(t)
                    if (hide < 1f) {
                        drawDust(t, 1f - hide)
                        drawGlow(t, logoY.floatValue, accent, 1f - hide)
                    }
                },
        )
        if (wall != null) {
            PosterWall(wall, clock, reelAt.longValue, exit)
            // Mörkare och en svag färgton över väggen, och projektorns ljuskägla med stoft.
            Spacer(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val t = clock.longValue
                        val shown = progress(t, reelAt.longValue, WALL_FADE_MS)
                        drawWallShade()
                        drawProjector(t, shown)
                        drawDust(t, shown)
                    },
            )
            TitleSweeps(
                titles = remember(wall) { wall.map { it.title.uppercase() } },
                clock = clock,
                startAt = reelAt.longValue + TITLES_AFTER_MS,
                exit = exit,
            )
        }
        // Symbolen. Utan vägg slår den in mot kameran i början; med väggen blixtrar
        // den till när kameran rusar in i slutet.
        Icon(
            Glyphs.AppSymbol,
            contentDescription = null,
            tint = accent,
            modifier = Modifier
                .size(148.dp)
                .onGloballyPositioned { logoY.floatValue = it.boundsInRoot().center.y }
                .graphicsLayer {
                    val t = clock.longValue
                    val e = exit.value
                    val p = easeOutExpo(progress(t, LOGO_AT_MS, LOGO_MS))
                    val slammed = (p * 1.6f).coerceIn(0f, 1f)
                    if (wall == null) {
                        val zoom = (1.9f - 0.9f * p) * (1f + 0.6f * e * e)
                        scaleX = zoom
                        scaleY = zoom
                        alpha = slammed
                    } else {
                        // Stod symbolen redan där när väggen kom tonar den bort; i slutet blixtrar den till.
                        val fading = slammed * (1f - progress(t, reelAt.longValue, WALL_FADE_MS / 2))
                        val flash = (1f - abs(2f * e - 1f)).pow(0.7f)
                        val zoom = if (flash > fading) 0.7f + 0.5f * e else 1.9f - 0.9f * p
                        scaleX = zoom
                        scaleY = zoom
                        alpha = maxOf(fading, flash)
                    }
                    // Eget lager, så att ljussvepet bara lägger sig på symbolen.
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    if (wall == null) drawSweep(clock.longValue)
                },
        )
        // Linsljuset: när symbolen slår in, eller när väggen tänds, och när kameran rusar in.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val at = if (wall == null) FLARE_AT_MS else reelAt.longValue + WALL_FLARE_DELAY_MS
                    drawFlare(clock.longValue, at, exit.value, logoY.floatValue)
                },
        )
        // Biofälten och mörka hörn, och laddningen i det nedre fältet om starten drar ut på tiden.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawVignette()
                    drawLetterbox(exit.value)
                    drawLoader(clock.longValue, accent, exit.value)
                },
        )
    }
}

/** Så länge starten visas minst: väggen en stund efter att den kommit fram, annars symbolen. */
private fun minimumShow(reelAt: Long): Long =
    if (reelAt < 0) MIN_SHOW_MS else maxOf(MIN_SHOW_WALL_MS, reelAt + WALL_MIN_MS)

// --- Filmväggen ----------------------------------------------------------------------

/**
 * Omslagen i fyra rader, snett i djupet, varje rad i sin takt och riktning.
 * Varje rad står två gånger efter varandra, så att den kan glida i det
 * oändliga utan glapp. Bara lagren flyttas; omslagen ritas en gång.
 */
@Composable
private fun PosterWall(reel: List<IntroReelItem>, clock: MutableLongState, shownAt: Long, exit: Animatable<Float, AnimationVector1D>) {
    val rows = remember(reel) { List(WALL_ROWS) { row -> List(ROW_TILES) { j -> reel[(j * WALL_ROWS + row) % reel.size] } } }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val t = clock.longValue
                val e = exit.value
                val appear = easeOutCubic(progress(t, shownAt, WALL_FADE_MS))
                alpha = appear * (1f - e * 0.6f)
                // Kameran glider sakta närmare; i slutet rusar den in i väggen.
                val dolly = 1f + 0.10f * progress(t, shownAt, WALL_DOLLY_MS)
                val zoom = WALL_ZOOM * dolly * (1.10f - 0.10f * appear) * (1f + 1.3f * e * e)
                scaleX = zoom
                scaleY = zoom
                rotationX = WALL_TILT_X * (1f - 0.7f * e)
                rotationZ = WALL_TILT_Z
                cameraDistance = 22f * density
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.wrapContentSize(unbounded = true),
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            rows.forEachIndexed { index, items -> PosterRow(items, ROW_SPEEDS[index % ROW_SPEEDS.size], clock) }
        }
    }
}

/** En rad omslag som glider i det oändliga: [speed] dp i sekunden, minus åt vänster. */
@Composable
private fun PosterRow(items: List<IntroReelItem>, speed: Float, clock: MutableLongState) {
    val loop = (TILE_WIDTH + TILE_GAP) * items.size
    Row(
        Modifier
            .requiredWidth(loop * 2)
            .graphicsLayer {
                val length = loop.toPx()
                val moved = (clock.longValue / 1000f * abs(speed) * density) % length
                // Innehållet upprepas efter en loop: hoppet tillbaka syns inte.
                translationX = if (speed > 0f) moved - length / 2f else length / 2f - moved
            },
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
    ) {
        repeat(2) {
            items.forEach { item -> PosterTile(item) }
        }
    }
}

@Composable
private fun PosterTile(item: IntroReelItem) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(TILE_WIDTH, TILE_HEIGHT)
            .clip(shape)
            .background(TILE_BACK)
            .border(1.dp, Color.White.copy(alpha = 0.07f), shape),
    ) {
        RemoteImage(url = item.poster, modifier = Modifier.fillMaxSize(), maxSide = 342, contentScale = ContentScale.Crop)
    }
}

/**
 * Filmernas titlar som driver förbi över väggen i jättestora bokstäver, en i
 * taget med lite överlapp, omväxlande åt höger och vänster och på olika
 * höjd: genomskinliga, och var tredje bara konturen. Mäts när de behövs och
 * ritas sedan bara om.
 */
@Composable
private fun TitleSweeps(titles: List<String>, clock: MutableLongState, startAt: Long, exit: Animatable<Float, AnimationVector1D>) {
    val measurer = rememberTextMeasurer()
    val style = remember { TextStyle(fontWeight = FontWeight.Black, fontSize = TITLE_SIZE, letterSpacing = 3.sp) }
    val layouts = remember(titles) { HashMap<Int, TextLayoutResult>() }
    Spacer(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val t = clock.longValue - startAt
                if (t < 0 || titles.isEmpty()) return@drawBehind
                val fade = 1f - exit.value
                if (fade <= 0f) return@drawBehind
                val newest = (t / TITLE_EVERY_MS).toInt()
                for (k in maxOf(0, newest - TITLE_OVERLAP)..newest) {
                    val local = t - k * TITLE_EVERY_MS
                    if (local < 0 || local > TITLE_MS) continue
                    val p = local / TITLE_MS.toFloat()
                    val index = k % titles.size
                    val layout = layouts.getOrPut(index) {
                        measurer.measure(AnnotatedString(titles[index]), style = style, maxLines = 1, softWrap = false)
                    }
                    val width = layout.size.width.toFloat()
                    // Mitten driver en halv skärm, i jämn fart med mjuk start och stopp.
                    val forward = k % 2 == 0
                    val from = if (forward) 0.74f else 0.26f
                    val to = if (forward) 0.26f else 0.74f
                    val center = size.width * (from + (to - from) * easeInOutSine(p))
                    val x = center - width / 2f
                    val y = size.height * TITLE_LANES[k % TITLE_LANES.size] - layout.size.height / 2f
                    val alpha = sin(PI.toFloat() * p).pow(1.3f) * fade
                    if (k % 3 == 1) {
                        drawText(layout, color = Color.White, topLeft = Offset(x, y), alpha = alpha * 0.8f, drawStyle = Stroke(width = 2.dp.toPx()))
                    } else {
                        drawText(layout, color = Color.White, topLeft = Offset(x, y), alpha = alpha * 0.46f)
                    }
                }
            },
    )
}

// --- Det som ritas -------------------------------------------------------------------

/** Väggen dämpad, med en svag kall och varm ton, som i en filmtrailer. */
private fun DrawScope.drawWallShade() {
    drawRect(Color.Black.copy(alpha = 0.34f))
    drawRect(
        Brush.linearGradient(
            listOf(GRADE_COLD.copy(alpha = 0.18f), Color.Transparent, GRADE_WARM.copy(alpha = 0.13f)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
    )
}

/** Projektorns ljuskägla uppifrån, som fladdrar svagt. */
private fun DrawScope.drawProjector(t: Long, shown: Float) {
    if (shown <= 0f) return
    val flicker = 0.88f + 0.08f * sin(t * 0.047f) + 0.04f * sin(t * 0.13f)
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.47f, -h * 0.05f)
        lineTo(w * 0.53f, -h * 0.05f)
        lineTo(w * 0.92f, h)
        lineTo(w * 0.08f, h)
        close()
    }
    drawPath(
        path,
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.16f * shown * flicker), Color.White.copy(alpha = 0.03f * shown * flicker), Color.Transparent),
            startY = 0f,
            endY = h,
        ),
    )
}

/** Strålkastare nedanför bilden som sveper fram och tillbaka, var och en i sin takt. */
private fun DrawScope.drawSearchlights(t: Long) {
    val shown = easeOutCubic(progress(t, LIGHTS_AT_MS, LIGHTS_MS))
    if (shown <= 0f) return
    val w = size.width
    val h = size.height
    val length = h * 1.5f
    val half = length * 0.075f
    val seconds = t / 1000f
    for (beam in 0 until BEAMS) {
        val pivot = Offset(w * (0.14f + 0.24f * beam), h + 30.dp.toPx())
        val period = 6.2f + beam * 1.3f
        val sway = sin((seconds / period + beam * 0.27f) * 2f * PI.toFloat())
        val lean = (beam - (BEAMS - 1) / 2f) * 9f
        val path = Path().apply {
            moveTo(pivot.x, pivot.y)
            lineTo(pivot.x - half, pivot.y - length)
            lineTo(pivot.x + half, pivot.y - length)
            close()
        }
        rotate(lean + sway * 15f, pivot) {
            drawPath(
                path,
                Brush.verticalGradient(
                    listOf(Color.Transparent, BEAM.copy(alpha = 0.07f * shown), BEAM.copy(alpha = 0.22f * shown)),
                    startY = pivot.y - length,
                    endY = pivot.y,
                ),
            )
        }
    }
}

/** Stoft som svävar uppåt i ljuset och glimtar. [strength]: 0–1, hur mycket det syns. */
private fun DrawScope.drawDust(t: Long, strength: Float) {
    val shown = progress(t, DUST_AT_MS, 1200) * strength
    if (shown <= 0f) return
    val w = size.width
    val h = size.height
    val seconds = t / 1000f
    val unit = 1.dp.toPx()
    for (i in 0 until DUST) {
        val speed = (6f + 18f * hash(i, 1)) * unit
        val y = h - ((seconds * speed + hash(i, 2) * h) % (h + 40f * unit)) + 20f * unit
        val x = w * hash(i, 3) + sin(seconds * (0.4f + hash(i, 4)) + i) * 14f * unit
        val twinkle = 0.55f + 0.45f * sin(seconds * (1.2f + 2f * hash(i, 5)) + i * 1.7f)
        val alpha = (0.06f + 0.30f * hash(i, 6)) * twinkle * shown
        drawCircle(DUST_COLOR.copy(alpha = alpha), radius = (0.7f + 1.6f * hash(i, 7)) * unit, center = Offset(x, y))
    }
}

/** Skenet bakom symbolen: tänds med den och andas sedan långsamt. [strength]: 0–1. */
private fun DrawScope.drawGlow(t: Long, logoY: Float, color: Color, strength: Float) {
    val shown = progress(t, LOGO_AT_MS, LOGO_MS) * strength
    if (shown <= 0f) return
    val breath = 0.8f + 0.2f * sin(t / 2600f * 2f * PI.toFloat())
    val center = Offset(size.width / 2, if (logoY > 0f) logoY else size.height / 2)
    drawRect(
        Brush.radialGradient(
            listOf(color.copy(alpha = 0.30f * shown * breath), Brand.violet.copy(alpha = 0.07f * shown * breath), Color.Transparent),
            center = center,
            radius = size.minDimension * 0.55f,
        ),
    )
}

/** Ljussvepet över symbolen, en gång, strax efter att den slagit in. */
private fun DrawScope.drawSweep(t: Long) {
    val local = t - SWEEP_AT_MS
    if (local < 0 || local > SWEEP_MS) return
    val f = local / SWEEP_MS.toFloat()
    val band = size.width * 0.55f
    val x = -band + (size.width + band * 2) * easeInOutCubic(f)
    drawRect(
        Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.9f), Color.Transparent),
            start = Offset(x - band / 2, 0f),
            end = Offset(x + band / 2, size.height),
        ),
        blendMode = BlendMode.SrcAtop,
    )
}

/**
 * Det liggande linsljuset genom symbolen: blixtrar till vid [at], och en
 * gång till när kameran rusar in vid slutet. Ett skarpt vitt streck med ett
 * blått sken omkring, som tonar ut mot kanterna.
 */
private fun DrawScope.drawFlare(t: Long, at: Long, exit: Float, logoY: Float) {
    val x = progress(t, at, FLARE_MS)
    val reveal = when {
        x <= 0f || x >= 1f -> 0f
        x < 0.12f -> x / 0.12f
        else -> ((1f - x) / 0.88f).pow(1.6f)
    }
    val strength = maxOf(reveal, sin(exit * PI.toFloat()) * 0.85f)
    if (strength <= 0.01f) return
    val y = if (logoY > 0f) logoY else size.height / 2
    val w = size.width
    val halo = 40.dp.toPx()
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, FLARE_BLUE.copy(alpha = 0.32f * strength), Color.Transparent),
            startY = y - halo,
            endY = y + halo,
        ),
        topLeft = Offset(0f, y - halo),
        size = Size(w, halo * 2),
    )
    val core = 2.dp.toPx()
    drawRect(
        Brush.horizontalGradient(
            0f to Color.Transparent,
            0.25f to FLARE_BLUE.copy(alpha = 0.5f * strength),
            0.5f to Color.White.copy(alpha = strength),
            0.75f to FLARE_BLUE.copy(alpha = 0.5f * strength),
            1f to Color.Transparent,
        ),
        topLeft = Offset(0f, y - core / 2),
        size = Size(w, core),
    )
}

/** Mörkare hörn, som i en biosalong. */
private fun DrawScope.drawVignette() {
    drawRect(
        Brush.radialGradient(
            0f to Color.Transparent,
            0.55f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.65f),
            center = center,
            radius = size.maxDimension * 0.7f,
        ),
    )
}

/** Biofälten upptill och nedtill (bilden i 2,39:1); de öppnar sig när det är slut. */
private fun DrawScope.drawLetterbox(exit: Float) {
    val bar = ((size.height - size.width / 2.39f) / 2f).coerceAtLeast(0f) * (1f - easeInCubic(exit))
    if (bar <= 0.5f) return
    drawRect(INTRO_BLACK, topLeft = Offset.Zero, size = Size(size.width, bar))
    drawRect(INTRO_BLACK, topLeft = Offset(0f, size.height - bar), size = Size(size.width, bar))
}

/** Laddningen, om starten drar ut på tiden: ett ljus som löper längs en tunn linje i det nedre fältet. */
private fun DrawScope.drawLoader(t: Long, color: Color, exit: Float) {
    if (t < LOADER_AT_MS) return
    val fade = progress(t, LOADER_AT_MS, 600) * (1f - exit)
    if (fade <= 0f) return
    val width = 200.dp.toPx()
    val height = 2.dp.toPx()
    val bar = ((size.height - size.width / 2.39f) / 2f).coerceAtLeast(48.dp.toPx())
    val left = (size.width - width) / 2
    val top = size.height - bar / 2 - height / 2
    drawRoundRect(
        Color.White.copy(alpha = 0.10f * fade),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2),
    )
    val phase = ((t - LOADER_AT_MS) % LOADER_PERIOD_MS) / LOADER_PERIOD_MS.toFloat()
    val length = width * 0.36f
    val x = left - length + (width + length) * easeInOutCubic(phase)
    clipRect(left, top, left + width, top + height) {
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, color.copy(alpha = fade), Color.White.copy(alpha = fade)),
                startX = x,
                endX = x + length,
            ),
            topLeft = Offset(x, top),
            size = Size(length, height),
        )
    }
}

// --- Tid och kurvor ------------------------------------------------------------------

/** 0–1 för hur långt något som börjar vid [at] och tar [duration] ms kommit vid [t]. */
private fun progress(t: Long, at: Long, duration: Int): Float =
    if (at < 0) 0f else ((t - at) / duration.toFloat()).coerceIn(0f, 1f)

private fun easeOutCubic(x: Float): Float = 1f - (1f - x).pow(3)

private fun easeInCubic(x: Float): Float = x * x * x

private fun easeInOutCubic(x: Float): Float = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f

private fun easeInOutSine(x: Float): Float = -(kotlin.math.cos(PI.toFloat() * x) - 1f) / 2f

private fun easeOutExpo(x: Float): Float = if (x >= 1f) 1f else 1f - 2f.pow(-10f * x)

/** Ett fast slumptal 0–1 för stoftkorn [i], egenskap [k]: samma varje bild, utan att något skapas. */
private fun hash(i: Int, k: Int): Float {
    var x = i * 374_761_393 + k * 668_265_263
    x = (x xor (x ushr 13)) * 1_274_126_177
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16_777_215f
}

private val INTRO_BLACK = Color(0xFF020306)
private val BEAM = Color(0xFFCFE4FF)
private val DUST_COLOR = Color(0xFFE6F0FF)
private val FLARE_BLUE = Color(0xFF5EA8FF)
private val GRADE_COLD = Color(0xFF2EA8FF)
private val GRADE_WARM = Color(0xFFFF8A3D)
private val TILE_BACK = Color(0xFF12151B)

/** Filmväggen: fyra rader om åtta omslag, snett i djupet. */
private const val WALL_ROWS = 4
private const val ROW_TILES = 8
private val TILE_WIDTH = 180.dp
private val TILE_HEIGHT = 270.dp
private val TILE_GAP = 20.dp
/** Radernas fart i dp per sekund; minus åt vänster. */
private val ROW_SPEEDS = floatArrayOf(-46f, 36f, -58f, 42f)
private const val WALL_ZOOM = 1.32f
private const val WALL_TILT_X = 24f
private const val WALL_TILT_Z = -8f
private const val WALL_FADE_MS = 900
private const val WALL_DOLLY_MS = 8_000
private const val WALL_FLARE_DELAY_MS = 120L
/** Minst så många filmer för en vägg; senare än så tar den inte över. */
private const val REEL_MIN = 8
private const val REEL_LATEST_MS = 3_500L

/** Titlarna: en ny var 0,8 sekund, var och en syns i 2,6 sekunder. */
private val TITLE_SIZE = 118.sp
private const val TITLE_EVERY_MS = 820L
private const val TITLE_MS = 2_600
private const val TITLE_OVERLAP = 4
private const val TITLES_AFTER_MS = 450L
private val TITLE_LANES = floatArrayOf(0.34f, 0.62f, 0.47f, 0.73f, 0.26f)

private const val BEAMS = 4
private const val DUST = 64
private const val DUST_AT_MS = 150L
private const val LIGHTS_AT_MS = 180L
private const val LIGHTS_MS = 900
private const val LOGO_AT_MS = 520L
private const val LOGO_MS = 1000
private const val FLARE_AT_MS = 560L
private const val FLARE_MS = 1300
private const val SWEEP_AT_MS = 1550L
private const val SWEEP_MS = 700
private const val LOADER_AT_MS = 4_200L
private const val LOADER_PERIOD_MS = 1300L

/** Utan vägg: tills symbolen och ljussvepet hunnit fram. Med vägg: titlarna en stund. */
private const val MIN_SHOW_MS = 2_350L
private const val MIN_SHOW_WALL_MS = 3_000L
private const val WALL_MIN_MS = 2_600L
private const val EXIT_MS = 680
/** Längre än så väntar den inte, även om inget blivit klart. */
private const val MAX_SHOW_MS = 20_000L

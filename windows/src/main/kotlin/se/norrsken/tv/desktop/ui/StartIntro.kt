package se.norrsken.tv.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.data.IntroReelItem
import se.norrsken.tv.desktop.data.IntroReelStore
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Starten, som inledningen på en film. En stor vägg av omslag från de
 * häftigaste filmerna man har — det som trendar, Marvel och de andra
 * filmserierna, nya filmer med högt betyg — står snett i djupet, i riktig
 * 3D. Omslagen vänds fram i en våg från mitten medan kameran sakta glider
 * bakåt; raderna driver åt olika håll, ett ljus vandrar över väggen och
 * omslagen glänser till när de vänds mot ljuset. Då och då vänds ett omslag
 * och en annan film kommer fram. Samma film syns aldrig två gånger samtidigt.
 *
 * När startsidan är klar lyfter filmen mitt i bild ut ur väggen och kommer
 * emot en, biofälten öppnar sig och allt tonar över i startsidan. Ingen
 * logga, ingen text, inget ljud. En knapptryckning eller ett klick hoppar över.
 *
 * Allt ritas i en enda ritning med en egen perspektivprojektion: inget mäts
 * om och inget sätts ihop på nytt medan det spelas, så att det går jämnt på
 * Shield också medan appen laddar som mest. Ingen oskärpa.
 */
@Composable
internal fun StartIntro(state: AppState, ready: Boolean, skip: Boolean, onFinished: () -> Unit) {
    // Millisekunder sedan starten. Läses bara i ritningen.
    val clock = remember { mutableLongStateOf(0L) }
    val exit = remember { Animatable(0f) }
    val finished = rememberUpdatedState(onFinished)
    val scene = remember { IntroScene() }
    var clicked by remember { mutableStateOf(false) }

    // Omslagen: de sparade från förra gången direkt, annars de som räknas fram nu.
    val saved by produceState(emptyList<IntroReelItem>()) {
        value = withContext(Dispatchers.IO) { IntroReelStore.load() }
    }
    var reel by remember { mutableStateOf<List<IntroReelItem>?>(null) }
    val candidate = saved.ifEmpty { state.introReel }
    LaunchedEffect(candidate) {
        // Väggen tar över en gång, och bara medan starten ännu har tid att visa den.
        if (reel == null && candidate.isNotEmpty() && exit.value == 0f && clock.longValue < REEL_LATEST_MS) {
            // Varje film en gång, också om listan är från en äldre version av appen.
            val unique = candidate
                .distinctBy { it.poster }
                .distinctBy { item -> item.title.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { item.poster } }
            scene.setReel(unique.size, clock.longValue)
            reel = unique
        }
    }
    LaunchedEffect(reel) {
        val items = reel ?: return@LaunchedEffect
        // De bästa filmerna först, några åt gången: de hamnar mitt i bild.
        val gate = Semaphore(LOAD_PARALLEL)
        coroutineScope {
            items.forEachIndexed { index, item ->
                launch {
                    val image = gate.withPermit { Images.load(item.poster, POSTER_SIDE) }
                    scene.posterLoaded(index, image)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { clock.longValue = it - start }
        }
    }
    val skipping = skip || clicked
    LaunchedEffect(ready, skipping) {
        if (!ready) return@LaunchedEffect
        // Spelas klart först, om man inte hoppar över.
        if (!skipping) {
            while (clock.longValue < scene.earliestExit(clock.longValue)) delay(40)
        }
        val duration = when {
            !scene.wallShown -> EXIT_PLAIN_MS
            skipping -> EXIT_SKIP_MS
            else -> EXIT_MS
        }
        exit.animateTo(1f, tween(duration, easing = LinearEasing))
        finished.value()
    }
    LaunchedEffect(Unit) {
        delay(MAX_SHOW_MS)
        exit.animateTo(1f, tween(EXIT_SKIP_MS, easing = LinearEasing))
        finished.value()
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Allt tonar bort mot startsidan bakom, när filmen kommit fram.
                alpha = 1f - smoothstep(scene.fadeFrom, 1f, exit.value)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .background(INTRO_BLACK)
            // Ett klick hoppar över, och går inte vidare till sidan bakom.
            .pointerInput(Unit) { detectTapGestures { clicked = true } },
    ) {
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind { scene.draw(this, clock.longValue, exit.value) },
        )
    }
}

/** En plats i väggen: rad, kolumn och omslaget som sitter där. */
private class Tile {
    var row = 0
    var col = 0
    /** Omslaget, eller -1 medan platsen väntar på ett. */
    var poster = -1
    /** När platsen kom in i bild. */
    var createdAt = 0L
    /** När omslaget börjar vändas fram. */
    var igniteAt = 0L
    /** Åt vilket håll det vänds fram: -1 till vänster om mitten, 1 till höger. */
    var side = 1f
    /** En vändning till ett annat omslag: när den började (-1: ingen), och till vilket. */
    var flipAt = -1L
    var flipTo = -1
    var flipDir = 1f
    /** Ett ljus som glider över omslaget: när det började (-1: inget). */
    var glintAt = -1L
    /** Filmen som lyfter ut ur väggen i slutet. */
    var hero = false
    /** Hur nära kameran den är just nu, för ritordningen. Också avståndet till mitten när den skapas. */
    var lift = 0f

    fun reset(row: Int, col: Int) {
        this.row = row
        this.col = col
        poster = -1
        igniteAt = 0L
        side = 1f
        flipAt = -1L
        flipTo = -1
        flipDir = 1f
        glintAt = -1L
        hero = false
        lift = 0f
    }
}

/**
 * Väggen. Allt som behövs mellan bilderna ligger här, i vanliga fält: det
 * ändras bara i ritningen och när ett omslag laddats, båda på UI-tråden.
 *
 * Väggens punkter (x, y) ligger i ett plan. Kameran vrider planet (lutning,
 * sidvridning och rotation), och en perspektivprojektion lägger det på
 * skärmen; allt blir en 3×3-matris per omslag som ritningen får. Samma
 * matris baklänges säger vilka platser som syns, så att bara de får omslag.
 */
private class IntroScene {
    // --- Omslagen --------------------------------------------------------------------
    private var count = 0
    private var images = arrayOfNulls<ImageBitmap>(0)
    private var users = IntArray(0)
    /** När omslaget senast lämnade bilden; de som aldrig visats i filmernas ordning. */
    private var freeSince = LongArray(0)
    private var srcX = IntArray(0)
    private var srcY = IntArray(0)
    private var srcW = IntArray(0)
    private var srcH = IntArray(0)
    private var loadedCount = 0
    private var failedCount = 0

    // --- Tiden -----------------------------------------------------------------------
    private var reelAt = -1L
    private var wallAt = -1L
    private var exitAt = -1L
    private var lastTime = 0L
    private var nextFlipAt = 0L
    private var nextGlintAt = 0L
    private var picks = 0
    private var heroChosen = false

    /** Väggen har tänts. */
    val wallShown: Boolean get() = wallAt >= 0

    /** När allt börjar tona bort mot startsidan, i slutets förlopp 0–1. */
    val fadeFrom: Float get() = if (wallAt >= 0) FADE_FROM else 0f

    // --- Måtten, i bildpunkter -------------------------------------------------------
    private var width = 0f
    private var height = 0f
    private var unit = 0f
    private var depth = 1f
    private var tileW = 1
    private var tileH = 1
    private var pitchX = 1f
    private var pitchY = 1f

    // --- Kameran i den här bilden ----------------------------------------------------
    private var zoom = 1f
    private val rot = FloatArray(9)
    private val wall = FloatArray(9)
    private val wallInverse = FloatArray(9)
    private val h = FloatArray(9)
    private var bar = 0f
    private var projX = 0f
    private var projY = 0f
    private var lightX = 0f
    private var lightY = 0f
    private var lightScreenX = 0f
    private var lightScreenY = 0f

    // --- Platserna -------------------------------------------------------------------
    private val tiles = ArrayList<Tile>()
    private val spare = ArrayList<Tile>()
    private val raised = ArrayList<Tile>()
    private val fresh = ArrayList<Tile>()
    private var rowFirst = 0
    private var rowCount = 0
    private val keepFrom = IntArray(MAX_ROWS)
    private val keepTo = IntArray(MAX_ROWS)
    private val enterFrom = IntArray(MAX_ROWS)
    private val enterTo = IntArray(MAX_ROWS)
    private val quadX = FloatArray(4)
    private val quadY = FloatArray(4)
    private val enterX = FloatArray(4)
    private val enterY = FloatArray(4)
    private var bandLow = 0f
    private var bandHigh = 0f
    private var viewLeft = 0f
    private var viewTop = 0f
    private var viewRight = 0f
    private var viewBottom = 0f

    // --- Filmen som lyfter ut --------------------------------------------------------
    private var heroPoster = -1
    private val heroCenter = FloatArray(3)
    private val heroAxisX = FloatArray(3)
    private val heroAxisY = FloatArray(3)
    private var heroScale = 1f

    // --- Det som bara beror på storleken --------------------------------------------
    private var gradeBrush: Brush? = null
    private var vignetteBrush: Brush? = null

    fun setReel(size: Int, now: Long) {
        count = size
        images = arrayOfNulls(size)
        users = IntArray(size)
        freeSince = LongArray(size) { Long.MIN_VALUE + it }
        srcX = IntArray(size)
        srcY = IntArray(size)
        srcW = IntArray(size)
        srcH = IntArray(size)
        reelAt = now
    }

    fun posterLoaded(index: Int, image: ImageBitmap?) {
        if (index !in 0 until count || images[index] != null) return
        if (image == null || image.width <= 0 || image.height <= 0) {
            failedCount++
            return
        }
        // Mitten av omslaget i 2:3, som platserna.
        val w = image.width
        val hh = image.height
        if (w * 3 > hh * 2) {
            val cw = (hh * 2 / 3).coerceAtLeast(1)
            srcX[index] = (w - cw) / 2
            srcY[index] = 0
            srcW[index] = cw
            srcH[index] = hh
        } else {
            val ch = (w * 3 / 2).coerceAtLeast(1)
            srcX[index] = 0
            srcY[index] = (hh - ch) / 2
            srcW[index] = w
            srcH[index] = ch
        }
        images[index] = image
        loadedCount++
    }

    /** Tidigast när starten får gå över i startsidan, om man inte hoppar över. */
    fun earliestExit(now: Long): Long = when {
        wallAt >= 0 -> wallAt + WALL_MIN_MS
        reelAt < 0 -> AMBIENT_MIN_MS
        // Det finns omslag: väggen tänds strax.
        loadedCount > 0 -> Long.MAX_VALUE
        loadedCount + failedCount < count && now < reelAt + WALL_WAIT_MS -> Long.MAX_VALUE
        else -> AMBIENT_MIN_MS
    }

    // --- En bild -----------------------------------------------------------------------

    fun draw(scope: DrawScope, t: Long, e: Float) {
        val size = scope.size
        if (size.width < 2f || size.height < 2f) return
        if (size.width != width || size.height != height) resize(size.width, size.height, t)
        lastTime = t
        if (e > 0f && exitAt < 0) exitAt = t
        maybeStartWall(t)
        if (wallAt < 0) {
            scope.drawAmbient(t)
            scope.drawFrame(t, e)
            return
        }
        val u = t - wallAt
        val open = smoothstep(BARS_OPEN_FROM, BARS_OPEN_TO, e)
        pose(u, e)
        if (!ranges(u, open)) {
            scope.drawFrame(t, e)
            return
        }
        if (e > 0f && !heroChosen) {
            heroChosen = true
            chooseHero(t, u)
        }
        light(u)
        updateTiles(t, u)
        scheduleLife(t, u)
        scope.drawWall(t, u)
        scope.drawLight()
        scope.drawSheen(u)
        if (e > 0f) scope.drawExit(e)
        scope.drawFrame(t, e)
    }

    private fun resize(w: Float, hh: Float, t: Long) {
        width = w
        height = hh
        unit = min(hh, w * 9f / 16f)
        depth = CAMERA_DISTANCE * w
        gradeBrush = null
        vignetteBrush = null
        if (wallAt >= 0) {
            // Fönstret bytte storlek: väggen läggs om.
            for (i in tiles.indices) {
                val tile = tiles[i]
                release(tile.poster, t)
                release(tile.flipTo, t)
                spare.add(tile)
            }
            tiles.clear()
            fitTiles()
        }
    }

    private fun maybeStartWall(t: Long) {
        if (wallAt >= 0 || reelAt < 0 || exitAt >= 0 || loadedCount == 0 || width <= 0f) return
        val settled = loadedCount + failedCount >= count
        if (loadedCount >= min(count, START_LOADED) || settled || t >= reelAt + WALL_WAIT_MS) {
            wallAt = t
            nextFlipAt = t + LIVE_FROM_MS
            nextGlintAt = t + LIVE_FROM_MS - 700L
            fitTiles()
        }
    }

    /**
     * Omslagens storlek: så stora att det finns en egen film till varje plats
     * som kan synas samtidigt, under hela starten. Med många filmer blir det
     * den vanliga storleken; med få blir omslagen större och färre.
     */
    private fun fitTiles() {
        val usable = (count - failedCount).coerceAtLeast(1)
        var k = 1f
        for (attempt in 0 until 14) {
            setTileSize(k)
            if (mostNeeded() + RESERVE <= usable) return
            k *= 1.08f
        }
    }

    private fun setTileSize(k: Float) {
        tileH = (TILE_H * unit * k).roundToInt().coerceAtLeast(8)
        tileW = (tileH * 2f / 3f).roundToInt().coerceAtLeast(6)
        val gap = (TILE_GAP * unit * k).roundToInt().coerceAtLeast(2)
        pitchX = (tileW + gap).toFloat()
        pitchY = (tileH + gap).toFloat()
    }

    /** Hur många platser som som mest behöver ett omslag samtidigt. */
    private fun mostNeeded(): Int {
        var most = 0
        for (u in SAMPLE_TIMES) {
            pose(u, 0f)
            if (ranges(u, 0f)) most = max(most, keptCount())
        }
        // I slutet: biofälten öppna och kameran närmare.
        val last = SAMPLE_TIMES[SAMPLE_TIMES.size - 1]
        pose(last, 1f)
        if (ranges(last, 1f)) most = max(most, keptCount())
        return most
    }

    private fun keptCount(): Int {
        var sum = 0
        for (i in 0 until rowCount) sum += (keepTo[i] - keepFrom[i] + 1).coerceAtLeast(0)
        return sum
    }

    // --- Kameran -----------------------------------------------------------------------

    /**
     * Kameran [u] ms efter att väggen tänts: nära och snett i början, och sedan
     * glider den sakta bakåt och rätar upp sig, och vaggar lite hela tiden.
     * I slutet ([e]) går den lite närmare.
     */
    private fun pose(u: Long, e: Float) {
        val p = 1f - exp(-u / SETTLE_MS)
        val seconds = u / 1000f
        zoom = (ZOOM_FROM + (ZOOM_TO - ZOOM_FROM) * p) * (1f + EXIT_PUSH * easeInOutCubic(e))
        val pitch = PITCH_FROM + (PITCH_TO - PITCH_FROM) * p
        val yaw = YAW_FROM + (YAW_TO - YAW_FROM) * p + YAW_SWAY * sin(seconds / 15f * TAU + 1f)
        val roll = ROLL_FROM + (ROLL_TO - ROLL_FROM) * p + ROLL_SWAY * sin(seconds / 12f * TAU)
        rotation(pitch, yaw, roll)
        val s = zoom
        homography(0f, 0f, 0f, s * rot[0], s * rot[3], s * rot[6], s * rot[1], s * rot[4], s * rot[7], wall)
        invert(wall, wallInverse)
    }

    /** Vridningen: lutning runt x, sidvridning runt y och rotation runt z, i grader. */
    private fun rotation(pitch: Float, yaw: Float, roll: Float) {
        val ca = cos(pitch * DEG)
        val sa = sin(pitch * DEG)
        val cb = cos(yaw * DEG)
        val sb = sin(yaw * DEG)
        val cg = cos(roll * DEG)
        val sg = sin(roll * DEG)
        rot[0] = cb * cg
        rot[1] = -cb * sg
        rot[2] = sb
        rot[3] = ca * sg + sa * sb * cg
        rot[4] = ca * cg - sa * sb * sg
        rot[5] = -sa * cb
        rot[6] = sa * sg - ca * sb * cg
        rot[7] = sa * cg + ca * sb * sg
        rot[8] = ca * cb
    }

    /**
     * Matrisen för ett plan: punkten (x, y) i planet ligger i kamerans rum på
     * o + x·a + y·b, och kameran står [depth] framför skärmens mitt.
     */
    private fun homography(
        ox: Float, oy: Float, oz: Float,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        out: FloatArray,
    ) {
        val cx = width / 2f
        val cy = height / 2f
        val d = depth
        out[0] = ax + cx * az / d
        out[1] = bx + cx * bz / d
        out[2] = ox + cx * (1f + oz / d)
        out[3] = ay + cy * az / d
        out[4] = by + cy * bz / d
        out[5] = oy + cy * (1f + oz / d)
        out[6] = az / d
        out[7] = bz / d
        out[8] = 1f + oz / d
    }

    /**
     * Ett omslag med mitten i ([x], [y]) på väggen, vridet [thetaDeg] grader
     * runt sin lodräta mittlinje och lyft [lift] av vägen mot kameran längs
     * synlinjen: det står kvar på sin plats i bild, men större.
     */
    private fun tileHomography(x: Float, y: Float, thetaDeg: Float, lift: Float) {
        val s = zoom
        val keep = 1f - lift
        val qx = s * (rot[0] * x + rot[1] * y) * keep
        val qy = s * (rot[3] * x + rot[4] * y) * keep
        val qz = s * (rot[6] * x + rot[7] * y) * keep - depth * lift
        val c = cos(thetaDeg * DEG)
        val sn = sin(thetaDeg * DEG)
        val ax = s * (rot[0] * c + rot[2] * sn)
        val ay = s * (rot[3] * c + rot[5] * sn)
        val az = s * (rot[6] * c + rot[8] * sn)
        val bx = s * rot[1]
        val by = s * rot[4]
        val bz = s * rot[7]
        val hw = tileW / 2f
        val hh = tileH / 2f
        homography(qx - ax * hw - bx * hh, qy - ay * hw - by * hh, qz - az * hw - bz * hh, ax, ay, az, bx, by, bz, h)
    }

    /** Väggens punkt (x, y) på skärmen, i [projX] och [projY]. */
    private fun project(x: Float, y: Float) {
        val w = wall[6] * x + wall[7] * y + wall[8]
        projX = (wall[0] * x + wall[1] * y + wall[2]) / w
        projY = (wall[3] * x + wall[4] * y + wall[5]) / w
    }

    // --- Vad som syns ------------------------------------------------------------------

    /**
     * Platserna som syns, rad för rad: de som ska få omslag (bilden och en
     * smal kant runt) och de som får behålla sina (en bredare kant). Skillnaden
     * gör att en plats i kanten inte byter omslag fram och tillbaka.
     */
    private fun ranges(u: Long, open: Float): Boolean {
        val full = ((height - width / LETTERBOX) / 2f).coerceAtLeast(0f)
        bar = full * (1f - open)
        if (!viewQuad(KEEP_MARGIN * width, quadX, quadY)) return false
        if (!viewQuad(ENTER_MARGIN * width, enterX, enterY)) return false
        viewLeft = min(min(quadX[0], quadX[1]), min(quadX[2], quadX[3]))
        viewRight = max(max(quadX[0], quadX[1]), max(quadX[2], quadX[3]))
        viewTop = min(min(quadY[0], quadY[1]), min(quadY[2], quadY[3]))
        viewBottom = max(max(quadY[0], quadY[1]), max(quadY[2], quadY[3]))
        val half = tileH / 2f
        rowFirst = ceil((viewTop - half) / pitchY).toInt()
        val last = floor((viewBottom + half) / pitchY).toInt()
        rowCount = (last - rowFirst + 1).coerceIn(0, MAX_ROWS)
        for (i in 0 until rowCount) {
            val r = rowFirst + i
            val top = r * pitchY - half
            val bottom = top + tileH
            val left = rowLeft(r, u)
            if (band(quadX, quadY, top, bottom)) {
                keepFrom[i] = ceil((bandLow - tileW - left) / pitchX).toInt()
                keepTo[i] = floor((bandHigh - left) / pitchX).toInt()
            } else {
                keepFrom[i] = 1
                keepTo[i] = 0
            }
            if (band(enterX, enterY, top, bottom)) {
                enterFrom[i] = ceil((bandLow - tileW - left) / pitchX).toInt()
                enterTo[i] = floor((bandHigh - left) / pitchX).toInt()
            } else {
                enterFrom[i] = 1
                enterTo[i] = 0
            }
        }
        return true
    }

    /** Bilden (innanför biofälten) och [margin] runt, baklänges till väggen: en fyrhörning. */
    private fun viewQuad(margin: Float, xs: FloatArray, ys: FloatArray): Boolean {
        val m = wallInverse
        for (i in 0 until 4) {
            val sx = if (i == 0 || i == 3) -margin else width + margin
            val sy = if (i < 2) bar - margin else height - bar + margin
            val w = m[6] * sx + m[7] * sy + m[8]
            if (abs(w) < 1e-9f) return false
            val x = (m[0] * sx + m[1] * sy + m[2]) / w
            val y = (m[3] * sx + m[4] * sy + m[5]) / w
            // Punkten måste ligga framför kameran.
            if (wall[6] * x + wall[7] * y + wall[8] <= 0.05f) return false
            xs[i] = x
            ys[i] = y
        }
        return true
    }

    /** Fyrhörningens bredd mellan två vågräta linjer, i [bandLow] och [bandHigh]. */
    private fun band(xs: FloatArray, ys: FloatArray, top: Float, bottom: Float): Boolean {
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        for (i in 0 until 4) {
            val j = (i + 1) and 3
            val x0 = xs[i]
            val y0 = ys[i]
            val x1 = xs[j]
            val y1 = ys[j]
            if (y0 >= top && y0 <= bottom) {
                low = min(low, x0)
                high = max(high, x0)
            }
            if ((y0 - top) * (y1 - top) < 0f) {
                val x = x0 + (x1 - x0) * (top - y0) / (y1 - y0)
                low = min(low, x)
                high = max(high, x)
            }
            if ((y0 - bottom) * (y1 - bottom) < 0f) {
                val x = x0 + (x1 - x0) * (bottom - y0) / (y1 - y0)
                low = min(low, x)
                high = max(high, x)
            }
        }
        if (low > high) return false
        bandLow = low
        bandHigh = high
        return true
    }

    /** Var radens kolumn 0 börjar: förskjuten per rad, och glider åt varannat håll. */
    private fun rowLeft(r: Int, u: Long): Float {
        val phase = ((r * 0.382f) % 1f + 1f) % 1f * pitchX
        val speed = ROW_SPEEDS[r.mod(ROW_SPEEDS.size)] * unit
        val direction = if (r.mod(2) == 0) 1f else -1f
        // Lite fartigare i början, och sedan lugnt.
        val kick = KICK * KICK_MS / 1000f * (1f - exp(-u / KICK_MS))
        return phase + direction * speed * (u / 1000f + kick)
    }

    private fun tileLeft(tile: Tile, u: Long): Float = rowLeft(tile.row, u) + tile.col * pitchX

    private fun tileTop(tile: Tile): Float = tile.row * pitchY - tileH / 2f

    /** Hela omslaget syns, minst [inset] innanför bildens kanter. */
    private fun fullyInside(tile: Tile, u: Long, inset: Float): Boolean {
        val left = tileLeft(tile, u)
        val top = tileTop(tile)
        for (k in 0 until 4) {
            val x = if (k == 0 || k == 3) left else left + tileW
            val y = if (k < 2) top else top + tileH
            project(x, y)
            if (projX < inset || projX > width - inset || projY < bar + inset || projY > height - bar - inset) return false
        }
        return true
    }

    // --- Omslagen på platserna ---------------------------------------------------------

    private fun find(row: Int, col: Int): Tile? {
        for (i in tiles.indices) {
            val tile = tiles[i]
            if (tile.row == row && tile.col == col) return tile
        }
        return null
    }

    private fun updateTiles(t: Long, u: Long) {
        // Platser som lämnat bilden lämnar tillbaka sina omslag.
        var i = tiles.size - 1
        while (i >= 0) {
            val tile = tiles[i]
            val row = tile.row - rowFirst
            val inside = row in 0 until rowCount && tile.col >= keepFrom[row] && tile.col <= keepTo[row]
            if (!inside && !tile.hero) {
                release(tile.poster, t)
                release(tile.flipTo, t)
                tiles.removeAt(i)
                spare.add(tile)
            }
            i--
        }
        // Vändningar som är klara: det nya omslaget sitter kvar.
        for (k in tiles.indices) {
            val tile = tiles[k]
            if (tile.flipAt >= 0 && t >= tile.flipAt + FLIP_MS.toLong()) {
                release(tile.poster, t)
                tile.poster = tile.flipTo
                tile.flipTo = -1
                tile.flipAt = -1L
            }
        }
        // Nya platser i bilden. Vågen går utåt från mitten, så de närmast mitten vänds först.
        for (row in 0 until rowCount) {
            val r = rowFirst + row
            for (col in enterFrom[row]..enterTo[row]) {
                if (find(r, col) != null) continue
                val tile = if (spare.isEmpty()) Tile() else spare.removeAt(spare.size - 1)
                tile.reset(r, col)
                tile.createdAt = t
                project(tileLeft(tile, u) + tileW / 2f, r * pitchY)
                val dx = projX - width / 2f
                val dy = projY - height / 2f
                tile.side = if (dx < 0f) -1f else 1f
                tile.lift = hypot(dx, dy) / (width / 2f)
                tile.igniteAt = if (u < BLOOM_UNTIL_MS) {
                    wallAt + (IGNITE_DELAY_MS + tile.lift * IGNITE_SPREAD_MS + hash(r, col) * IGNITE_JITTER_MS).toLong()
                } else {
                    // Kommer in från kanten: sitter redan på plats.
                    t - IGNITE_MS.toLong()
                }
                // Närmast mitten först: de får de bästa filmerna.
                var at = fresh.size
                while (at > 0 && fresh[at - 1].lift > tile.lift) at--
                fresh.add(at, tile)
            }
        }
        for (k in fresh.indices) tiles.add(fresh[k])
        fresh.clear()
        // Omslag till platserna som väntar. Kom det sent vänds det fram nu.
        for (k in tiles.indices) {
            val tile = tiles[k]
            if (tile.poster >= 0) continue
            val poster = take(shared = true)
            if (poster < 0) break
            tile.poster = poster
            if (tile.createdAt < t && tile.igniteAt < t) tile.igniteAt = t
        }
    }

    /**
     * Ett omslag som inte syns någon annanstans: det som varit borta längst,
     * eller nästa film i ordningen. Finns inget sådant, och inga fler på väg,
     * får [shared] ta det som används minst: hellre det än ett hål i väggen.
     */
    private fun take(shared: Boolean): Int {
        var best = -1
        for (i in 0 until count) {
            if (images[i] == null || users[i] != 0) continue
            if (best < 0 || freeSince[i] < freeSince[best]) best = i
        }
        if (best < 0 && shared && loadedCount + failedCount >= count) {
            for (i in 0 until count) {
                if (images[i] == null) continue
                if (best < 0 || users[i] < users[best] || (users[i] == users[best] && freeSince[i] < freeSince[best])) best = i
            }
        }
        if (best >= 0) users[best]++
        return best
    }

    private fun release(poster: Int, t: Long) {
        if (poster < 0 || poster >= count) return
        users[poster] = (users[poster] - 1).coerceAtLeast(0)
        if (users[poster] == 0) freeSince[poster] = t
    }

    /** Liv i väggen: då och då vänds ett omslag till en annan film, och ljuset glider över ett. */
    private fun scheduleLife(t: Long, u: Long) {
        if (exitAt >= 0) return
        if (t >= nextFlipAt) {
            nextFlipAt = t + (FLIP_EVERY_MS * (0.7f + 0.6f * hash(picks, 11))).toLong()
            val tile = pick(t, u, nearLight = false)
            if (tile != null) {
                val poster = take(shared = false)
                if (poster >= 0) {
                    tile.flipAt = t
                    tile.flipTo = poster
                    tile.flipDir = if (hash(picks, 12) < 0.5f) -1f else 1f
                }
            }
        }
        if (t >= nextGlintAt) {
            nextGlintAt = t + (GLINT_EVERY_MS * (0.7f + 0.6f * hash(picks, 13))).toLong()
            pick(t, u, nearLight = true)?.glintAt = t
        }
    }

    /** Ett omslag som syns helt och står still, gärna nära mitten eller ljuset. */
    private fun pick(t: Long, u: Long, nearLight: Boolean): Tile? {
        picks++
        val targetX = if (nearLight) lightScreenX else width / 2f
        val targetY = if (nearLight) lightScreenY else height / 2f
        val inset = width * 0.02f
        var best: Tile? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in tiles.indices) {
            val tile = tiles[i]
            if (tile.poster < 0 || tile.hero || tile.flipAt >= 0 || tile.glintAt >= 0) continue
            if (t < tile.igniteAt + IGNITE_MS.toLong()) continue
            if (!fullyInside(tile, u, inset)) continue
            project(tileLeft(tile, u) + tileW / 2f, tile.row * pitchY)
            val distance = hypot(projX - targetX, projY - targetY) / width
            val score = hash(tile.row * 31 + tile.col, picks) - distance * 1.6f
            if (score > bestScore) {
                bestScore = score
                best = tile
            }
        }
        return best
    }

    /** Ljuset på väggen: från mitten av bilden vandrar det sakta runt. */
    private fun light(u: Long) {
        val seconds = u / 1000f
        lightX = LIGHT_SWING_X * unit * sin(seconds / 13f * TAU)
        lightY = LIGHT_SWING_Y * unit * sin(seconds / 9f * TAU)
        project(lightX, lightY)
        lightScreenX = projX
        lightScreenY = projY
    }

    /** Filmen närmast mitten, som syns helt: den lyfter ut ur väggen. */
    private fun chooseHero(t: Long, u: Long) {
        var best: Tile? = null
        var bestDistance = Float.MAX_VALUE
        for (i in tiles.indices) {
            val tile = tiles[i]
            if (tile.poster < 0 || tile.flipAt >= 0 || t < tile.igniteAt + IGNITE_MS.toLong()) continue
            if (!fullyInside(tile, u, 0f)) continue
            project(tileLeft(tile, u) + tileW / 2f, tile.row * pitchY)
            val distance = hypot(projX - width / 2f, projY - height / 2f)
            if (distance < bestDistance) {
                bestDistance = distance
                best = tile
            }
        }
        val hero = best ?: return
        hero.hero = true
        heroPoster = hero.poster
        val x = tileLeft(hero, u) + tileW / 2f
        val y = hero.row * pitchY
        val s = zoom
        heroCenter[0] = s * (rot[0] * x + rot[1] * y)
        heroCenter[1] = s * (rot[3] * x + rot[4] * y)
        heroCenter[2] = s * (rot[6] * x + rot[7] * y)
        heroAxisX[0] = rot[0]
        heroAxisX[1] = rot[3]
        heroAxisX[2] = rot[6]
        heroAxisY[0] = rot[1]
        heroAxisY[1] = rot[4]
        heroAxisY[2] = rot[7]
        heroScale = s
    }

    // --- Det som ritas -----------------------------------------------------------------

    private fun DrawScope.poster(index: Int, alpha: Float) {
        val image = images[index]
        if (image == null) {
            drawRect(TILE_BACK, size = Size(tileW.toFloat(), tileH.toFloat()), alpha = alpha)
            return
        }
        drawImage(
            image = image,
            srcOffset = IntOffset(srcX[index], srcY[index]),
            srcSize = IntSize(srcW[index], srcH[index]),
            dstSize = IntSize(tileW, tileH),
            alpha = alpha,
            filterQuality = FilterQuality.Medium,
        )
    }

    private fun DrawScope.drawWall(t: Long, u: Long) {
        raised.clear()
        // Omslagen som sitter på väggen: en och samma projektion, bara flyttade.
        projected(wall) {
            for (i in tiles.indices) {
                val tile = tiles[i]
                if (tile.poster < 0 || tile.hero || t < tile.igniteAt) continue
                if (t < tile.igniteAt + IGNITE_MS.toLong() || tile.flipAt >= 0) {
                    tile.lift = liftOf(tile, t)
                    // Det närmast kameran ritas sist.
                    var at = raised.size
                    while (at > 0 && raised[at - 1].lift > tile.lift) at--
                    raised.add(at, tile)
                    continue
                }
                translate(tileLeft(tile, u), tileTop(tile)) {
                    poster(tile.poster, 1f)
                    glint(tile, t)
                }
            }
        }
        for (i in raised.indices) drawRaised(raised[i], t, u)
        raised.clear()
    }

    private fun liftOf(tile: Tile, t: Long): Float {
        val ignite = (t - tile.igniteAt) / IGNITE_MS
        if (ignite < 1f) return IGNITE_LIFT * (1f - easeOutCubic(ignite.coerceIn(0f, 1f)))
        val f = ((t - tile.flipAt) / FLIP_MS).coerceIn(0f, 1f)
        return FLIP_LIFT * sin(PI_F * f)
    }

    /**
     * Ett omslag som vänds: fram ur väggen i början, eller till en annan film.
     * Det är mörkare när det står på kant, och glänser till när det vänds
     * genom ljuset.
     */
    private fun DrawScope.drawRaised(tile: Tile, t: Long, u: Long) {
        var shown = tile.poster
        val theta: Float
        var alpha = 1f
        val ignite = (t - tile.igniteAt) / IGNITE_MS
        if (ignite < 1f) {
            val q = ignite.coerceIn(0f, 1f)
            theta = tile.side * 90f * (1f - easeOutBack(q))
            alpha = smoothstep(0f, 0.22f, q)
        } else {
            val f = ((t - tile.flipAt) / FLIP_MS).coerceIn(0f, 1f)
            val turn = 180f * easeInOutCubic(f)
            if (turn > 90f) {
                // Andra sidan: den nya filmen, rättvänd.
                if (tile.flipTo >= 0) shown = tile.flipTo
                theta = tile.flipDir * (turn - 180f)
            } else {
                theta = tile.flipDir * turn
            }
        }
        tileHomography(tileLeft(tile, u) + tileW / 2f, tile.row * pitchY, theta, tile.lift)
        val facing = abs(cos(theta * DEG))
        val shade = EDGE_SHADE * (1f - facing)
        val turned = (abs(theta) - SPEC_ANGLE) / SPEC_WIDTH
        val spec = SPEC_MAX * exp(-turned * turned)
        val area = Size(tileW.toFloat(), tileH.toFloat())
        projected(h) {
            poster(shown, alpha)
            if (shade > 0.01f) drawRect(Color.Black, size = area, alpha = shade * alpha)
            if (spec > 0.01f) drawRect(SHINE, size = area, alpha = spec * alpha)
        }
    }

    /** Ett ljus som glider snett över ett omslag, som på blankt papper. */
    private fun DrawScope.glint(tile: Tile, t: Long) {
        if (tile.glintAt < 0) return
        val g = (t - tile.glintAt) / GLINT_MS
        if (g >= 1f) {
            tile.glintAt = -1L
            return
        }
        val w = tileW.toFloat()
        val hh = tileH.toFloat()
        val dx = 0.91f
        val dy = 0.41f
        val length = dx * w + dy * hh
        val half = length * 0.17f
        val center = -half + (length + 2f * half) * easeInOutSine(g)
        val strength = GLINT_MAX * sin(PI_F * g)
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to SHINE.copy(alpha = strength),
                1f to Color.Transparent,
                start = Offset(dx * (center - half), dy * (center - half)),
                end = Offset(dx * (center + half), dy * (center + half)),
            ),
            size = Size(w, hh),
        )
    }

    /** Ljuset som vandrar över väggen: ljust där det är, mörkare längre bort. */
    private fun DrawScope.drawLight() {
        val topLeft = Offset(viewLeft, viewTop)
        val area = Size(viewRight - viewLeft, viewBottom - viewTop)
        val center = Offset(lightX, lightY)
        val radius = POOL_RADIUS * unit
        projected(wall) {
            drawRect(
                Brush.radialGradient(
                    0f to Color.Transparent,
                    0.5f to Color.Black.copy(alpha = POOL_DARK * 0.35f),
                    1f to Color.Black.copy(alpha = POOL_DARK),
                    center = center,
                    radius = radius,
                ),
                topLeft = topLeft,
                size = area,
            )
            drawRect(
                Brush.radialGradient(
                    0f to WARM.copy(alpha = POOL_WARM),
                    1f to Color.Transparent,
                    center = center,
                    radius = radius * 0.7f,
                ),
                topLeft = topLeft,
                size = area,
            )
        }
    }

    /** Ett svagt glanssvep över hela väggen då och då, som ljus i glas. */
    private fun DrawScope.drawSheen(u: Long) {
        if (u < SHEEN_FROM_MS) return
        val local = (u - SHEEN_FROM_MS) % SHEEN_EVERY_MS
        if (local > SHEEN_MS) return
        val f = local / SHEEN_MS.toFloat()
        val band = width * 0.22f
        val x = -band + (width + 2f * band) * easeInOutSine(f)
        val strength = SHEEN_MAX * sin(PI_F * f)
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to SHINE.copy(alpha = strength),
                1f to Color.Transparent,
                start = Offset(x - band, 0f),
                end = Offset(x + band, band * 0.6f),
            ),
            topLeft = Offset(0f, bar),
            size = Size(width, height - 2f * bar),
        )
    }

    /** Slutet: väggen mörknar, filmen lyfter ut och kommer emot en, och ljuset slår upp. */
    private fun DrawScope.drawExit(e: Float) {
        val dim = EXIT_DIM * smoothstep(0f, 0.45f, e)
        drawRect(Color.Black, size = Size(width, height), alpha = dim)
        if (heroPoster >= 0) drawHero(e)
        val bell = (e - BLOOM_AT) / BLOOM_WIDTH
        val bloom = BLOOM_MAX * exp(-bell * bell)
        if (bloom > 0.01f) {
            drawRect(
                Brush.radialGradient(
                    0f to GLOW.copy(alpha = bloom),
                    1f to Color.Transparent,
                    center = Offset(width / 2f, height / 2f),
                    radius = width * 0.6f,
                ),
                size = Size(width, height),
            )
        }
    }

    /**
     * Filmen som lyfter ut: från sin plats i väggen, rakt mot kameran tills den
     * står mitt i bild och nästan fyller höjden, med en liten sväng på vägen.
     * Sedan fortsätter den förbi medan allt tonar över i startsidan.
     */
    private fun DrawScope.drawHero(e: Float) {
        val p = easeInOutCubic((e / HERO_TRAVEL).coerceIn(0f, 1f))
        val q = 1f - p
        val zf = -HERO_Z * depth
        val end = HERO_FILL * height * (depth + zf) / (depth * tileH)
        val cx = heroCenter[0] * q
        val cy = heroCenter[1] * q
        val cz = heroCenter[2] * q + zf * p
        // Axlarna vrids mot kameran.
        var ax = heroAxisX[0] * q + p
        var ay = heroAxisX[1] * q
        var az = heroAxisX[2] * q
        val al = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-6f)
        ax /= al
        ay /= al
        az /= al
        var bx = heroAxisY[0] * q
        var by = heroAxisY[1] * q + p
        var bz = heroAxisY[2] * q
        val dot = ax * bx + ay * by + az * bz
        bx -= ax * dot
        by -= ay * dot
        bz -= az * dot
        val bl = sqrt(bx * bx + by * by + bz * bz).coerceAtLeast(1e-6f)
        bx /= bl
        by /= bl
        bz /= bl
        // En liten sväng runt den lodräta axeln.
        val phi = HERO_TWIST * DEG * sin(PI_F * p)
        val cp = cos(phi)
        val sp = sin(phi)
        val ax2 = ax * cp + az * sp
        val az2 = -ax * sp + az * cp
        val bx2 = bx * cp + bz * sp
        val bz2 = -bx * sp + bz * cp
        val past = smoothstep(HERO_TRAVEL - 0.05f, 1f, e)
        val length = (heroScale * q + end * p) * (1f + HERO_GROW * past * past)
        val hw = tileW / 2f
        val hh = tileH / 2f
        val aX = ax2 * length
        val aY = ay * length
        val aZ = az2 * length
        val bX = bx2 * length
        val bY = by * length
        val bZ = bz2 * length
        homography(cx - aX * hw - bX * hh, cy - aY * hw - bY * hh, cz - aZ * hw - bZ * hh, aX, aY, aZ, bX, bY, bZ, h)
        val glow = smoothstep(0.08f, 0.5f, e)
        val w = tileW.toFloat()
        val ht = tileH.toFloat()
        projected(h) {
            if (glow > 0f) {
                // Ljus bakom filmen och en tunn ljus kant.
                val r = ht * 0.9f
                drawRect(
                    Brush.radialGradient(
                        0f to GLOW.copy(alpha = HERO_GLOW * glow),
                        1f to Color.Transparent,
                        center = Offset(w / 2f, ht / 2f),
                        radius = r,
                    ),
                    topLeft = Offset(w / 2f - r, ht / 2f - r),
                    size = Size(2f * r, 2f * r),
                )
                val edge = ht * 0.006f
                drawRect(SHINE, topLeft = Offset(-edge, -edge), size = Size(w + 2f * edge, ht + 2f * edge), alpha = 0.45f * glow)
            }
            poster(heroPoster, 1f)
        }
    }

    /** Innan väggen tänts: ett svagt sken i mörkret. */
    private fun DrawScope.drawAmbient(t: Long) {
        val shown = smoothstep(150f, 1400f, t.toFloat())
        if (shown <= 0f) return
        val breath = 0.8f + 0.2f * sin(t / 2600f * TAU)
        drawRect(
            Brush.radialGradient(
                0f to COLD.copy(alpha = 0.07f * shown * breath),
                1f to Color.Transparent,
                center = Offset(width / 2f, height / 2f),
                radius = min(width, height) * 0.7f,
            ),
            size = Size(width, height),
        )
    }

    /** Färgtonen, mörkare hörn, stoft i ljuset och biofälten (bilden i 2,39:1), som öppnar sig i slutet. */
    private fun DrawScope.drawFrame(t: Long, e: Float) {
        val area = Size(width, height)
        val grade = gradeBrush ?: Brush.verticalGradient(
            0f to COLD.copy(alpha = 0.10f),
            0.45f to Color.Transparent,
            1f to WARM.copy(alpha = 0.07f),
            startY = 0f,
            endY = height,
        ).also { gradeBrush = it }
        val vignette = vignetteBrush ?: Brush.radialGradient(
            0f to Color.Transparent,
            0.55f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.55f),
            center = Offset(width / 2f, height / 2f),
            radius = max(width, height) * 0.72f,
        ).also { vignetteBrush = it }
        drawRect(grade, size = area)
        drawRect(vignette, size = area)
        drawDust(t, 1f - e)
        val full = ((height - width / LETTERBOX) / 2f).coerceAtLeast(0f)
        val b = full * (1f - smoothstep(BARS_OPEN_FROM, BARS_OPEN_TO, e))
        if (b > 0.5f) {
            drawRect(INTRO_BLACK, topLeft = Offset.Zero, size = Size(width, b))
            drawRect(INTRO_BLACK, topLeft = Offset(0f, height - b), size = Size(width, b))
        }
    }

    /** Stoft som svävar uppåt i ljuset och glimtar. [strength]: 0–1, hur mycket det syns. */
    private fun DrawScope.drawDust(t: Long, strength: Float) {
        val shown = smoothstep(DUST_AT_MS, DUST_AT_MS + 1200f, t.toFloat()) * strength
        if (shown <= 0f) return
        val seconds = t / 1000f
        val dot = unit / 720f
        for (i in 0 until DUST) {
            val speed = (6f + 18f * hash(i, 1)) * dot
            val y = height - ((seconds * speed + hash(i, 2) * height) % (height + 40f * dot)) + 20f * dot
            val x = width * hash(i, 3) + sin(seconds * (0.4f + hash(i, 4)) + i) * 14f * dot
            val twinkle = 0.55f + 0.45f * sin(seconds * (1.2f + 2f * hash(i, 5)) + i * 1.7f)
            val alpha = (0.05f + 0.26f * hash(i, 6)) * twinkle * shown
            drawCircle(DUST_COLOR.copy(alpha = alpha), radius = (0.7f + 1.6f * hash(i, 7)) * dot, center = Offset(x, y))
        }
    }
}

// --- Matriser ------------------------------------------------------------------------

/** Ritar [block] genom matrisen [m] (3×3 radvis, med perspektiv), och återställer sedan. */
private inline fun DrawScope.projected(m: FloatArray, block: DrawScope.() -> Unit) {
    val canvas = drawContext.canvas
    canvas.save()
    concatPerspective(m)
    block()
    canvas.restore()
}

private fun invert(m: FloatArray, out: FloatArray) {
    val a = m[0]
    val b = m[1]
    val c = m[2]
    val d = m[3]
    val e = m[4]
    val f = m[5]
    val g = m[6]
    val hh = m[7]
    val i = m[8]
    val c00 = e * i - f * hh
    val c01 = -(d * i - f * g)
    val c02 = d * hh - e * g
    val det = a * c00 + b * c01 + c * c02
    val inv = if (abs(det) < 1e-12f) 0f else 1f / det
    out[0] = c00 * inv
    out[1] = -(b * i - c * hh) * inv
    out[2] = (b * f - c * e) * inv
    out[3] = c01 * inv
    out[4] = (a * i - c * g) * inv
    out[5] = -(a * f - c * d) * inv
    out[6] = c02 * inv
    out[7] = -(a * hh - b * g) * inv
    out[8] = (a * e - b * d) * inv
}

// --- Kurvor --------------------------------------------------------------------------

private fun smoothstep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun easeOutCubic(x: Float): Float = 1f - (1f - x).pow(3)

private fun easeInOutCubic(x: Float): Float = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f

private fun easeInOutSine(x: Float): Float = -(cos(PI_F * x) - 1f) / 2f

/** Landar med en liten studs förbi målet. */
private fun easeOutBack(x: Float): Float {
    val c1 = 1.05f
    val c3 = c1 + 1f
    val y = x - 1f
    return 1f + c3 * y * y * y + c1 * y * y
}

/** Ett fast slumptal 0–1 för [i] och egenskap [k]: samma varje bild, utan att något skapas. */
private fun hash(i: Int, k: Int): Float {
    var x = i * 374_761_393 + k * 668_265_263
    x = (x xor (x ushr 13)) * 1_274_126_177
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16_777_215f
}

private const val PI_F = 3.1415927f
private const val TAU = 6.2831855f
private const val DEG = 0.017453292f

private val INTRO_BLACK = Color(0xFF020306)
private val TILE_BACK = Color(0xFF0E1116)
private val SHINE = Color(0xFFFFFFFF)
private val GLOW = Color(0xFFFFF1DC)
private val WARM = Color(0xFFFFC27A)
private val COLD = Color(0xFF3E8BFF)
private val DUST_COLOR = Color(0xFFE6F0FF)

/** Omslagen: höjd och mellanrum som del av skärmens höjd, och hur skarpt de laddas. */
private const val TILE_H = 0.31f
private const val TILE_GAP = 0.026f
private const val POSTER_SIDE = 520
private const val LOAD_PARALLEL = 6
/** Väggen tänds när så många omslag laddats (eller alla, eller efter en stund). */
private const val START_LOADED = 24
private const val WALL_WAIT_MS = 1_800L
/** Omslag i reserv utöver de som syns, till vändningarna. */
private const val RESERVE = 3
private const val MAX_ROWS = 24
private val SAMPLE_TIMES = longArrayOf(0L, 1_500L, 3_000L, 5_000L, 8_000L, 14_000L, 20_000L)

/** Kameran: avståndet i skärmbredder, hur den lägger sig och vaggar (grader). */
private const val CAMERA_DISTANCE = 1.5f
private const val SETTLE_MS = 3_000f
private const val ZOOM_FROM = 1.30f
private const val ZOOM_TO = 1.0f
private const val PITCH_FROM = -12f
private const val PITCH_TO = -7f
private const val YAW_FROM = -22f
private const val YAW_TO = -12f
private const val ROLL_FROM = -14f
private const val ROLL_TO = -10.5f
private const val YAW_SWAY = 1.2f
private const val ROLL_SWAY = 0.8f
private const val EXIT_PUSH = 0.12f

/** Radernas fart: del av skärmens höjd per sekund. Lite fartigare i början. */
private val ROW_SPEEDS = floatArrayOf(0.026f, 0.021f, 0.030f, 0.023f, 0.028f, 0.020f)
private const val KICK = 1.4f
private const val KICK_MS = 1_400f

/** Biofälten, och kanterna runt bilden där platser får och behåller omslag (del av bredden). */
private const val LETTERBOX = 2.39f
private const val ENTER_MARGIN = 0.015f
private const val KEEP_MARGIN = 0.05f

/** Vågen när väggen tänds: från mitten och utåt. */
private const val IGNITE_DELAY_MS = 250f
private const val IGNITE_SPREAD_MS = 1_100f
private const val IGNITE_JITTER_MS = 140f
private const val IGNITE_MS = 950f
private const val IGNITE_LIFT = 0.22f
private const val BLOOM_UNTIL_MS = 2_600L

/** Livet i väggen. */
private const val LIVE_FROM_MS = 2_800L
private const val FLIP_EVERY_MS = 1_150f
private const val FLIP_MS = 1_400f
private const val FLIP_LIFT = 0.12f
private const val GLINT_EVERY_MS = 950f
private const val GLINT_MS = 900f
private const val GLINT_MAX = 0.36f
private const val EDGE_SHADE = 0.78f
private const val SPEC_MAX = 0.42f
private const val SPEC_ANGLE = 24f
private const val SPEC_WIDTH = 11f

/** Ljuset som vandrar, och glanssvepen. */
private const val LIGHT_SWING_X = 0.55f
private const val LIGHT_SWING_Y = 0.28f
private const val POOL_RADIUS = 1.25f
private const val POOL_DARK = 0.42f
private const val POOL_WARM = 0.07f
private const val SHEEN_FROM_MS = 2_300L
private const val SHEEN_EVERY_MS = 7_000L
private const val SHEEN_MS = 1_700L
private const val SHEEN_MAX = 0.07f
private const val DUST = 44
private const val DUST_AT_MS = 150f

/** Slutet, i förloppet 0–1. */
private const val HERO_TRAVEL = 0.62f
private const val HERO_Z = 0.30f
private const val HERO_FILL = 0.80f
private const val HERO_TWIST = 16f
private const val HERO_GROW = 0.7f
private const val HERO_GLOW = 0.22f
private const val EXIT_DIM = 0.80f
private const val BLOOM_AT = 0.6f
private const val BLOOM_WIDTH = 0.17f
private const val BLOOM_MAX = 0.24f
private const val BARS_OPEN_FROM = 0.18f
private const val BARS_OPEN_TO = 0.72f
private const val FADE_FROM = 0.62f

/** Hur länge: senast då väggen får ta över, minst så länge den visas, och slutens längd. */
private const val REEL_LATEST_MS = 3_500L
private const val WALL_MIN_MS = 4_200L
private const val AMBIENT_MIN_MS = 900L
private const val EXIT_MS = 1_700
private const val EXIT_SKIP_MS = 1_100
private const val EXIT_PLAIN_MS = 600
/** Längre än så väntar den inte, även om inget blivit klart. */
private const val MAX_SHOW_MS = 20_000L

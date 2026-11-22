package se.norrsken.tv.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
 * Filmväggen: omslag från de häftigaste filmerna man har — det som trendar,
 * Marvel och de andra filmserierna, klassiker som Jurassic Park och Ensam
 * hemma, nya filmer med högt betyg — på en stor vägg som står snett i
 * djupet, i riktig 3D. Starten visar den i full styrka: omslagen tonas fram
 * ur mörkret, och sedan klipper väggen mellan filmerna allt snabbare, som
 * en filmtrailer, tills allt stannar med ett slag. Sedan blir väggen kvar
 * som appens bakgrund, mörk och dämpad, och glider sakta medan man inte
 * trycker på något.
 *
 * Omslagen roteras aldrig, och väggen vrids inte: den står fast och
 * raderna glider. Samma film syns aldrig två gånger samtidigt: väggen
 * räknar ut vilka platser som syns och ger var och en ett eget omslag.
 * Allt ritas i en enda ritning genom en och samma perspektivprojektion —
 * inget mäts om och inget sätts ihop på nytt medan den rör sig. Ingen
 * oskärpa och ingen glans.
 *
 * Fälten ändras bara på UI-tråden: i ritningen, när ett omslag laddats och
 * i klockan som driver väggen.
 */
internal class PosterWall {
    // --- Omslagen ----------------------------------------------------------------------
    private var count = 0
    private var images = arrayOfNulls<ImageBitmap>(0)
    private var users = IntArray(0)
    /** När omslaget senast lämnade bilden (väggens tid); de som aldrig visats i filmernas ordning. */
    private var freeSince = LongArray(0)
    private var srcX = IntArray(0)
    private var srcY = IntArray(0)
    private var srcW = IntArray(0)
    private var srcH = IntArray(0)
    private var loadedCount = 0
    private var failedCount = 0
    private var reelAt = -1L

    // --- Tiden -------------------------------------------------------------------------
    /** Väggens egen tid, ms sedan den tändes. Läses i ritningen: varje ny tid ritar om. */
    private val clock = mutableLongStateOf(0L)
    /** Ökar när ett omslag laddats, så att ritningen tar med det också medan väggen står still. */
    private val loads = mutableIntStateOf(0)
    private var elapsed = 0.0
    private var nextSwapAt = 0L

    /** Väggen har tänts. */
    var lit = false
        private set

    /** Väggen tändes i starten: den har klippen och slaget. Tänds den först som bakgrund blir det lugnare. */
    private var cinematic = false

    /** Efter starten: väggen är bakgrund, och omslagen byts inte längre. */
    var ambient = false

    /** Senaste knapptryckningen eller musrörelsen, i [nowMs]. */
    var lastInput = 0L

    /** Något omslag tonas fram eller byts: väggen ska gå i full fart tills det är klart. */
    var busy = false
        private set

    val hasReel: Boolean get() = count > 0

    /** Väggens tid, ms sedan den tändes. */
    val time: Long get() = clock.longValue

    // --- Måtten, i bildpunkter ---------------------------------------------------------
    private var width = 0f
    private var height = 0f
    private var unit = 0f
    private var depth = 1f
    private var tileW = 1
    private var tileH = 1
    private var pitchX = 1f
    private var pitchY = 1f

    // --- Kameran i den här bilden ------------------------------------------------------
    private var zoom = 1f
    private val rot = FloatArray(9)
    private val wall = FloatArray(9)
    private val wallInverse = FloatArray(9)
    private var projX = 0f
    private var projY = 0f
    private var lightX = 0f
    private var lightY = 0f
    private var poolRadius = POOL_RADIUS
    private var poolDark = POOL_DARK
    private var fogOk = false
    private var fogStartX = 0f
    private var fogStartY = 0f
    private var fogEndX = 0f
    private var fogEndY = 0f

    // --- Platserna ---------------------------------------------------------------------
    private val tiles = ArrayList<Tile>()
    private val spare = ArrayList<Tile>()
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
    private var preparedAt = Long.MIN_VALUE
    private var dirty = true
    private var ready = false

    init {
        // Väggen står fast: samma vinkel hela tiden, bara kameran går närmare och längre bort.
        rotation(PITCH, YAW, ROLL)
    }

    fun setReel(size: Int) {
        count = size
        images = arrayOfNulls(size)
        users = IntArray(size)
        freeSince = LongArray(size) { Long.MIN_VALUE + it }
        srcX = IntArray(size)
        srcY = IntArray(size)
        srcW = IntArray(size)
        srcH = IntArray(size)
        reelAt = nowMs()
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
        dirty = true
        loads.intValue++
    }

    /** Tänds när tillräckligt många omslag laddats, när alla är klara, eller efter en stund. */
    fun tryLight(now: Long) {
        if (lit || count == 0 || loadedCount == 0 || width <= 0f) return
        val settled = loadedCount + failedCount >= count
        if (loadedCount >= min(count, START_LOADED) || settled || now >= reelAt + WALL_WAIT_MS) {
            lit = true
            cinematic = !ambient
            elapsed = 0.0
            clock.longValue = 0L
            nextSwapAt = if (cinematic) SLAM_MS + SWAP_AFTER_SLAM_MS else Long.MAX_VALUE
            fitTiles()
            dirty = true
        }
    }

    /** Omslagen laddas och väggen tänds snart: starten väntar in den. */
    fun lightingSoon(now: Long): Boolean =
        !lit && count > 0 && (loadedCount > 0 || (loadedCount + failedCount < count && now < reelAt + WALL_WAIT_MS))

    /** Väggens tid går fram [ms]; syns först efter [publish]. */
    fun advance(ms: Double) {
        elapsed += ms
    }

    fun publish() {
        val v = elapsed.toLong()
        if (v != clock.longValue) clock.longValue = v
    }

    // --- Ritningen ---------------------------------------------------------------------

    /** Starten: väggen i full styrka, med ljuset som vandrar och djupet som mörknar. */
    fun drawIntro(scope: DrawScope) {
        noteSize(scope.size)
        val u = clock.longValue
        loads.intValue
        if (!lit) return
        prepare(u)
        if (!ready) return
        scope.drawTiles(u, null)
        scope.drawPool()
        scope.drawFog(Color.Black, INTRO_FOG)
    }

    /**
     * Bakgrunden i appen: samma vägg, mörk och med lite färg, tonad mot
     * temats bakgrundsfärg [tint] så att text och omslag framför syns tydligt.
     * [quiet]: sidor med mycket text, där den är ännu mörkare.
     */
    fun drawBackdrop(scope: DrawScope, tint: Color, quiet: Boolean) {
        noteSize(scope.size)
        val u = clock.longValue
        loads.intValue
        if (!lit) return
        prepare(u)
        if (!ready) return
        with(scope) {
            drawRect(tint)
            drawTiles(u, backdropFilter)
            drawFog(tint, BACKDROP_FOG)
            drawRect(
                Brush.verticalGradient(
                    0f to tint.copy(alpha = 0.34f),
                    0.5f to tint.copy(alpha = 0.52f),
                    1f to tint.copy(alpha = 0.80f),
                ),
            )
            if (quiet) drawRect(tint.copy(alpha = QUIET_DIM))
        }
    }

    private fun noteSize(size: Size) {
        if (size.width < 2f || size.height < 2f) return
        if (size.width != width || size.height != height) resize(size.width, size.height)
    }

    private fun resize(w: Float, hh: Float) {
        width = w
        height = hh
        unit = min(hh, w * 9f / 16f)
        depth = CAMERA_DISTANCE * w
        dirty = true
        if (lit) {
            // Fönstret bytte storlek: väggen läggs om.
            val u = clock.longValue
            for (i in tiles.indices) {
                val tile = tiles[i]
                release(tile.poster, u)
                release(tile.previous, u)
                spare.add(tile)
            }
            tiles.clear()
            fitTiles()
        }
    }

    /** Allt som hör till väggens tid [u], en gång per tid (starten och bakgrunden ritar samma). */
    private fun prepare(u: Long) {
        if (u == preparedAt && !dirty) return
        preparedAt = u
        dirty = false
        pose(u)
        ready = ranges(u)
        if (!ready) return
        light(u)
        fog()
        updateTiles(u)
    }

    /**
     * Omslagens storlek: så stora att det finns en egen film till varje plats
     * som kan synas samtidigt. Med många filmer blir det den vanliga storleken;
     * med få blir omslagen större och färre.
     */
    private fun fitTiles() {
        val usable = (count - failedCount).coerceAtLeast(1)
        var k = 1f
        for (attempt in 0 until 14) {
            setTileSize(k)
            if (mostNeeded() + RESERVE <= usable) break
            k *= 1.08f
        }
        dirty = true
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
            pose(u)
            if (ranges(u)) most = max(most, keptCount())
        }
        return most
    }

    private fun keptCount(): Int {
        var sum = 0
        for (i in 0 until rowCount) sum += (keepTo[i] - keepFrom[i] + 1).coerceAtLeast(0)
        return sum
    }

    // --- Kameran -----------------------------------------------------------------------

    /**
     * Kameran [u] ms efter att väggen tänts: nära i början, sedan glider den
     * sakta rakt bakåt. Under klippen trycker den sig fram, och vid slaget
     * studsar den tillbaka. Vinkeln är densamma hela tiden.
     */
    private fun pose(u: Long) {
        val p = 1f - exp(-u / SETTLE_MS)
        zoom = (ZOOM_FROM + (ZOOM_TO - ZOOM_FROM) * p) * (1f + FLURRY_PUSH * impact(u))
        val s = zoom
        homography(0f, 0f, 0f, s * rot[0], s * rot[3], s * rot[6], s * rot[1], s * rot[4], s * rot[7], wall)
        invert(wall, wallInverse)
    }

    /**
     * Klippens tryck: växer allt fortare fram till slaget, och studsar sedan
     * tillbaka förbi vila och lägger sig — som en stöt.
     */
    private fun impact(u: Long): Float {
        if (!cinematic || u < FLURRY_FROM_MS) return 0f
        if (u < SLAM_MS) {
            val x = (u - FLURRY_FROM_MS) / (SLAM_MS - FLURRY_FROM_MS).toFloat()
            return x * x * x
        }
        val t = (u - SLAM_MS).toFloat()
        return exp(-t / 260f) * cos(t / 900f * TAU)
    }

    /** Vinkeln: lutning runt x, sidvridning runt y och rotation runt z, i grader. */
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
    ) = planeMatrix(width / 2f, height / 2f, depth, ox, oy, oz, ax, ay, az, bx, by, bz, out)

    /** Väggens punkt (x, y) på skärmen, i [projX] och [projY]. */
    private fun project(x: Float, y: Float) {
        val w = wall[6] * x + wall[7] * y + wall[8]
        projX = (wall[0] * x + wall[1] * y + wall[2]) / w
        projY = (wall[3] * x + wall[4] * y + wall[5]) / w
    }

    // --- Vad som syns ------------------------------------------------------------------

    /**
     * Platserna som syns på hela skärmen, rad för rad: de som ska få omslag
     * (bilden och en smal kant runt) och de som får behålla sina (en bredare
     * kant). Skillnaden gör att en plats i kanten inte byter omslag fram och tillbaka.
     */
    private fun ranges(u: Long): Boolean {
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

    /** Skärmen och [margin] runt, baklänges till väggen: en fyrhörning. */
    private fun viewQuad(margin: Float, xs: FloatArray, ys: FloatArray): Boolean {
        val m = wallInverse
        for (i in 0 until 4) {
            val sx = if (i == 0 || i == 3) -margin else width + margin
            val sy = if (i < 2) -margin else height + margin
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
        return phase + direction * speed * (driftTime(u) / 1000f + kick)
    }

    /**
     * Radernas egen tid: under klippen rusar de allt fortare, efter slaget
     * står de nästan still en stund och kommer sedan i gång igen.
     */
    private fun driftTime(u: Long): Float {
        val t = u.toFloat()
        if (!cinematic || t <= FLURRY_FROM_MS) return t
        val rush = (SLAM_MS - FLURRY_FROM_MS).toFloat()
        if (t <= SLAM_MS) {
            val x = (t - FLURRY_FROM_MS) / rush
            return t + DRIFT_RUSH * rush * x * x * x / 3f
        }
        val atSlam = SLAM_MS + DRIFT_RUSH * rush / 3f
        val calm = DRIFT_CALM_MS
        val since = t - SLAM_MS
        if (since <= calm) {
            // En fjärdedels fart direkt efter slaget, mjukt tillbaka till full fart.
            val x = since / calm
            return atSlam + 0.25f * since + 0.75f * calm * (x * x * x - x * x * x * x / 2f)
        }
        return atSlam + 0.25f * calm + 0.75f * calm * 0.5f + (since - calm)
    }

    private fun tileLeft(tile: Tile, u: Long): Float = rowLeft(tile.row, u) + tile.col * pitchX

    private fun tileTop(tile: Tile): Float = tile.row * pitchY - tileH / 2f

    /** Hela omslaget syns i startens bild (innanför biofälten), minst [inset] innanför kanterna. */
    private fun fullyInside(tile: Tile, u: Long, inset: Float): Boolean {
        val bar = ((height - width / LETTERBOX) / 2f).coerceAtLeast(0f)
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

    /**
     * Ljuset på väggen: från mitten av bilden vandrar det sakta runt. Under
     * klippen drar det ihop sig, och vid slaget slår det upp över hela väggen.
     */
    private fun light(u: Long) {
        val seconds = u / 1000f
        lightX = LIGHT_SWING_X * unit * sin(seconds / 13f * TAU)
        lightY = LIGHT_SWING_Y * unit * sin(seconds / 9f * TAU)
        poolRadius = POOL_RADIUS
        poolDark = POOL_DARK
        if (cinematic && u >= FLURRY_FROM_MS) {
            if (u < SLAM_MS) {
                val x = smoothstep(FLURRY_FROM_MS.toFloat(), SLAM_MS.toFloat(), u.toFloat())
                poolRadius = POOL_RADIUS + (POOL_TIGHT - POOL_RADIUS) * x
                poolDark = POOL_DARK + (POOL_DARK_TIGHT - POOL_DARK) * x
            } else {
                val burst = smoothstep(SLAM_MS.toFloat(), SLAM_MS + 140f, u.toFloat())
                val fade = exp(-(u - SLAM_MS) / POOL_SETTLE_MS)
                poolRadius = POOL_TIGHT + (POOL_RADIUS + (POOL_WIDE - POOL_RADIUS) * fade - POOL_TIGHT) * burst
                poolDark = POOL_DARK_TIGHT + (POOL_DARK + (POOL_DARK_WIDE - POOL_DARK) * fade - POOL_DARK_TIGHT) * burst
            }
        }
    }

    /** Djupet: väggen mörknar bort mot det som ligger längst från kameran. */
    private fun fog() {
        val gx = rot[6]
        val gy = rot[7]
        val length = sqrt(gx * gx + gy * gy)
        if (length < 1e-4f) {
            fogOk = false
            return
        }
        val nx = gx / length
        val ny = gy / length
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        for (i in 0 until 4) {
            val p = quadX[i] * nx + quadY[i] * ny
            low = min(low, p)
            high = max(high, p)
        }
        fogOk = high - low > 1f
        fogStartX = nx * low
        fogStartY = ny * low
        fogEndX = nx * high
        fogEndY = ny * high
    }

    // --- Omslagen på platserna ---------------------------------------------------------

    private fun find(row: Int, col: Int): Tile? {
        for (i in tiles.indices) {
            val tile = tiles[i]
            if (tile.row == row && tile.col == col) return tile
        }
        return null
    }

    private fun updateTiles(u: Long) {
        // Platser som lämnat bilden lämnar tillbaka sina omslag.
        var i = tiles.size - 1
        while (i >= 0) {
            val tile = tiles[i]
            val row = tile.row - rowFirst
            val inside = row in 0 until rowCount && tile.col >= keepFrom[row] && tile.col <= keepTo[row]
            if (!inside) {
                release(tile.poster, u)
                release(tile.previous, u)
                tiles.removeAt(i)
                spare.add(tile)
            }
            i--
        }
        // Nya platser i bilden. De närmast mitten tonas fram först.
        for (row in 0 until rowCount) {
            val r = rowFirst + row
            for (col in enterFrom[row]..enterTo[row]) {
                if (find(r, col) != null) continue
                val tile = if (spare.isEmpty()) Tile() else spare.removeAt(spare.size - 1)
                tile.reset(r, col)
                tile.createdAt = u
                project(tileLeft(tile, u) + tileW / 2f, r * pitchY)
                tile.dist = hypot(projX - width / 2f, projY - height / 2f) / (width / 2f)
                tile.igniteAt = if (u < BLOOM_UNTIL_MS) {
                    (IGNITE_DELAY_MS + tile.dist * IGNITE_SPREAD_MS + hash(r, col) * IGNITE_JITTER_MS).toLong()
                } else {
                    // Kommer in från kanten: sitter redan på plats.
                    u - IGNITE_MS.toLong()
                }
                // Mitt i klippen: med från det slag som är nu, inte alla som redan varit.
                tile.beats = beatsPassed(tile, u)
                // Närmast mitten först: de får de bästa filmerna.
                var at = fresh.size
                while (at > 0 && fresh[at - 1].dist > tile.dist) at--
                fresh.add(at, tile)
            }
        }
        for (k in fresh.indices) tiles.add(fresh[k])
        fresh.clear()
        // Omslag till platserna som väntar. Kom det sent tonas det fram nu.
        for (k in tiles.indices) {
            val tile = tiles[k]
            if (tile.poster >= 0) continue
            val poster = take(shared = true)
            if (poster < 0) break
            tile.poster = poster
            if (tile.createdAt < u && tile.igniteAt < u) tile.igniteAt = u
        }
        // Klippen: vid varje slag byter omslagen film, i en våg från mitten.
        // Vid själva slaget byter alla på en gång.
        var moving = false
        for (k in tiles.indices) {
            val tile = tiles[k]
            if (tile.poster < 0) continue
            if (tile.previous >= 0 && u >= tile.cutAt + tile.cutMs) {
                release(tile.previous, u)
                tile.previous = -1
            }
            if (cinematic && u < SLAM_MS + 200L) {
                val passed = beatsPassed(tile, u)
                if (passed > tile.beats) {
                    tile.beats = passed
                    cut(tile, u, cutLength(passed - 1))
                }
            }
            if (tile.previous >= 0 || u < tile.igniteAt + IGNITE_MS.toLong()) moving = true
        }
        // Efter slaget, om starten fortsätter: då och då byter ett omslag film.
        if (!ambient && u >= nextSwapAt) {
            nextSwapAt = u + (SWAP_EVERY_MS * (0.7f + 0.6f * hash(u.toInt(), 11))).toLong()
            pick(u)?.let { cut(it, u, SWAP_FADE_MS) }
        }
        busy = moving
    }

    /** Hur många av klippens slag som nått omslaget vid tiden [u]. Slaget självt räknas som det sista. */
    private fun beatsPassed(tile: Tile, u: Long): Int {
        if (!cinematic) return 0
        var n = 0
        for (k in BEATS.indices) {
            val at = if (k == BEATS.lastIndex) BEATS[k].toFloat() else BEATS[k] + tile.dist * beatSpread(k)
            if (u >= at) n = k + 1 else break
        }
        return n
    }

    /** Hur fort vågen går ut från mitten vid slag [k]: snabbare ju tätare slagen kommer. */
    private fun beatSpread(k: Int): Float {
        val gap = if (k + 1 < BEATS.size) (BEATS[k + 1] - BEATS[k]).toFloat() else 0f
        return min(BEAT_SPREAD_MS, gap * 0.7f)
    }

    /** Övertoningen vid slag [k]: kortare ju tätare slagen kommer. Slaget självt är ett rent klipp. */
    private fun cutLength(k: Int): Float {
        if (k >= BEATS.lastIndex) return SLAM_CUT_MS
        val gap = (BEATS[k + 1] - BEATS[k]).toFloat()
        return (gap * 0.5f).coerceIn(60f, 170f)
    }

    /**
     * Omslaget byter till en film som inte syns någon annanstans, och tonar
     * över i den på [fadeMs]. Den gamla räknas som synlig tills den tonat bort.
     * Finns ingen ledig film står den kvar.
     */
    private fun cut(tile: Tile, u: Long, fadeMs: Float) {
        if (tile.previous >= 0) {
            release(tile.previous, u)
            tile.previous = -1
        }
        if (fadeMs <= HARD_CUT_MS) {
            // Ett rent klipp: den gamla filmen syns inte längre, så den kan lämnas
            // tillbaka först — då räcker filmerna till att alla byter på en gång.
            val old = tile.poster
            release(old, u)
            val next = take(shared = false)
            tile.poster = if (next >= 0) next else old.also { if (it >= 0) users[it]++ }
            return
        }
        val next = take(shared = false)
        if (next < 0) return
        tile.previous = tile.poster
        tile.poster = next
        tile.cutAt = u
        tile.cutMs = fadeMs
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

    private fun release(poster: Int, u: Long) {
        if (poster < 0 || poster >= count) return
        users[poster] = (users[poster] - 1).coerceAtLeast(0)
        if (users[poster] == 0) freeSince[poster] = u
    }

    /** Ett omslag som syns helt och står still, gärna nära mitten. */
    private fun pick(u: Long): Tile? {
        val inset = width * 0.02f
        var best: Tile? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in tiles.indices) {
            val tile = tiles[i]
            if (tile.poster < 0 || tile.previous >= 0) continue
            if (u < tile.igniteAt + IGNITE_MS.toLong()) continue
            if (!fullyInside(tile, u, inset)) continue
            project(tileLeft(tile, u) + tileW / 2f, tile.row * pitchY)
            val distance = hypot(projX - width / 2f, projY - height / 2f) / width
            val score = hash(tile.row * 31 + tile.col, u.toInt()) - distance * 1.6f
            if (score > bestScore) {
                bestScore = score
                best = tile
            }
        }
        return best
    }

    // --- Det som ritas -----------------------------------------------------------------

    private fun DrawScope.poster(index: Int, alpha: Float, filter: ColorFilter?) {
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
            colorFilter = filter,
            filterQuality = FilterQuality.Medium,
        )
    }

    /**
     * Alla omslag genom en och samma projektion, bara flyttade. Ett omslag som
     * tonas fram växer lite in på sin plats; ett som byter film tonar över.
     */
    private fun DrawScope.drawTiles(u: Long, filter: ColorFilter?) {
        val center = Offset(tileW / 2f, tileH / 2f)
        projected(wall) {
            for (i in tiles.indices) {
                val tile = tiles[i]
                if (tile.poster < 0 || u < tile.igniteAt) continue
                val ignite = (u - tile.igniteAt) / IGNITE_MS
                translate(tileLeft(tile, u), tileTop(tile)) {
                    if (ignite < 1f) {
                        val q = ignite.coerceIn(0f, 1f)
                        val grow = 1f + IGNITE_GROW * (1f - easeOutCubic(q))
                        scale(grow, grow, pivot = center) {
                            poster(tile.poster, smoothstep(0f, 1f, q), filter)
                        }
                    } else if (tile.previous >= 0) {
                        val fade = ((u - tile.cutAt) / tile.cutMs).coerceIn(0f, 1f)
                        poster(tile.previous, 1f, filter)
                        poster(tile.poster, fade, filter)
                    } else {
                        poster(tile.poster, 1f, filter)
                    }
                }
            }
        }
    }

    /** Ljuset som vandrar över väggen i starten: ljust där det är, mörkare längre bort. */
    private fun DrawScope.drawPool() {
        val radius = poolRadius * unit
        projected(wall) {
            drawRect(
                Brush.radialGradient(
                    0f to Color.Transparent,
                    0.5f to Color.Black.copy(alpha = poolDark * 0.35f),
                    1f to Color.Black.copy(alpha = poolDark),
                    center = Offset(lightX, lightY),
                    radius = radius,
                ),
                topLeft = Offset(viewLeft, viewTop),
                size = Size(viewRight - viewLeft, viewBottom - viewTop),
            )
        }
    }

    /** Djupet: det som ligger längst bort tonar in i [color]. */
    private fun DrawScope.drawFog(color: Color, strength: Float) {
        if (!fogOk) return
        projected(wall) {
            drawRect(
                Brush.linearGradient(
                    0f to Color.Transparent,
                    0.3f to Color.Transparent,
                    1f to color.copy(alpha = strength),
                    start = Offset(fogStartX, fogStartY),
                    end = Offset(fogEndX, fogEndY),
                ),
                topLeft = Offset(viewLeft, viewTop),
                size = Size(viewRight - viewLeft, viewBottom - viewTop),
            )
        }
    }
}

/** En plats i väggen: rad, kolumn och omslaget som sitter där. */
private class Tile {
    var row = 0
    var col = 0
    /** Omslaget, eller -1 medan platsen väntar på ett. */
    var poster = -1
    /** Filmen den tonar över från efter ett klipp, eller -1. */
    var previous = -1
    var cutAt = 0L
    var cutMs = 1f
    /** Hur många av klippens slag som nått den. */
    var beats = 0
    /** När platsen kom in i bild (väggens tid). */
    var createdAt = 0L
    /** När omslaget börjar tonas fram (väggens tid). */
    var igniteAt = 0L
    /** Avståndet till bildens mitt när platsen kom in, i halva skärmbredder. */
    var dist = 0f

    fun reset(row: Int, col: Int) {
        this.row = row
        this.col = col
        poster = -1
        previous = -1
        cutAt = 0L
        cutMs = 1f
        beats = 0
        createdAt = 0L
        igniteAt = 0L
        dist = 0f
    }
}

// --- Väggen i appen ------------------------------------------------------------------

/**
 * Laddar omslagen och driver väggens tid. I starten går den i full fart.
 * Sedan glider den sakta medan man inte trycker på något, och står still
 * medan man navigerar och medan video spelas: då kostar den ingenting.
 */
@Composable
internal fun PosterWallDriver(state: AppState, wall: PosterWall) {
    // Filmerna: de sparade från förra gången direkt, annars de som räknas fram nu.
    val saved by produceState(emptyList<IntroReelItem>()) {
        value = withContext(Dispatchers.IO) { IntroReelStore.load() }
    }
    var reel by remember { mutableStateOf<List<IntroReelItem>?>(null) }
    val candidate = saved.ifEmpty { state.introReel }
    LaunchedEffect(candidate) {
        if (reel == null && candidate.isNotEmpty()) {
            // Varje film en gång, också om listan är från en äldre version av appen.
            val unique = candidate
                .distinctBy { it.poster }
                .distinctBy { item -> item.title.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { item.poster } }
                .take(MAX_POSTERS)
            wall.setReel(unique.size)
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
                    wall.posterLoaded(index, image)
                }
            }
        }
    }
    LaunchedEffect(wall) {
        var speed = 1f
        var last = -1L
        var published = 0L
        while (true) {
            wall.ambient = state.introDone
            if (!wall.lit) {
                // Tänds så fort omslagen räcker; dröjer de, kollas det då och då.
                if (wall.lightingSoon(nowMs())) withFrameMillis { wall.tryLight(nowMs()) } else delay(250)
                continue
            }
            val idle = nowMs() - wall.lastInput >= IDLE_MS
            val target = when {
                !state.introDone -> 1f
                state.nowPlaying != null -> 0f
                // Ett omslag som tonas fram (det laddades sent) får tonas klart.
                wall.busy -> AMBIENT_SPEED
                idle -> AMBIENT_SPEED
                else -> 0f
            }
            if (target <= 0f) {
                // Man trycker på fjärren, eller video spelas: väggen står still direkt,
                // och ingen bild ritas om förrän den ska röra sig igen.
                speed = 0f
                last = -1L
                delay(120)
                continue
            }
            withFrameMillis { frame ->
                val dt = if (last < 0) 16L else (frame - last).coerceIn(0L, 100L)
                last = frame
                // Kommer i gång mjukt.
                speed = if (target > speed) min(target, speed + dt / EASE_MS) else max(target, speed - dt / EASE_MS)
                wall.advance(dt * speed.toDouble())
                // Som bakgrund räcker tjugofyra bilder i sekunden.
                if (!state.introDone || frame - published >= AMBIENT_FRAME_MS) {
                    wall.publish()
                    published = frame
                }
            }
        }
    }
}

/**
 * Väggen som bakgrund bakom sidorna: mörk, dämpad och tonad mot temats
 * färg. Tänds väggen först nu (första starten) tonas omslagen fram här.
 */
@Composable
internal fun PosterWallBackdrop(wall: PosterWall, shown: Boolean, quiet: Boolean, modifier: Modifier = Modifier) {
    val tint = LocalPalette.current.background
    Spacer(modifier.drawBehind { if (shown) wall.drawBackdrop(this, tint, quiet) })
}

// --- Omslag som tonas fram ------------------------------------------------------------

/**
 * Omslagen tonas fram i en våg när en sida visas, som väggen i starten: de
 * som kommer fram den första stunden, i den ordning de kommer. Det som
 * rullas fram senare står redan på plats.
 */
internal class CoverEntrance {
    private var first = -1L
    private var count = 0

    /** Ordningen för ett omslag som kommer fram nu, eller -1 om sidan redan stått en stund. */
    fun next(): Int {
        val now = nowMs()
        if (first < 0) first = now
        if (now - first > ENTRANCE_WINDOW_MS) return -1
        return count++
    }
}

internal val LocalCoverEntrance = compositionLocalOf<CoverEntrance?> { null }

/**
 * Kortet tonas fram och glider upp lite på plats när sidan visas. Inget
 * roteras, och genomskinligheten läggs direkt på det som ritas, utan
 * mellanlager: det kostar nästan ingenting.
 */
@Composable
internal fun Modifier.coverEntrance(): Modifier {
    val entrance = LocalCoverEntrance.current ?: return this
    val order = remember(entrance) { entrance.next() }
    if (order < 0) return this
    val progress = remember(entrance) { Animatable(0f) }
    LaunchedEffect(entrance) {
        delay(min(order, ENTRANCE_MAX_STEPS) * ENTRANCE_STAGGER_MS)
        progress.animateTo(1f, tween(ENTRANCE_MS, easing = ENTRANCE_EASING))
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * ENTRANCE_RISE.toPx()
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

// --- Hjälp -----------------------------------------------------------------------------

/** Ritar [block] genom matrisen [m] (3×3 radvis, med perspektiv), och återställer sedan. */
internal inline fun DrawScope.projected(m: FloatArray, block: DrawScope.() -> Unit) {
    val canvas = drawContext.canvas
    canvas.save()
    concatPerspective(m)
    block()
    canvas.restore()
}

/**
 * Matrisen för ett plan sett av en kamera [d] framför skärmens mitt ([cx], [cy]):
 * punkten (x, y) i planet ligger i kamerans rum på o + x·a + y·b.
 */
internal fun planeMatrix(
    cx: Float, cy: Float, d: Float,
    ox: Float, oy: Float, oz: Float,
    ax: Float, ay: Float, az: Float,
    bx: Float, by: Float, bz: Float,
    out: FloatArray,
) {
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

/** Monotona millisekunder: för väntan och för när man senast tryckte. */
internal fun nowMs(): Long = System.nanoTime() / 1_000_000L

internal fun smoothstep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun easeOutCubic(x: Float): Float = 1f - (1f - x).pow(3)

internal fun easeInOutCubic(x: Float): Float = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f

/** Ett fast slumptal 0–1 för [i] och egenskap [k]: samma varje bild, utan att något skapas. */
private fun hash(i: Int, k: Int): Float {
    var x = i * 374_761_393 + k * 668_265_263
    x = (x xor (x ushr 13)) * 1_274_126_177
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16_777_215f
}

/**
 * Klippens slag, i väggens tid: först glest, sedan allt tätare, och sist
 * slaget självt. Räknas fram en gång.
 */
private val BEATS: LongArray = run {
    val list = ArrayList<Long>()
    var at = FLURRY_FROM_MS.toFloat()
    var gap = BEAT_FIRST_GAP_MS
    while (at < SLAM_MS - BEAT_MIN_GAP_MS) {
        list += at.toLong()
        at += gap
        gap = max(BEAT_MIN_GAP_MS, gap * BEAT_SHRINK)
    }
    list += SLAM_MS
    list.toLongArray()
}

/** Bakgrunden: omslagen mörka och med hälften av färgen kvar. */
private val backdropFilter: ColorFilter = run {
    val s = BACKDROP_SATURATION
    val b = BACKDROP_BRIGHTNESS
    val lr = 0.2126f * (1f - s)
    val lg = 0.7152f * (1f - s)
    val lb = 0.0722f * (1f - s)
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                (lr + s) * b, lg * b, lb * b, 0f, 0f,
                lr * b, (lg + s) * b, lb * b, 0f, 0f,
                lr * b, lg * b, (lb + s) * b, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

internal const val TAU = 6.2831855f
internal const val DEG = 0.017453292f

private val TILE_BACK = Color(0xFF0E1116)

/** Omslagen: höjd och mellanrum som del av skärmens höjd, och hur skarpt de laddas. */
private const val TILE_H = 0.31f
private const val TILE_GAP = 0.026f
private const val POSTER_SIDE = 460
private const val MAX_POSTERS = 64
private const val LOAD_PARALLEL = 6
/** Väggen tänds när så många omslag laddats (eller alla, eller efter en stund). */
private const val START_LOADED = 24
private const val WALL_WAIT_MS = 1_800L
/** Omslag i reserv utöver de som syns, till klippen. */
private const val RESERVE = 8
private const val MAX_ROWS = 24
private val SAMPLE_TIMES = longArrayOf(0L, 1_500L, 3_000L, 4_000L, 4_600L, 6_000L, 9_000L, 14_000L, 20_000L, 60_000L)

/** Kameran: avståndet i skärmbredder, den fasta vinkeln (grader), och hur den glider bakåt. */
private const val CAMERA_DISTANCE = 1.5f
private const val PITCH = -9f
private const val YAW = -15f
private const val ROLL = -11.5f
private const val SETTLE_MS = 3_400f
private const val ZOOM_FROM = 1.26f
private const val ZOOM_TO = 1.0f

/** Radernas fart: del av skärmens höjd per sekund (väggens tid). Lite fartigare i början. */
private val ROW_SPEEDS = floatArrayOf(0.025f, 0.020f, 0.029f, 0.022f, 0.027f, 0.019f)
private const val KICK = 1.3f
private const val KICK_MS = 1_500f

/** Startens biofält (för vilka omslag som syns helt), och kanterna runt bilden (del av bredden). */
internal const val LETTERBOX = 2.39f
private const val ENTER_MARGIN = 0.015f
private const val KEEP_MARGIN = 0.05f

/** När väggen tänds: omslagen tonas fram från mitten och utåt och växer lite in på plats. */
private const val IGNITE_DELAY_MS = 250f
private const val IGNITE_SPREAD_MS = 1_250f
private const val IGNITE_JITTER_MS = 160f
private const val IGNITE_MS = 900f
private const val IGNITE_GROW = 0.05f
private const val BLOOM_UNTIL_MS = 2_800L

/**
 * Klippen, som i en filmtrailer: väggen byter filmer i vågor från mitten,
 * allt tätare, och vid slaget byter alla på en gång och allt stannar.
 */
internal const val FLURRY_FROM_MS = 3_000L
internal const val SLAM_MS = 4_700L
private const val BEAT_FIRST_GAP_MS = 330f
private const val BEAT_MIN_GAP_MS = 110f
private const val BEAT_SHRINK = 0.82f
private const val BEAT_SPREAD_MS = 320f
/** Vid slaget: ett rent klipp för alla. Kortare övertoning än så räknas som rent klipp. */
private const val SLAM_CUT_MS = 0f
private const val HARD_CUT_MS = 30f
private const val FLURRY_PUSH = 0.10f
private const val DRIFT_RUSH = 1.8f
private const val DRIFT_CALM_MS = 1_600f

/** Efter slaget, om starten fortsätter: ett omslag i taget byter film. */
private const val SWAP_AFTER_SLAM_MS = 1_400L
private const val SWAP_EVERY_MS = 1_100f
private const val SWAP_FADE_MS = 650f

/** Ljuset som vandrar i starten: drar ihop sig under klippen och slår upp vid slaget. Och djupet. */
private const val LIGHT_SWING_X = 0.55f
private const val LIGHT_SWING_Y = 0.28f
private const val POOL_RADIUS = 1.3f
private const val POOL_DARK = 0.36f
private const val POOL_TIGHT = 0.95f
private const val POOL_DARK_TIGHT = 0.52f
private const val POOL_WIDE = 2.6f
private const val POOL_DARK_WIDE = 0.12f
private const val POOL_SETTLE_MS = 1_100f
private const val INTRO_FOG = 0.50f
private const val BACKDROP_FOG = 0.55f

/** Bakgrunden i appen: hur mörk och hur färgstark, hur fort, och när den står still. */
private const val BACKDROP_BRIGHTNESS = 0.36f
private const val BACKDROP_SATURATION = 0.5f
private const val QUIET_DIM = 0.5f
private const val AMBIENT_SPEED = 0.45f
private const val AMBIENT_FRAME_MS = 41L
private const val IDLE_MS = 1_500L
private const val EASE_MS = 1_400f

/** Korten som tonas fram när en sida visas. */
private const val ENTRANCE_WINDOW_MS = 900L
private const val ENTRANCE_STAGGER_MS = 30L
private const val ENTRANCE_MAX_STEPS = 20
private const val ENTRANCE_MS = 520
private val ENTRANCE_RISE = 18.dp
private val ENTRANCE_EASING = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

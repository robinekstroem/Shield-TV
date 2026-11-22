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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
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
 * Filmväggen: omslag från de häftigaste filmerna man har — det som trendar,
 * Marvel och de andra filmserierna, nya filmer med högt betyg — på en stor
 * vägg som står snett i djupet, i riktig 3D. Starten visar den i full
 * styrka. Sedan blir den kvar som appens bakgrund, mörk och dämpad, och
 * glider sakta medan man inte trycker på något.
 *
 * Samma film syns aldrig två gånger samtidigt: väggen räknar ut vilka
 * platser som syns och ger var och en ett eget omslag. Allt ritas i en
 * enda ritning med en egen perspektivprojektion — inget mäts om och inget
 * sätts ihop på nytt medan den rör sig. Ingen oskärpa och ingen glans.
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
    private var nextFlipAt = 0L

    /** Väggen har tänts. */
    var lit = false
        private set

    /** Efter starten: väggen är bakgrund, och omslagen byts inte längre. */
    var ambient = false

    /** Senaste knapptryckningen eller musrörelsen, i [nowMs]. */
    var lastInput = 0L

    /** Något omslag håller på att vändas: väggen ska inte stanna mitt i. */
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
    private val h = FloatArray(9)
    private var projX = 0f
    private var projY = 0f
    private var lightX = 0f
    private var lightY = 0f
    private var fogOk = false
    private var fogStartX = 0f
    private var fogStartY = 0f
    private var fogEndX = 0f
    private var fogEndY = 0f

    // --- Platserna ---------------------------------------------------------------------
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
    private var preparedAt = Long.MIN_VALUE
    private var dirty = true
    private var ready = false

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
            elapsed = 0.0
            clock.longValue = 0L
            nextFlipAt = LIVE_FROM_MS
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
            // Sidor med mycket text (tablån, kanalerna, inställningarna): ännu mörkare.
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
                release(tile.flipTo, u)
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
        scheduleLife(u)
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
     * Kameran [u] ms efter att väggen tänts: nära och snett i början, sedan
     * glider den sakta bakåt och rätar upp sig, och vaggar lite hela tiden.
     */
    private fun pose(u: Long) {
        val p = 1f - exp(-u / SETTLE_MS)
        val seconds = u / 1000f
        zoom = ZOOM_FROM + (ZOOM_TO - ZOOM_FROM) * p
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
        return phase + direction * speed * (u / 1000f + kick)
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

    /** Ljuset på väggen: från mitten av bilden vandrar det sakta runt. */
    private fun light(u: Long) {
        val seconds = u / 1000f
        lightX = LIGHT_SWING_X * unit * sin(seconds / 13f * TAU)
        lightY = LIGHT_SWING_Y * unit * sin(seconds / 9f * TAU)
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
                release(tile.flipTo, u)
                tiles.removeAt(i)
                spare.add(tile)
            }
            i--
        }
        // Vändningar som är klara: det nya omslaget sitter kvar.
        var moving = false
        for (k in tiles.indices) {
            val tile = tiles[k]
            if (tile.flipAt >= 0 && u >= tile.flipAt + FLIP_MS.toLong()) {
                release(tile.poster, u)
                tile.poster = tile.flipTo
                tile.flipTo = -1
                tile.flipAt = -1L
            }
            if (tile.flipAt >= 0 || (tile.poster >= 0 && u < tile.igniteAt + IGNITE_MS.toLong())) moving = true
        }
        // Nya platser i bilden. Vågen går utåt från mitten, så de närmast mitten vänds först.
        for (row in 0 until rowCount) {
            val r = rowFirst + row
            for (col in enterFrom[row]..enterTo[row]) {
                if (find(r, col) != null) continue
                val tile = if (spare.isEmpty()) Tile() else spare.removeAt(spare.size - 1)
                tile.reset(r, col)
                tile.createdAt = u
                project(tileLeft(tile, u) + tileW / 2f, r * pitchY)
                val dx = projX - width / 2f
                val dy = projY - height / 2f
                tile.side = if (dx < 0f) -1f else 1f
                tile.lift = hypot(dx, dy) / (width / 2f)
                tile.igniteAt = if (u < BLOOM_UNTIL_MS) {
                    (IGNITE_DELAY_MS + tile.lift * IGNITE_SPREAD_MS + hash(r, col) * IGNITE_JITTER_MS).toLong()
                } else {
                    // Kommer in från kanten: sitter redan på plats.
                    u - IGNITE_MS.toLong()
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
            if (tile.createdAt < u && tile.igniteAt < u) tile.igniteAt = u
            moving = true
        }
        busy = moving
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

    /** I starten: då och då vänds ett omslag och en annan film kommer fram. */
    private fun scheduleLife(u: Long) {
        if (ambient || u < nextFlipAt) return
        nextFlipAt = u + (FLIP_EVERY_MS * (0.7f + 0.6f * hash(u.toInt(), 11))).toLong()
        val tile = pick(u) ?: return
        val poster = take(shared = false)
        if (poster < 0) return
        tile.flipAt = u
        tile.flipTo = poster
        tile.flipDir = if (hash(u.toInt(), 12) < 0.5f) -1f else 1f
        busy = true
    }

    /** Ett omslag som syns helt och står still, gärna nära mitten. */
    private fun pick(u: Long): Tile? {
        val inset = width * 0.02f
        var best: Tile? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in tiles.indices) {
            val tile = tiles[i]
            if (tile.poster < 0 || tile.flipAt >= 0) continue
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

    private fun DrawScope.drawTiles(u: Long, filter: ColorFilter?) {
        raised.clear()
        // Omslagen som sitter på väggen: en och samma projektion, bara flyttade.
        projected(wall) {
            for (i in tiles.indices) {
                val tile = tiles[i]
                if (tile.poster < 0 || u < tile.igniteAt) continue
                if (u < tile.igniteAt + IGNITE_MS.toLong() || tile.flipAt >= 0) {
                    tile.lift = liftOf(tile, u)
                    // Det närmast kameran ritas sist.
                    var at = raised.size
                    while (at > 0 && raised[at - 1].lift > tile.lift) at--
                    raised.add(at, tile)
                    continue
                }
                translate(tileLeft(tile, u), tileTop(tile)) {
                    poster(tile.poster, 1f, filter)
                }
            }
        }
        for (i in raised.indices) drawRaised(raised[i], u, filter)
        raised.clear()
    }

    private fun liftOf(tile: Tile, u: Long): Float {
        val ignite = (u - tile.igniteAt) / IGNITE_MS
        if (ignite < 1f) return IGNITE_LIFT * (1f - easeOutCubic(ignite.coerceIn(0f, 1f)))
        val f = ((u - tile.flipAt) / FLIP_MS).coerceIn(0f, 1f)
        return FLIP_LIFT * sin(PI_F * f)
    }

    /**
     * Ett omslag som vänds: fram ur mörkret när väggen tänds, eller till en
     * annan film. Det är mörkare när det står på kant, som i verkligheten.
     */
    private fun DrawScope.drawRaised(tile: Tile, u: Long, filter: ColorFilter?) {
        var shown = tile.poster
        val theta: Float
        var alpha = 1f
        val ignite = (u - tile.igniteAt) / IGNITE_MS
        if (ignite < 1f) {
            val q = ignite.coerceIn(0f, 1f)
            theta = tile.side * IGNITE_TURN * (1f - easeOutCubic(q))
            alpha = smoothstep(0f, 0.4f, q)
        } else {
            val f = ((u - tile.flipAt) / FLIP_MS).coerceIn(0f, 1f)
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
        val area = Size(tileW.toFloat(), tileH.toFloat())
        projected(h) {
            poster(shown, alpha, filter)
            if (shade > 0.01f) drawRect(Color.Black, size = area, alpha = shade * alpha)
        }
    }

    /** Ljuset som vandrar över väggen i starten: ljust där det är, mörkare längre bort. */
    private fun DrawScope.drawPool() {
        val radius = POOL_RADIUS * unit
        projected(wall) {
            drawRect(
                Brush.radialGradient(
                    0f to Color.Transparent,
                    0.5f to Color.Black.copy(alpha = POOL_DARK * 0.35f),
                    1f to Color.Black.copy(alpha = POOL_DARK),
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
    /** När platsen kom in i bild (väggens tid). */
    var createdAt = 0L
    /** När omslaget börjar vändas fram (väggens tid). */
    var igniteAt = 0L
    /** Åt vilket håll det vänds fram: -1 till vänster om mitten, 1 till höger. */
    var side = 1f
    /** En vändning till ett annat omslag: när den började (-1: ingen), och till vilket. */
    var flipAt = -1L
    var flipTo = -1
    var flipDir = 1f
    /** Hur nära kameran den är just nu, för ritordningen. Också avståndet till mitten när den skapas. */
    var lift = 0f

    fun reset(row: Int, col: Int) {
        this.row = row
        this.col = col
        poster = -1
        createdAt = 0L
        igniteAt = 0L
        side = 1f
        flipAt = -1L
        flipTo = -1
        flipDir = 1f
        lift = 0f
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
            if (!wall.lit) {
                // Tänds så fort omslagen räcker; dröjer de, kollas det då och då.
                if (wall.lightingSoon(nowMs())) withFrameMillis { wall.tryLight(nowMs()) } else delay(250)
                continue
            }
            wall.ambient = state.introDone
            val target = when {
                !state.introDone -> 1f
                // Ett omslag som vänds får vändas klart.
                wall.busy -> AMBIENT_SPEED
                state.nowPlaying != null -> 0f
                nowMs() - wall.lastInput < IDLE_MS -> 0f
                else -> AMBIENT_SPEED
            }
            if (speed <= 0f && target <= 0f) {
                // Stilla: ingen bild ritas om förrän väggen ska röra sig igen.
                speed = 0f
                last = -1L
                delay(150)
                continue
            }
            withFrameMillis { frame ->
                val dt = if (last < 0) 16L else (frame - last).coerceIn(0L, 100L)
                last = frame
                // Kommer i gång mjukt, stannar fort när man trycker.
                val rate = dt / (if (target <= 0f) STOP_MS else EASE_MS)
                speed = if (target > speed) min(target, speed + rate) else max(target, speed - rate)
                wall.advance(dt * speed.toDouble())
                // Som bakgrund räcker trettio bilder i sekunden.
                if (!state.introDone || frame - published >= 33L) {
                    wall.publish()
                    published = frame
                }
            }
        }
    }
}

/**
 * Väggen som bakgrund bakom sidorna: mörk, dämpad och tonad mot temats
 * färg. Tänds väggen först nu (första starten) vänds omslagen fram här.
 */
@Composable
internal fun PosterWallBackdrop(wall: PosterWall, shown: Boolean, quiet: Boolean, modifier: Modifier = Modifier) {
    val tint = LocalPalette.current.background
    Spacer(modifier.drawBehind { if (shown) wall.drawBackdrop(this, tint, quiet) })
}

// --- Omslag som vänds fram ------------------------------------------------------------

/**
 * Omslagen vänds fram i en våg när en sida visas, som väggen i starten: de
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

/** Kortet vänds fram när sidan visas, som omslagen på väggen. */
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
        rotationY = ENTRANCE_TURN * (1f - p)
        cameraDistance = ENTRANCE_CAMERA * density
    }
}

// --- Hjälp -----------------------------------------------------------------------------

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

/** Monotona millisekunder: för väntan och för när man senast tryckte. */
internal fun nowMs(): Long = System.nanoTime() / 1_000_000L

internal fun smoothstep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun easeOutCubic(x: Float): Float = 1f - (1f - x).pow(3)

private fun easeInOutCubic(x: Float): Float = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f

/** Ett fast slumptal 0–1 för [i] och egenskap [k]: samma varje bild, utan att något skapas. */
private fun hash(i: Int, k: Int): Float {
    var x = i * 374_761_393 + k * 668_265_263
    x = (x xor (x ushr 13)) * 1_274_126_177
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16_777_215f
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

private const val PI_F = 3.1415927f
private const val TAU = 6.2831855f
private const val DEG = 0.017453292f

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
/** Omslag i reserv utöver de som syns, till vändningarna. */
private const val RESERVE = 3
private const val MAX_ROWS = 24
private val SAMPLE_TIMES = longArrayOf(0L, 1_500L, 3_000L, 5_000L, 8_000L, 14_000L, 20_000L, 60_000L)

/** Kameran: avståndet i skärmbredder, hur den lägger sig och vaggar (grader). */
private const val CAMERA_DISTANCE = 1.5f
private const val SETTLE_MS = 3_200f
private const val ZOOM_FROM = 1.28f
private const val ZOOM_TO = 1.0f
private const val PITCH_FROM = -12f
private const val PITCH_TO = -7f
private const val YAW_FROM = -21f
private const val YAW_TO = -12f
private const val ROLL_FROM = -13.5f
private const val ROLL_TO = -10.5f
private const val YAW_SWAY = 1.1f
private const val ROLL_SWAY = 0.7f

/** Radernas fart: del av skärmens höjd per sekund (väggens tid). Lite fartigare i början. */
private val ROW_SPEEDS = floatArrayOf(0.025f, 0.020f, 0.029f, 0.022f, 0.027f, 0.019f)
private const val KICK = 1.3f
private const val KICK_MS = 1_500f

/** Startens biofält (för vilka omslag som syns helt), och kanterna runt bilden (del av bredden). */
internal const val LETTERBOX = 2.39f
private const val ENTER_MARGIN = 0.015f
private const val KEEP_MARGIN = 0.05f

/** Vågen när väggen tänds: från mitten och utåt, lugnt, utan studs. */
private const val IGNITE_DELAY_MS = 250f
private const val IGNITE_SPREAD_MS = 1_250f
private const val IGNITE_JITTER_MS = 160f
private const val IGNITE_MS = 1_150f
private const val IGNITE_TURN = 80f
private const val IGNITE_LIFT = 0.18f
private const val BLOOM_UNTIL_MS = 2_800L

/** Vändningarna i starten. */
private const val LIVE_FROM_MS = 3_000L
private const val FLIP_EVERY_MS = 1_250f
private const val FLIP_MS = 1_500f
private const val FLIP_LIFT = 0.10f
private const val EDGE_SHADE = 0.78f

/** Ljuset som vandrar i starten, och djupet. */
private const val LIGHT_SWING_X = 0.55f
private const val LIGHT_SWING_Y = 0.28f
private const val POOL_RADIUS = 1.3f
private const val POOL_DARK = 0.36f
private const val INTRO_FOG = 0.50f
private const val BACKDROP_FOG = 0.55f

/** Bakgrunden i appen: hur mörk och hur färgstark, hur fort, och när den står still. */
private const val BACKDROP_BRIGHTNESS = 0.36f
private const val BACKDROP_SATURATION = 0.5f
private const val QUIET_DIM = 0.5f
private const val AMBIENT_SPEED = 0.45f
private const val IDLE_MS = 1_500L
private const val EASE_MS = 1_400f
private const val STOP_MS = 260f

/** Korten som vänds fram när en sida visas. */
private const val ENTRANCE_WINDOW_MS = 900L
private const val ENTRANCE_STAGGER_MS = 34L
private const val ENTRANCE_MAX_STEPS = 22
private const val ENTRANCE_MS = 620
private const val ENTRANCE_TURN = 62f
private const val ENTRANCE_CAMERA = 14f
private val ENTRANCE_EASING = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

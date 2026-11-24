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
 * djupet, i riktig 3D. Starten visar den i full styrka: omslagen tonas
 * lugnt fram ur mörkret, och sedan klipper väggen mellan filmerna allt
 * snabbare, som en filmtrailer, ända upp till toppen där omslagen flyger
 * till startsidan — och klippen glesnar igen medan väggen tonar ner. Sedan
 * blir väggen kvar som appens bakgrund, mörk och dämpad, och glider sakta
 * hela tiden — med Filmsiden också med ett sidenband bakom omslagen.
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
    /** Omslagens adresser, så att slutet kan skicka samma film till sitt kort på startsidan. */
    private var urls: List<String> = emptyList()

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

    /** Väggen tändes i starten: den har klippen. Tänds den först som bakgrund blir det lugnare. */
    private var cinematic = false

    /** När klippen börjar ta fart, i väggens tid (se [startRush]); Long.MAX_VALUE tills dess. */
    private var rushAt = Long.MAX_VALUE

    /** Skärmsläckaren visas: väggen går i full fart, omslag tonar över i nya och vågor går ut från mitten. */
    var saver = false
        private set
    /** Skärmsläckarens vågor: hur många som gått, när den senaste gick och när nästa går (väggens tid). */
    private var wave = 0
    private var waveAt = 0L
    private var nextWaveAt = Long.MAX_VALUE

    /** Efter starten: väggen är bakgrund, och omslagen byts inte längre. */
    var ambient = false

    /** Senaste knapptryckningen eller musrörelsen, i [nowMs]. */
    var lastInput = 0L

    /** Något omslag tonas fram eller byts: väggen ska gå i full fart tills det är klart. */
    var busy = false
        private set

    /** Filmsiden: sidenbandet bakom omslagen, och dess egen tid (ms). */
    private val silk = SilkBand()
    private var silkTime = 0.0
    /** Mellanrummet mellan omslagen, i väggens mått: där Filmsidens ljus lyser fram. */
    private var rimGap = 1f

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

    fun setReel(posters: List<String>) {
        val size = posters.size
        urls = posters.map { it.trim() }
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
            nextSwapAt = if (cinematic) SWAP_FROM_MS else Long.MAX_VALUE
            rushAt = Long.MAX_VALUE
            fitTiles()
            dirty = true
        }
    }

    /**
     * Startsidan är klar: klippen börjar ta fart — långsamt och mjukt först,
     * sedan allt tätare — och når toppen vid [peakAt], där väggen flyter över
     * i startsidan. Tidigast när väggen hunnit tonas fram.
     */
    fun startRush() {
        if (!lit || !cinematic || rushAt != Long.MAX_VALUE) return
        rushAt = max(elapsed.toLong() + 16L, RUSH_EARLIEST_MS)
    }

    /** Skärmsläckaren kommer fram: väggen vaknar, med enstaka byten och snart en första våg. */
    fun startSaver() {
        saver = true
        val u = clock.longValue
        nextSwapAt = u + SAVER_SWAP_FROM_MS
        nextWaveAt = u + SAVER_WAVE_FIRST_MS
    }

    fun stopSaver() {
        saver = false
        nextWaveAt = Long.MAX_VALUE
    }

    /** Toppen, i väggens tid: klippen går som fortast. Long.MAX_VALUE innan farten börjat. */
    val peakAt: Long get() = if (rushAt == Long.MAX_VALUE) Long.MAX_VALUE else rushAt + Rush.peak

    /** Omslagen laddas och väggen tänds snart: starten väntar in den. */
    fun lightingSoon(now: Long): Boolean =
        !lit && count > 0 && (loadedCount > 0 || (loadedCount + failedCount < count && now < reelAt + WALL_WAIT_MS))

    /** Väggens tid går fram [ms]; syns först efter [publish]. */
    fun advance(ms: Double) {
        elapsed += ms
    }

    /** Sidenbandets tid går fram [ms], i jämn takt oavsett väggens fart; syns med nästa [publish]. */
    fun advanceSilk(ms: Double) {
        silkTime += ms
    }

    fun publish() {
        val v = elapsed.toLong()
        if (v != clock.longValue) clock.longValue = v
    }

    // --- Ritningen ---------------------------------------------------------------------

    /**
     * Starten: väggen i full styrka, med ett stilla ljus kring mitten och
     * djupet som mörknar. [silkColors]: skärmsläckaren med Filmsiden — bandet
     * slingrar sig genom väggen, en bit bakom omslagen och nästa framför dem.
     * Starten själv ritas alltid utan.
     */
    fun drawIntro(scope: DrawScope, silkColors: SilkColors? = null) {
        noteSize(scope.size)
        val u = clock.longValue
        loads.intValue
        if (!lit) return
        prepare(u)
        if (!ready) return
        if (silkColors != null) {
            silk.prepare(scope.size.width, scope.size.height, silkTime)
            with(silk) { scope.drawBack(silkColors, 1f) }
        }
        scope.drawTiles(u, null, silkColors, rimGain = 1f, coverLight = SAVER_COVER_LIGHT)
        scope.drawPool()
        scope.drawFog(Color.Black, INTRO_FOG)
        if (silkColors != null) {
            with(silk) {
                scope.drawFront(silkColors, 1f)
                scope.drawLight(silkColors, 0.8f)
            }
        }
    }

    /**
     * Bakgrunden i appen: samma vägg, mörk och med lite färg, tonad mot
     * temats bakgrundsfärg [tint] så att text och omslag framför syns tydligt.
     * [quiet]: sidor med mycket text, där den är ännu mörkare.
     */
    fun drawBackdrop(scope: DrawScope, tint: Color, quiet: Boolean, silkColors: SilkColors? = null) {
        noteSize(scope.size)
        val u = clock.longValue
        loads.intValue
        if (!lit) return
        prepare(u)
        if (!ready) return
        with(scope) {
            drawRect(tint)
            // Filmsiden: bandet bakom omslagen. Det som ligger över väggen sedan mörkar
            // också mellanrummen, så ljuset där är starkare från början.
            if (silkColors != null) {
                silk.prepare(size.width, size.height, silkTime)
                with(silk) { drawBack(silkColors, BACKDROP_SILK) }
            }
            drawTiles(u, backdropFilter, silkColors, rimGain = BACKDROP_RIM, coverLight = BACKDROP_COVER_LIGHT)
            drawFog(tint, BACKDROP_FOG)
            drawRect(
                Brush.verticalGradient(
                    0f to tint.copy(alpha = 0.24f),
                    0.5f to tint.copy(alpha = 0.40f),
                    1f to tint.copy(alpha = 0.68f),
                ),
            )
            if (silkColors != null) with(silk) { drawLight(silkColors, 1f) }
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
                // Ett omslag som flyger till startsidan behåller sin film tills det landat.
                if (!tile.detached) release(tile.poster, u)
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
        rimGap = gap.toFloat()
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
     * sakta bakåt och lägger sig. När klippen tar fart glider den mjukt fram
     * mot väggen igen, allt fortare, och fortsätter en bit förbi toppen medan
     * den saktar in — inga stötar och inga stopp. Vinkeln är densamma hela tiden.
     */
    private fun pose(u: Long) {
        val back = easeOutCubic((u / GLIDE_MS).coerceIn(0f, 1f))
        zoom = (ZOOM_FROM + (ZOOM_TO - ZOOM_FROM) * back) * (1f + push(u))
        val s = zoom
        homography(0f, 0f, 0f, s * rot[0], s * rot[3], s * rot[6], s * rot[1], s * rot[4], s * rot[7], wall)
        invert(wall, wallInverse)
    }

    /**
     * Hur mycket närmare kameran går medan klippen tar fart: allt fortare fram
     * till toppen, och sedan med samma fart in i en mjuk inbromsning.
     */
    private fun push(u: Long): Float {
        if (rushAt == Long.MAX_VALUE || u <= rushAt) return 0f
        val t = (u - rushAt).toFloat()
        val peak = Rush.peak.toFloat()
        if (t <= peak) {
            val x = t / peak
            return PUSH * x * x
        }
        return PUSH + 2f * PUSH / peak * PUSH_EASE_MS * (1f - exp(-(t - peak) / PUSH_EASE_MS))
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
     * Radernas egen tid: när klippen tar fart glider de allt fortare, och
     * efter toppen saktar de mjukt in till vanlig fart igen — inget stopp.
     */
    private fun driftTime(u: Long): Float {
        val t = u.toFloat()
        if (rushAt == Long.MAX_VALUE || u <= rushAt) return t
        val since = (u - rushAt).toFloat()
        val peak = Rush.peak.toFloat()
        if (since <= peak) {
            val x = since / peak
            return t + DRIFT_RUSH * peak * x * x * x / 3f
        }
        return t + DRIFT_RUSH * peak / 3f + DRIFT_RUSH * DRIFT_EASE_MS * (1f - exp(-(since - peak) / DRIFT_EASE_MS))
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
     * Ljuset på väggen: ett mjukt, stilla sken kring mitten som mörknar mot
     * kanterna. Ingen strålkastare, inget som vandrar.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun light(u: Long) {
        lightX = 0f
        lightY = 0f
        poolRadius = POOL_RADIUS
        poolDark = POOL_DARK
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
                if (!tile.detached) release(tile.poster, u)
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
                tile.wave = wave
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
        // Skärmsläckaren: då och då en mjuk våg av nya filmer ut från mitten.
        if (saver && flyers.isEmpty() && u >= nextWaveAt) {
            wave++
            waveAt = nextWaveAt
            nextWaveAt = waveAt + SAVER_WAVE_EVERY_MS
        }
        // Klippen: vid varje slag byter omslagen film, i en våg från mitten.
        var moving = false
        for (k in tiles.indices) {
            val tile = tiles[k]
            if (tile.poster < 0 || tile.detached) continue
            if (tile.previous >= 0 && u >= tile.cutAt + tile.cutMs) {
                release(tile.previous, u)
                tile.previous = -1
            }
            if (saver && tile.wave < wave && u >= waveAt + tile.dist * SAVER_WAVE_SPREAD_MS) {
                tile.wave = wave
                if (!tile.reserved && tile.previous < 0 && u >= tile.igniteAt + IGNITE_MS.toLong()) {
                    cut(tile, u, SAVER_WAVE_FADE_MS)
                }
            }
            // Klippen: allt tätare fram till toppen och sedan allt glesare medan
            // väggen tonar bort. Omslag som ska flyga till startsidan klipps inte,
            // och omslag som ännu tonas fram får tona klart.
            if (rushAt != Long.MAX_VALUE && u >= rushAt) {
                val passed = beatsPassed(tile, u)
                if (passed > tile.beats) {
                    tile.beats = passed
                    if (!tile.reserved && u >= tile.igniteAt + IGNITE_MS.toLong()) cut(tile, u, cutLength(passed - 1))
                }
            }
            // En plats som fylls på efter slutet tonas fram i sin egen takt: den håller inte väggen i gång.
            if (tile.previous >= 0 || (u < tile.igniteAt + IGNITE_MS.toLong() && !tile.refill)) moving = true
        }
        // Medan starten väntar på startsidan, och i skärmsläckaren: då och då tonar
        // ett omslag långsamt över i ett annat.
        if ((saver || (!ambient && rushAt == Long.MAX_VALUE)) && flyers.isEmpty() && u >= nextSwapAt) {
            val every = if (saver) SAVER_SWAP_EVERY_MS else SWAP_EVERY_MS
            nextSwapAt = u + (every * (0.7f + 0.6f * hash(u.toInt(), 11))).toLong()
            pick(u)?.let { cut(it, u, if (saver) SAVER_SWAP_FADE_MS else SWAP_FADE_MS) }
        }
        busy = moving
    }

    /** Hur många av klippens slag som nått omslaget vid tiden [u]: vågorna går ut från mitten. */
    private fun beatsPassed(tile: Tile, u: Long): Int {
        if (rushAt == Long.MAX_VALUE || u < rushAt) return 0
        val t = (u - rushAt).toFloat()
        val beats = Rush.beats
        var n = 0
        for (k in beats.indices) {
            if (t >= beats[k] + tile.dist * beatSpread(k)) n = k + 1 else break
        }
        return n
    }

    /** Hur långt efter mitten slaget [k] når kanten: vågen går fortare ju tätare klippen. */
    private fun beatSpread(k: Int): Float = min(BEAT_SPREAD_MS, Rush.tempo[k] * 0.7f)

    /** Övertoningen i slaget [k]: lång och mjuk i början, kort vid toppen. */
    private fun cutLength(k: Int): Float = (Rush.tempo[k] * BEAT_FADE).coerceIn(BEAT_FADE_MIN_MS, BEAT_FADE_MAX_MS)

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
            if (tile.poster < 0 || tile.previous >= 0 || tile.detached) continue
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
    private fun DrawScope.drawTiles(
        u: Long,
        filter: ColorFilter?,
        silkColors: SilkColors? = null,
        rimGain: Float = 1f,
        coverLight: Float = 0f,
    ) {
        val center = Offset(tileW / 2f, tileH / 2f)
        val w = tileW.toFloat()
        val hh = tileH.toFloat()
        projected(wall) {
            for (i in tiles.indices) {
                val tile = tiles[i]
                // Omslag som flyger till startsidan ritas av slutet, inte här.
                if (tile.poster < 0 || u < tile.igniteAt || tile.detached) continue
                val ignite = (u - tile.igniteAt) / IGNITE_MS
                val left = tileLeft(tile, u)
                val top = tileTop(tile)
                // Filmsiden: hur nära bandet omslaget står. Kanten kommer när omslaget nästan tonats fram.
                var near = 0f
                if (silkColors != null) {
                    project(left + w / 2f, top + hh / 2f)
                    near = silk.nearness(projX, projY) * smoothstep(0.6f, 1f, ignite)
                }
                translate(left, top) {
                    if (near > 0.02f && silkColors != null) rimBehind(silkColors, near, w, hh, rimGap, rimGain)
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
                    if (near > 0.02f && silkColors != null) coverLit(silkColors, near, coverLight, w, hh)
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

    // --- Slutet: omslagen flyger till startsidan ---------------------------------------

    private val flyers = ArrayList<Flyer>()
    private val flightX = FloatArray(4)
    private val flightY = FloatArray(4)
    private val flightMatrix = FloatArray(9)

    /**
     * Slutet på starten: ett omslag från väggen flyger till varje kort i
     * [targets] (i väggens bildpunkter). Syns kortets film på väggen är det
     * den som flyger dit; annars ett omslag ovanför kortet, som tonar över i
     * kortets bild på vägen. Det stora omslaget överst får det som står
     * närmast mitten, och lyfter först. Resten lyfter i en våg från vänster,
     * rad för rad. Ger när vart och ett landar, i ms efter att slutet
     * började, eller -1 där det inte fanns något omslag att skicka.
     */
    fun planMorph(targets: List<MorphTarget>): LongArray = planFlights(targets, gather = false).land

    /**
     * Skärmsläckaren kommer: omslaget på varje kort i [targets] lyfter och
     * flyger in på en plats på väggen — samma film om den finns där — och
     * tonar över i väggens film på vägen. Platserna står tomma tills omslagen
     * är framme. Ger när de lyfter och landar, och när väggen täcker allt.
     */
    fun planGather(targets: List<MorphTarget>): FlightTimes = planFlights(targets, gather = true)

    private fun planFlights(targets: List<MorphTarget>, gather: Boolean): FlightTimes {
        val lifts = LongArray(targets.size) { -1L }
        val lands = LongArray(targets.size) { -1L }
        val none = FlightTimes(lifts, lands, GATHER_COVER_MS)
        flyers.clear()
        if (!lit || targets.isEmpty() || width <= 0f) return none
        val u = clock.longValue
        prepare(u)
        if (!ready) return none
        // Omslagen som står i bild och inte håller på att bytas. De får sticka ut
        // en bit under biofälten eller kanten: de flyger ändå in mot mitten.
        val inset = width * 0.01f
        val free = ArrayList<Tile>()
        for (i in tiles.indices) {
            val tile = tiles[i]
            if (tile.poster < 0 || tile.previous >= 0 || tile.detached) continue
            if (u < tile.igniteAt + IGNITE_MS.toLong()) continue
            project(tileLeft(tile, u) + tileW / 2f, tile.row * pitchY)
            if (projX < width * 0.02f || projX > width * 0.98f || projY < height * 0.02f || projY > height * 0.98f) continue
            free.add(tile)
        }
        val chosen = arrayOfNulls<Tile>(targets.size)
        // Samma film på väggen som på kortet: den flyger till sitt kort.
        for (i in targets.indices) {
            val wanted = targets[i].urls
            if (wanted.isEmpty()) continue
            for (k in free.indices) {
                val poster = free[k].poster
                if (poster < urls.size && urls[poster] in wanted) {
                    chosen[i] = free.removeAt(k)
                    break
                }
            }
        }
        // Det stora omslaget först, sedan från vänster: det som står närmast ovanför.
        val order = targets.indices.sortedWith(compareBy<Int>({ if (targets[it].prominent) 0 else 1 }, { targets[it].centerX }))
        for (i in order) {
            if (chosen[i] != null || free.isEmpty()) continue
            val target = targets[i]
            var best = -1
            var bestCost = Float.POSITIVE_INFINITY
            for (k in free.indices) {
                val tile = free[k]
                // Det stora omslaget: ett som syns helt, så nära mitten som möjligt.
                val whole = !target.prominent || fullyInside(tile, u, inset)
                project(tileLeft(tile, u) + tileW / 2f, tile.row * pitchY)
                val cost = if (target.prominent) {
                    hypot(projX - width / 2f, projY - height / 2f) + (if (whole) 0f else width)
                } else {
                    val dy = projY - target.centerY
                    abs(projX - target.centerX) + 0.25f * abs(dy) + (if (dy > 0f) 0.6f * dy else 0f)
                }
                if (cost < bestCost) {
                    bestCost = cost
                    best = k
                }
            }
            if (best >= 0) chosen[i] = free.removeAt(best)
        }
        // Raderna på startsidan, uppifrån.
        val rowGap = height * 0.06f
        val rowTops = ArrayList<Float>()
        targets.filter { !it.prominent }.map { it.top }.sorted().forEach { top ->
            if (rowTops.isEmpty() || top - rowTops.last() > rowGap) rowTops.add(top)
        }
        for (i in targets.indices) {
            val tile = chosen[i] ?: continue
            val target = targets[i]
            var row = 0
            for (r in rowTops.indices) if (rowTops[r] <= target.top + 0.5f) row = r
            val start = if (target.prominent) {
                MORPH_FIRST_MS
            } else {
                MORPH_FIRST_MS + MORPH_LEAD_MS + (target.centerX / width).coerceIn(0f, 1f) * MORPH_SWEEP_MS + row * MORPH_ROW_MS
            }
            val flight = if (target.prominent) MORPH_HERO_FLIGHT_MS else MORPH_FLIGHT_MS
            val same = tile.poster < urls.size && urls[tile.poster] in target.urls
            tile.reserved = true
            val flyer = Flyer(tile, tile.poster, target, start.toLong(), flight, same, gather)
            if (gather) {
                // Från kortet: platsen på väggen står tom tills omslaget är framme.
                flyer.xs[0] = target.left
                flyer.ys[0] = target.top
                flyer.xs[1] = target.right
                flyer.ys[1] = target.top
                flyer.xs[2] = target.right
                flyer.ys[2] = target.bottom
                flyer.xs[3] = target.left
                flyer.ys[3] = target.bottom
                flyer.launched = true
                tile.detached = true
            }
            flyers.add(flyer)
            lifts[i] = start.toLong()
            lands[i] = start.toLong() + flight
        }
        // Väggen tonas fram över sidan när alla omslag har lyft (skärmsläckaren).
        val covered = (lifts.maxOrNull() ?: 0L).coerceAtLeast(0L) + GATHER_COVER_MS
        coveredAt = covered
        return FlightTimes(lifts, lands, covered)
    }

    /** Skärmsläckaren: när väggen täcker allt, så att omslagen som landat kan lämna över till den. */
    private var coveredAt = 0L

    /**
     * Omslagen på väg till startsidan, [m] ms efter att slutet började: i
     * full styrka, ovanpå allt. Ett omslag lämnar väggen först när det lyfter.
     */
    fun drawFlyers(scope: DrawScope, m: Long) {
        if (flyers.isEmpty()) return
        noteSize(scope.size)
        val u = clock.longValue
        prepare(u)
        for (i in flyers.indices) {
            val flyer = flyers[i]
            if (flyer.canceled || m < flyer.start) continue
            if (flyer.gather) {
                scope.drawGatherer(flyer, m, u)
                continue
            }
            if (!flyer.launched && !liftOff(flyer, u)) continue
            scope.drawFlyer(flyer, m)
        }
    }

    /**
     * Ett omslag på väg från ett kort in på väggen: i en båge, lite lyft mot
     * en på vägen, från kortets ram till platsens perspektiv och från kortets
     * bild till väggens film — mot platsen där den står just nu, eftersom
     * väggen glider. Framme lämnar det över till väggen, med samma bild på
     * samma ställe; tills väggen täcker allt ritas det kvar ovanpå.
     */
    private fun DrawScope.drawGatherer(flyer: Flyer, m: Long, u: Long) {
        val tile = flyer.tile
        val target = flyer.target
        val alive = tile.poster == flyer.poster && tiles.contains(tile)
        if (alive) {
            val left = tileLeft(tile, u)
            val top = tileTop(tile)
            for (k in 0 until 4) {
                project(if (k == 0 || k == 3) left else left + tileW, if (k < 2) top else top + tileH)
                flyer.endX[k] = projX
                flyer.endY[k] = projY
            }
            flyer.shade = shadeAt(left + tileW / 2f, top + tileH / 2f)
            flyer.reached = true
        }
        if (!flyer.reached) return
        val raw = ((m - flyer.start).toFloat() / flyer.flight).coerceIn(0f, 1f)
        if (alive) {
            // Framme: väggen tar över platsen. Omslaget ritas kvar tills väggen täcker allt.
            // Går flygningen tillbaka (en knapp på vägen in) lämnar väggen platsen igen.
            val home = raw >= 1f
            tile.detached = !home
            tile.reserved = !home
            flyer.handed = home
            if (home && m >= coveredAt) return
        }
        // Platsen försvann ur bilden under färden: omslaget tonar bort där.
        val alpha = if (alive || raw < 1f) 1f else 1f - smoothstep(0f, MORPH_DISSOLVE_MS.toFloat(), (m - flyer.start - flyer.flight).toFloat())
        if (alpha <= 0.002f) return
        val p = FLIGHT_EASING.transform(raw)
        val xs = flyer.xs
        val ys = flyer.ys
        val sx = (xs[0] + xs[1] + xs[2] + xs[3]) / 4f
        val sy = (ys[0] + ys[1] + ys[2] + ys[3]) / 4f
        val ex = flyer.endX
        val ey = flyer.endY
        val tx = (ex[0] + ex[1] + ex[2] + ex[3]) / 4f
        val ty = (ey[0] + ey[1] + ey[2] + ey[3]) / 4f
        val midX = (sx + tx) / 2f
        val midY = (sy + ty) / 2f - MORPH_ARC * hypot(tx - sx, ty - sy)
        val q = 1f - p
        val cx = q * q * sx + 2f * q * p * midX + p * p * tx
        val cy = q * q * sy + 2f * q * p * midY + p * p * ty
        val lift = 1f + MORPH_LIFT * sin(p * PI_F)
        for (k in 0 until 4) {
            val fromX = xs[k] - sx
            val fromY = ys[k] - sy
            flightX[k] = cx + (fromX + (ex[k] - tx - fromX) * p) * lift
            flightY[k] = cy + (fromY + (ey[k] - ty - fromY) * p) * lift
        }
        quadMatrix(flightX, flightY, FLIGHT_SIZE.toFloat(), flightMatrix)
        val aspect = target.aspect + (POSTER_ASPECT - target.aspect) * p
        val card = target.image
        // Kortets bild först; den tonar över i väggens film på vägen.
        val blend = if (card == null || flyer.same) 1f else smoothstep(0.22f, 0.78f, p)
        val shade = flyer.shade * smoothstep(0.5f, 1f, p)
        projected(flightMatrix) {
            if (card != null && blend < 0.999f) cropped(card, aspect, alpha)
            if (blend > 0.001f) wallPoster(flyer.poster, aspect, alpha * blend)
            if (shade > 0.003f) {
                drawRect(Color.Black, size = Size(FLIGHT_SIZE.toFloat(), FLIGHT_SIZE.toFloat()), alpha = shade * alpha)
            }
        }
    }

    /** Skärmsläckaren är framme, eller avbröts på vägen: platserna på väggen tar över. */
    fun endGather() {
        val u = clock.longValue
        for (i in flyers.indices) {
            val flyer = flyers[i]
            if (!flyer.gather) continue
            val tile = flyer.tile
            tile.reserved = false
            if (tile.poster == flyer.poster && tiles.contains(tile)) {
                tile.detached = false
            } else if (!flyer.handed) {
                // Platsen lämnade bilden innan omslaget var framme: filmen blir ledig först nu.
                release(flyer.poster, u)
            }
        }
        flyers.removeAll { it.gather }
        dirty = true
    }

    /** Alla flygningar avslutas: de till väggen lämnar över till den, de från den landar. */
    fun endFlights() {
        endGather()
        endMorph()
    }

    /** Omslaget lämnar väggen: hörnen där det står just nu, och hur mörkt det är där. */
    private fun liftOff(flyer: Flyer, u: Long): Boolean {
        val tile = flyer.tile
        if (!ready || tile.detached || tile.poster != flyer.poster || !tiles.contains(tile)) {
            flyer.canceled = true
            return false
        }
        val left = tileLeft(tile, u)
        val top = tileTop(tile)
        for (k in 0 until 4) {
            project(if (k == 0 || k == 3) left else left + tileW, if (k < 2) top else top + tileH)
            flyer.xs[k] = projX
            flyer.ys[k] = projY
        }
        flyer.shade = shadeAt(left + tileW / 2f, top + tileH / 2f)
        tile.detached = true
        flyer.launched = true
        return true
    }

    /** Hur mörk väggen är i starten i punkten (x, y): ljuset, djupet och hörnen. */
    private fun shadeAt(x: Float, y: Float): Float {
        val reach = (hypot(x - lightX, y - lightY) / (poolRadius * unit)).coerceIn(0f, 1f)
        val pool = if (reach < 0.5f) poolDark * 0.7f * reach else poolDark * (0.35f + 1.3f * (reach - 0.5f))
        var fog = 0f
        if (fogOk) {
            val dx = fogEndX - fogStartX
            val dy = fogEndY - fogStartY
            val s = ((x - fogStartX) * dx + (y - fogStartY) * dy) / (dx * dx + dy * dy)
            fog = INTRO_FOG * ((s - 0.3f) / 0.7f).coerceIn(0f, 1f)
        }
        project(x, y)
        val corner = hypot(projX - width / 2f, projY - height / 2f) / (max(width, height) * VIGNETTE_REACH)
        val vignette = VIGNETTE_DARK * ((corner - VIGNETTE_FROM) / (1f - VIGNETTE_FROM)).coerceIn(0f, 1f)
        return 1f - (1f - pool) * (1f - fog) * (1f - vignette)
    }

    /**
     * Ett omslag i luften: i en båge från väggen till kortet, lite lyft mot
     * en på vägen. Formen glider från väggens perspektiv till kortets ram och
     * bilden från väggens film till kortets. När det landat tonar det bort,
     * och kortet under — samma bild — står kvar.
     */
    private fun DrawScope.drawFlyer(flyer: Flyer, m: Long) {
        val target = flyer.target
        val land = flyer.start + flyer.flight
        val alpha = 1f - smoothstep(land.toFloat(), (land + MORPH_DISSOLVE_MS).toFloat(), m.toFloat())
        if (alpha <= 0.002f) return
        val raw = ((m - flyer.start).toFloat() / flyer.flight).coerceIn(0f, 1f)
        val p = FLIGHT_EASING.transform(raw)
        val xs = flyer.xs
        val ys = flyer.ys
        val sx = (xs[0] + xs[1] + xs[2] + xs[3]) / 4f
        val sy = (ys[0] + ys[1] + ys[2] + ys[3]) / 4f
        val tx = target.centerX
        val ty = target.centerY
        val midX = (sx + tx) / 2f
        val midY = (sy + ty) / 2f - MORPH_ARC * hypot(tx - sx, ty - sy)
        val q = 1f - p
        val cx = q * q * sx + 2f * q * p * midX + p * p * tx
        val cy = q * q * sy + 2f * q * p * midY + p * p * ty
        val lift = 1f + MORPH_LIFT * sin(p * PI_F)
        for (k in 0 until 4) {
            val endX = (if (k == 0 || k == 3) target.left else target.right) - tx
            val endY = (if (k < 2) target.top else target.bottom) - ty
            val fromX = xs[k] - sx
            val fromY = ys[k] - sy
            flightX[k] = cx + (fromX + (endX - fromX) * p) * lift
            flightY[k] = cy + (fromY + (endY - fromY) * p) * lift
        }
        quadMatrix(flightX, flightY, FLIGHT_SIZE.toFloat(), flightMatrix)
        val aspect = POSTER_ASPECT + (target.aspect - POSTER_ASPECT) * p
        val card = target.image
        val blend = if (card == null || flyer.same) 0f else smoothstep(0.22f, 0.78f, p)
        val shade = flyer.shade * (1f - smoothstep(0f, 0.5f, p))
        projected(flightMatrix) {
            if (blend < 0.999f) wallPoster(flyer.poster, aspect, alpha)
            if (card != null && blend > 0.001f) cropped(card, aspect, alpha * blend)
            if (shade > 0.003f) {
                drawRect(Color.Black, size = Size(FLIGHT_SIZE.toFloat(), FLIGHT_SIZE.toFloat()), alpha = shade * alpha)
            }
        }
    }

    /** Väggens omslag, i 2:3 som på väggen eller beskuret till kortets form på vägen. */
    private fun DrawScope.wallPoster(index: Int, aspect: Float, alpha: Float) {
        val image = images[index] ?: return
        if (abs(aspect - POSTER_ASPECT) < 0.002f) {
            drawImage(
                image = image,
                srcOffset = IntOffset(srcX[index], srcY[index]),
                srcSize = IntSize(srcW[index], srcH[index]),
                dstSize = IntSize(FLIGHT_SIZE, FLIGHT_SIZE),
                alpha = alpha,
                filterQuality = FilterQuality.Medium,
            )
        } else {
            cropped(image, aspect, alpha)
        }
    }

    /** Mitten av [image] i formen [aspect], som korten visar sina bilder. */
    private fun DrawScope.cropped(image: ImageBitmap, aspect: Float, alpha: Float) {
        val w = image.width
        val hh = image.height
        if (w <= 0 || hh <= 0) return
        val cw: Int
        val ch: Int
        if (w > hh * aspect) {
            ch = hh
            cw = (hh * aspect).roundToInt().coerceIn(1, w)
        } else {
            cw = w
            ch = (w / aspect).roundToInt().coerceIn(1, hh)
        }
        drawImage(
            image = image,
            srcOffset = IntOffset((w - cw) / 2, (hh - ch) / 2),
            srcSize = IntSize(cw, ch),
            dstSize = IntSize(FLIGHT_SIZE, FLIGHT_SIZE),
            alpha = alpha,
            filterQuality = FilterQuality.Medium,
        )
    }

    /**
     * Slutet är klart och omslagen har landat. Platserna de lämnade på väggen
     * — nu bakgrunden — får andra filmer, som tonas fram när väggen rör sig
     * igen. Så syns aldrig samma film på kortet och i väggen bakom.
     */
    fun endMorph() {
        if (flyers.isEmpty()) return
        val u = clock.longValue
        for (i in flyers.indices) {
            val flyer = flyers[i]
            flyer.tile.reserved = false
            if (!flyer.launched) continue
            val tile = flyer.tile
            if (tile.detached && tile.poster == flyer.poster && tiles.contains(tile)) {
                val next = take(shared = true)
                release(flyer.poster, u)
                tile.poster = next
                tile.previous = -1
                tile.detached = false
                tile.refill = true
                tile.createdAt = u
                tile.igniteAt = u + REFILL_DELAY_MS
            } else {
                // Platsen lämnade bilden medan omslaget flög: filmen blir ledig först nu.
                release(flyer.poster, u)
            }
        }
        flyers.clear()
        dirty = true
    }
}

/**
 * Ett kort på startsidan som ett omslag från väggen ska landa på: platsen i
 * väggens bildpunkter, kortets bildadresser och bilden kortet visar (om den
 * redan laddats). [prominent]: det stora omslaget överst på startsidan.
 */
internal class MorphTarget(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val urls: List<String>,
    val image: ImageBitmap?,
    val prominent: Boolean,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val aspect: Float get() = (right - left) / (bottom - top).coerceAtLeast(1f)
}

/**
 * När omslagen lyfter och landar, i ms efter att flygningen började, per kort
 * (-1 där inget flyger), och när väggen täcker allt (skärmsläckaren).
 */
internal class FlightTimes(val lift: LongArray, val land: LongArray, val coveredAt: Long)

/** Ett omslag på väg mellan väggen och ett kort. */
private class Flyer(
    val tile: Tile,
    val poster: Int,
    val target: MorphTarget,
    /** När det lyfter och hur länge det flyger, i ms efter att slutet började. */
    val start: Long,
    val flight: Long,
    /** Samma film som kortet: ingen övertoning på vägen. */
    val same: Boolean,
    /** Från kortet in på väggen (skärmsläckaren), i stället för från väggen till kortet. */
    val gather: Boolean = false,
) {
    var launched = false
    var canceled = false
    /** In på väggen: platsens hörn just nu, om den har setts, och om väggen tagit över den. */
    val endX = FloatArray(4)
    val endY = FloatArray(4)
    var reached = false
    var handed = false
    /** Hörnen när det lämnade väggen: uppe till vänster, uppe till höger, nere till höger, nere till vänster. */
    val xs = FloatArray(4)
    val ys = FloatArray(4)
    /** Hur mörk väggen var där det lämnade den. */
    var shade = 0f
}

/**
 * Matrisen som lägger kvadraten [size]×[size] på fyrhörningen med hörnen
 * ([xs], [ys]) — uppe till vänster, uppe till höger, nere till höger, nere
 * till vänster. 3×3 radvis, med perspektiv.
 */
private fun quadMatrix(xs: FloatArray, ys: FloatArray, size: Float, out: FloatArray) {
    val x0 = xs[0]
    val y0 = ys[0]
    val x1 = xs[1]
    val y1 = ys[1]
    val x2 = xs[2]
    val y2 = ys[2]
    val x3 = xs[3]
    val y3 = ys[3]
    val sx = x0 - x1 + x2 - x3
    val sy = y0 - y1 + y2 - y3
    var g = 0f
    var h = 0f
    if (abs(sx) > 1e-3f || abs(sy) > 1e-3f) {
        val dx1 = x1 - x2
        val dx2 = x3 - x2
        val dy1 = y1 - y2
        val dy2 = y3 - y2
        val den = dx1 * dy2 - dx2 * dy1
        if (abs(den) > 1e-6f) {
            g = (sx * dy2 - dx2 * sy) / den
            h = (dx1 * sy - sx * dy1) / den
        }
    }
    val inv = 1f / size
    out[0] = (x1 - x0 + g * x1) * inv
    out[1] = (x3 - x0 + h * x3) * inv
    out[2] = x0
    out[3] = (y1 - y0 + g * y1) * inv
    out[4] = (y3 - y0 + h * y3) * inv
    out[5] = y0
    out[6] = g * inv
    out[7] = h * inv
    out[8] = 1f
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
    /** Omslaget flyger till startsidan: väggen ritar det inte, och filmen är upptagen tills det landat. */
    var detached = false
    /** Fylls på efter starten: tonas fram när väggen rör sig, utan att hålla den i gång. */
    var refill = false
    /** Ska flyga till startsidan: klipps inte medan det väntar på sin tur. */
    var reserved = false
    /** Skärmsläckarens senaste våg som nått platsen. */
    var wave = 0

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
        detached = false
        refill = false
        reserved = false
        wave = 0
    }
}

// --- Väggen i appen ------------------------------------------------------------------

/**
 * Laddar omslagen och driver väggens tid. I starten går den i full fart.
 * Sedan glider den sakta hela tiden bakom sidorna, också medan man
 * navigerar. Bara medan video spelas står den still: då syns den inte.
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
            wall.setReel(unique.map { it.poster })
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
            val target = when {
                !state.introDone -> 1f
                // Skärmsläckaren: väggen i full fart.
                wall.saver -> 1f
                state.nowPlaying != null -> 0f
                else -> AMBIENT_SPEED
            }
            if (target <= 0f) {
                // Video spelas: väggen står still direkt, och ingen bild ritas om
                // förrän den ska röra sig igen.
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
                // Sidenbandet går i sin egen jämna takt, också när väggen går i full fart.
                wall.advanceSilk(dt * min(1f, speed / AMBIENT_SPEED).toDouble())
                // Som bakgrund räcker trettio bilder i sekunden: varannan bild på en 60 Hz-tv, jämnt.
                if (!state.introDone || wall.saver || frame - published >= AMBIENT_FRAME_MS) {
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
 * [silk]: Filmsiden, med sidenbandet bakom omslagen.
 */
@Composable
internal fun PosterWallBackdrop(
    wall: PosterWall,
    shown: Boolean,
    quiet: Boolean,
    silk: SilkColors? = null,
    modifier: Modifier = Modifier,
) {
    val tint = LocalPalette.current.background
    Spacer(modifier.drawBehind { if (shown) wall.drawBackdrop(this, tint, quiet, silk) })
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
 * mellanlager: det kostar nästan ingenting. Landar ett omslag från
 * startens filmvägg på kortet ([spot]) visar det sig i stället när
 * omslaget är framme.
 */
@Composable
internal fun Modifier.coverEntrance(spot: MorphSpot? = null): Modifier {
    // I startens slut: kortet visar sig när omslaget som flyger hit landar, utan att glida upp.
    if (spot != null && spot.landAt >= 0L) return morphReveal(spot)
    val entrance = LocalCoverEntrance.current ?: return morphReveal(spot)
    val order = remember(entrance) { entrance.next() }
    if (order < 0) return morphReveal(spot)
    val progress = remember(entrance) { Animatable(0f) }
    LaunchedEffect(entrance) {
        delay(min(order, ENTRANCE_MAX_STEPS) * ENTRANCE_STAGGER_MS)
        progress.animateTo(1f, tween(ENTRANCE_MS, easing = ENTRANCE_EASING))
    }
    return graphicsLayer {
        val p = progress.value
        // Skärmsläckaren: kortet är borta medan dess omslag är på väggen.
        alpha = p * (if (spot != null) spot.morph.cardAlpha(spot) else 1f)
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
 * Klippen, räknat från att farten börjar öka: först glest och mjukt, sedan
 * allt tätare fram till toppen — där väggen flyter över i startsidan — och
 * därefter allt glesare medan väggen tonar bort. Inget stopp någonstans.
 * [tempo]: tiden mellan klippen kring varje slag, som avgör hur fort vågen
 * går och hur lång övertoningen är. Räknas fram en gång.
 */
private object Rush {
    val beats: LongArray
    val tempo: FloatArray
    val peak: Long

    init {
        val at = ArrayList<Long>()
        val gaps = ArrayList<Float>()
        var time = 0f
        var gap = BEAT_FIRST_GAP_MS
        while (gap >= BEAT_MIN_GAP_MS) {
            at += time.toLong()
            gaps += gap
            time += gap
            gap *= BEAT_SHRINK
        }
        peak = time.toLong()
        gap = BEAT_MIN_GAP_MS
        while (true) {
            gap *= BEAT_GROW
            time += gap
            if (time > peak + SETTLE_BEATS_MS) break
            at += time.toLong()
            gaps += gap
        }
        beats = at.toLongArray()
        tempo = gaps.toFloatArray()
    }
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
private const val GLIDE_MS = 3_200f
private const val ZOOM_FROM = 1.22f
private const val ZOOM_TO = 1.0f

/** Radernas fart: del av skärmens höjd per sekund (väggens tid). Lite fartigare i början. */
private val ROW_SPEEDS = floatArrayOf(0.025f, 0.020f, 0.029f, 0.022f, 0.027f, 0.019f)
private const val KICK = 0.5f
private const val KICK_MS = 1_500f

/** Startens biofält (för vilka omslag som syns helt), och kanterna runt bilden (del av bredden). */
internal const val LETTERBOX = 2.39f
private const val ENTER_MARGIN = 0.015f
private const val KEEP_MARGIN = 0.05f

/** När väggen tänds: omslagen tonas fram från mitten och utåt och växer lite in på plats — lugnt. */
private const val IGNITE_DELAY_MS = 250f
private const val IGNITE_SPREAD_MS = 1_400f
private const val IGNITE_JITTER_MS = 160f
private const val IGNITE_MS = 1_100f
private const val IGNITE_GROW = 0.05f
private const val BLOOM_UNTIL_MS = 2_800L

/**
 * Klippen, som i en filmtrailer: väggen byter filmer i vågor från mitten.
 * Först långt mellan klippen och långa, mjuka övertoningar, sedan allt
 * tätare och kortare fram till toppen, och efter den allt glesare igen.
 * Tidigast så här länge efter att väggen tänts börjar farten öka.
 */
private const val RUSH_EARLIEST_MS = 2_200L
private const val BEAT_FIRST_GAP_MS = 640f
private const val BEAT_MIN_GAP_MS = 105f
private const val BEAT_SHRINK = 0.78f
private const val BEAT_GROW = 1.35f
private const val SETTLE_BEATS_MS = 1_400L
private const val BEAT_SPREAD_MS = 320f
private const val BEAT_FADE = 0.6f
private const val BEAT_FADE_MIN_MS = 60f
private const val BEAT_FADE_MAX_MS = 420f
/** Kortare övertoning än så räknas som rent klipp. */
private const val HARD_CUT_MS = 30f
/** Kameran glider fram mot väggen medan klippen tar fart, och raderna glider fortare. */
private const val PUSH = 0.06f
private const val PUSH_EASE_MS = 900f
private const val DRIFT_RUSH = 1.6f
private const val DRIFT_EASE_MS = 1_500f

/**
 * Skärmsläckaren: ett omslag i taget tonar över i ett annat, och då och då
 * går en mjuk våg av nya filmer ut från mitten. Lugnt — det är en skärmsläckare.
 */
private const val SAVER_SWAP_FROM_MS = 1_600L
private const val SAVER_SWAP_EVERY_MS = 1_100f
private const val SAVER_SWAP_FADE_MS = 1_000f
private const val SAVER_WAVE_FIRST_MS = 7_000L
private const val SAVER_WAVE_EVERY_MS = 15_000L
private const val SAVER_WAVE_SPREAD_MS = 650f
private const val SAVER_WAVE_FADE_MS = 700f

/** Medan starten väntar på startsidan: ett omslag i taget tonar långsamt över i ett annat. */
private const val SWAP_FROM_MS = 2_800L
private const val SWAP_EVERY_MS = 1_300f
private const val SWAP_FADE_MS = 900f

/**
 * Slutet: när det stora omslaget lyfter, när de andra börjar, vågen över
 * bredden och nedåt raderna, hur länge de flyger och hur fort de tonar in i
 * korten. Banan böjer uppåt och omslagen lyfts lite mot en på vägen.
 */
private const val MORPH_FIRST_MS = 40f
private const val MORPH_LEAD_MS = 110f
private const val MORPH_SWEEP_MS = 420f
private const val MORPH_ROW_MS = 120f
private const val MORPH_FLIGHT_MS = 1_000L
private const val MORPH_HERO_FLIGHT_MS = 1_150L
internal const val MORPH_DISSOLVE_MS = 220L
private const val MORPH_ARC = 0.18f
/** Skärmsläckaren: väggen tonas fram över sidan så här länge, när alla omslag lyft. */
internal const val GATHER_COVER_MS = 700L
private const val MORPH_LIFT = 0.10f
private val FLIGHT_EASING = CubicBezierEasing(0.6f, 0f, 0.2f, 1f)
private const val FLIGHT_SIZE = 1024
private const val POSTER_ASPECT = 2f / 3f
/** Platserna omslagen lämnade fylls på en stund efter slutet (väggens tid). */
private const val REFILL_DELAY_MS = 400L
private const val PI_F = 3.1415927f

/** Startens mörkare hörn: var de börjar och hur mörka de blir, som del av halva diagonalen. */
internal const val VIGNETTE_FROM = 0.55f
internal const val VIGNETTE_DARK = 0.55f
internal const val VIGNETTE_REACH = 0.72f

/** Ljuset på väggen, stilla kring mitten, och djupet. */
private const val POOL_RADIUS = 1.35f
private const val POOL_DARK = 0.32f
private const val INTRO_FOG = 0.50f
private const val BACKDROP_FOG = 0.46f

/** Bakgrunden i appen: hur mörk och hur färgstark, hur fort, och när den står still. */
private const val BACKDROP_BRIGHTNESS = 0.46f
private const val BACKDROP_SATURATION = 0.6f
/** Live TV, Tablå och Inställningar: lika mörk som förut, fast resten blev ljusare. */
private const val QUIET_DIM = 0.68f

/** Filmsiden bakom sidorna: bandets styrka (väggen över det mörkar), kanten och ljuset på omslagen. */
private const val BACKDROP_SILK = 1.5f
private const val BACKDROP_RIM = 1.4f
private const val BACKDROP_COVER_LIGHT = 0.10f
private const val SAVER_COVER_LIGHT = 0.10f
private const val AMBIENT_SPEED = 0.45f
private const val AMBIENT_FRAME_MS = 32L
private const val EASE_MS = 1_400f

/** Korten som tonas fram när en sida visas. */
private const val ENTRANCE_WINDOW_MS = 900L
private const val ENTRANCE_STAGGER_MS = 30L
private const val ENTRANCE_MAX_STEPS = 20
private const val ENTRANCE_MS = 520
private val ENTRANCE_RISE = 18.dp
private val ENTRANCE_EASING = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

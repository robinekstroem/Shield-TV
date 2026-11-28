package se.norrsken.tv.desktop.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Omslagsväggen bakom sidorna, som man väljer under Inställningar: bara
 * väggen, Filmsiden — väggen med ett sidenband bakom omslagen, som i
 * Polarnatt — i lila och rosa, isblått eller guld, eller väggen i 3D
 * ([depth]): omslagen som askar, i lager på olika avstånd eller på en vägg
 * som böjer sig runt en.
 */
enum class WallStyle(
    val label: String,
    val description: String,
    val silk: SilkColors?,
    val depth: WallDepth = WallDepth.FLAT,
) {
    WALL(
        "Filmväggen",
        "Omslagen på en vägg i djupet, som glider sakta bakom sidorna.",
        null,
    ),
    SILK(
        "Filmsiden",
        "Ett sidenband i lila och rosa bakom omslagen. Ljuset lyser fram mellan dem.",
        SilkColors(glow = Color(0xFFA357FF), accent = Color(0xFFFF66C9), thread = Color(0xFFF8EEFF)),
    ),
    WAVE(
        "Våg",
        "Sidenbandet i isblått, som kallt ljus bakom omslagen.",
        SilkColors(glow = Color(0xFF2E9BFF), accent = Color(0xFF63F0FF), thread = Color(0xFFEAFBFF)),
    ),
    CHAMPAGNE(
        "Champagne",
        "Sidenbandet i guld, som varmt ljus bakom omslagen.",
        SilkColors(glow = Color(0xFFFFA33A), accent = Color(0xFFFFE08A), thread = Color(0xFFFFF7E4)),
    ),
    CASES(
        "3D Fodral",
        "Varje omslag en tunn ask med kant och glans, som på hyllan i en videobutik.",
        null,
        WallDepth.CASES,
    ),
    LAYERS(
        "3D Djup",
        "Omslagen i lager på olika avstånd: de närmaste större och ljusare, med skugga bakom.",
        null,
        WallDepth.LAYERS,
    ),
    CURVE(
        "3D Böjd vägg",
        "Väggen böjer sig runt dig, och omslagen glider runt i kurvan.",
        null,
        WallDepth.CURVE,
    ),
}

/**
 * Väggens 3D: platt (som förut), askar med kant ([CASES]), lager på olika
 * avstånd ([LAYERS]) eller en vägg som böjer sig runt tittaren ([CURVE]).
 */
enum class WallDepth { FLAT, CASES, LAYERS, CURVE }

/** Sidenbandets färger: skenet, tonen det skiftar mot längs bandet, och trådarna. */
class SilkColors(val glow: Color, val accent: Color, val thread: Color) {
    internal val glowTint = ColorFilter.tint(glow)
    internal val accentTint = ColorFilter.tint(accent)
}

/**
 * Sidenbandet: fem hårfina, lysande trådar som vrider sig och möts, en nästan
 * genomskinlig hinna emellan och ett mjukt sken, strax ovanför skärmens mitt.
 * Vridningarna vandrar sakta längs bandet, och där trådarna möts glimmar det.
 *
 * Ritas bara med sådant grafikkortet gör direkt — korta raka linjestycken,
 * cirklar och en liten färdig bild för skenet och hinnan, sträckt i smala
 * skivor som följer bandet — så att det går lika lätt på Shielden som i
 * Windows. Inga skuggare, inga stora kurvor att räkna ut, och inget som skapas
 * per bild utom en pensel per glimt. [prepare] räknar ut bandet för en tid;
 * sedan ritar [drawBack] det som ligger bakom omslagen, [drawFront] det som
 * går framför dem (skärmsläckaren) och [drawLight] ljuset i rummet.
 */
internal class SilkBand {
    private var w = 0f
    private var h = 0f
    private var preparedAt = Double.NaN
    /** Bandets tid i sekunder. */
    private var seconds = 0.0
    private val xs = FloatArray(SAMPLES + 1)
    private val centers = FloatArray(SAMPLES + 1)
    /** Halva bandets bredd med tecken: trådarna byter sida där den går genom noll. */
    private val spreads = FloatArray(SAMPLES + 1)
    private val glintX = FloatArray(MAX_GLINTS)
    private val glintY = FloatArray(MAX_GLINTS)
    private var glints = 0
    private val crossX = FloatArray(MAX_GLINTS)
    private val crossY = FloatArray(MAX_GLINTS)
    private var crossings = 0
    private var frontPhase = 0f
    private var threadWidth = 1.5f

    /** Bandet vid tiden [timeMs] (bandets egen tid) i en bild som är [width] × [height]. */
    fun prepare(width: Float, height: Float, timeMs: Double) {
        if (width == w && height == h && timeMs == preparedAt) return
        w = width
        h = height
        preparedAt = timeMs
        seconds = timeMs / 1000.0
        threadWidth = max(1.2f, h * 0.0016f)
        val wave = phase(0.085)
        val ripple = phase(0.13)
        val breath = phase(0.07)
        val twist = phase(0.085)
        frontPhase = phase(0.05)
        val left = -EDGE * w
        val right = (1f + EDGE) * w
        for (i in 0..SAMPLES) {
            val x = left + (right - left) * i / SAMPLES
            val u = x / w
            xs[i] = x
            centers[i] = h * (BASE_Y + 0.070f * sin(TAU * 0.40f * u + wave + 0.9f) + 0.028f * sin(TAU * 1.05f * u - ripple + 2.1f))
            val envelope = h * (0.016f + 0.030f * (0.5f + 0.5f * sin(TAU * 0.55f * u + breath + 0.4f)))
            spreads[i] = envelope * cos(TAU * TWIST_FREQ * u + twist)
        }
        // Där trådarna möts: vridningen står på kant.
        glints = 0
        val k = TAU * TWIST_FREQ
        val from = ceil((k * left / w + twist - PI_F / 2f) / PI_F).toInt()
        val to = floor((k * right / w + twist - PI_F / 2f) / PI_F).toInt()
        for (n in from..to) {
            if (glints >= MAX_GLINTS) break
            val x = (PI_F / 2f + n * PI_F - twist) / k * w
            glintX[glints] = x
            glintY[glints] = centerAt(x)
            glints++
        }
        // Där bandet går genom väggen (skärmsläckaren): framför blir bakom.
        crossings = 0
        val kf = TAU * FRONT_FREQ
        val cf = ceil((kf * left / w - frontPhase + 1f) / PI_F).toInt()
        val ct = floor((kf * right / w - frontPhase + 1f) / PI_F).toInt()
        for (n in cf..ct) {
            if (crossings >= MAX_GLINTS) break
            val x = (n * PI_F + frontPhase - 1f) / kf * w
            crossX[crossings] = x
            crossY[crossings] = centerAt(x)
            crossings++
        }
    }

    /** Hur nära bandet punkten är: 1 mitt i det, 0 en bit ifrån. För omslagens kant och ljus. */
    fun nearness(x: Float, y: Float): Float {
        if (h <= 0f) return 0f
        val reach = h * RIM_REACH + abs(spreadAt(x))
        val d = abs(y - centerAt(x)) / reach
        return if (d >= 1f) 0f else (1f - d) * (1f - d)
    }

    /** Hur mycket av bandet som går framför omslagen vid [x] (skärmsläckaren): 0 bakom, 1 framför. */
    fun front(x: Float): Float {
        if (w <= 0f) return 0f
        val s = sin(TAU * FRONT_FREQ * x / w - frontPhase + 1f)
        return smoothstep(-0.3f, 0.3f, s)
    }

    /** Det som ligger bakom omslagen: skenet, hinnan, trådarna och glimtarna. Syns i mellanrummen. */
    fun DrawScope.drawBack(colors: SilkColors, strength: Float) {
        glow(glowStrip, colors.glowTint, radius = 0.15f, gain = 1.8f, alpha = 0.62f * strength)
        glow(glowStrip, colors.accentTint, radius = 0.05f, gain = 1.15f, alpha = 0.45f * strength)
        membrane(colors, 0.16f * strength, frontOnly = false)
        threads(colors, strength, frontOnly = false)
        for (i in 0 until glints) glint(colors, glintX[i], glintY[i], 0.95f * strength, 1f)
    }

    /**
     * Skärmsläckaren: bandet slingrar sig genom väggen — det här är bitarna
     * framför omslagen, med en skugga på dem och ett sken där det går igenom.
     */
    fun DrawScope.drawFront(colors: SilkColors, strength: Float) {
        glow(glowStrip, SHADOW_TINT, radius = 0.05f, gain = 1.0f, alpha = 0.40f * strength, frontOnly = true, dy = h * 0.035f)
        glow(glowStrip, colors.glowTint, radius = 0.07f, gain = 1.3f, alpha = 0.40f * strength, frontOnly = true)
        glow(glowStrip, colors.accentTint, radius = 0.035f, gain = 1.0f, alpha = 0.32f * strength, frontOnly = true)
        membrane(colors, 0.22f * strength, frontOnly = true)
        threads(colors, strength, frontOnly = true)
        for (i in 0 until glints) {
            val f = front(glintX[i])
            if (f > 0.02f) glint(colors, glintX[i], glintY[i], 0.95f * strength * f, 1f)
        }
        // Där bandet går genom väggen lyser det till.
        for (i in 0 until crossings) glint(colors, crossX[i], crossY[i], 0.75f * strength, 1.5f)
    }

    /** Ljuset i rummet: omslagen nära bandet får lite av det, och ljusprickar driver omkring. */
    fun DrawScope.drawLight(colors: SilkColors, strength: Float) {
        glow(glowStrip, colors.glowTint, radius = 0.11f, gain = 1.4f, alpha = 0.12f * strength)
        for (i in 0 until glints) glint(colors, glintX[i], glintY[i], 0.30f * strength, 0.8f)
        specks(colors, strength)
    }

    // --- Delarna --------------------------------------------------------------------------

    private fun phase(perSecond: Double): Float = ((perSecond * seconds) % (2.0 * PI)).toFloat()

    private fun outer(k: Int): Boolean = k == 0 || k == THREADS - 1

    private fun sampleAt(x: Float): Float {
        val left = xs[0]
        val right = xs[SAMPLES]
        if (right <= left) return 0f
        return ((x - left) / (right - left) * SAMPLES).coerceIn(0f, SAMPLES.toFloat())
    }

    private fun centerAt(x: Float): Float {
        val f = sampleAt(x)
        val i = min(f.toInt(), SAMPLES - 1)
        return centers[i] + (centers[i + 1] - centers[i]) * (f - i)
    }

    private fun spreadAt(x: Float): Float {
        val f = sampleAt(x)
        val i = min(f.toInt(), SAMPLES - 1)
        return spreads[i] + (spreads[i + 1] - spreads[i]) * (f - i)
    }

    /**
     * Ett mjukt sken längs bandet: [strip] sträckt i smala skivor som följer
     * bandet. [radius] i bildens höjd, [gain] hur mycket bandets egen bredd
     * lägger till, [frontOnly] bara där bandet går framför omslagen.
     */
    private fun DrawScope.glow(
        strip: ImageBitmap,
        tint: ColorFilter,
        radius: Float,
        gain: Float,
        alpha: Float,
        frontOnly: Boolean = false,
        dy: Float = 0f,
    ) {
        if (alpha <= 0.003f) return
        val left = xs[0]
        val step = (xs[SAMPLES] - left) / SLICES
        var x0 = left.roundToInt()
        for (s in 0 until SLICES) {
            val x1 = (left + step * (s + 1)).roundToInt()
            val mid = left + step * (s + 0.5f)
            val a = if (frontOnly) alpha * front(mid) else alpha
            if (a > 0.003f && x1 > x0) {
                val r = h * radius + abs(spreadAt(mid)) * gain
                drawImage(
                    image = strip,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(STRIP_W, STRIP_H),
                    dstOffset = IntOffset(x0, (centerAt(mid) + dy - r).roundToInt()),
                    dstSize = IntSize(x1 - x0, (2f * r).roundToInt().coerceAtLeast(1)),
                    alpha = a.coerceAtMost(1f),
                    colorFilter = tint,
                    filterQuality = FilterQuality.Low,
                )
            }
            x0 = x1
        }
    }

    /** Hinnan mellan de yttre trådarna: tunn där bandet står på kant, bred där det vänder sig mot en. */
    private fun DrawScope.membrane(colors: SilkColors, alpha: Float, frontOnly: Boolean) {
        val left = xs[0]
        val step = (xs[SAMPLES] - left) / MEMBRANE_SLICES
        var x0 = left.roundToInt()
        for (s in 0 until MEMBRANE_SLICES) {
            val x1 = (left + step * (s + 1)).roundToInt()
            val mid = left + step * (s + 0.5f)
            val half = abs(spreadAt(mid))
            val a = if (frontOnly) alpha * front(mid) else alpha
            if (a > 0.003f && x1 > x0 && half > 1f) {
                val pad = half * 0.3f + 1f
                drawImage(
                    image = plateauStrip,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(STRIP_W, STRIP_H),
                    dstOffset = IntOffset(x0, (centerAt(mid) - half - pad).roundToInt()),
                    dstSize = IntSize(x1 - x0, (2f * (half + pad)).roundToInt().coerceAtLeast(1)),
                    alpha = a.coerceAtMost(1f),
                    colorFilter = colors.accentTint,
                    filterQuality = FilterQuality.Low,
                )
            }
            x0 = x1
        }
    }

    /** Trådarna: korta raka stycken, de yttre ljusast. Framför omslagen bara där bandet är framför. */
    private fun DrawScope.threads(colors: SilkColors, strength: Float, frontOnly: Boolean) {
        for (k in 0 until THREADS) {
            val f = THREAD_AT[k]
            val base = (if (outer(k)) 0.95f else 0.6f) * strength
            var x0 = xs[0]
            var y0 = centers[0] + f * spreads[0]
            for (i in 1..SAMPLES) {
                val x1 = xs[i]
                val y1 = centers[i] + f * spreads[i]
                val a = if (frontOnly) base * front((x0 + x1) * 0.5f) else base
                if (a > 0.01f) {
                    drawLine(colors.thread, Offset(x0, y0), Offset(x1, y1), strokeWidth = threadWidth, alpha = a.coerceAtMost(1f))
                }
                x0 = x1
                y0 = y1
            }
        }
    }

    private fun DrawScope.glint(colors: SilkColors, x: Float, y: Float, alpha: Float, scale: Float) {
        if (alpha <= 0.01f) return
        val r = h * 0.045f * scale
        val center = Offset(x, y)
        drawCircle(
            Brush.radialGradient(
                0f to colors.thread.copy(alpha = alpha.coerceAtMost(1f)),
                0.16f to colors.glow.copy(alpha = (alpha * 0.55f).coerceAtMost(1f)),
                1f to Color.Transparent,
                center = center,
                radius = r,
            ),
            radius = r,
            center = center,
        )
    }

    private fun DrawScope.specks(colors: SilkColors, strength: Float) {
        for (k in 0 until SPECKS) {
            val speed = 0.004 + 0.010 * silkHash(k, 2)
            val direction = if (k % 2 == 0) 1.0 else -1.0
            var u = ((silkHash(k, 1) + direction * speed * seconds) % 1.0).toFloat()
            if (u < 0f) u += 1f
            val x = (-0.03f + u * 1.06f) * w
            val off = silkHash(k, 3) * 2f - 1f
            val drift = sin(((0.3 + 0.2 * silkHash(k, 6)) * seconds + k) % (2.0 * PI)).toFloat()
            val y = centerAt(x) + off * abs(off) * h * 0.22f + drift * h * 0.012f
            val twinkle = 0.5f + 0.5f * sin(((0.6 + 1.2 * silkHash(k, 4)) * seconds + k * 1.7) % (2.0 * PI)).toFloat()
            val near = 1f - abs(off)
            val a = strength * (0.25f + 0.75f * twinkle) * (0.35f + 0.65f * near) * 0.75f
            val r = h * (0.0011f + 0.0016f * silkHash(k, 5))
            drawCircle(colors.thread, radius = r, center = Offset(x, y), alpha = a.coerceIn(0f, 1f))
        }
    }

    companion object {
        private const val SAMPLES = 48
        private const val SLICES = 36
        private const val MEMBRANE_SLICES = 48
        private const val THREADS = 5
        private val THREAD_AT = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
        private const val MAX_GLINTS = 6
        private const val SPECKS = 34
        /** Bandet går en bit utanför bilden på båda sidor. */
        private const val EDGE = 0.06f
        /** Strax ovanför mitten, som i Polarnatt. */
        private const val BASE_Y = 0.42f
        private const val TWIST_FREQ = 0.62f
        private const val FRONT_FREQ = 0.5f
        /** Hur långt från bandet omslagen får kant och ljus, i bildens höjd. */
        private const val RIM_REACH = 0.17f
        private const val TAU = 6.2831855f
        private const val PI_F = 3.1415927f
        private const val STRIP_W = 4
        private const val STRIP_H = 128
        private val SHADOW_TINT = ColorFilter.tint(Color.Black)

        /** Skenets profil: vit, mjuk som en klocka på höjden. Görs en gång. */
        private val glowStrip: ImageBitmap by lazy {
            strip(List(15) { k ->
                val d = (k / 14f - 0.5f) * 2f
                Color.White.copy(alpha = exp(-4.2f * d * d))
            }, null)
        }

        /** Hinnans profil: jämn i mitten, mjuk i kanterna. */
        private val plateauStrip: ImageBitmap by lazy {
            strip(
                listOf(Color.Transparent, Color.White, Color.White, Color.Transparent),
                listOf(0f, 0.25f, 0.75f, 1f),
            )
        }

        private fun strip(colors: List<Color>, stops: List<Float>?): ImageBitmap {
            val image = ImageBitmap(STRIP_W, STRIP_H)
            val paint = Paint().apply {
                shader = LinearGradientShader(Offset(0f, 0f), Offset(0f, STRIP_H.toFloat()), colors, stops)
            }
            Canvas(image).drawRect(0f, 0f, STRIP_W.toFloat(), STRIP_H.toFloat(), paint)
            return image
        }
    }
}

/** Ett tal mellan 0 och 1 som alltid blir detsamma för samma (i, k). */
private fun silkHash(i: Int, k: Int): Float {
    var x = i * 374761393 + k * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16777215f
}

/**
 * Filmsiden: ett omslag nära bandet. Ljuset bakom lyser fram runt det, i
 * mellanrummet: ramar i bandets färg som omslaget sedan täcker, ljusast
 * närmast kanten. Ritas före omslaget, i omslagets egna mått.
 */
internal fun DrawScope.rimBehind(colors: SilkColors, near: Float, tileW: Float, tileH: Float, gap: Float, gain: Float) {
    rimFrame(colors.glow, gap * 0.46f, near * 0.22f * gain, tileW, tileH)
    rimFrame(colors.glow, gap * 0.26f, near * 0.32f * gain, tileW, tileH)
    rimFrame(colors.thread, gap * 0.075f, near * 0.85f * gain, tileW, tileH)
}

private fun DrawScope.rimFrame(color: Color, out: Float, alpha: Float, tileW: Float, tileH: Float) {
    if (alpha <= 0.005f) return
    drawRect(color, topLeft = Offset(-out, -out), size = Size(tileW + 2f * out, tileH + 2f * out), alpha = alpha.coerceAtMost(1f))
}

/** Filmsiden: omslaget nära bandet får lite av ljuset, som ljus som läggs till. Efter omslaget. */
internal fun DrawScope.coverLit(colors: SilkColors, near: Float, amount: Float, tileW: Float, tileH: Float) {
    val a = near * amount
    if (a <= 0.005f) return
    drawRect(colors.glow, size = Size(tileW, tileH), alpha = a.coerceAtMost(1f), blendMode = BlendMode.Plus)
}

/**
 * Inställningarnas lilla bild av en vägg: omslag snett i bild och, med
 * Filmsiden, bandet bakom dem med ljuset i mellanrummen och kanten runt
 * omslagen nära det. Ritas en gång, stilla.
 */
internal fun DrawScope.wallMiniature(colors: SilkColors?, band: SilkBand) {
    drawRect(MINI_BACK)
    val w = size.width
    val h = size.height
    if (colors != null) {
        band.prepare(w, h, MINI_TIME_MS)
        with(band) { drawBack(colors, 1f) }
    }
    val tileH = h * 0.42f
    val tileW = tileH * 2f / 3f
    val gap = tileH * 0.09f
    val corner = CornerRadius(tileW * 0.05f)
    val turn = MINI_TURN * PI.toFloat() / 180f
    val cosT = cos(turn)
    val sinT = sin(turn)
    val px = w / 2f
    val py = h / 2f
    rotate(MINI_TURN) {
        var r = -2
        var y = -tileH * 0.95f
        while (y < h + tileH) {
            val shift = ((r * 0.382f) % 1f + 1f) % 1f * (tileW + gap)
            var c = -3
            var x = -tileW * 2f + shift
            while (x < w + tileW) {
                val color = MINI_COVERS[(r * 5 + c * 3).mod(MINI_COVERS.size)]
                // Bandet ligger rakt, omslagen snett: var omslaget hamnar i bilden.
                val lx = x + tileW / 2f - px
                val ly = y + tileH / 2f - py
                val near = if (colors != null) band.nearness(px + lx * cosT - ly * sinT, py + lx * sinT + ly * cosT) else 0f
                if (colors != null && near > 0.02f) {
                    translate(x, y) { rimBehind(colors, near, tileW, tileH, gap, 1.2f) }
                }
                drawRoundRect(
                    Brush.verticalGradient(listOf(color, color.darker(0.55f)), startY = y, endY = y + tileH),
                    topLeft = Offset(x, y),
                    size = Size(tileW, tileH),
                    cornerRadius = corner,
                )
                if (colors != null && near > 0.02f) {
                    translate(x, y) { coverLit(colors, near, 0.25f, tileW, tileH) }
                }
                x += tileW + gap
                c++
            }
            y += tileH + gap
            r++
        }
    }
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.45f))))
    // Här syns bandet också där det går framför omslagen, så att det känns igen i den lilla bilden.
    if (colors != null) {
        with(band) {
            drawFront(colors, 1f)
            drawLight(colors, 1f)
        }
    }
}

/**
 * Den lilla bilden av väggen i 3D: askar med kant och glans, lager med de
 * närmaste större och ljusare, eller kolumner som vänder sig mot mitten.
 */
internal fun DrawScope.wallMiniature3D(depth: WallDepth) {
    drawRect(MINI_BACK)
    val w = size.width
    val h = size.height
    val tileH = h * 0.4f
    val tileW = tileH * 2f / 3f
    val gap = tileH * 0.1f
    val corner = CornerRadius(tileW * 0.05f)
    fun cover(color: Color, x: Float, y: Float, cw: Float, ch: Float, alpha: Float = 1f) {
        drawRoundRect(
            Brush.verticalGradient(listOf(color, color.darker(0.55f)), startY = y, endY = y + ch),
            topLeft = Offset(x, y),
            size = Size(cw, ch),
            cornerRadius = corner,
            alpha = alpha,
        )
    }
    when (depth) {
        WallDepth.CASES -> {
            val side = tileW * 0.16f
            var r = 0
            var y = -tileH * 0.45f
            while (y < h) {
                var x = -tileW * 0.6f + (if (r % 2 == 0) 0f else tileW * 0.5f)
                var c = 0
                while (x < w) {
                    val color = MINI_COVERS[(r * 5 + c * 3).mod(MINI_COVERS.size)]
                    // Kanten till höger och nedtill, sedan framsidan med glansen.
                    val edge = Path().apply {
                        moveTo(x + tileW, y)
                        lineTo(x + tileW + side, y + side * 0.6f)
                        lineTo(x + tileW + side, y + tileH + side * 0.6f)
                        lineTo(x + side, y + tileH + side * 0.6f)
                        lineTo(x, y + tileH)
                        lineTo(x + tileW, y + tileH)
                        close()
                    }
                    drawPath(edge, Color(0xFF15171D))
                    cover(color, x, y, tileW, tileH)
                    drawRect(
                        Brush.linearGradient(
                            0.3f to Color.Transparent,
                            0.47f to Color.White.copy(alpha = 0.3f),
                            0.62f to Color.Transparent,
                            start = Offset(x, y),
                            end = Offset(x + tileW, y + tileH),
                        ),
                        topLeft = Offset(x, y),
                        size = Size(tileW, tileH),
                    )
                    x += tileW + gap + side
                    c++
                }
                y += tileH + gap + side * 0.6f
                r++
            }
        }
        WallDepth.LAYERS -> {
            // Långt bort: små och mörka.
            val small = 0.55f
            var r = 0
            var y = -tileH * small * 0.3f
            while (y < h) {
                var x = -tileW * small * 0.3f + (if (r % 2 == 0) 0f else tileW * small * 0.5f)
                var c = 0
                while (x < w) {
                    cover(MINI_COVERS[(r * 3 + c * 5).mod(MINI_COVERS.size)], x, y, tileW * small, tileH * small, alpha = 0.45f)
                    x += (tileW + gap) * small
                    c++
                }
                y += (tileH + gap) * small
                r++
            }
            // Nära: stora och ljusa, med skugga.
            val near = listOf(Offset(0.06f, 0.18f), Offset(0.42f, 0.42f), Offset(0.74f, 0.1f))
            near.forEachIndexed { k, spot ->
                val x = spot.x * w
                val y = spot.y * h
                val cw = tileW * 1.15f
                val ch = tileH * 1.15f
                drawRoundRect(Color.Black.copy(alpha = 0.5f), topLeft = Offset(x + cw * 0.1f, y + ch * 0.12f), size = Size(cw, ch), cornerRadius = corner)
                cover(MINI_COVERS[(k * 4 + 1).mod(MINI_COVERS.size)], x, y, cw, ch)
            }
        }
        WallDepth.CURVE -> {
            // Kolumner som vänder sig mot mitten: smalare och högre ut mot kanterna.
            val columns = 9
            for (c in 0 until columns) {
                val t = (c - (columns - 1) / 2f) / ((columns - 1) / 2f)
                val grow = 1f + 0.35f * t * t
                val cw = tileW * (1f - 0.35f * t * t) * 0.95f
                val ch = tileH * grow
                val cx = w / 2f + t * w * 0.55f
                val lean = t * ch * 0.12f
                var r = -1
                var y = h / 2f - ch * 1.6f + (if (c % 2 == 0) 0f else ch * 0.45f)
                while (y < h) {
                    val color = MINI_COVERS[(r * 5 + c * 3).mod(MINI_COVERS.size)]
                    val left = cx - cw / 2f
                    val panel = Path().apply {
                        moveTo(left, y - lean)
                        lineTo(left + cw, y + lean)
                        lineTo(left + cw, y + ch - lean)
                        lineTo(left, y + ch + lean)
                        close()
                    }
                    drawPath(panel, Brush.verticalGradient(listOf(color, color.darker(0.55f)), startY = y, endY = y + ch), alpha = 1f - 0.35f * abs(t))
                    y += ch + gap * grow
                    r++
                }
            }
        }
        WallDepth.FLAT -> Unit
    }
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.45f))))
}

private fun Color.darker(by: Float): Color = Color(red * (1f - by), green * (1f - by), blue * (1f - by), alpha)

private const val MINI_TIME_MS = 41_000.0
private const val MINI_TURN = -11f
private val MINI_BACK = Color(0xFF05070A)
private val MINI_COVERS = listOf(
    Color(0xFF34507A), Color(0xFF6B3550), Color(0xFF2C6B5E), Color(0xFF7A5A30),
    Color(0xFF4E3C7E), Color(0xFF3F6A34), Color(0xFF7E3434), Color(0xFF2F5C7E),
    Color(0xFF5C2F6B), Color(0xFF6B6330),
)

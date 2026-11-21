package se.norrsken.tv.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * En kanals logga, lika stor som alla andra och utan platta bakom.
 *
 * Leverantörernas loggor är olika stora, har ofta tomma genomskinliga kanter
 * och ibland en egen vit ruta. Här klipps de tomma kanterna bort, och varje
 * logga får ungefär lika stor yta i rutan — en bred logga hela bredden, en
 * fyrkantig mindre — så att listan ser lugn ut. En logga med egen bakgrund får
 * rundade hörn, och en svart logga, som annars försvinner mot den mörka
 * bakgrunden, ritas i textens färg. Saknas loggan: kanalens initialer i en
 * tunn ram.
 *
 * Bilden avkodas större än rutan och krymps mjukt, så att kanterna blir
 * skarpa i stället för pixliga.
 */
@Composable
fun ChannelLogo(
    url: String?,
    name: String,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    initialsStyle: TextStyle = MaterialTheme.typography.labelMedium,
) {
    val palette = LocalPalette.current
    val key = url?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
    // Större än rutan: när tomma kanter klipps bort förstoras resten.
    val side = with(LocalDensity.current) { (maxOf(width, height).toPx() * 2f).roundToInt() }.coerceIn(96, 720)
    val initial = remember(key, side) { key?.let { Images.cached(it, side) } }
    var image by remember(key, side) { mutableStateOf(initial) }
    var missing by remember(key, side) { mutableStateOf(key == null) }
    if (key != null && image == null && !missing) {
        LaunchedEffect(key, side) {
            val loaded = Images.load(key, side)
            if (loaded == null) missing = true else image = loaded
        }
    }
    val current = image
    val shape = if (current != null && key != null) rememberLogoShape(key, side, current) else null
    val alpha by animateFloatAsState(
        targetValue = if (shape != null) 1f else 0f,
        animationSpec = tween(if (initial != null) 0 else 140),
        label = "kanallogga",
    )
    Box(modifier.size(width, height), contentAlignment = Alignment.Center) {
        if (current != null && shape != null) {
            val ink = palette.textPrimary
            Canvas(Modifier.size(width, height)) { drawLogo(current, shape, alpha, ink) }
        } else if (missing) {
            Box(
                Modifier
                    .size(width = minOf(width, height * 1.6f), height = height)
                    .border(1.dp, palette.hairline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initials(name),
                    style = initialsStyle,
                    color = palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** Var i bilden loggan faktiskt finns, och hur den ska ritas. */
private class LogoShape(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    /** Loggan har egen bakgrund ända ut i hörnen: rundas av. */
    val opaque: Boolean,
    /** Svart eller mörkgrå på genomskinligt: ritas i textens färg. */
    val dark: Boolean,
)

/** Uträknat en gång per logga och storlek, i hela appen. */
private val shapes = ConcurrentHashMap<String, LogoShape>()

@Composable
private fun rememberLogoShape(key: String, side: Int, image: ImageBitmap): LogoShape? {
    val id = "$side|$key"
    var shape by remember(id, image) { mutableStateOf(shapes[id]) }
    if (shape == null) {
        LaunchedEffect(id, image) {
            val found = withContext(Dispatchers.Default) { runCatching { analyse(image) }.getOrNull() }
                ?: LogoShape(0, 0, image.width, image.height, opaque = false, dark = false)
            if (shapes.size > MAX_SHAPES) shapes.clear()
            shapes[id] = found
            shape = found
        }
    }
    return shape
}

/** Går igenom bildens punkter en gång: var loggan finns, och om den är mörk eller har egen bakgrund. */
private fun analyse(image: ImageBitmap): LogoShape {
    val w = image.width
    val h = image.height
    val pixels = IntArray(w * h)
    image.readPixels(pixels)
    var minX = w
    var minY = h
    var maxX = -1
    var maxY = -1
    var light = 0.0
    var colour = 0.0
    var count = 0
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            val p = pixels[row + x]
            if ((p ushr 24) < SEEN_ALPHA) continue
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val most = maxOf(r, g, b)
            val least = minOf(r, g, b)
            light += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
            colour += if (most == 0) 0.0 else (most - least).toDouble() / most
            count++
        }
    }
    if (maxX < 0) return LogoShape(0, 0, w, h, opaque = false, dark = false)
    fun solid(x: Int, y: Int) = (pixels[y * w + x] ushr 24) >= SOLID_ALPHA
    val opaque = solid(minX, minY) && solid(maxX, minY) && solid(minX, maxY) && solid(maxX, maxY)
    val dark = !opaque && light / count < DARK_LIGHTNESS && colour / count < GREY_COLOUR
    return LogoShape(minX, minY, maxX - minX + 1, maxY - minY + 1, opaque, dark)
}

private fun DrawScope.drawLogo(image: ImageBitmap, shape: LogoShape, alpha: Float, ink: Color) {
    val w = shape.width.toFloat()
    val h = shape.height.toFloat()
    // Inpassad, och alla ungefär lika stora: samma yta, så långt rutan räcker.
    val fit = minOf(size.width / w, size.height / h)
    val even = sqrt(size.width * size.height * LOGO_AREA / (w * h))
    val scale = minOf(fit, even)
    val dw = (w * scale).roundToInt().coerceAtLeast(1)
    val dh = (h * scale).roundToInt().coerceAtLeast(1)
    val left = ((size.width - dw) / 2f).roundToInt()
    val top = ((size.height - dh) / 2f).roundToInt()
    val filter = if (shape.dark) ColorFilter.tint(ink, BlendMode.SrcIn) else null
    val draw: DrawScope.() -> Unit = {
        drawImage(
            image = image,
            srcOffset = IntOffset(shape.left, shape.top),
            srcSize = IntSize(shape.width, shape.height),
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(dw, dh),
            alpha = alpha,
            colorFilter = filter,
            filterQuality = FilterQuality.High,
        )
    }
    if (shape.opaque) {
        val radius = minOf(dw, dh) * 0.16f
        val path = Path().apply {
            addRoundRect(RoundRect(left.toFloat(), top.toFloat(), (left + dw).toFloat(), (top + dh).toFloat(), CornerRadius(radius)))
        }
        clipPath(path) { draw(this) }
    } else {
        draw(this)
    }
}

/** Andelen av rutan varje logga får täcka. Breda loggor når hela bredden först. */
private const val LOGO_AREA = 0.45f
/** Svagare än så räknas inte som en del av loggan. */
private const val SEEN_ALPHA = 24
private const val SOLID_ALPHA = 230
private const val DARK_LIGHTNESS = 0.22
private const val GREY_COLOUR = 0.25
private const val MAX_SHAPES = 4_000

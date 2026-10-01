package se.norrsken.tv.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SLIDE_SECONDS = 40f
private const val FADE_SECONDS = 4f

/**
 * Egna bakgrundsbilder bakom appen. Flera bilder byter av varandra med en
 * långsam övertoning. Temats färger läggs över — mörkare nedåt och en aning
 * av accentfärgen — så att bilderna smälter in i vilket tema som helst och
 * texten går att läsa.
 */
@Composable
fun CustomBackdrop(paths: List<String>, running: Boolean, modifier: Modifier = Modifier) {
    if (paths.isEmpty()) return
    val palette = LocalPalette.current
    val images = rememberBackgroundImages(paths, maxSide = 2560).values.toList()
    val seconds = rememberSeconds(running, fps = 20)
    Canvas(modifier) {
        if (images.isEmpty()) return@Canvas
        val t = seconds.floatValue
        val index = (t / SLIDE_SECONDS).toInt() % images.size
        val local = t % SLIDE_SECONDS
        val mix = if (images.size > 1 && local > SLIDE_SECONDS - FADE_SECONDS) {
            (local - (SLIDE_SECONDS - FADE_SECONDS)) / FADE_SECONDS
        } else {
            0f
        }
        // Långsam zoom fram och tillbaka: bilden lever utan att dra blicken.
        val zoom = 1.03f + 0.03f * sin(t * 0.05f)
        drawCover(images[index], zoom, alpha = 1f)
        if (mix > 0f) drawCover(images[(index + 1) % images.size], zoom, alpha = mix)
        drawRect(Brush.verticalGradient(listOf(palette.background.copy(alpha = 0.5f), palette.background.copy(alpha = 0.85f))))
        drawRect(palette.focus.copy(alpha = 0.05f))
    }
}

/** Bilden fyller ytan och beskärs i kanterna, som ett skrivbordsunderlägg. */
private fun DrawScope.drawCover(image: ImageBitmap, zoom: Float, alpha: Float) {
    val scale = maxOf(size.width / image.width, size.height / image.height) * zoom
    val srcWidth = (size.width / scale).coerceAtMost(image.width.toFloat())
    val srcHeight = (size.height / scale).coerceAtMost(image.height.toFloat())
    drawImage(
        image = image,
        srcOffset = IntOffset(((image.width - srcWidth) / 2f).roundToInt(), ((image.height - srcHeight) / 2f).roundToInt()),
        srcSize = IntSize(srcWidth.roundToInt(), srcHeight.roundToInt()),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Medium,
    )
}

/** Bilderna inlästa och nedskalade, i samma ordning som sökvägarna. Laddas i bakgrunden. */
@Composable
internal fun rememberBackgroundImages(paths: List<String>, maxSide: Int): Map<String, ImageBitmap> {
    var images by remember(paths, maxSide) { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    LaunchedEffect(paths, maxSide) {
        images = withContext(Dispatchers.IO) {
            val loaded = LinkedHashMap<String, ImageBitmap>()
            for (path in paths) {
                runCatching { Images.decodeFile(File(path).readBytes(), maxSide) }.getOrNull()?.let { loaded[path] = it }
            }
            loaded
        }
    }
    return images
}


package se.norrsken.tv.desktop.ui

import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.RRect
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.FilterBlurMode
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame as AwtFrame
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import kotlin.math.roundToInt
import kotlin.math.roundToInt

import org.jetbrains.skia.Image as SkImage
import org.jetbrains.skia.Paint as SkPaint
import org.jetbrains.skia.Rect as SkRect

import org.jetbrains.skia.Shader
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.player.Frame
import se.norrsken.tv.desktop.player.VideoPlayer
import se.norrsken.tv.desktop.player.YuvRenderer

// Det som skiljer Windows från Shield i gränssnittet. Shield-bygget har en
// egen fil med samma namn och samma funktioner, skrivna för Android.

/** Rullningslisterna i temats färger. */
@Composable
fun ProvideScrollbars(palette: Palette, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalScrollbarStyle provides ScrollbarStyle(
            minimalHeight = 32.dp,
            thickness = 8.dp,
            shape = RoundedCornerShape(4.dp),
            hoverDurationMillis = 250,
            unhoverColor = palette.focus.copy(alpha = 0.10f),
            hoverColor = palette.focus.copy(alpha = 0.28f),
        ),
    ) {
        content()
    }
}

@Composable
fun Scrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    VerticalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier)
}

@Composable
fun Scrollbar(state: LazyGridState, modifier: Modifier = Modifier) {
    VerticalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier)
}

@Composable
fun Scrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    VerticalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier)
}

@Composable
fun HorizontalBar(state: ScrollState, modifier: Modifier = Modifier) {
    HorizontalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier)
}

/**
 * Glöd bakom ett element, i elementets form: en suddad kopia av formen i
 * glödens färg, ritad innan elementet. Läggs före clip i kedjan, annars
 * klipps glöden bort. Genomskinlig färg ritar ingenting.
 *
 * Ritas med Skias egen pensel direkt på duken — Compose-penseln har ingen
 * suddning. Samma väg som videobilden i spelaren.
 */
fun Modifier.glow(color: Color, shape: Shape, radius: Dp = 18.dp): Modifier =
    if (color.alpha <= 0.01f) {
        this
    } else {
        drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            val sigma = radius.toPx() / 2f
            val argb = color.toArgb()
            drawIntoCanvas { canvas ->
                val blur = MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma)
                val paint = SkiaPaint()
                paint.isAntiAlias = true
                paint.color = argb
                paint.maskFilter = blur
                val native = canvas.nativeCanvas
                when (outline) {
                    is Outline.Rounded -> {
                        val box = outline.roundRect
                        native.drawRRect(
                            RRect.makeLTRB(box.left, box.top, box.right, box.bottom, box.topLeftCornerRadius.x),
                            paint,
                        )
                    }
                    else -> {
                        val bounds = outline.bounds
                        native.drawRect(SkiaRect.makeLTRB(bounds.left, bounds.top, bounds.right, bounds.bottom), paint)
                    }
                }
                paint.close()
                blur.close()
            }
        }
    }

/** Öppnar en adress i webbläsaren. */
internal fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
    }
}

/** Windows egen fildialog, flera bilder åt gången. Tom lista om man avbryter. */
internal fun pickImages(): List<File> {
    val dialog = FileDialog(null as AwtFrame?, "Välj bakgrundsbilder", FileDialog.LOAD)
    dialog.isMultipleMode = true
    // Filtret i Windows fildialog sätts via filnamnet.
    dialog.file = "*.jpg;*.jpeg;*.png;*.webp;*.bmp"
    dialog.isVisible = true
    return dialog.files.toList()
}

/** Muspekaren göms med kontrollerna, som i alla videospelare. */
internal val hiddenCursor: PointerIcon by lazy {
    runCatching {
        val blank = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(blank, Point(0, 0), "dold"))
    }.getOrElse { PointerIcon(Cursor.getDefaultCursor()) }
}

/**
 * Bilden. Ritas om exakt när VLC levererat en ny ruta: [FrameSink.signal]
 * läses i ritsteget, så Compose ritar om just den här ytan och inget annat.
 */
@Composable
internal fun VideoSurface(player: VideoPlayer, sampleAspect: Float, sharp: Boolean, modifier: Modifier) {
    // Rutan hör till spelaren, inte till ytan. Byts fönster — in i eller ut ur
    // helskärm — visas samma bild direkt, även när videon står pausad och
    // inga nya rutor kommer. Den stängs när spelaren stängs (AppState).
    val holder = remember(player) {
        (player.surfaceState as? FrameHolder) ?: FrameHolder().also { player.surfaceState = it }
    }
    Canvas(modifier) {
        if (player.frames.signal.longValue < 0L) return@Canvas
        player.frames.take()?.let { holder.replace(it) }
        val frame = holder.frame ?: return@Canvas

        val frameAspect = frame.width * sampleAspect / frame.height
        val boxAspect = size.width / size.height
        val drawWidth: Float
        val drawHeight: Float
        if (frameAspect > boxAspect) {
            drawWidth = size.width
            drawHeight = size.width / frameAspect
        } else {
            drawHeight = size.height
            drawWidth = size.height * frameAspect
        }
        val left = (size.width - drawWidth) / 2
        val top = (size.height - drawHeight) / 2

        when (frame) {
            is Frame.Yuv -> {
                val paint = holder.yuvPaint(sharp) ?: return@Canvas
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save()
                    native.translate(left, top)
                    native.scale(drawWidth / frame.width, drawHeight / frame.height)
                    native.drawRect(SkRect.makeWH(frame.width.toFloat(), frame.height.toFloat()), paint)
                    native.restore()
                }
            }
            is Frame.Rgba -> {
                val image = holder.rgbaImage ?: return@Canvas
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(frame.width, frame.height),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
                    filterQuality = if (sharp) FilterQuality.High else FilterQuality.Low,
                )
            }
        }
    }
}

/** Äger rutan som visas just nu och allt som byggts av den. Stänger det när en ny tar över. */
private class FrameHolder : AutoCloseable {
    var frame: Frame? = null
        private set
    var rgbaImage: ImageBitmap? = null
        private set
    private var planes: List<SkImage> = emptyList()
    private var shader: Shader? = null
    private var shaderSharp = false
    private val paint = SkPaint()

    fun replace(next: Frame) {
        clear()
        frame = next
        when (next) {
            is Frame.Rgba -> rgbaImage = next.bitmap.asComposeImageBitmap()
            is Frame.Yuv -> planes = listOf(
                SkImage.makeFromBitmap(next.y),
                SkImage.makeFromBitmap(next.u),
                SkImage.makeFromBitmap(next.v),
            )
        }
    }

    fun yuvPaint(sharp: Boolean): SkPaint? {
        val current = frame as? Frame.Yuv ?: return null
        if (planes.size != 3) return null
        if (shader == null || shaderSharp != sharp) {
            shader?.close()
            shader = YuvRenderer.shader(planes[0], planes[1], planes[2], current.width, current.height, sharp)
            shaderSharp = sharp
        }
        val built = shader ?: return null
        paint.shader = built
        return paint
    }

    private fun clear() {
        shader?.close()
        shader = null
        planes.forEach { it.close() }
        planes = emptyList()
        rgbaImage = null
        frame?.close()
        frame = null
    }

    fun release() {
        clear()
        paint.close()
    }

    override fun close() = release()
}

/** Windows har tangentbord och fildialog; inmatningen från telefonen finns bara på Shield. */
internal const val remoteInputAvailable = false
internal const val canPickFiles = true

@Composable
internal fun RemoteInputPanel(state: AppState) = Unit

/** Menyernas upplösning väljs bara på Shield; i Windows bestämmer fönstret. */
@Composable
internal fun DisplayOptions() = Unit

/** Windows styrs med mus: omslagen förstoras lite vid hovring, ingen titel överst. */
internal const val tvLayout = false

/** Under appens namn i sidomenyn. */
internal const val platformName = "Windows"

/** Bilder läggs till med fildialogen i Windows; på Shield skickas de från telefonen. */
@Composable
internal fun BackgroundUploadHint(state: AppState) = Unit

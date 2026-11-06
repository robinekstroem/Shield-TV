package se.norrsken.tv.desktop.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect as SkiaRect

// iPad-versionen av plattformsdelen för det delade utseendet. Compose på iPad
// ritar med Skia, precis som i Windows, så glöden är samma kod.

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

/** Rullningslister behövs inte med pekskärm: man drar i listan. */
@Composable
fun ProvideScrollbars(palette: Palette, content: @Composable () -> Unit) {
    content()
}

@Composable
fun Scrollbar(state: LazyListState, modifier: Modifier = Modifier) = Unit

@Composable
fun Scrollbar(state: LazyGridState, modifier: Modifier = Modifier) = Unit

@Composable
fun Scrollbar(state: ScrollState, modifier: Modifier = Modifier) = Unit

@Composable
fun HorizontalBar(state: ScrollState, modifier: Modifier = Modifier) = Unit

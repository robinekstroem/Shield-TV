package se.norrsken.tv.desktop.ui

import android.content.Intent
import android.graphics.BlurMaskFilter
import android.net.Uri
import android.view.SurfaceView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import se.norrsken.tv.desktop.data.AppDirs
import se.norrsken.tv.desktop.player.VideoPlayer
import java.io.File

// Det som skiljer Shield från Windows i gränssnittet. Samma namn och samma
// funktioner som Windows-filen, skrivna för Android.

/** Ingen rullningslist med fjärrkontroll: markeringen visar var man är. */
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

/**
 * Glöd bakom ett element, i elementets form: en suddad kopia av formen i
 * glödens färg. Androids egen suddning, som Shields grafikkort ritar.
 */
fun Modifier.glow(color: Color, shape: Shape, radius: Dp = 18.dp): Modifier =
    if (color.alpha <= 0.01f) {
        this
    } else {
        drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            val blur = (radius.toPx() * 0.6f).coerceAtLeast(1f)
            // Före apply: inne i blocket betyder "color" penselns egen färg.
            val argb = color.toArgb()
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    this.color = argb
                    maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                }
                val native = canvas.nativeCanvas
                when (outline) {
                    is Outline.Rounded -> {
                        val box = outline.roundRect
                        native.drawRoundRect(
                            box.left, box.top, box.right, box.bottom,
                            box.topLeftCornerRadius.x, box.topLeftCornerRadius.y, paint,
                        )
                    }
                    else -> {
                        val bounds = outline.bounds
                        native.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, paint)
                    }
                }
            }
        }
    }

/** Öppnar en adress, om det finns något på Shield som kan öppna den. */
internal fun openInBrowser(url: String) {
    runCatching {
        AppDirs.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Ingen fildialog på en tv-box. Egna bakgrundsbilder läggs in från Windows-appen. */
internal fun pickImages(): List<File> = emptyList()

/** Ingen muspekare att gömma; Shield styrs med fjärrkontrollen. */
internal val hiddenCursor: PointerIcon = PointerIcon.Default

/**
 * Bilden. ExoPlayer ritar direkt i en SurfaceView med hårdvarans hjälp —
 * även HDR. Ytan får bildens format; undertexterna ritas ovanpå.
 */
@Composable
internal fun VideoSurface(player: VideoPlayer, sampleAspect: Float, sharp: Boolean, modifier: Modifier) {
    val status by player.status.collectAsState()
    val cues by player.cues.collectAsState()
    Box(modifier, contentAlignment = Alignment.Center) {
        val ratio = if (status.videoWidth > 0 && status.videoHeight > 0) {
            status.videoWidth * sampleAspect / status.videoHeight
        } else {
            16f / 9f
        }
        AndroidView(
            factory = { context -> SurfaceView(context).also { player.attachSurface(it) } },
            modifier = Modifier.aspectRatio(ratio),
            onRelease = { player.detachSurface(it) },
        )
        if (cues.isNotEmpty()) {
            Text(
                cues.joinToString("\n"),
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 64.dp, end = 64.dp, bottom = 56.dp),
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = Color.White,
                    fontFamily = FontFamily.Default,
                    fontStyle = FontStyle.Normal,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 8f),
                ),
            )
        }
    }
}

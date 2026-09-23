package se.norrsken.tv.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalMotion
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.core.ui.theme.LocalThemeStyle

/**
 * Bygger en faktarad utan tomma hål.
 *
 * "2026 • • 4K" uppstår så fort man skriver ihop fält rakt av och något
 * saknas. Här filtreras tomma bort innan de sätts ihop, så raden antingen
 * blir riktig eller försvinner helt.
 */
fun metaLine(vararg parts: String?): String =
    parts.mapNotNull { it?.trim()?.takeIf { value -> value.isNotEmpty() } }
        .joinToString("  •  ")

/**
 * Backdrop med två lager.
 *
 * Den gamla bilden ligger kvar tills den nya faktiskt är inläst — först då
 * korsfadas den in. Utan det blinkar det svart mellan två bilder varje gång
 * fokus flyttas, vilket är det som mest av allt får ett gränssnitt att
 * kännas billigt.
 *
 * Den nya bilden tonar in samtidigt som den går från 1,025 till 1,000 i
 * skala — en rörelse man snarare känner än ser.
 */
@Composable
fun HeroBackdrop(url: String?, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    var shown by remember { mutableStateOf<String?>(null) }
    var incoming by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (url != null && url != shown) {
            incoming = url
            ready = false
        }
        if (url == null) {
            shown = null
            incoming = null
        }
    }

    val fade by animateFloatAsState(
        targetValue = if (ready) 1f else 0f,
        animationSpec = tween(motion.scale(550)),
        label = "heroFade",
    )

    LaunchedEffect(fade, incoming) {
        if (fade >= 1f && incoming != null) {
            shown = incoming
            incoming = null
            ready = false
        }
    }

    Box(modifier.fillMaxSize()) {
        shown?.let { current ->
            AsyncImage(
                model = current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        incoming?.let { next ->
            AsyncImage(
                model = next,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { ready = true },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = fade
                        val scale = 1.025f - 0.025f * fade
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
    }
}

/**
 * Mörkningen som gör texten läsbar utan att se ut som en svart ruta.
 *
 * Fyra lager: en bred gradient från vänster som bär texten, en från botten
 * som syr ihop bilden med innehållsraderna, en mycket svag från toppen, och
 * en vinjett som drar blicken inåt. Stoppen följer spec:ens värden.
 */
@Composable
fun HeroScrim(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val base = palette.background

    Box(
        modifier.fillMaxSize().drawBehind {
            drawRect(
                Brush.horizontalGradient(
                    0.00f to Color.Black.copy(alpha = 0.95f),
                    0.15f to Color.Black.copy(alpha = 0.88f),
                    0.30f to Color.Black.copy(alpha = 0.65f),
                    0.45f to Color.Black.copy(alpha = 0.30f),
                    0.60f to Color.Black.copy(alpha = 0.08f),
                    0.72f to Color.Transparent,
                )
            )
            drawRect(
                Brush.verticalGradient(
                    0.55f to Color.Transparent,
                    0.70f to base.copy(alpha = 0.15f),
                    0.82f to base.copy(alpha = 0.45f),
                    0.92f to base.copy(alpha = 0.75f),
                    1.00f to base,
                )
            )
            drawRect(
                Brush.verticalGradient(
                    0.00f to Color.Black.copy(alpha = 0.35f),
                    0.18f to Color.Transparent,
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                    center = center,
                    radius = size.maxDimension * 0.75f,
                )
            )
        }
    )
}

/** Det heron visar. Allt utom titeln får saknas. */
data class HeroContent(
    val key: String,
    val title: String,
    val meta: String,
    val overview: String?,
    val backdropUrl: String?,
    val isSeries: Boolean,
    val isLive: Boolean,
)

/**
 * Herons textblock till vänster.
 *
 * Byts innehållet tonar det gamla ut nedåt och det nya in underifrån —
 * aldrig två titlar fullt synliga samtidigt. Texten byter snabbare än
 * bilden, så bytet känns lett av innehållet och inte av bilden.
 */
@Composable
fun HeroInfo(
    content: HeroContent?,
    onPlay: () -> Unit,
    onInfo: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val motion = LocalMotion.current
    val style = LocalThemeStyle.current

    var shown by remember { mutableStateOf(content) }
    var visible by remember { mutableStateOf(true) }

    // Nyckeln på hela objektet, inte bara id:t: när TMDB fyller i backdrop
    // och beskrivning i efterhand ändras innehållet men inte nyckeln, och
    // heron ska visa det nya utan att animera om.
    LaunchedEffect(content) {
        if (shown?.key == content?.key) {
            shown = content
            return@LaunchedEffect
        }
        visible = false
        kotlinx.coroutines.delay(motion.scale(140).toLong())
        shown = content
        visible = true
    }

    val appear by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(motion.scale(240)),
        label = "heroText",
    )

    val current = shown ?: return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = metrics.screenEdge)
            .graphicsLayer {
                alpha = appear
                translationY = (1f - appear) * 10.dp.toPx()
            },
        verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
    ) {
        Text(
            text = if (style.uppercaseHeadings) current.title.uppercase() else current.title,
            style = MaterialTheme.typography.displayLarge,
            color = palette.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (current.meta.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (current.isLive) {
                    Box(
                        Modifier
                            .background(palette.signal, MaterialTheme.shapes.small)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "LIVE",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.onAccent,
                        )
                    }
                }
                Text(
                    text = current.meta,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textSecondary,
                )
            }
        }
        current.overview?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.padding(top = metrics.spaceSm),
            horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
        ) {
            PrimaryAction(
                label = when {
                    current.isLive -> "Titta live"
                    current.isSeries -> "Visa avsnitt"
                    else -> "Spela"
                },
                onClick = onPlay,
            )
            if (onInfo != null && !current.isLive) {
                SecondaryAction(label = "Mer info", onClick = onInfo)
            }
        }
    }
}

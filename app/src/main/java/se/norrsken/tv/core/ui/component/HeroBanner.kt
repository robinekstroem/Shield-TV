package se.norrsken.tv.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.core.ui.theme.LocalThemeStyle

/**
 * Titeln som bar hemskarmen.
 *
 * [facts] ar bara det som faktiskt ar kant om titeln — ar aret okant ska
 * raden vara kortare, inte fyllas ut. Formatmarken som 4K eller Dolby Vision
 * hor inte hit: de gar att veta forst nar strommen spelar, och en badge som
 * gissar ar en losgn snyggt satt.
 */
data class HeroContent(
    val id: Long,
    val title: String,
    val facts: List<String>,
    val overview: String?,
    val imageUrl: String?,
    val isSeries: Boolean,
)

@Composable
fun HeroBanner(
    content: HeroContent,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    onInfo: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(metrics.heroHeight),
    ) {
        if (content.imageUrl != null) {
            AsyncImage(
                model = content.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Tva gradienter, inte en. Den vanstra barjar texten, den nedre
        // syr ihop bilden med raderna under sa kanten inte syns.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(palette.scrim, palette.scrimClear),
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(palette.scrimClear, palette.background),
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(metrics.screenEdge)
                .widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
        ) {
            Text(
                text = if (LocalThemeStyle.current.uppercaseHeadings) {
                    content.title.uppercase()
                } else {
                    content.title
                },
                style = MaterialTheme.typography.displayLarge,
                color = palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (content.facts.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd)) {
                    content.facts.forEach { fact ->
                        Text(
                            text = fact,
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textSecondary,
                        )
                    }
                }
            }

            content.overview?.let { text ->
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
                    label = if (content.isSeries) "Visa avsnitt" else "Spela",
                    onClick = onPlay,
                )
                if (onInfo != null) {
                    SecondaryAction(label = "Mer info", onClick = onInfo)
                }
            }
        }
    }
}

/**
 * Vit knapp for den primara handlingen, som i mockupen. Vit — inte kulor —
 * sa den fungerar over vilken backdrop som helst.
 */
@Composable
fun SecondaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    FocusableSurface(
        onClick = onClick,
        focusScale = 1.04f,
        cornerRadius = metrics.radiusCard,
        modifier = modifier,
    ) { focused ->
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) palette.focus else palette.textPrimary,
            modifier = Modifier.padding(horizontal = metrics.spaceLg, vertical = metrics.spaceSm),
        )
    }
}

@Composable
fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    FocusableSurface(
        onClick = onClick,
        focusScale = 1.04f,
        cornerRadius = metrics.radiusCard,
        background = false,
        modifier = modifier,
    ) { focused ->
        Box(
            Modifier
                .background(
                    if (focused) palette.focus else palette.textPrimary,
                    androidx.compose.foundation.shape.RoundedCornerShape(metrics.radiusCard),
                )
                .padding(horizontal = metrics.spaceLg, vertical = metrics.spaceSm),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = palette.onAccent,
            )
        }
    }
}

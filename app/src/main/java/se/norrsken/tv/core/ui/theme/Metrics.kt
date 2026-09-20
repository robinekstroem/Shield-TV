package se.norrsken.tv.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Avstånd, radier och fokusbeteende. Skiljer sig mellan TV och mobil eftersom
 * tittavståndet skiljer sig med en faktor tio.
 */
@Immutable
data class Metrics(
    val spaceXs: Dp,
    val spaceSm: Dp,
    val spaceMd: Dp,
    val spaceLg: Dp,
    val spaceXl: Dp,
    /** Marginal mot skärmkant. På TV måste den täcka overscan på äldre paneler. */
    val screenEdge: Dp,

    val radiusPoster: Dp,
    val radiusCard: Dp,
    val radiusSheet: Dp,

    /** Hur mycket en markerad poster växer. Mer än så börjar rutnätet vibrera. */
    val focusScalePoster: Float,
    val focusScaleWide: Float,
    val focusRingWidth: Dp,

    val posterWidth: Dp,
    val railSpacing: Dp,
    /** Hojden pa hero-sektionen. Tillrackligt for att bilden ska baras. */
    val heroHeight: Dp,
    /** Bredden pa ett liggande kort i en rad (16:9-format). */
    val wideCardWidth: Dp,
    /** Sidomenyns bredd i ihopdraget respektive utfallt lage. */
    val railCollapsed: Dp,
    val railExpanded: Dp,
) {
    companion object {
        val Tv = Metrics(
            spaceXs = 4.dp,
            spaceSm = 8.dp,
            spaceMd = 16.dp,
            spaceLg = 32.dp,
            spaceXl = 56.dp,
            screenEdge = 48.dp,
            radiusPoster = 8.dp,
            radiusCard = 12.dp,
            radiusSheet = 20.dp,
            focusScalePoster = 1.08f,
            focusScaleWide = 1.04f,
            focusRingWidth = 3.dp,
            posterWidth = 168.dp,
            railSpacing = 20.dp,
            heroHeight = 420.dp,
            wideCardWidth = 260.dp,
            railCollapsed = 88.dp,
            railExpanded = 256.dp,
        )

        val Handheld = Metrics(
            spaceXs = 4.dp,
            spaceSm = 8.dp,
            spaceMd = 12.dp,
            spaceLg = 20.dp,
            spaceXl = 32.dp,
            screenEdge = 16.dp,
            radiusPoster = 8.dp,
            radiusCard = 12.dp,
            radiusSheet = 20.dp,
            // Touch har ingen fokusmarkering — skalningen används bara vid tryck.
            focusScalePoster = 1.0f,
            focusScaleWide = 1.0f,
            focusRingWidth = 2.dp,
            posterWidth = 116.dp,
            railSpacing = 12.dp,
            heroHeight = 280.dp,
            wideCardWidth = 200.dp,
            railCollapsed = 0.dp,
            railExpanded = 0.dp,
        )
    }
}

val LocalMetrics = staticCompositionLocalOf { Metrics.Handheld }

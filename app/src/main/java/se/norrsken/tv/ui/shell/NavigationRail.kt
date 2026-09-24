package se.norrsken.tv.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.norrsken.tv.R
import se.norrsken.tv.core.ui.component.FocusableSurface
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalMotion
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.core.ui.theme.LocalThemeStyle
import se.norrsken.tv.navigation.Destination

/**
 * TV-menyn. Ihopdragen till ikoner medan anvandaren ar ute i innehallet,
 * utfalld med etikett och underrubrik sa fort fokus kommer in i den.
 *
 * Horisontellt utrymme ar det dyraste pa en TV. En permanent utfalld meny
 * ater 250 dp av varje rad med omslag, hela tiden, for information
 * anvandaren bara behover i navigeringsogonblicket.
 */
@Composable
fun NavigationRail(
    current: Destination,
    onSelect: (Destination) -> Unit,
    counts: LibraryCounts,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val motion = LocalMotion.current

    var expanded by remember { mutableStateOf(false) }

    // Utan det har har ingenting fokus nar appen startar pa en TV, och
    // fjarrkontrollen gor ingenting forran anvandaren racker ratt riktning.
    // withFrameNanos forst: LaunchedEffect startar innan layouten ar klar
    // och requestFocus mot en omonterad nod kastar.
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { initialFocus.requestFocus() }
    }

    // Menyn maste kunna rullas. En TV-skarm ar bara runt 540 dp hog, och
    // atta val med tva rader text vardera plus logotypen blir hogre an sa.
    // Utan rullning hamnade Installningar nedanfor kanten, dit fokus aldrig
    // nadde — och da gick det inte att lagga in en spellista alls. Med
    // storre textstorlek hade fler val forsvunnit.
    //
    // verticalScroll rullar automatiskt fram det val som far fokus.
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(if (expanded) metrics.railExpanded else metrics.railCollapsed)
            // Halvgenomskinlig, så snö och norrsken syns även bakom menyn.
            // Helt täckande hade gjort den till en vägg mitt i atmosfären.
            // Utfälld täcker menyn innehållet under sig och måste vara
            // tätare. Kollapsad får atmosfären lysa igenom.
            .background(
                Brush.horizontalGradient(
                    listOf(
                        palette.background.copy(alpha = if (expanded) 0.97f else 0.85f),
                        palette.background.copy(alpha = if (expanded) 0.90f else 0.35f),
                    )
                )
            )
            .onFocusChanged { expanded = it.hasFocus }
            .focusGroup()
            .animateContentSize(tween(motion.scale(motion.transitionMs)))
            .verticalScroll(scroll)
            .padding(vertical = metrics.spaceMd),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceXs),
    ) {
        Brand(expanded = expanded)

        Destination.entries.forEach { destination ->
            RailItem(
                destination = destination,
                selected = destination == current,
                expanded = expanded,
                count = destination.countFrom(counts),
                onClick = { onSelect(destination) },
                modifier = if (destination == current) {
                    Modifier.focusRequester(initialFocus)
                } else {
                    Modifier
                },
            )
        }
    }
}

@Composable
private fun Brand(expanded: Boolean) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Column(
        modifier = Modifier.padding(
            start = metrics.spaceLg,
            bottom = metrics.spaceLg,
        ),
    ) {
        // Namnet får inte plats i den smala menyn och klipptes till "NOR".
        // Bättre att det inte står något alls än en avhuggen rad.
        if (!expanded) return@Column
        Text(
            text = "NORRSKEN",
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
            maxLines = 1,
        )
        AnimatedVisibility(visible = expanded && !LocalThemeStyle.current.minimal) {
            Text(
                text = stringResource(R.string.brand_tagline),
                style = MaterialTheme.typography.labelMedium,
                color = palette.textTertiary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RailItem(
    destination: Destination,
    selected: Boolean,
    expanded: Boolean,
    count: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val motion = LocalMotion.current

    FocusableSurface(
        onClick = onClick,
        background = false,
        focusScale = 1f,
        cornerRadius = metrics.radiusCard,
        modifier = modifier.padding(horizontal = metrics.spaceSm),
    ) { focused ->
        val surface by animateColorAsState(
            targetValue = when {
                focused -> palette.surfaceRaised
                selected -> palette.surface
                else -> Color.Transparent
            },
            animationSpec = tween(motion.scale(motion.focusMs)),
            label = "railSurface",
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(surface, RoundedCornerShape(metrics.radiusCard))
                .padding(horizontal = metrics.spaceMd, vertical = metrics.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
        ) {
            // Lodrat streck till vanster om vald post, som i mockupen.
            Box(
                Modifier
                    .width(3.dp)
                    .height(28.dp)
                    .background(
                        if (selected) palette.signal else Color.Transparent,
                        RoundedCornerShape(2.dp),
                    )
            )

            Icon(
                imageVector = destination.icon,
                contentDescription = null,
                tint = when {
                    focused || selected -> palette.textPrimary
                    else -> palette.textTertiary
                },
                modifier = Modifier.size(24.dp),
            )

            AnimatedVisibility(visible = expanded) {
                Column {
                    Text(
                        text = stringResource(destination.label),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (focused || selected) {
                            palette.textPrimary
                        } else {
                            palette.textSecondary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // I minimalt läge står bara namnet. "Live TV /
                    // Direktsändning" säger samma sak två gånger.
                    if (!LocalThemeStyle.current.minimal) {
                        Text(
                            // Riktig siffra nar det finns en, annars beskrivningen.
                            // Aldrig en pahittad mangd.
                            text = count?.let { destination.countLabel(it) }
                                ?: stringResource(destination.sublabel),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun Destination.countLabel(count: Int): String {
    val noun = when (this) {
        Destination.LiveTv -> "kanaler"
        Destination.Movies -> "filmer"
        Destination.Series -> "serier"
        else -> "objekt"
    }
    // Tusentalsavgransare: "12 480 kanaler" gar att lasa pa tre meters hall,
    // "12480" gor det inte.
    return "${"%,d".format(count).replace(',', ' ')} $noun"
}

private fun Destination.countFrom(counts: LibraryCounts): Int? = when (this) {
    Destination.LiveTv -> counts.channels.takeIf { it > 0 }
    Destination.Movies -> counts.movies.takeIf { it > 0 }
    Destination.Series -> counts.series.takeIf { it > 0 }
    else -> null
}

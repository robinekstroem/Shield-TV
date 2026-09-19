package se.norrsken.tv.ui.shell

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.norrsken.tv.core.ui.component.FocusableSurface
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalMotion
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.navigation.Destination

/**
 * TV-menyn. Ihopdragen till ikoner medan användaren är ute i innehållet,
 * utfälld med etiketter så fort fokus kommer in i den.
 *
 * Skälet: på en TV är horisontellt utrymme det dyraste du har. En permanent
 * utfälld meny äter 200 dp av varje rad med posters, hela tiden, för
 * information användaren bara behöver i det ögonblick den navigerar.
 */
@Composable
fun NavigationRail(
    current: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val motion = LocalMotion.current

    var expanded by remember { mutableStateOf(false) }

    // Utan det här har ingenting fokus när appen startar på en TV, och
    // fjärrkontrollen gör bokstavligen ingenting förrän användaren råkar
    // trycka på rätt riktning. Menyn tar fokus först; höger går in i
    // innehållet.
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { initialFocus.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(palette.background)
            .onFocusChanged { expanded = it.hasFocus }
            .focusGroup()
            .animateContentSize(tween(motion.scale(motion.transitionMs)))
            .padding(vertical = metrics.spaceLg, horizontal = metrics.spaceMd),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceXs, Alignment.CenterVertically),
    ) {
        Destination.entries.forEach { destination ->
            val selected = destination == current
            FocusableSurface(
                onClick = { onSelect(destination) },
                background = false,
                focusScale = 1f,
                modifier = if (selected) {
                    Modifier.focusRequester(initialFocus)
                } else {
                    Modifier
                },
            ) { focused ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
                    modifier = Modifier.padding(
                        horizontal = metrics.spaceMd,
                        vertical = metrics.spaceSm,
                    ),
                ) {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = null,
                        tint = when {
                            focused -> palette.focus
                            selected -> palette.textPrimary
                            else -> palette.textTertiary
                        },
                        modifier = Modifier.size(24.dp),
                    )
                    AnimatedVisibility(visible = expanded) {
                        Text(
                            text = stringResource(destination.label),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (focused || selected) {
                                palette.textPrimary
                            } else {
                                palette.textSecondary
                            },
                            modifier = Modifier.width(136.dp),
                        )
                    }
                }
            }
        }
    }
}

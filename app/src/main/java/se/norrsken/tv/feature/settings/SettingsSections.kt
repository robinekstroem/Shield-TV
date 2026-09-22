package se.norrsken.tv.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import se.norrsken.tv.core.ui.component.FocusableSurface
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.core.ui.theme.LocalThemeStyle

/**
 * Byggstenarna for installningsskarmen. Allt ar [FocusableSurface], sa varje
 * rad gar att na med D-pad och fokus syns alltid — kravet var att det aldrig
 * far finnas ett lage dar man maste anvanda touch.
 */
@Composable
fun SettingsHeader(title: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = palette.textSecondary,
        modifier = modifier.padding(top = metrics.spaceLg, bottom = metrics.spaceSm),
    )
}

/**
 * Val mellan namngivna alternativ. Pilar i stallet for lista: pa en
 * fjarrkontroll ar hoger/vanster snabbare an att oppna en meny, valja och
 * stanga den.
 */
@Composable
fun <T> OptionRow(
    title: String,
    description: String?,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    fun step(delta: Int) {
        if (options.isEmpty()) return
        val index = options.indexOf(selected).takeIf { it >= 0 } ?: 0
        val next = ((index + delta) % options.size + options.size) % options.size
        onSelect(options[next])
    }

    FocusableSurface(
        onClick = { step(1) },
        modifier = modifier
            .fillMaxWidth()
            // Hoger och vanster stegar. Pa en fjarrkontroll ar det snabbare
            // an att trycka OK sig igenom en lista, och vanster maste kunna
            // ga bakat — annars far man varva hela vagen runt.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> { step(1); true }
                    Key.DirectionLeft -> { step(-1); true }
                    else -> false
                }
            },
    ) { _ ->
        Row(
            modifier = Modifier.padding(metrics.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                )
                // Beskrivningen är förklaring, inte funktion — den är det
                // första som tas bort i minimalt läge.
                if (description != null && !LocalThemeStyle.current.minimal) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textTertiary,
                    )
                }
            }
            Text(
                text = label(selected),
                style = MaterialTheme.typography.titleMedium,
                color = palette.signal,
            )
        }
    }
}

/** En rad som leder vidare till en egen skärm. */
@Composable
fun LinkRow(
    title: String,
    description: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    FocusableSurface(onClick = onClick, modifier = modifier.fillMaxWidth()) { focused ->
        Row(
            modifier = Modifier.padding(metrics.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                )
                // Beskrivningen är förklaring, inte funktion — den är det
                // första som tas bort i minimalt läge.
                if (description != null && !LocalThemeStyle.current.minimal) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textTertiary,
                    )
                }
            }
            Text(
                text = "Öppna",
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) palette.focus else palette.signal,
            )
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    description: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    OptionRow(
        title = title,
        description = description,
        options = listOf(true, false),
        selected = checked,
        label = { if (it) "På" else "Av" },
        onSelect = onChange,
        modifier = modifier,
    )
}

/**
 * En funktion som enheten eller appen inte stodjer just nu.
 *
 * Visas som otillganglig med skalet utskrivet, i stallet for att utelamnas
 * eller — vilket vore samst — finnas som ett reglage som inte gor nagot.
 */
@Composable
fun UnavailableRow(
    title: String,
    reason: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(metrics.spaceMd),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceXs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.textTertiary,
        )
        Text(
            text = reason,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textTertiary,
        )
    }
}

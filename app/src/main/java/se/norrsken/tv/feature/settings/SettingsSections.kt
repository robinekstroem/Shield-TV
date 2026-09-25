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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
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
 * Val mellan namngivna alternativ — alla synliga på en gång.
 *
 * Tidigare stegade raden ett steg per tryck. Man såg aldrig vilka val som
 * fanns, bara det man råkade stå på, och fel val krävde att man varvade
 * hela vägen runt. Nu ligger alternativen som brickor: man ser dem, pekar
 * på det man vill ha och trycker en gång.
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = metrics.spaceMd, vertical = metrics.spaceSm),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceXs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
        )
        // Beskrivningen är förklaring, inte funktion — den är det första
        // som tas bort i minimalt läge.
        if (description != null && !LocalThemeStyle.current.minimal) {
            Text(
                text = description,
                style = MaterialTheme.typography.labelMedium,
                color = palette.textTertiary,
            )
        }
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm),
        ) {
            options.forEach { option ->
                val active = option == selected
                FocusableSurface(
                    onClick = { onSelect(option) },
                    focusScale = 1f,
                    cornerRadius = metrics.radiusCard,
                    background = active,
                ) { focused ->
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            focused -> palette.focus
                            active -> palette.signal
                            else -> palette.textSecondary
                        },
                        maxLines = 1,
                        modifier = Modifier.padding(
                            horizontal = metrics.spaceMd,
                            vertical = metrics.spaceXs,
                        ),
                    )
                }
            }
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

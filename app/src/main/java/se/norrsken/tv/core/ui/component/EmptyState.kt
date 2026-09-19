package se.norrsken.tv.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette

/**
 * En tom skärm är en uppmaning att göra något, inte ett felmeddelande.
 * Därför: vad som saknas, vad som händer när du fixat det, och knappen.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(metrics.screenEdge),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceMd, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.textPrimary,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.textSecondary,
            modifier = Modifier.widthIn(max = 460.dp),
        )
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.surfaceRaised,
                    contentColor = palette.textPrimary,
                ),
                modifier = Modifier.padding(top = metrics.spaceSm),
            ) {
                Text(actionLabel)
            }
        }
    }
}

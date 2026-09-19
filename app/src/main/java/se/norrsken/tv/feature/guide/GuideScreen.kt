package se.norrsken.tv.feature.guide

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.norrsken.tv.core.ui.component.EmptyState

@Composable
fun GuideScreen(modifier: Modifier = Modifier) {
    EmptyState(
        modifier = modifier,
        title = "Ingen tablå",
        body = "Tablån byggs i nästa fas. Den behöver en EPG-källa från din spellista.",
    )
}

package se.norrsken.tv.feature.guide

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.norrsken.tv.core.ui.component.EmptyState

@Composable
fun GuideScreen(modifier: Modifier = Modifier) {
    EmptyState(
        modifier = modifier,
        title = "Ingen tablå",
        body = "Tablån behöver en EPG-källa. Lägg till en spellista först, sedan dess EPG.",
        actionLabel = "Lägg till spellista",
        onAction = { /* Fas 2: öppnar importflödet */ },
    )
}

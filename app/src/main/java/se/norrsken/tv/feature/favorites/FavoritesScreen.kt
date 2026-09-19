package se.norrsken.tv.feature.favorites

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.norrsken.tv.core.ui.component.EmptyState

@Composable
fun FavoritesScreen(modifier: Modifier = Modifier) {
    EmptyState(
        modifier = modifier,
        title = "Inga favoriter",
        body = "Markera en kanal, film eller serie som favorit så hamnar den här.",
        actionLabel = "Lägg till spellista",
        onAction = { /* Fas 2: öppnar importflödet */ },
    )
}

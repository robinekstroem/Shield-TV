package se.norrsken.tv.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.norrsken.tv.core.ui.component.EmptyState

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    EmptyState(
        modifier = modifier,
        title = "Inget bibliotek än",
        body = "Lägg till en spellista så hämtar appen kanaler, filmer och serier.",
        actionLabel = "Lägg till spellista",
        onAction = { /* Fas 2: öppnar importflödet */ },
    )
}

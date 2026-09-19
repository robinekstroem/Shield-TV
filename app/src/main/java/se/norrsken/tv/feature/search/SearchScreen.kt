package se.norrsken.tv.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.norrsken.tv.core.ui.component.EmptyState

@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    EmptyState(
        modifier = modifier,
        title = "Sök i biblioteket",
        body = "Sökningen går över kanaler, tablå, filmer och serier. Byggs i Fas 8.",
    )
}

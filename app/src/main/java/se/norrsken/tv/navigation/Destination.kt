package se.norrsken.tv.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import se.norrsken.tv.R

/**
 * Appens huvudområden. Ordningen här är ordningen i menyn.
 */
enum class Destination(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    Home("home", R.string.nav_home, Icons.Filled.Home),
    LiveTv("live", R.string.nav_live, Icons.Filled.PlayArrow),
    Guide("guide", R.string.nav_guide, Icons.Filled.DateRange),
    Movies("movies", R.string.nav_movies, Icons.Filled.Star),
    Series("series", R.string.nav_series, Icons.AutoMirrored.Filled.List),
    Favorites("favorites", R.string.nav_favorites, Icons.Filled.Favorite),
    Search("search", R.string.nav_search, Icons.Filled.Search),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings),
}

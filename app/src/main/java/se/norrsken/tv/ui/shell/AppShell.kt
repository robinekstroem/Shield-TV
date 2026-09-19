package se.norrsken.tv.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import se.norrsken.tv.core.device.LocalDeviceProfile
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.feature.favorites.FavoritesScreen
import se.norrsken.tv.feature.guide.GuideScreen
import se.norrsken.tv.feature.home.HomeScreen
import se.norrsken.tv.feature.livetv.LiveTvScreen
import se.norrsken.tv.feature.movies.MoviesScreen
import se.norrsken.tv.feature.search.SearchScreen
import se.norrsken.tv.feature.series.SeriesScreen
import se.norrsken.tv.feature.settings.SettingsScreen
import se.norrsken.tv.navigation.Destination

/**
 * Appens ram. Samma navigationsgraf på båda enhetstyperna — bara menyn
 * byter form. Affärslogik och skärmar delas rakt av.
 */
@Composable
fun AppShell(modifier: Modifier = Modifier) {
    val profile = LocalDeviceProfile.current
    val palette = LocalPalette.current
    val navController = rememberNavController()
    val current = currentDestination(navController)

    val select: (Destination) -> Unit = { destination ->
        if (destination != current) {
            navController.navigate(destination.route) {
                popUpTo(Destination.Home.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(palette.background)) {
        if (profile.isTv) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(current = current, onSelect = select)
                Box(Modifier.weight(1f)) { AppNavHost(navController) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                // enableEdgeToEdge ritar under statusfältet. Utan det här
                // hamnar rubrikerna under klockan och hålkameran.
                Box(
                    Modifier
                        .weight(1f)
                        .statusBarsPadding()
                ) {
                    AppNavHost(navController)
                }
                BottomBar(current = current, onSelect = select)
            }
        }
    }
}

@Composable
private fun currentDestination(navController: NavHostController): Destination {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    return Destination.entries.firstOrNull { it.route == route } ?: Destination.Home
}

@Composable
private fun AppNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Destination.Home.route,
    ) {
        composable(Destination.Home.route) { HomeScreen() }
        composable(Destination.LiveTv.route) { LiveTvScreen() }
        composable(Destination.Guide.route) { GuideScreen() }
        composable(Destination.Movies.route) { MoviesScreen() }
        composable(Destination.Series.route) { SeriesScreen() }
        composable(Destination.Favorites.route) { FavoritesScreen() }
        composable(Destination.Search.route) { SearchScreen() }
        composable(Destination.Settings.route) { SettingsScreen() }
    }
}

package se.norrsken.tv.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import se.norrsken.tv.core.ui.theme.LocalMetrics
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import se.norrsken.tv.core.device.LocalDeviceProfile
import se.norrsken.tv.core.ui.effects.AmbientBackground
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.core.ui.theme.LocalMotion
import se.norrsken.tv.core.ui.theme.LocalPreferences
import se.norrsken.tv.core.ui.theme.effectFor
import se.norrsken.tv.feature.categories.CategoryManagerScreen
import se.norrsken.tv.feature.favorites.FavoritesScreen
import se.norrsken.tv.feature.guide.GuideScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.norrsken.tv.feature.home.HomeScreen
import se.norrsken.tv.feature.livetv.LiveTvScreen
import se.norrsken.tv.feature.moviedetail.MovieDetailScreen
import se.norrsken.tv.feature.movies.MoviesScreen
import se.norrsken.tv.feature.player.PlayerScreen
import se.norrsken.tv.feature.search.SearchScreen
import se.norrsken.tv.feature.series.SeriesScreen
import se.norrsken.tv.feature.seriesdetail.SeriesDetailScreen
import se.norrsken.tv.data.settings.EffectLevel
import se.norrsken.tv.feature.player.PlayableKind
import se.norrsken.tv.feature.settings.SettingsScreen
import se.norrsken.tv.feature.settings.SettingsSection
import se.norrsken.tv.feature.settings.SettingsSectionScreen
import se.norrsken.tv.navigation.Destination

/**
 * Appens ram. Samma navigationsgraf på båda enhetstyperna — bara menyn
 * byter form. Affärslogik och skärmar delas rakt av.
 */
@Composable
fun AppShell(
    modifier: Modifier = Modifier,
    shellViewModel: ShellViewModel = hiltViewModel(),
) {
    val profile = LocalDeviceProfile.current
    val palette = LocalPalette.current
    val preferences = LocalPreferences.current
    val navController = rememberNavController()
    val current = currentDestination(navController)
    val counts by shellViewModel.counts.collectAsStateWithLifecycle()

    // Ingen jamforelse mot current: star man pa en underrutt ar current
    // huvudavdelningen, och ett likhetstest hade da gjort menyvalet dott.
    val select: (Destination) -> Unit = { destination ->
        navController.navigate(destination.route) {
            popUpTo(Destination.Home.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // movableContentOf: utan den raknas de tva AppNavHost-anropen nedan som
    // skilda anropsplatser, och Compose river och bygger om hela
    // navigationsgrafen varje gang spelaren oppnas eller stangs. Med den
    // flyttas samma instans mellan platserna och backstacken overlever.
    val navHost = remember(navController) {
        movableContentOf { AppNavHost(navController) }
    }

    // Spelaren tar hela skarmen. En meny bredvid en TV-sandning ar inte
    // helskarm, och pa en TV ar det direkt fel.
    if (onPlayerRoute(navController)) {
        Box(modifier = modifier.fillMaxSize().background(palette.background)) {
            navHost()
        }
        return
    }

    Box(modifier = modifier.fillMaxSize().background(palette.background)) {
        // Atmosfären ligger under allt annat. Skärmarna saknar egen
        // bakgrund, så den syns igenom — utom i spelaren, som har sitt eget
        // svarta lager.
        AmbientBackground(
            effect = effectFor(preferences.theme),
            // Rörelse avstängd — i systemet eller i appen — betyder inga
            // partiklar heller.
            level = if (LocalMotion.current.reduced) EffectLevel.OFF else preferences.effects,
            isTv = profile.isTv,
        )
        if (profile.isTv) {
            // Menyn ligger ÖVER innehållet, inte bredvid. Expanderar den i
            // en rad flyttas hela skärmen åt höger, och då hoppar bilden
            // varje gång man går in i menyn.
            Box(Modifier.fillMaxSize()) {
                // Hemskärmen får hela ytan: dess backdrop ska gå ända ut i
                // vänsterkanten och menyn ligga ovanpå den. Övriga skärmar
                // har text längst till vänster och behöver marginalen.
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = if (current == Destination.Home) {
                                0.dp
                            } else {
                                LocalMetrics.current.railCollapsed
                            }
                        )
                ) { navHost() }
                NavigationRail(
                    current = current,
                    onSelect = select,
                    counts = counts,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        } else {
            // Mobil: innehållet ritas edge-to-edge. Topbaren ligger ovanpå
            // artworken i stället för att reservera en svart remsa överst.
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    navHost()
                    TopBar(
                        current = current,
                        onSelect = select,
                        modifier = Modifier.statusBarsPadding().align(Alignment.TopCenter),
                    )
                }
                BottomBar(current = current, onSelect = select)
            }
        }
    }
}

@Composable
private fun onPlayerRoute(navController: NavHostController): Boolean {
    val entry by navController.currentBackStackEntryAsState()
    return entry?.destination?.route?.startsWith(PLAYER_ROUTE) == true
}

@Composable
private fun currentDestination(navController: NavHostController): Destination {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    return Destination.entries.firstOrNull { it.route == route }
        // Underrutter hor till sin huvudavdelning. Utan det markerade menyn
        // "Hem" nar man stod pa en seriesida.
        ?: when {
            route?.startsWith(SERIES_ROUTE) == true -> Destination.Series
            route?.startsWith(MOVIE_ROUTE) == true -> Destination.Movies
            route?.startsWith(SETTINGS_SECTION_ROUTE) == true -> Destination.Settings
            // Hanteringsskarmen hor till den avdelning man kom ifran. Rutten
            // ar monstret, inte vardet, sa typen avgors av argumentet.
            route?.startsWith(CATEGORIES_ROUTE) == true -> when (entry?.arguments?.getString("kind")) {
                "LIVE" -> Destination.LiveTv
                "SERIES" -> Destination.Series
                else -> Destination.Movies
            }
            else -> Destination.Home
        }
}

@Composable
private fun AppNavHost(navController: NavHostController) {
    val motion = LocalMotion.current
    // Runt 420 ms: tillräckligt för att kännas som att man går in i något,
    // kort nog att aldrig stå i vägen. Noll när rörelse är avstängd.
    val duration = motion.scale(motion.transitionMs + 120)

    NavHost(
        navController = navController,
        startDestination = Destination.Home.route,
        // Ny skärm växer fram ur en aning mindre skala. Den gamla tonar
        // bara bort — två rörelser samtidigt ser nervöst ut.
        enterTransition = {
            fadeIn(tween(duration)) + scaleIn(tween(duration), initialScale = 0.96f)
        },
        exitTransition = { fadeOut(tween(duration / 2)) },
        popEnterTransition = { fadeIn(tween(duration)) },
        popExitTransition = {
            fadeOut(tween(duration / 2)) + scaleOut(tween(duration / 2), targetScale = 0.96f)
        },
    ) {
        composable(Destination.Home.route) {
            HomeScreen(
                onOpenMovie = { id -> navController.navigate(MOVIE_ROUTE + id) },
                onPlayMovie = { id -> navController.play(PlayableKind.MOVIE, id) },
                onPlayEpisode = { id -> navController.play(PlayableKind.EPISODE, id) },
                onOpenSeries = { id -> navController.navigate(SERIES_ROUTE + id) },
                onOpenChannel = { id -> navController.play(PlayableKind.CHANNEL, id) },
                // Direkt till Källor: den som trycker på knappen vill lägga
                // till en spellista, inte bläddra i inställningar.
                onOpenSettings = {
                    navController.navigate(SETTINGS_SECTION_ROUTE + SettingsSection.SOURCES.name) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Destination.LiveTv.route) {
            LiveTvScreen(
                onOpenChannel = { id -> navController.play(PlayableKind.CHANNEL, id) },
                onManageCategories = { navController.navigate(CATEGORIES_ROUTE + "LIVE") },
            )
        }
        composable(Destination.Guide.route) {
            GuideScreen(
                onPlayChannel = { id -> navController.play(PlayableKind.CHANNEL, id) },
            )
        }
        composable(Destination.Movies.route) {
            MoviesScreen(
                onOpenMovie = { id -> navController.navigate(MOVIE_ROUTE + id) },
                onManageCategories = { navController.navigate(CATEGORIES_ROUTE + "MOVIE") },
            )
        }
        composable(Destination.Series.route) {
            SeriesScreen(
                onOpenSeries = { id -> navController.navigate(SERIES_ROUTE + id) },
                onManageCategories = { navController.navigate(CATEGORIES_ROUTE + "SERIES") },
            )
        }
        composable(Destination.Favorites.route) {
            FavoritesScreen(
                onOpenMovie = { id -> navController.navigate(MOVIE_ROUTE + id) },
                onOpenSeries = { id -> navController.navigate(SERIES_ROUTE + id) },
                onOpenChannel = { id -> navController.play(PlayableKind.CHANNEL, id) },
            )
        }
        composable(Destination.Search.route) {
            SearchScreen(
                onOpenChannel = { id -> navController.play(PlayableKind.CHANNEL, id) },
                onOpenMovie = { id -> navController.navigate(MOVIE_ROUTE + id) },
                onOpenSeries = { id -> navController.navigate(SERIES_ROUTE + id) },
            )
        }

        composable(
            route = MOVIE_ROUTE + "{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            MovieDetailScreen(
                onPlay = { id -> navController.play(PlayableKind.MOVIE, id) },
            )
        }
        composable(Destination.Settings.route) {
            SettingsScreen(
                onOpenSection = { section ->
                    navController.navigate(SETTINGS_SECTION_ROUTE + section.name)
                },
            )
        }

        composable(
            route = SETTINGS_SECTION_ROUTE + "{section}",
            arguments = listOf(navArgument("section") { type = NavType.StringType }),
        ) { entry ->
            val section = entry.arguments?.getString("section")
                ?.let { runCatching { SettingsSection.valueOf(it) }.getOrNull() }
                ?: SettingsSection.SOURCES
            SettingsSectionScreen(
                section = section,
                onManageCategories = { kind -> navController.navigate(CATEGORIES_ROUTE + kind) },
            )
        }

        composable(
            route = CATEGORIES_ROUTE + "{kind}",
            arguments = listOf(navArgument("kind") { type = NavType.StringType }),
        ) {
            CategoryManagerScreen()
        }

        composable(
            route = SERIES_ROUTE + "{seriesId}",
            arguments = listOf(navArgument("seriesId") { type = NavType.StringType }),
        ) {
            SeriesDetailScreen(
                onPlayEpisode = { id -> navController.play(PlayableKind.EPISODE, id) },
            )
        }

        composable(
            route = PLAYER_ROUTE + "{kind}/{id}",
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("id") { type = NavType.StringType },
            ),
        ) {
            PlayerScreen(onBack = { navController.popBackStack() })
        }
    }
}

private fun NavHostController.play(kind: PlayableKind, id: Long) {
    navigate(PLAYER_ROUTE + kind.name + "/" + id)
}

private const val PLAYER_ROUTE = "player/"
private const val SERIES_ROUTE = "series-detail/"
private const val MOVIE_ROUTE = "movie-detail/"
private const val CATEGORIES_ROUTE = "categories/"
private const val SETTINGS_SECTION_ROUTE = "settings/"

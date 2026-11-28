package se.norrsken.tv.desktop.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import com.sun.net.httpserver.HttpServer
import org.jetbrains.skia.EncodedImageFormat
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.NowPlaying
import se.norrsken.tv.desktop.Screen
import se.norrsken.tv.desktop.data.GuideChannel
import se.norrsken.tv.desktop.data.League
import se.norrsken.tv.desktop.data.LiveChannel
import se.norrsken.tv.desktop.data.Movie
import se.norrsken.tv.desktop.data.Programme
import se.norrsken.tv.desktop.data.Series
import se.norrsken.tv.desktop.data.Source
import se.norrsken.tv.desktop.data.SourceContent
import se.norrsken.tv.desktop.data.SourceGuide
import se.norrsken.tv.desktop.data.SourceType
import se.norrsken.tv.desktop.data.Storage
import se.norrsken.tv.desktop.data.TitleCleaner
import se.norrsken.tv.desktop.data.TmdbFacts
import se.norrsken.tv.desktop.data.TmdbStore
import se.norrsken.tv.desktop.data.WatchEntry
import se.norrsken.tv.desktop.data.WatchKind
import se.norrsken.tv.desktop.data.WeeklyTitle
import se.norrsken.tv.desktop.data.CollectionKeys
import se.norrsken.tv.desktop.data.DirectorFilms
import se.norrsken.tv.desktop.data.JulKeys
import se.norrsken.tv.desktop.data.TmdbListTitle
import se.norrsken.tv.desktop.player.VideoPlayer
import androidx.compose.foundation.layout.fillMaxSize
import se.norrsken.tv.desktop.data.FootballMatch
import se.norrsken.tv.desktop.data.MatchGoal
import se.norrsken.tv.desktop.data.MatchState
import se.norrsken.tv.desktop.data.isMalmoFF
import se.norrsken.tv.desktop.ui.AppRoot
import se.norrsken.tv.desktop.ui.CardMorph
import se.norrsken.tv.desktop.ui.PosterWall
import se.norrsken.tv.desktop.ui.PosterWallDriver
import se.norrsken.tv.desktop.ui.SaverControl
import se.norrsken.tv.desktop.ui.ScreenSaver
import se.norrsken.tv.desktop.ui.WallStyle
import se.norrsken.tv.desktop.ui.WallStyleChooser
import se.norrsken.tv.desktop.ui.MenuStyle
import se.norrsken.tv.desktop.ui.MenuStyleChooser
import se.norrsken.tv.desktop.data.IntroReelItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.norrsken.tv.desktop.ui.MatchNoticeOverlay
import se.norrsken.tv.desktop.ui.PlayerLayers
import se.norrsken.tv.desktop.ui.PlayerStill
import se.norrsken.tv.desktop.ui.TvNav
import se.norrsken.tv.desktop.ui.TvDrawer
import se.norrsken.tv.desktop.ui.TvTheme
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sin

/*
 * Förhandsbilder av appen som den ser ut på tv:n, ritade utan fönster — bara
 * för granskning hos GitHub (Kontroll Windows). Ett påhittat bibliotek läggs
 * på disken innan appen startar (kanaler, filmer, serier och en tablå, med
 * egna bilder från en liten server här), och fotbollen hämtas från ESPN som
 * vanligt. Storleken är Shieldens standard: 1 600 × 900 i layoutens enheter
 * på en 1080p-skärm, med texten som där.
 *
 * Argument: mappen bilderna hamnar i, och valfritt namnen på de bilder som
 * ska ritas (annars alla).
 */

private const val WIDTH = 1920
private const val HEIGHT = 1080
private val TV_DENSITY = Density(density = 1.2f, fontScale = 1.25f)

private lateinit var out: File

fun main(args: Array<String>) {
    out = File(args.firstOrNull() ?: "build/previews").also { it.mkdirs() }
    val only = args.drop(1).toSet()
    val images = PreviewImages.start()
    val data = PreviewData.seed(images.base)

    // Appens jobb går på Swing-tråden, som i Windows-appen.
    val mainWorks = runCatching {
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(5_000) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { true } }
        }
    }.getOrElse { println("Swing-tråden svarar inte: $it"); false }
    println("Swing-tråden: $mainWorks. Mappar: ${se.norrsken.tv.desktop.data.AppDirs.config} och ${se.norrsken.tv.desktop.data.AppDirs.cache}")
    val state = AppState()
    waitFor("starten", 30_000) { !state.loading }
    // Biblioteket och tablån direkt i minnet: utan Windows DPAPI sparas inget bibliotek på disken.
    // En film med fakta från "TMDB", för spelarens rubrik och pausbild.
    val featured = data.movies[3]
    val tmdb = TitleCleaner.searchTitle(featured.name).let { (title, year) ->
        mapOf(
            TmdbStore.keyOf("movie", title, year) to TmdbFacts(
                id = 1,
                genres = "Drama, Thriller",
                rating = 7.8,
                votes = 1200,
                overview = "När en storm slår ut all kontakt med fastlandet måste fyrvaktaren och en främling överleva natten — " +
                    "och förstå vad som egentligen hände ombord på skeppet som drev in i viken.",
                year = year,
            ),
        )
    }
    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Main) { state.previewLibrary(data.source, data.content, data.guide, tmdb) }
    waitFor("biblioteket", 10_000) { state.library.movies.isNotEmpty() }
    println(
        "Läge: laddar=${state.loading}, källor=${state.sources.size}, filmer=${state.library.movies.size}, " +
            "serier=${state.library.series.size}, kanaler=${state.library.channels.size}, sparat=${Storage().loadSources().size}",
    )
    // Filmväggen bakom sidorna: alla omslag, som med ett riktigt bibliotek.
    state.previewReel(
        data.movies.mapNotNull { movie -> movie.poster?.let { IntroReelItem(movie.name, it, null) } } +
            data.content.series.mapNotNull { series -> series.cover?.let { IntroReelItem(series.name, it, null) } },
    )
    state.introDone = true
    state.previewWeeklyTop(previewTopTen(images.base))
    state.previewJul(previewChristmas(), emptyList(), previewDirectors())
    // Tablån läses från disken en stund efter starten.
    waitFor("tablån", 20_000) { !state.guide.isEmpty }

    val scenes = scenes(state, data)
    for (scene in scenes) {
        if (only.isNotEmpty() && scene.name !in only) continue
        runCatching { scene.render() }.onFailure {
            System.err.println("Förhandsbilden ${scene.name} gick inte att rita:")
            it.printStackTrace()
        }
    }
    println("Förhandsbilder: ${out.listFiles()?.size ?: 0} st i ${out.absolutePath}")
    images.stop()
    // AppState har jobb som aldrig tar slut (klockan, fotbollen).
    Runtime.getRuntime().halt(0)
}

private class Scene(
    val name: String,
    /** Före första bilden. */
    val before: () -> Unit = {},
    /** Efter första bilden: det som kräver att skärmen finns (markeringen, menyerna). */
    val after: () -> Unit = {},
    /** Inför varje bild: håller t.ex. spelarens knappar framme. */
    val each: () -> Unit = {},
    /** Hur länge det får ta sig: bilder, animationer, nätet. */
    val settleMs: Long = 2_600L,
    val content: @Composable () -> Unit,
) {
    @OptIn(ExperimentalComposeUiApi::class)
    fun render() {
        before()
        val scene = ImageComposeScene(WIDTH, HEIGHT, TV_DENSITY, content = content)
        try {
            val start = System.nanoTime()
            scene.render(0L)
            after()
            val until = System.currentTimeMillis() + settleMs
            while (System.currentTimeMillis() < until) {
                each()
                scene.render(System.nanoTime() - start)
                Thread.sleep(40)
            }
            each()
            val image = scene.render(System.nanoTime() - start)
            val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("Ingen PNG")
            File(out, "$name.png").writeBytes(png)
            println("Ritade $name")
        } finally {
            scene.close()
        }
    }
}

private fun scenes(state: AppState, data: PreviewData): List<Scene> {
    val frame = PreviewImages.still().toComposeImageBitmap()
    val movie = data.movies[3]
    val film = NowPlaying(
        title = movie.name,
        subtitle = movie.group,
        live = false,
        name = movie.name,
        entry = WatchEntry(
            kind = WatchKind.MOVIE,
            sourceId = movie.sourceId,
            itemId = movie.streamId.toString(),
            title = movie.name,
            subtitle = movie.group,
            image = movie.poster,
            watchedAtUtc = System.currentTimeMillis(),
        ),
    )
    val filmStatus = VideoPlayer.Status(
        phase = VideoPlayer.Phase.PLAYING,
        timeMs = 47 * 60_000L + 12_000L,
        lengthMs = 2 * 3_600_000L + 8 * 60_000L,
        seekable = true,
        hasVideo = true,
        videoWidth = 3840,
        videoHeight = 2160,
    )
    val channel = data.channels[0]
    val live = NowPlaying(title = channel.name, subtitle = channel.group, live = true, channel = channel, queue = data.channels, name = channel.name)
    val liveStatus = VideoPlayer.Status(phase = VideoPlayer.Phase.PLAYING, hasVideo = true, videoWidth = 1920, videoHeight = 1080)

    fun player(status: VideoPlayer.Status): @Composable () -> Unit = {
        TvTheme(state.theme) {
            PlayerLayers(state, collapse = 0f, highlighted = false, staged = false, still = PlayerStill(status, frame))
        }
    }
    val app: @Composable () -> Unit = { TvTheme(state.theme) { AppRoot(state) } }
    fun screen(target: Screen): () -> Unit = {
        state.previewPlayer(null)
        state.previewNotice(null)
        state.screen = target
    }
    // Malmö FF:s nästa match, som om den pågick: för rutan i hörnet.
    fun mffMatch(live: Boolean): FootballMatch? {
        val next = state.footballMatches.firstOrNull { it.isMalmoFF() && it.state == MatchState.UPCOMING }
            ?: state.footballMatches.lastOrNull { it.isMalmoFF() }
            ?: return null
        val home = next.home.isMalmoFF()
        return FootballMatch(
            id = next.id,
            league = next.league,
            kickoff = if (live) System.currentTimeMillis() - 67 * 60_000L else System.currentTimeMillis() + 14 * 60_000L,
            state = if (live) MatchState.LIVE else MatchState.UPCOMING,
            status = if (live) "67'" else "",
            home = next.home,
            away = next.away,
            homeScore = if (live) (if (home) 2 else 1) else null,
            awayScore = if (live) (if (home) 1 else 2) else null,
            venue = next.venue,
            goals = if (live) listOf(MatchGoal("67'", home, "I. Kiese Thelin", penalty = false, ownGoal = false)) else emptyList(),
            slug = next.slug,
        )
    }

    return listOf(
        Scene(
            "spelare-film",
            before = { state.previewPlayer(film) },
            each = { state.touchPlayer(force = true) },
            content = player(filmStatus),
        ),
        Scene(
            "spelare-film-spolning",
            before = { state.previewPlayer(film, scrubTo = filmStatus.timeMs + 150_000L, scrubFrom = filmStatus.timeMs) },
            after = { state.focusPlayerControls() },
            each = { state.touchPlayer(force = true) },
            content = player(filmStatus),
        ),
        Scene(
            "spelare-film-paus",
            before = { state.previewPlayer(film) },
            settleMs = 4_600L,
            content = player(filmStatus.copy(phase = VideoPlayer.Phase.PAUSED)),
        ),
        Scene(
            "spelare-film-tidslinje",
            before = { state.previewPlayer(film) },
            after = {
                state.focusPlayerControls()
            },
            each = { state.touchPlayer(force = true) },
            settleMs = 1_200L,
            content = player(filmStatus),
        ),
        Scene(
            "spelare-live-laddar",
            before = { state.previewPlayer(live) },
            content = player(VideoPlayer.Status(phase = VideoPlayer.Phase.OPENING)),
        ),
        Scene(
            "spelare-live-kanalruta",
            before = { state.previewPlayer(live, zapTo = data.channels[2]) },
            content = player(liveStatus),
        ),
        Scene(
            "spelare-live-knappar",
            before = { state.previewPlayer(live) },
            after = { state.focusPlayerControls() },
            each = { state.touchPlayer(force = true) },
            content = player(liveStatus),
        ),
        Scene("start", before = screen(Screen.HOME), settleMs = 4_000L, content = app),
        Scene(
            "meny",
            before = screen(Screen.HOME),
            after = { TvNav.openMenu() },
            settleMs = 3_500L,
            content = app,
        ),
        Scene(
            "filmer-kategorier",
            before = screen(Screen.MOVIES),
            after = { TvNav.openCategories() },
            settleMs = 3_500L,
            content = app,
        ),
        Scene("livetv", before = screen(Screen.LIVE), settleMs = 3_500L, content = app),
        Scene(
            "fotboll",
            before = {
                screen(Screen.FOOTBALL)()
                state.footballLeague = null
            },
            settleMs = 14_000L,
            content = app,
        ),
        Scene(
            "fotboll-mff",
            before = {
                screen(Screen.FOOTBALL)()
                state.footballLeague = League.MALMO_FF
            },
            settleMs = 6_000L,
            content = app,
        ),
        Scene(
            "fotboll-allsvenskan",
            before = {
                screen(Screen.FOOTBALL)()
                state.footballLeague = League.ALLSVENSKAN
            },
            settleMs = 6_000L,
            content = app,
        ),
        Scene(
            "meny-fotboll",
            before = screen(Screen.FOOTBALL),
            after = { TvNav.openMenu() },
            settleMs = 3_500L,
            content = app,
        ),
        Scene(
            "notis-mal",
            before = {
                screen(Screen.HOME)()
                mffMatch(live = true)?.let { state.previewNotice(AppState.MatchNotice(AppState.MatchNoticeKind.GOAL, it, "I. Kiese Thelin", "67'", null)) }
            },
            settleMs = 3_000L,
            content = app,
        ),
        Scene(
            "notis-avspark",
            before = {
                state.previewPlayer(live)
                mffMatch(live = false)?.let {
                    state.previewNotice(AppState.MatchNotice(AppState.MatchNoticeKind.KICKOFF_SOON, it, null, null, data.channels[17]))
                }
            },
            settleMs = 3_000L,
            content = {
                TvTheme(state.theme) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                        PlayerLayers(state, collapse = 0f, highlighted = false, staged = false, still = PlayerStill(liveStatus, frame))
                        MatchNoticeOverlay(state)
                    }
                }
            },
        ),
        Scene(
            "fotboll-match",
            before = {
                screen(Screen.FOOTBALL)()
                state.footballLeague = null
                val match = state.footballMatches
                    .filter { it.homeScore != null }
                    .maxByOrNull { it.kickoff }
                    ?: state.footballMatches.firstOrNull()
                if (match != null) state.openMatch(match)
            },
            settleMs = 7_000L,
            content = app,
        ),
        // Omslagsväggen bakom sidorna, och Filmsiden i sina färger. Väggen behöver
        // några sekunder på sig att tändas och tona fram omslagen.
        Scene(
            "vagg-hem",
            before = {
                screen(Screen.HOME)()
                state.selectWallStyle(WallStyle.WALL)
            },
            settleMs = 9_000L,
            content = app,
        ),
        Scene(
            "filmsiden-hem",
            before = {
                screen(Screen.HOME)()
                state.selectWallStyle(WallStyle.SILK)
            },
            settleMs = 9_000L,
            content = app,
        ),
        Scene(
            "filmsiden-vag-filmer",
            before = {
                screen(Screen.MOVIES)()
                state.selectWallStyle(WallStyle.WAVE)
            },
            settleMs = 9_000L,
            content = app,
        ),
        Scene(
            "filmsiden-champagne-tabla",
            before = {
                screen(Screen.GUIDE)()
                state.selectWallStyle(WallStyle.CHAMPAGNE)
            },
            settleMs = 9_000L,
            content = app,
        ),
        // Väggen i 3D (Inställningar → Omslagsväggen): som bakgrund, och i skärmsläckaren
        // där den täcker allt. 3D:n tonar fram när väggen lagt sig.
        Scene(
            "vagg-3d-fodral",
            before = {
                screen(Screen.HOME)()
                state.selectWallStyle(WallStyle.CASES)
            },
            settleMs = 11_000L,
            content = app,
        ),
        Scene(
            "vagg-3d-djup",
            before = {
                screen(Screen.MOVIES)()
                state.selectWallStyle(WallStyle.LAYERS)
            },
            settleMs = 11_000L,
            content = app,
        ),
        Scene(
            "vagg-3d-bojd",
            before = {
                screen(Screen.HOME)()
                state.selectWallStyle(WallStyle.CURVE)
            },
            settleMs = 11_000L,
            content = app,
        ),
        Scene(
            "skarmslackare-3d-fodral",
            before = {
                screen(Screen.HOME)()
                state.selectWallStyle(WallStyle.CASES)
            },
            settleMs = 14_000L,
            content = {
                TvTheme(state.theme) {
                    val wall = remember { PosterWall() }
                    PosterWallDriver(state, wall)
                    val morph = remember { CardMorph() }
                    val saver = remember { SaverControl().also { it.start() } }
                    ScreenSaver(state, wall, morph, saver)
                }
            },
        ),
        Scene(
            "skarmslackare-3d-djup",
            before = {
                screen(Screen.HOME)()
                state.selectWallStyle(WallStyle.LAYERS)
            },
            settleMs = 14_000L,
            content = {
                TvTheme(state.theme) {
                    val wall = remember { PosterWall() }
                    PosterWallDriver(state, wall)
                    val morph = remember { CardMorph() }
                    val saver = remember { SaverControl().also { it.start() } }
                    ScreenSaver(state, wall, morph, saver)
                }
            },
        ),
        Scene(
            "skarmslackare-3d-bojd",
            before = {
                screen(Screen.HOME)()
                state.selectWallStyle(WallStyle.CURVE)
            },
            settleMs = 14_000L,
            content = {
                TvTheme(state.theme) {
                    val wall = remember { PosterWall() }
                    PosterWallDriver(state, wall)
                    val morph = remember { CardMorph() }
                    val saver = remember { SaverControl().also { it.start() } }
                    ScreenSaver(state, wall, morph, saver)
                }
            },
        ),
        // Appens märke i retro (fönstrets ikon på Windows), stort och i de små storlekarna.
        Scene(
            "logga",
            settleMs = 600L,
            content = {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF2A2D36)).padding(48.dp)) {
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.Bottom,
                    ) {
                        for (side in listOf(256, 128, 64, 32, 24, 16)) {
                            androidx.compose.foundation.Image(
                                androidx.compose.ui.graphics.vector.rememberVectorPainter(se.norrsken.tv.desktop.ui.Glyphs.AppMark),
                                contentDescription = null,
                                modifier = Modifier.size(side.dp),
                            )
                        }
                    }
                }
            },
        ),
        Scene(
            "installningar-vagg",
            before = { state.selectWallStyle(WallStyle.CASES) },
            settleMs = 1_500L,
            content = {
                TvTheme(state.theme) {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).padding(48.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            WallStyleChooser(state)
                        }
                    }
                }
            },
        ),
        Scene(
            "skarmslackare-filmsiden",
            before = {
                screen(Screen.HOME)()
                state.selectWallStyle(WallStyle.SILK)
            },
            settleMs = 11_000L,
            content = {
                TvTheme(state.theme) {
                    val wall = remember { PosterWall() }
                    PosterWallDriver(state, wall)
                    val morph = remember { CardMorph() }
                    val saver = remember { SaverControl().also { it.start() } }
                    ScreenSaver(state, wall, morph, saver)
                }
            },
        ),
        // Menyns utseenden (Inställningar → Menyn): valet, och varje meny framme vid Filmer.
        Scene(
            "installningar-meny",
            before = { state.selectMenuStyle(MenuStyle.MULTIVERSE) },
            settleMs = 1_500L,
            content = {
                TvTheme(state.theme) {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).padding(48.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            MenuStyleChooser(state)
                        }
                    }
                }
            },
        ),
        Scene(
            "meny-minimal",
            before = {
                screen(Screen.MOVIES)()
                state.selectWallStyle(WallStyle.WALL)
                state.selectMenuStyle(MenuStyle.MINIMAL)
            },
            after = { TvNav.openMenu() },
            each = { if (TvNav.drawer != TvDrawer.MENU) TvNav.openMenu() },
            settleMs = 3_500L,
            content = app,
        ),
        Scene(
            "meny-multiversum",
            before = {
                screen(Screen.MOVIES)()
                state.selectWallStyle(WallStyle.WALL)
                state.selectMenuStyle(MenuStyle.MULTIVERSE)
            },
            after = { TvNav.openMenu() },
            each = { if (TvNav.drawer != TvDrawer.MENU) TvNav.openMenu() },
            settleMs = 4_500L,
            content = app,
        ),
        Scene(
            "meny-stark",
            before = {
                screen(Screen.MOVIES)()
                state.selectWallStyle(WallStyle.WALL)
                state.selectMenuStyle(MenuStyle.STARK)
            },
            after = { TvNav.openMenu() },
            each = { if (TvNav.drawer != TvDrawer.MENU) TvNav.openMenu() },
            settleMs = 3_500L,
            content = app,
        ),
        Scene(
            "meny-stark-hem",
            before = {
                screen(Screen.HOME)()
                state.selectMenuStyle(MenuStyle.STARK)
            },
            after = { TvNav.openMenu() },
            each = { if (TvNav.drawer != TvDrawer.MENU) TvNav.openMenu() },
            settleMs = 3_500L,
            content = app,
        ),
        Scene(
            "meny-original-igen",
            before = {
                screen(Screen.MOVIES)()
                state.selectMenuStyle(MenuStyle.ORIGINAL)
            },
            after = { TvNav.openMenu() },
            settleMs = 3_000L,
            content = app,
        ),
        // Samlingarna: galleriet, Jönssonligan och en regissör. Julen: julfilmerna och julkalendrarna.
        Scene(
            "samlingar",
            before = {
                screen(Screen.MOVIES)()
                state.selectMenuStyle(MenuStyle.ORIGINAL)
                TvNav.drawer = TvDrawer.NONE
                state.openCollection(CollectionKeys.GALLERY)
            },
            settleMs = 3_500L,
            content = app,
        ),
        Scene(
            "samling-jonssonligan",
            before = {
                screen(Screen.MOVIES)()
                TvNav.drawer = TvDrawer.NONE
                state.openCollection(CollectionKeys.of("jonssonligan"))
            },
            settleMs = 3_500L,
            content = app,
        ),
        Scene(
            "samling-regissor",
            before = {
                screen(Screen.MOVIES)()
                TvNav.drawer = TvDrawer.NONE
                state.openCollection(CollectionKeys.of("regi-9001"))
            },
            settleMs = 3_500L,
            content = app,
        ),
        Scene(
            "julfilmer",
            before = {
                screen(Screen.MOVIES)()
                TvNav.drawer = TvDrawer.NONE
                state.openCollection(JulKeys.FILMS)
            },
            settleMs = 3_500L,
            content = app,
        ),
        Scene(
            "julkalendrar-kategorier",
            before = {
                screen(Screen.SERIES)()
                state.seriesGroup = JulKeys.CALENDARS
            },
            after = { TvNav.openCategories() },
            settleMs = 3_500L,
            content = app,
        ),
        // Veckans topp 10: sidan, med ettan markerad, och den gula knappen i varje meny.
        Scene(
            "topp10",
            before = {
                screen(Screen.TOP10)()
                state.selectMenuStyle(MenuStyle.ORIGINAL)
                TvNav.drawer = TvDrawer.NONE
            },
            settleMs = 4_000L,
            content = app,
        ),
        Scene(
            "topp10-meny",
            before = {
                screen(Screen.TOP10)()
                state.selectMenuStyle(MenuStyle.ORIGINAL)
            },
            after = { TvNav.openMenu() },
            each = { if (TvNav.drawer != TvDrawer.MENU) TvNav.openMenu() },
            settleMs = 3_500L,
            content = app,
        ),
        Scene(
            "topp10-meny-minimal",
            before = {
                screen(Screen.TOP10)()
                state.selectMenuStyle(MenuStyle.MINIMAL)
            },
            after = { TvNav.openMenu() },
            each = { if (TvNav.drawer != TvDrawer.MENU) TvNav.openMenu() },
            settleMs = 3_500L,
            content = app,
        ),
        Scene(
            "topp10-meny-multiversum",
            before = {
                screen(Screen.TOP10)()
                state.selectMenuStyle(MenuStyle.MULTIVERSE)
            },
            after = { TvNav.openMenu() },
            each = { if (TvNav.drawer != TvDrawer.MENU) TvNav.openMenu() },
            settleMs = 4_500L,
            content = app,
        ),
        Scene(
            "topp10-meny-stark",
            before = {
                screen(Screen.TOP10)()
                state.selectMenuStyle(MenuStyle.STARK)
            },
            after = { TvNav.openMenu() },
            each = { if (TvNav.drawer != TvDrawer.MENU) TvNav.openMenu() },
            settleMs = 3_500L,
            content = app,
        ),
    )
}

/**
 * Veckans topp 10 utan TMDB: påhittade filmer, hälften ur det påhittade
 * biblioteket (samma namn och år, så att de räknas som "finns") och hälften
 * inte, med egna affischer och bilder från servern här.
 */
private fun previewTopTen(base: String): List<WeeklyTitle> {
    class Top(val title: String, val year: Int, val libraryIndex: Int, val genres: String, val rating: Double, val overview: String)
    val list = listOf(
        Top("Kodnamn Orion", 2023, 7, "Action, Thriller", 7.9, "En avhoppad kodknäckare får tre dygn på sig att hitta läckan i en hemlig satellitstation, innan någon annan gör det."),
        Top("Stjärnfallet", 2025, -1, "Science fiction, Drama", 7.1, "När en meteorit slår ner i en liten norrländsk by börjar alla i byn drömma samma dröm, varje natt."),
        Top("Nordljus", 2016, 0, "Drama", 8.2, "Två syskon återvänder till fjällbyn där de växte upp, och till en vinter som ingen av dem pratat om sedan dess."),
        Top("Sista passet", 2025, -1, "Skräck, Thriller", 6.8, "Det är sista natten innan sjukhuset stängs för gott. Då rullas en patient in som inte borde finnas."),
        Top("Vargtimmen", 2024, 8, "Thriller", 7.4, "En fyrvaktare på en ö i Bottenhavet hör någon knacka på dörren mitt i stormen."),
        Top("Isvägen", 2017, 37, "Action, Äventyr", 7.0, "Vintervägen över sjön är enda sättet att få fram medicinen i tid. Isen har redan börjat spricka."),
        Top("Mörkrets hamn", 2025, -1, "Kriminal, Thriller", 6.6, "En tullare i hamnen hittar en container som inte står i några papper, och någon vill ha den tillbaka."),
        Top("Djupet", 2016, 9, "Science fiction, Skräck", 7.7, "Ett forskarlag på havsbotten tappar kontakten med ytan och inser att de inte är ensamma där nere."),
        Top("Den långa natten", 2024, -1, "Kriminal, Drama", 7.3, "Polarnattens första dygn: en polis letar efter en försvunnen turist innan ljuset försvinner helt."),
        Top("Glaciären", 2017, 10, "Äventyr, Mysterium", 6.9, "En glaciolog hittar ett flygplan i isen som försvann för femtio år sedan, med motorerna fortfarande varma."),
    )
    return list.mapIndexed { k, top ->
        WeeklyTitle(
            id = 9_000 + k,
            title = top.title,
            original = top.title,
            year = top.year,
            poster = if (top.libraryIndex >= 0) "$base/poster/${top.libraryIndex}.png" else "$base/topp/$k.png",
            backdrop = "$base/backdrop/${k + 3}.png",
            overview = top.overview,
            rating = top.rating,
            genres = top.genres,
        )
    }
}

/** Filmerna med riktiga titlar och år i förhandsbiblioteket. */
private val PREVIEW_EXTRA_MOVIES = listOf(
    "Varning för Jönssonligan" to 1981,
    "Jönssonligan får guldfeber" to 1984,
    "Jönssonligan på Mallorca" to 1989,
    "Jönssonligans största kupp" to 1995,
    "Sällskapsresan" to 1980,
    "Sällskapsresan II – Snowroller" to 1985,
    "Den ofrivillige golfaren" to 1991,
    "Tomten är far till alla barnen" to 1999,
    "Snöänglarna" to 2018,
    "Julnatten" to 2021,
    "Pepparkaksstaden" to 2016,
)

/** Julkalendrarna i förhandsbiblioteket: SVT:s, så att de känns igen utan TMDB. */
private val PREVIEW_CALENDARS = listOf(
    "Mysteriet på Greveholm" to 1996,
    "Sunes jul" to 1991,
    "Superhjältejul" to 2009,
    "Tjuvarnas jul" to 2011,
    "Panik i tomteverkstan" to 2019,
)

/** TMDB:s julfilmer, som de skulle se ut: tre av förhandsbibliotekets filmer och en till. */
private fun previewChristmas(): List<TmdbListTitle> = listOf(
    TmdbListTitle(8001, "Tomten är far till alla barnen", "Tomten är far till alla barnen", 1999, 6.7, 180),
    TmdbListTitle(8002, "Snöänglarna", "Snow Angels", 2018, 7.6, 640),
    TmdbListTitle(8003, "Julnatten", "The Night Before", 2021, 7.1, 2400),
    TmdbListTitle(8004, "Pepparkaksstaden", "Gingerbread Town", 2016, 6.2, 90),
    TmdbListTitle(8005, "En julsaga", "A Christmas Carol", 2009, 6.9, 3000),
)

/** En regissör, som TMDB skulle ge den: Lasse Åbergs filmer, varav tre finns. */
private fun previewDirectors(): List<DirectorFilms> = listOf(
    DirectorFilms(
        9001,
        "Lasse Åberg",
        listOf(
            TmdbListTitle(7001, "Repmånad", "Repmånad", 1979, 6.6, 60),
            TmdbListTitle(7002, "Sällskapsresan", "Sällskapsresan", 1980, 7.3, 300),
            TmdbListTitle(7003, "Sällskapsresan II – Snowroller", "Sällskapsresan II – Snowroller", 1985, 6.9, 200),
            TmdbListTitle(7004, "Den ofrivillige golfaren", "Den ofrivillige golfaren", 1991, 6.4, 120),
            TmdbListTitle(7005, "Hälsoresan – En smal film av stor vikt", "Hälsoresan", 1999, 5.9, 80),
            TmdbListTitle(7006, "The Stig-Helmer Story", "The Stig-Helmer Story", 2011, 4.9, 40),
        ),
    ),
)

private val PREVIEW_TOP_TITLES = listOf(
    "Kodnamn Orion", "Stjärnfallet", "Nordljus", "Sista passet", "Vargtimmen",
    "Isvägen", "Mörkrets hamn", "Djupet", "Den långa natten", "Glaciären",
)

private fun waitFor(what: String, timeoutMs: Long, done: () -> Boolean) {
    val until = System.currentTimeMillis() + timeoutMs
    while (!done()) {
        if (System.currentTimeMillis() > until) {
            println("Väntade förgäves på $what")
            return
        }
        Thread.sleep(100)
    }
}

/** Det påhittade biblioteket, sparat där appen läser sitt. */
private class PreviewData(
    val source: Source,
    val content: SourceContent,
    val guide: SourceGuide,
) {
    val channels: List<LiveChannel> get() = content.channels
    val movies: List<Movie> get() = content.movies

    companion object {
        private const val SOURCE = "forhandsvisning"

        private val CHANNELS = listOf(
            "SVT1 HD" to "Sverige", "SVT2 HD" to "Sverige", "TV4 HD" to "Sverige", "Kanal 5 HD" to "Sverige",
            "TV6 HD" to "Sverige", "Sjuan HD" to "Sverige", "TV8" to "Sverige", "Kanal 9" to "Sverige",
            "TV10" to "Sverige", "TV4 Fakta" to "Dokumentär", "Discovery HD" to "Dokumentär",
            "National Geographic" to "Dokumentär", "History" to "Dokumentär", "Animal Planet" to "Dokumentär",
            "Eurosport 1 HD" to "Sport", "Eurosport 2 HD" to "Sport", "TV4 Sport" to "Sport",
            "TV4 Fotboll" to "Sport", "V Sport Football" to "Sport", "V Sport Premium" to "Sport",
            "Viaplay Sport 1" to "Sport", "Viaplay Sport 2" to "Sport", "SVT24" to "Nyheter",
            "CNN International" to "Nyheter", "BBC World News" to "Nyheter", "Sky News" to "Nyheter",
            "TV4 Film" to "Film", "V Film Premiere" to "Film", "V Film Action" to "Film", "V Film Hits" to "Film",
        )

        private val MOVIES = listOf(
            "Nordljus", "Den sista vintern", "Midnattssol", "Stormen", "Silverkusten", "Järnvägen",
            "Fyren", "Kodnamn Orion", "Vargtimmen", "Djupet", "Glaciären", "Nattåget", "Ekot",
            "Skuggspel", "Havsörnen", "Kartan", "Dimman", "Gränsen", "Rymdhamnen", "Vulkanen",
            "Labyrinten", "Spegeln", "Tornet", "Kometen", "Tidvatten", "Ödemarken", "Fjärran land",
            "Kristallen", "Brännpunkt", "Sista ronden", "Röda linjen", "Vinterkriget", "Neonstaden",
            "Ankarplats", "Åskväder", "Guldfeber", "Ljusår", "Isvägen", "Månskugga", "Strömkantring",
        )

        private val SERIES = listOf(
            "Kustbevakarna", "Stadsljus", "Fjällräddarna", "Kommissarien", "Rederiet", "Hotellet",
            "Skärgården", "Nattpatrullen", "Arvingarna", "Gruvan", "Kabinen", "Stationen",
            "Ön", "Bergsbyn", "Tullen", "Akuten", "Fyrvaktarna", "Hamnen",
        )

        private val PROGRAMMES = listOf(
            "Rapport", "Sportnytt", "Go'kväll", "Kulturnyheterna", "Morgonstudion", "Djursjukhuset",
            "Allsvenskan: Studio", "Fotboll: Premier League", "Kvällsöppet", "Nyhetsmorgon",
            "Dokument utifrån", "Vem vet mest?", "Antikrundan", "På spåret", "Uppdrag granskning",
            "Champions League: Höjdpunkter", "Matchen", "Filmklubben", "Skavlan", "Melodifestivalen",
        )

        fun seed(base: String): PreviewData {
            val now = System.currentTimeMillis()
            val source = Source(SOURCE, "Förhandsvisning", SourceType.M3U, "http://exempel.invalid/lista.m3u", lastSyncUtc = now)
            val channels = CHANNELS.mapIndexed { i, (name, group) ->
                LiveChannel(
                    key = "$SOURCE:live:$i",
                    sourceId = SOURCE,
                    name = name,
                    group = group,
                    logo = "$base/logo/$i.png",
                    number = 100 + i + 1,
                    epgId = "kanal$i.se",
                    url = "http://exempel.invalid/live/$i.ts",
                    streamId = null,
                )
            }
            val movieGroups = listOf("Nyheter", "Action", "Drama", "Thriller", "Familj")
            val movies = MOVIES.mapIndexed { i, name ->
                Movie(
                    key = "$SOURCE:movie:$i",
                    sourceId = SOURCE,
                    streamId = 1000 + i,
                    name = "$name (${2016 + i % 9})",
                    group = movieGroups[i % movieGroups.size],
                    poster = "$base/poster/$i.png",
                    rating = 6.0 + (i % 7) * 0.4,
                    addedUtc = now - i * 6 * 3_600_000L,
                    extension = "mkv",
                )
            }
            // Samlingarna och julen: några riktiga titlar med rätt år, så att galleriet,
            // en regissör och julens kategorier har något att visa.
            val extraMovies = PREVIEW_EXTRA_MOVIES.mapIndexed { k, (name, year) ->
                Movie(
                    key = "$SOURCE:movie:x$k",
                    sourceId = SOURCE,
                    streamId = 5000 + k,
                    name = "$name ($year)",
                    group = "Familj",
                    poster = "$base/extra/$k.png",
                    rating = 6.4 + (k % 5) * 0.3,
                    addedUtc = now - (200 + k) * 6 * 3_600_000L,
                    extension = "mkv",
                )
            }
            val seriesGroups = listOf("Svenska serier", "Drama", "Kriminal")
            val extraSeries = PREVIEW_CALENDARS.mapIndexed { k, (name, year) ->
                Series(
                    key = "$SOURCE:series:x$k",
                    sourceId = SOURCE,
                    seriesId = 6000 + k,
                    name = name,
                    group = "Svenska serier",
                    cover = "$base/extra/${20 + k}.png",
                    backdrop = "$base/backdrop/${30 + k}.png",
                    plot = "En av SVT:s julkalendrar, med en lucka om dagen fram till julafton.",
                    genre = "Familj",
                    releaseDate = "$year-12-01",
                    rating = 7.2 + (k % 4) * 0.2,
                    addedUtc = now - (100 + k) * 12 * 3_600_000L,
                )
            }
            val series = SERIES.mapIndexed { i, name ->
                Series(
                    key = "$SOURCE:series:$i",
                    sourceId = SOURCE,
                    seriesId = 2000 + i,
                    name = name,
                    group = seriesGroups[i % seriesGroups.size],
                    cover = "$base/poster/${100 + i}.png",
                    backdrop = "$base/backdrop/$i.png",
                    plot = "En påhittad serie för förhandsbilderna: människor, platser och hemligheter som långsamt kommer upp till ytan.",
                    genre = "Drama",
                    releaseDate = "${2015 + i % 10}-03-01",
                    rating = 7.0 + (i % 5) * 0.3,
                    addedUtc = now - i * 12 * 3_600_000L,
                )
            }
            val content = SourceContent(
                channels = channels,
                movies = movies + extraMovies,
                series = series + extraSeries,
                liveGroups = channels.map { it.group }.distinct(),
                movieGroups = movieGroups,
                seriesGroups = seriesGroups,
                epgUrl = null,
            )

            // Tablån: program runt nu, olika långa, så att något alltid pågår.
            val hour = 3_600_000L
            val programmes = HashMap<String, List<Programme>>()
            channels.forEachIndexed { i, channel ->
                var start = now - hour - (i * 7 % 50) * 60_000L
                val list = ArrayList<Programme>()
                var n = i
                while (start < now + 10 * hour) {
                    val length = listOf(30, 45, 60, 90, 120)[(n * 3) % 5] * 60_000L
                    list += Programme(
                        start = start,
                        stop = start + length,
                        title = PROGRAMMES[n % PROGRAMMES.size],
                        subtitle = null,
                        description = "Ett påhittat program i förhandsbildernas tablå.",
                        category = null,
                    )
                    start += length
                    n++
                }
                programmes[channel.epgId!!] = list
            }
            val guide = SourceGuide(
                channels = channels.map { GuideChannel(it.epgId!!, listOf(it.name)) },
                programmes = programmes,
                fetchedAt = now,
            )
            return PreviewData(source, content, guide)
        }
    }
}

/** Bilderna till det påhittade biblioteket, från en liten server på datorn. */
private class PreviewImages(private val server: HttpServer) {
    val base: String get() = "http://127.0.0.1:${server.address.port}"

    fun stop() = server.stop(0)

    companion object {
        private val cache = ConcurrentHashMap<String, ByteArray>()

        fun start(): PreviewImages {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val bytes = runCatching { cache.getOrPut(path) { png(render(path)) } }.getOrNull()
                if (bytes == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.responseHeaders.add("Content-Type", "image/png")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                exchange.close()
            }
            server.executor = Executors.newFixedThreadPool(4)
            server.start()
            return PreviewImages(server)
        }

        private fun png(image: BufferedImage): ByteArray =
            ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

        private fun render(path: String): BufferedImage {
            val parts = path.trim('/').split('/')
            val index = parts.getOrNull(1)?.substringBefore('.')?.toIntOrNull() ?: 0
            return when (parts.firstOrNull()) {
                "poster" -> poster(index)
                "topp" -> poster(index + 60, PREVIEW_TOP_TITLES.getOrNull(index))
                "extra" -> poster(index + 70, if (index >= 20) PREVIEW_CALENDARS.getOrNull(index - 20)?.first else PREVIEW_EXTRA_MOVIES.getOrNull(index)?.first)
                "backdrop" -> landscape(1280, 720, index)
                "logo" -> logo(index)
                else -> error("okänd bild $path")
            }
        }

        private fun hue(index: Int, shift: Float = 0f): Color =
            Color.getHSBColor(((index * 0.137f + shift) % 1f + 1f) % 1f, 0.62f, 0.78f)

        /** En affisch: en färgskala, en form och titeln ([label], annars bibliotekets titel). */
        private fun poster(index: Int, label: String? = null): BufferedImage {
            val w = 400
            val h = 600
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val top = hue(index)
            val bottom = hue(index, 0.33f).darker().darker()
            g.paint = GradientPaint(0f, 0f, top, 0f, h.toFloat(), bottom)
            g.fillRect(0, 0, w, h)
            g.color = Color(255, 255, 255, 60)
            val r = 120 + (index * 37) % 120
            g.fill(Ellipse2D.Float(w * 0.5f - r + (index % 3 - 1) * 60f, h * 0.38f - r, r * 2f, r * 2f))
            g.color = Color(0, 0, 0, 90)
            val ridge = Path2D.Float().apply {
                moveTo(0f, h * 0.72f)
                for (x in 0..w step 20) lineTo(x.toFloat(), h * 0.66f + 40f * sin(x / 37f + index).toFloat())
                lineTo(w.toFloat(), h.toFloat())
                lineTo(0f, h.toFloat())
                closePath()
            }
            g.fill(ridge)
            val title = (label ?: MOVIE_TITLES.getOrNull(index) ?: SERIES_TITLES.getOrNull(index - 100) ?: "Titel").uppercase()
            g.color = Color.WHITE
            var size = 54
            g.font = Font("SansSerif", Font.BOLD, size)
            while (g.fontMetrics.stringWidth(title) > w - 40 && size > 20) {
                size -= 2
                g.font = Font("SansSerif", Font.BOLD, size)
            }
            g.drawString(title, (w - g.fontMetrics.stringWidth(title)) / 2, (h * 0.88f).toInt())
            g.dispose()
            return image
        }

        private val MOVIE_TITLES = listOf(
            "Nordljus", "Den sista vintern", "Midnattssol", "Stormen", "Silverkusten", "Järnvägen",
            "Fyren", "Kodnamn Orion", "Vargtimmen", "Djupet", "Glaciären", "Nattåget", "Ekot",
            "Skuggspel", "Havsörnen", "Kartan", "Dimman", "Gränsen", "Rymdhamnen", "Vulkanen",
            "Labyrinten", "Spegeln", "Tornet", "Kometen", "Tidvatten", "Ödemarken", "Fjärran land",
            "Kristallen", "Brännpunkt", "Sista ronden", "Röda linjen", "Vinterkriget", "Neonstaden",
            "Ankarplats", "Åskväder", "Guldfeber", "Ljusår", "Isvägen", "Månskugga", "Strömkantring",
        )
        private val SERIES_TITLES = listOf(
            "Kustbevakarna", "Stadsljus", "Fjällräddarna", "Kommissarien", "Rederiet", "Hotellet",
            "Skärgården", "Nattpatrullen", "Arvingarna", "Gruvan", "Kabinen", "Stationen",
            "Ön", "Bergsbyn", "Tullen", "Akuten", "Fyrvaktarna", "Hamnen",
        )

        /** En kanallogga: namnets första ord i vitt på genomskinligt. */
        private fun logo(index: Int): BufferedImage {
            val w = 320
            val h = 180
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val name = PreviewChannelNames.getOrNull(index) ?: "TV"
            val word = name.split(' ').take(2).joinToString(" ").replace(" HD", "")
            g.color = hue(index, 0.5f)
            g.fill(RoundRectangle2D.Float(20f, 40f, 280f, 100f, 40f, 40f))
            g.color = Color.WHITE
            var size = 56
            g.font = Font("SansSerif", Font.BOLD, size)
            while (g.fontMetrics.stringWidth(word) > 250 && size > 18) {
                size -= 2
                g.font = Font("SansSerif", Font.BOLD, size)
            }
            g.drawString(word, (w - g.fontMetrics.stringWidth(word)) / 2, 90 + g.fontMetrics.ascent / 3)
            g.dispose()
            return image
        }

        private val PreviewChannelNames = listOf(
            "SVT1 HD", "SVT2 HD", "TV4 HD", "Kanal 5 HD", "TV6 HD", "Sjuan HD", "TV8", "Kanal 9",
            "TV10", "TV4 Fakta", "Discovery HD", "National Geographic", "History", "Animal Planet",
            "Eurosport 1 HD", "Eurosport 2 HD", "TV4 Sport", "TV4 Fotboll", "V Sport Football",
            "V Sport Premium", "Viaplay Sport 1", "Viaplay Sport 2", "SVT24", "CNN International",
            "BBC World News", "Sky News", "TV4 Film", "V Film Premiere", "V Film Action", "V Film Hits",
        )

        /** Ett landskap i skymning: ljus himmel överst, mörkt vatten nederst — som en filmbild. */
        fun landscape(w: Int, h: Int, index: Int): BufferedImage {
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val sky = listOf(
                Color(255, 170, 92) to Color(84, 46, 118),
                Color(120, 190, 255) to Color(20, 40, 90),
                Color(255, 120, 120) to Color(40, 20, 60),
            )[abs(index) % 3]
            g.paint = GradientPaint(0f, h * 0.62f, sky.first, 0f, 0f, sky.second)
            g.fillRect(0, 0, w, (h * 0.62f).toInt())
            g.color = Color(255, 236, 200)
            val sun = h * 0.11f
            g.fill(Ellipse2D.Float(w * 0.62f - sun, h * 0.44f - sun, sun * 2, sun * 2))
            for (layer in 0..2) {
                val shade = 40 + layer * 18
                g.color = Color(shade / 2, shade / 3, shade)
                val ridge = Path2D.Float().apply {
                    moveTo(0f, h * 0.62f)
                    var x = 0f
                    while (x <= w) {
                        val y = h * (0.40f + layer * 0.07f) + (h * 0.08f) * sin(x / (w * 0.09f) + layer * 2f + index).toFloat() +
                            (h * 0.03f) * sin(x / (w * 0.023f) + layer).toFloat()
                        lineTo(x, y)
                        x += w / 120f
                    }
                    lineTo(w.toFloat(), h * 0.62f)
                    closePath()
                }
                g.fill(ridge)
            }
            g.paint = GradientPaint(0f, h * 0.62f, Color(60, 40, 70), 0f, h.toFloat(), Color(6, 8, 16))
            g.fillRect(0, (h * 0.62f).toInt(), w, h)
            g.color = Color(255, 220, 170, 70)
            g.stroke = BasicStroke(h * 0.004f)
            for (i in 0..14) {
                val y = h * 0.64f + i * h * 0.018f
                val half = (w * 0.10f) * (1f - i / 16f)
                g.drawLine((w * 0.62f - half).toInt(), y.toInt(), (w * 0.62f + half).toInt(), y.toInt())
            }
            g.dispose()
            return image
        }

        /** Bilden bakom spelaren. */
        fun still(): BufferedImage = landscape(WIDTH, HEIGHT, 0)
    }
}

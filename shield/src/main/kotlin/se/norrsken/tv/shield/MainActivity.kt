package se.norrsken.tv.shield

import se.norrsken.tv.desktop.Screen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.data.AppDirs
import se.norrsken.tv.desktop.data.CollectionKeys
import se.norrsken.tv.desktop.platform.WindowsIntegration
import se.norrsken.tv.desktop.ui.AppRoot
import se.norrsken.tv.desktop.ui.AppTheme
import se.norrsken.tv.desktop.ui.CoverPrefetch
import se.norrsken.tv.desktop.ui.Images
import se.norrsken.tv.desktop.ui.TvDrawer
import se.norrsken.tv.desktop.ui.TvNav
import se.norrsken.tv.desktop.ui.TvTheme

/**
 * Shield-appen: Windows-appens skärmar, styrda med fjärrkontrollen.
 *
 * Pilarna flyttar markeringen; det markerade glöder och växer, som när
 * musen står på något i Windows. OK väljer.
 *
 * I spelaren:
 * - OK eller vänster (live-tv): kanallistan ovanpå bilden. OK (film): paus.
 * - Upp och ner (live-tv), kanal upp och ner: byter kanal. Kanalrutan visar
 *   kanalen direkt; den startar när man slutat trycka, eller med OK.
 * - Höger (live-tv), upp och ner (film): knapparna fram — paus, kanalbyte
 *   eller spolning, kanallistan, ljud och undertext, minimera och stäng. I
 *   en film står markeringen först på tidslinjen.
 * - Vänster och höger (film, och på tidslinjen): spolar tio sekunder,
 *   snabbspolningen trettio. Hålls knappen nere går det allt fortare. Målet
 *   syns på tidslinjen, och bilden hoppar dit när man slutat trycka, eller
 *   med OK.
 * - Play/paus: paus. I minispelaren: tillbaka till helskärm.
 * - ≡: ljud och undertext, och stänger panelen igen.
 *
 * Den lilla bilden nere i högra hörnet: Tillbaka i helskärm krymper bilden
 * dit, och man är där man var. Play förstorar den igen. Pil höger eller ner
 * förbi innehållets kant markerar den; då förstorar OK den och Tillbaka
 * stänger den. I Fotboll kan en match som pågår visas där utan ljud.
 *
 * Tillbaka, ett steg i taget: listan eller panelen i spelaren, en spolning
 * eller ett kanalbyte som inte hunnit ske (bilden stannar där den var), knapparna,
 * bilden krymper till hörnet (den lilla bilden stängs när den är markerad),
 * kategorierna, film-, serie- och matchsidan, och sist menyn. I menyn leder
 * Tillbaka till startsidan; där stängs först den lilla bilden, sedan appen.
 */
class MainActivity : ComponentActivity() {
    private lateinit var state: AppState

    /** Felet från förra gången, visas före allt annat. */
    private var crashReport by mutableStateOf<String?>(null)

    private val ready: Boolean get() = ::state.isInitialized

    /** Något i appen har markeringen. Falskt när det man stod på just försvann. */
    private var anyFocus = false

    /** Försök i rad att lägga tillbaka en tappad markering (se dispatchKeyEvent). */
    private var focusRescues = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReport.install(applicationContext)
        // "Om appen" visar versionen, så att man ser att uppdateringen gick in.
        runCatching {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(packageName, 0)
            System.setProperty("tv.version", info.versionName.orEmpty())
        }
        AppDirs.init(applicationContext)
        WindowsIntegration.attach(this)
        // Sitter felet i själva starten ska rapporten ändå gå att se: appen
        // startas först när man tryckt Fortsätt.
        crashReport = CrashReport.take(applicationContext)
        if (crashReport == null) state = AppState()

        setContent {
            // Tv:n ger 960 dp i bredd. Windows-layouten får i stället den bredd
            // som är vald under Utseende (1 280, 1 600 eller 1 920), och texten
            // får en egen storlek så att den går att läsa från soffan.
            val base = LocalDensity.current
            val widthPx = LocalConfiguration.current.screenWidthDp * base.density
            val resolution = TvDisplay.resolution
            val density = remember(base, widthPx, resolution) {
                val scale = widthPx / resolution.widthDp
                val textPx = resolution.textPx * (widthPx / 1920f)
                Density(density = scale, fontScale = textPx / scale * base.fontScale)
            }
            CompositionLocalProvider(LocalDensity provides density) {
                val report = crashReport
                if (report != null) {
                    TvTheme(AppTheme.GRAPHITE, translucent = false) {
                        CrashScreen(report, onContinue = {
                            if (!ready) state = AppState()
                            crashReport = null
                        })
                    }
                } else {
                    TvTheme(state.theme, translucent = state.backgrounds.isNotEmpty()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .onFocusChanged {
                                    anyFocus = it.hasFocus
                                    if (it.hasFocus) focusRescues = 0
                                }
                                .onPreviewKeyEvent { handleRemote(it) },
                        ) {
                            AppRoot(state)
                            val playing = state.nowPlaying
                            // Kanallistan glider in från vänster, ovanpå bilden.
                            AnimatedVisibility(
                                visible = state.channelListOpen && playing != null && playing.live && !state.miniPlayer,
                                enter = fadeIn(tween(160)) + slideInHorizontally(tween(220)) { -it / 5 },
                                exit = fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { -it / 5 },
                            ) {
                                ChannelOverlay(state, onClose = {
                                    state.channelListOpen = false
                                    state.focusPlayer()
                                })
                            }
                        }
                        // Omslagen sparas på Shield, de nyaste filmerna först. Färre än på
                        // datorn: Shield har mindre lagring.
                        CoverPrefetch(state, limit = 6_000)
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    /** Tillbaka-knappen, ett steg i taget (se klassens beskrivning). */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (!ready || crashReport != null) {
                leaveApp()
                return
            }
            val playing = state.nowPlaying
            when {
                state.channelListOpen -> {
                    state.channelListOpen = false
                    state.focusPlayer()
                }
                state.trackPanelOpen -> state.trackPanelOpen = false
                // En spolning eller ett kanalbyte som inte hunnit ske: bilden stannar där den var.
                playing != null && !state.miniPlayer && (state.cancelScrub() || state.cancelZap()) -> Unit
                // Rutan Nästa avsnitt: Tillbaka betyder "titta klart".
                playing != null && !state.miniPlayer && state.dismissPlayerPrompt() -> Unit
                // Knapparna i spelaren: tillbaka till bilden först.
                playing != null && !state.miniPlayer && state.leavePlayerControls() -> Unit
                // Tillbaka i helskärm: bilden glider ner i hörnet och spelar vidare, och
                // markeringen är tillbaka där man var. Play förstorar den igen.
                playing != null && !state.miniPlayer -> {
                    state.minimizePlayer()
                    TvNav.restoreFocus()
                }
                // Den lilla bilden är markerad (som direkt efter att den krympt): stäng den.
                playing != null && state.playerBoxFocused -> {
                    state.closePlayer()
                    TvNav.restoreFocus()
                }
                // Kategorierna framme: tillbaka in i innehållet, som pil höger.
                TvNav.drawer == TvDrawer.CATEGORIES && TvNav.leaveCategories != null -> TvNav.leaveCategories?.invoke()
                // Menyn framme: in i det man var i igen om det finns en sida att backa
                // ur, annars till startsidan — och från startsidan stängs appen.
                TvNav.drawer == TvDrawer.MENU -> when {
                    hasSubPage() -> TvNav.leaveMenu?.invoke() ?: run { TvNav.drawer = TvDrawer.NONE }
                    state.screen != Screen.HOME -> {
                        TvNav.holdMenu = true
                        state.screen = Screen.HOME
                        TvNav.openMenu()
                    }
                    // Den lilla bilden spelar: den stängs först, appen vid nästa tryck.
                    playing != null && state.miniPlayer -> state.closePlayer()
                    else -> leaveApp()
                }
                state.selectedMatch != null -> state.closeMatch()
                state.selectedMovie != null -> state.closeMovie()
                state.selectedSeries != null -> state.closeSeries()
                // Inställningar: från kategorisidan tillbaka till inställningarna.
                state.screen == Screen.SETTINGS && state.managingCategories != null -> state.managingCategories = null
                // Fotboll: från en liga tillbaka till översikten.
                state.screen == Screen.FOOTBALL && state.footballLeague != null -> state.footballLeague = null
                // Samlingar: från en samling (eller Disney) tillbaka till galleriet.
                state.screen == Screen.MOVIES && CollectionKeys.isInside(state.movieGroup) ->
                    state.openCollection(CollectionKeys.GALLERY)
                // Som på andra tv-appar: Tillbaka i innehållet tar fram menyn, med
                // markeringen på fliken man är i. Den lilla bilden spelar vidare.
                else -> TvNav.openMenu()
            }
        }
    }

    /** Det finns en sida att backa ur innan menyn: en film, serie, match, samling eller kategorisidan. */
    private fun hasSubPage(): Boolean =
        state.selectedMatch != null || state.selectedMovie != null || state.selectedSeries != null ||
            (state.screen == Screen.SETTINGS && state.managingCategories != null) ||
            (state.screen == Screen.FOOTBALL && state.footballLeague != null) ||
            (state.screen == Screen.MOVIES && CollectionKeys.isInside(state.movieGroup))

    /**
     * Ingenting kvar att stänga: lämna appen som vanligt. Påslagen igen
     * efteråt — appen kan ligga kvar i bakgrunden, och då ska Tillbaka
     * fungera som förut när man kommer tillbaka.
     */
    private fun leaveApp() {
        backCallback.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
        backCallback.isEnabled = true
    }

    /**
     * Markeringen tappades, till exempel när det man stod på stängdes, och ingen
     * pil har något att utgå från. Första trycket lägger den på något vettigt
     * — i spelaren bilden, annars det man senast stod på eller menyn — i
     * stället för på något som råkar ligga närmast en kant.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Det som hittas i bakgrunden väntar medan man trycker (se AppState.noteInput).
        if (ready && event.action == android.view.KeyEvent.ACTION_DOWN) state.noteInput()
        // Starten: OK (eller en pil) går rakt in i appen. Tas här, eftersom inget
        // är markerat medan omslagen visas, och går inte vidare till sidan bakom.
        if (ready && crashReport == null && !state.introDone && event.keyCode in NAVIGATION_KEYS) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) state.requestIntroSkip()
            return true
        }
        // Två försök som inte hjälpte: då får tv:n sköta trycket som vanligt,
        // så att fjärrkontrollen aldrig kan fastna här.
        if (ready && crashReport == null && !anyFocus && !state.loading && focusRescues < 2 &&
            event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode in NAVIGATION_KEYS
        ) {
            focusRescues++
            val playing = state.nowPlaying
            when {
                playing != null && !state.miniPlayer -> state.focusPlayer()
                else -> TvNav.restoreFocus()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleRemote(event: KeyEvent): Boolean {
        if (!ready || event.type != KeyEventType.KeyDown) return false
        val playing = state.nowPlaying ?: return false

        // Minispelaren: fjärrkontrollen styr appen bakom; play tar tillbaka bilden.
        if (state.miniPlayer) {
            if (event.key == Key.MediaPlayPause || event.key == Key.MediaPlay) {
                state.expandPlayer()
                return true
            }
            return false
        }

        // ≡ öppnar panelen för ljud och undertext, och stänger den igen.
        if (event.key == Key.Menu && !state.channelListOpen) {
            state.commitZap()
            state.trackPanelOpen = !state.trackPanelOpen
            if (!state.trackPanelOpen) state.focusPlayer()
            state.touchPlayer(force = true)
            return true
        }

        // Kanallistan och panelen för ljud och undertext styrs med pilarna som resten av appen.
        if (state.channelListOpen || state.trackPanelOpen) return false

        val live = playing.live
        // Knappen hålls nere: spolningen går allt fortare.
        val held = event.nativeKeyEvent.repeatCount > 0
        val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter

        if (live) {
            // Pil upp och ner byter alltid kanal — också från knapparna och felrutan.
            when (event.key) {
                Key.DirectionUp, Key.ChannelUp, Key.MediaRewind -> {
                    state.zap(-1)
                    return true
                }
                Key.DirectionDown, Key.ChannelDown, Key.MediaFastForward -> {
                    state.zap(1)
                    return true
                }
            }
            // Ett kanalbyte med pilarna på gång: det görs nu, och OK gör inget mer.
            // Från spelarens knappar (Nästa kanal) väntar bytet, så att OK kan trycka igen.
            if (!state.playerControlsFocused && state.commitZap() && ok) return true
        }

        // Tidslinjen i en film: pil vänster och höger spolar, OK pausar — eller
        // hoppar direkt dit spolningen kommit. Upp och ner flyttar markeringen.
        if (!live && state.playerTimelineFocused) {
            state.touchPlayer(force = true)
            when {
                event.key == Key.DirectionLeft -> state.scrub(-1, held)
                event.key == Key.DirectionRight -> state.scrub(1, held)
                event.key == Key.MediaRewind -> state.scrub(-1, held, big = true)
                event.key == Key.MediaFastForward -> state.scrub(1, held, big = true)
                ok || event.key == Key.MediaPlayPause || event.key == Key.MediaPlay || event.key == Key.MediaPause ->
                    if (!state.commitScrub()) state.togglePause()
                else -> return false
            }
            return true
        }

        // Spelarens knappar har markeringen: pilarna flyttar mellan dem och OK
        // trycker på den man står på. Knapparna står kvar så länge man använder dem.
        if (state.playerControlsFocused) {
            state.touchPlayer(force = true)
            when (event.key) {
                Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> state.togglePause()
                Key.MediaRewind -> state.scrub(-1, held, big = true)
                Key.MediaFastForward -> state.scrub(1, held, big = true)
                else -> return false
            }
            return true
        }

        when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                // En spolning på gång: OK hoppar dit direkt.
                if (state.commitScrub()) return true
                // Hoppa över intro eller Nästa avsnitt syns: OK väljer den.
                if (state.acceptPlayerPrompt()) return true
                if (live) {
                    state.openChannelList()
                } else {
                    state.togglePause()
                    state.touchPlayer(force = true)
                }
                return true
            }
            Key.DirectionLeft -> {
                if (live) state.openChannelList() else state.scrub(-1, held)
                return true
            }
            // Pil höger i direktsänt: knapparna fram (paus, kanalbyte, kanallistan, ljud och
            // undertext, minimera, stäng), med markeringen på Spela/Paus. Utan play- och
            // menyknappar på fjärrkontrollen är det vägen dit. I en film: spola framåt.
            Key.DirectionRight -> {
                if (live) state.focusPlayerControls() else state.scrub(1, held)
                return true
            }
            // Pil upp eller ner i en film: knapparna fram, med markeringen på tidslinjen.
            // (Ljudet sköts med tv:ns egen fjärrkontroll.)
            Key.DirectionUp, Key.DirectionDown -> {
                state.focusPlayerControls()
                return true
            }
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                state.togglePause()
                state.touchPlayer(force = true)
                return true
            }
            Key.MediaFastForward -> {
                state.scrub(1, held, big = true)
                return true
            }
            Key.MediaRewind -> {
                state.scrub(-1, held, big = true)
                return true
            }
        }
        return state.handleKey(event)
    }

    override fun onStart() {
        super.onStart()
        Images.appHidden = false
        if (ready) {
            state.appVisible = true
            // Tillbaka efter en stund: kanalerna och tablån hämtas om, så att
            // Fotboll hittar dagens kanaler direkt.
            state.refreshChannelsAndGuide()
        }
    }

    override fun onStop() {
        // Hem-knappen eller en annan app: positionen sparas och bilden pausas.
        if (ready) {
            state.saveProgressNow()
            state.pausePlayback()
        }
        // Omslagen och fotbollen hämtas inte i förväg medan appen inte syns.
        Images.appHidden = true
        if (ready) state.appVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        if (ready && isFinishing) state.shutdown()
        WindowsIntegration.attach(null)
        super.onDestroy()
    }
}

/** Pilarna och OK: knapparna som behöver något markerat att utgå från. */
private val NAVIGATION_KEYS = setOf(
    android.view.KeyEvent.KEYCODE_DPAD_UP,
    android.view.KeyEvent.KEYCODE_DPAD_DOWN,
    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
    android.view.KeyEvent.KEYCODE_ENTER,
    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
)

package se.norrsken.tv.shield

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import se.norrsken.tv.desktop.platform.WindowsIntegration
import se.norrsken.tv.desktop.ui.AppRoot
import se.norrsken.tv.desktop.ui.AppTheme
import se.norrsken.tv.desktop.ui.TvTheme

/**
 * Shield-appen: Windows-appens skärmar, styrda med fjärrkontrollen.
 *
 * Pilarna flyttar markeringen; det markerade glöder och växer, som när
 * musen står på något i Windows. OK väljer.
 *
 * I spelaren:
 * - OK eller vänster (live-tv): kanallistan ovanpå bilden. OK (film): paus.
 * - Upp och ner, kanal upp och ner: byter kanal.
 * - Vänster och höger (film): spolar tio sekunder; snabbspolning trettio.
 * - Play/paus: paus. I minispelaren: tillbaka till helskärm.
 * - ≡: ljud och undertext.
 * - Tillbaka: stänger listan eller panelen, krymper sedan bilden till
 *   hörnet och stänger den till sist.
 */
class MainActivity : ComponentActivity() {
    private lateinit var state: AppState

    /** Kanallistan ovanpå bilden. */
    private var channelsOpen by mutableStateOf(false)

    /** Felet från förra gången, visas före allt annat. */
    private var crashReport by mutableStateOf<String?>(null)

    private val ready: Boolean get() = ::state.isInitialized

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReport.install(applicationContext)
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
                        Box(Modifier.fillMaxSize().onPreviewKeyEvent { handleRemote(it) }) {
                            AppRoot(state)
                            val playing = state.nowPlaying
                            if (channelsOpen && playing != null && playing.live && !state.miniPlayer) {
                                ChannelOverlay(state, onClose = { channelsOpen = false })
                            }
                        }
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!ready || crashReport != null) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        return
                    }
                    val playing = state.nowPlaying
                    when {
                        channelsOpen -> channelsOpen = false
                        state.trackPanelOpen -> state.trackPanelOpen = false
                        // Som Esc i Windows: bilden krymper till hörnet och fortsätter där.
                        playing != null && !state.miniPlayer -> state.minimizePlayer()
                        state.selectedMovie != null -> state.closeMovie()
                        state.selectedSeries != null -> state.closeSeries()
                        playing != null -> state.closePlayer()
                        else -> {
                            // Ingenting kvar att stänga: lämna appen som vanligt.
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            },
        )
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

        // Kanallistan och panelen för ljud och undertext styrs med pilarna som resten av appen.
        if (channelsOpen || state.trackPanelOpen) return false

        val live = playing.live
        when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (live) channelsOpen = true else state.togglePause()
                state.touchPlayer(force = true)
                return true
            }
            Key.DirectionLeft -> if (live) {
                channelsOpen = true
                return true
            }
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                state.togglePause()
                state.touchPlayer(force = true)
                return true
            }
            Key.Menu -> {
                state.trackPanelOpen = true
                state.touchPlayer(force = true)
                return true
            }
            Key.ChannelUp -> {
                state.switchChannel(-1)
                return true
            }
            Key.ChannelDown -> {
                state.switchChannel(1)
                return true
            }
            Key.MediaFastForward -> {
                if (live) state.switchChannel(1) else state.videoPlayer?.seekBy(30_000)
                state.touchPlayer(force = true)
                return true
            }
            Key.MediaRewind -> {
                if (live) state.switchChannel(-1) else state.videoPlayer?.seekBy(-30_000)
                state.touchPlayer(force = true)
                return true
            }
        }
        return state.handleKey(event)
    }

    override fun onDestroy() {
        if (ready && isFinishing) state.shutdown()
        WindowsIntegration.attach(null)
        super.onDestroy()
    }
}

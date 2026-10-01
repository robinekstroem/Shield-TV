package se.norrsken.tv.shield

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.data.AppDirs
import se.norrsken.tv.desktop.platform.WindowsIntegration
import se.norrsken.tv.desktop.ui.AppRoot
import se.norrsken.tv.desktop.ui.TvTheme

/**
 * Shield-appen: Windows-appens skärmar, styrda med fjärrkontrollen.
 *
 * Pilarna flyttar markeringen; det markerade glöder och växer, som när
 * musen står på något i Windows. OK väljer. I spelaren gäller samma
 * tangenter som i Windows, plus fjärrkontrollens egna.
 */
class MainActivity : ComponentActivity() {
    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppDirs.init(applicationContext)
        WindowsIntegration.attach(this)
        state = AppState()

        setContent {
            // Tv:n ger 960 dp i bredd. Windows-layouten behöver mer, så den får
            // samma utrymme som ett fönster på 1 280 dp — och texten skalas upp
            // igen, så att den går att läsa från soffan.
            val base = LocalDensity.current
            val density = remember(base) { Density(density = base.density * 0.75f, fontScale = base.fontScale * 1.15f) }
            CompositionLocalProvider(LocalDensity provides density) {
                TvTheme(state.theme, translucent = state.backgrounds.isNotEmpty()) {
                    Box(Modifier.fillMaxSize().onPreviewKeyEvent { handleRemote(it) }) {
                        AppRoot(state)
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        state.trackPanelOpen -> state.trackPanelOpen = false
                        state.nowPlaying != null -> state.closePlayer()
                        state.selectedMovie != null -> state.closeMovie()
                        state.selectedSeries != null -> state.closeSeries()
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

    /** Fjärrkontrollen i spelaren. Allt annat går till markeringen, som i resten av appen. */
    private fun handleRemote(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val playing = state.nowPlaying
        val watching = playing != null && !state.miniPlayer
        // Panelen för ljud och undertext styrs med pilarna som resten av appen.
        if (watching && state.trackPanelOpen) return false
        if (watching) {
            val live = playing?.live == true
            when (event.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
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
        }
        return state.handleKey(event)
    }

    override fun onDestroy() {
        if (isFinishing) state.shutdown()
        WindowsIntegration.attach(null)
        super.onDestroy()
    }
}

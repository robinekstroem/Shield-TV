package se.norrsken.tv.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import se.norrsken.tv.desktop.platform.WindowsIntegration
import se.norrsken.tv.desktop.player.VlcRuntime
import se.norrsken.tv.desktop.ui.AppRoot
import se.norrsken.tv.desktop.ui.Glyphs
import se.norrsken.tv.desktop.ui.PlayerScreen
import se.norrsken.tv.desktop.ui.TvTheme
import java.awt.Dimension
import java.awt.GraphicsEnvironment

fun main() {
    // Före allt annat: var VLC ligger måste vara känt innan JNA används första gången.
    VlcRuntime.prepare()

    application {
        val state = remember { AppState() }
        val windowState = rememberWindowState(
            size = DpSize(1360.dp, 860.dp),
            position = WindowPosition(Alignment.Center),
        )
        var mainWindow by remember { mutableStateOf<ComposeWindow?>(null) }

        Window(
            onCloseRequest = {
                state.shutdown()
                exitApplication()
            },
            state = windowState,
            title = "Tv",
            icon = rememberVectorPainter(Glyphs.AppMark),
            onPreviewKeyEvent = state::handleKey,
        ) {
            LaunchedEffect(Unit) {
                mainWindow = window
                window.minimumSize = Dimension(960, 600)
                WindowsIntegration.darkTitleBar(window)
            }
            // Tillbaka från helskärm: huvudfönstret får tangentbordet igen.
            var wasFullscreen by remember { mutableStateOf(false) }
            LaunchedEffect(state.fullscreen) {
                if (wasFullscreen && !state.fullscreen) {
                    window.toFront()
                    window.requestFocus()
                }
                wasFullscreen = state.fullscreen
            }
            TvTheme(state.theme, translucent = state.backgrounds.isNotEmpty()) {
                AppRoot(state)
            }
        }

        if (state.fullscreen && state.nowPlaying != null) {
            FullscreenPlayer(state, mainWindow)
        }
    }
}

/**
 * Helskärm som ett eget ramlöst fönster över hela skärmen, på den skärm
 * appen står på.
 *
 * Inte Javas exklusiva helskärmsläge: det minimerar fönstret så fort något
 * annat får fokus — ett klick på en andra skärm och tv:n försvinner — och
 * blinkar svart när det slås på och av. Ett vanligt fönster som täcker
 * skärmen behandlar Windows ändå som helskärm; aktivitetsfältet hamnar bakom.
 */
@Composable
private fun FullscreenPlayer(state: AppState, owner: ComposeWindow?) {
    val bounds = remember {
        (owner?.graphicsConfiguration ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration)
            .bounds
    }
    val windowState = rememberWindowState(
        placement = WindowPlacement.Floating,
        position = WindowPosition(bounds.x.dp, bounds.y.dp),
        size = DpSize(bounds.width.dp, bounds.height.dp),
    )
    Window(
        // Alt+F4 lämnar helskärm, men stänger inte appen.
        onCloseRequest = { state.fullscreen = false },
        state = windowState,
        title = "Tv",
        icon = rememberVectorPainter(Glyphs.AppMark),
        undecorated = true,
        resizable = false,
        onPreviewKeyEvent = state::handleKey,
    ) {
        LaunchedEffect(Unit) {
            window.toFront()
            window.requestFocus()
        }
        TvTheme(state.theme, translucent = state.backgrounds.isNotEmpty()) {
            PlayerScreen(state)
        }
    }
}

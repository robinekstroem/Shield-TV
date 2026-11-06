package se.norrsken.tv.desktop

import se.norrsken.tv.desktop.ui.separateFullscreenWindow
import se.norrsken.tv.desktop.ui.platformName
import se.norrsken.tv.desktop.ui.CoverPrefetch
import kotlinx.coroutines.delay
import androidx.compose.ui.window.WindowState
import se.norrsken.tv.desktop.data.AppDirs
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
    installCrashReport()
    // Mac: mörkt utseende och appens namn i menyraden, innan något fönster finns.
    WindowsIntegration.prepareApp()
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
                WindowsIntegration.installQuitBehaviour()
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
            CoverPrefetch(state, COVER_PREFETCH_LIMIT)
        }

        if (separateFullscreenWindow) {
            if (state.fullscreen && state.nowPlaying != null) {
                FullscreenPlayer(state, mainWindow)
            }
        } else {
            MacFullscreen(state, windowState)
        }
    }
}

/** Omslag som sparas på disken i förväg: datorn har plats för många. */
private const val COVER_PREFETCH_LIMIT = 8_000

/**
 * Mac: helskärm är Macens egen. Hela fönstret glider ut till ett eget
 * skrivbord, med spelaren över allt, och tillbaka igen. Var fönstret redan i
 * helskärm när filmen startade får det stanna där.
 */
@Composable
private fun MacFullscreen(state: AppState, windowState: WindowState) {
    var enteredByApp by remember { mutableStateOf(false) }
    var placementBefore by remember { mutableStateOf(WindowPlacement.Floating) }
    LaunchedEffect(state.fullscreen) {
        if (state.fullscreen) {
            if (windowState.placement != WindowPlacement.Fullscreen) {
                placementBefore = windowState.placement
                windowState.placement = WindowPlacement.Fullscreen
                enteredByApp = true
            }
        } else if (enteredByApp) {
            enteredByApp = false
            windowState.placement = placementBefore
        }
    }
    // Lämnar man helskärm med Macens gröna knapp eller ⌃⌘F följer appen med.
    // Efter en paus: medan Macens animering pågår kan läget hinna se annorlunda ut.
    LaunchedEffect(windowState.placement) {
        if (windowState.placement == WindowPlacement.Fullscreen || !state.fullscreen) return@LaunchedEffect
        delay(1_500)
        if (windowState.placement != WindowPlacement.Fullscreen && state.fullscreen) {
            enteredByApp = false
            state.fullscreen = false
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

/**
 * Stängs appen av ett fel visas felet i en ruta som går att fotografera och
 * skicka, och det sparas i senaste-krasch.txt bredvid inställningarna.
 */
private fun installCrashReport() {
    Thread.setDefaultUncaughtExceptionHandler { _, error ->
        val text = buildString {
            append("Tv för ").append(platformName).append(' ').append(System.getProperty("tv.version").orEmpty()).append('\n')
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 6) {
                if (depth > 0) append("\n\nOrsak: ")
                append(current.javaClass.name).append(": ").append(current.message.orEmpty())
                for (frame in current.stackTrace.take(10)) append("\n    at ").append(frame.toString())
                val cause = current.cause
                current = if (cause === current) null else cause
                depth++
            }
        }
        runCatching { java.io.File(AppDirs.config, "senaste-krasch.txt").writeText(text, Charsets.UTF_8) }
        runCatching {
            val area = javax.swing.JTextArea(text, 24, 110).apply {
                isEditable = false
                font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13)
            }
            javax.swing.JOptionPane.showMessageDialog(
                null,
                javax.swing.JScrollPane(area),
                "Tv stängdes av ett fel – ta en bild och skicka den i chatten",
                javax.swing.JOptionPane.ERROR_MESSAGE,
            )
        }
        kotlin.system.exitProcess(1)
    }
}

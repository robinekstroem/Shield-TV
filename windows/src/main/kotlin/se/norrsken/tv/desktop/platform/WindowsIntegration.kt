package se.norrsken.tv.desktop.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import java.awt.Desktop
import java.awt.Window
import java.awt.desktop.QuitStrategy

/**
 * Det lilla som behöver prata direkt med operativsystemet. Heter
 * WindowsIntegration av historiska skäl, men gör samma sak på Mac, på Macens
 * sätt. Allt är best effort: misslyckas ett anrop fungerar appen ändå, bara
 * lite mindre polerat.
 */
object WindowsIntegration {

    val isMac: Boolean = Platform.isMac()

    interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    interface Power : Library {
        fun SetThreadExecutionState(flags: Int): Int
    }

    private val dwm: Dwm? by lazy {
        if (!Platform.isWindows()) null
        else runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull()
    }

    private val power: Power? by lazy {
        if (!Platform.isWindows()) null
        else runCatching { Native.load("kernel32", Power::class.java) }.getOrNull()
    }

    /**
     * Anropas först av allt i main(), innan något fönster finns: inställningar
     * som Java bara läser när fönstersystemet startar.
     */
    fun prepareApp() {
        if (!isMac) return
        // Mörka titelrader och menyer oavsett Macens eget läge: appen är mörk,
        // och en ljus list ovanför den ser ut som ett fel.
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
        System.setProperty("apple.awt.application.name", "Tv")
    }

    /**
     * Mac: Cmd+Q stänger fönstret på vanligt sätt i stället för att avsluta
     * Java direkt, så att positionen i filmen sparas och spelaren stängs snyggt.
     */
    fun installQuitBehaviour() {
        if (!isMac) return
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_STRATEGY)) {
                Desktop.getDesktop().setQuitStrategy(QuitStrategy.CLOSE_ALL_WINDOWS)
            }
        }
    }

    /** Mörk titelrad i Windows. En vit list ovanför en svart app ser ut som ett fel. På Mac: se [prepareApp]. */
    fun darkTitleBar(window: Window) {
        val api = dwm ?: return
        runCatching {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            val enabled = IntByReference(1)
            if (api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, enabled, 4) != 0) {
                // Windows 10 före 20H1 använde ett annat nummer.
                api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, enabled, 4)
            }
        }
    }

    /** Macens caffeinate medan något spelas; avslutas när uppspelningen slutar. */
    private var caffeinate: Process? = null

    /**
     * Håller skärmen vaken under uppspelning. VLC gör det själv bara när det
     * äger fönstret; här ritar appen bilden, så ansvaret hamnar här.
     *
     * Windows: flaggan gäller per tråd, så den sätts alltid från UI-tråden, som
     * lever lika länge som appen. Mac: Apples verktyg caffeinate, som av sig
     * själv slutar när appen gör det, även om den skulle krascha.
     */
    @Synchronized
    fun keepAwake(enabled: Boolean) {
        if (isMac) {
            if (enabled) {
                if (caffeinate?.isAlive == true) return
                caffeinate = runCatching {
                    ProcessBuilder("/usr/bin/caffeinate", "-d", "-i", "-w", ProcessHandle.current().pid().toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                }.getOrNull()
            } else {
                caffeinate?.destroy()
                caffeinate = null
            }
            return
        }
        val api = power ?: return
        val flags = if (enabled) ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED else ES_CONTINUOUS
        runCatching { api.SetThreadExecutionState(flags) }
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19
    private const val ES_CONTINUOUS = Int.MIN_VALUE // 0x80000000
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002
}

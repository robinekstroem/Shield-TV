package se.norrsken.tv.desktop.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/**
 * Det lilla som behöver prata direkt med Windows. Allt är best effort:
 * misslyckas ett anrop fungerar appen ändå, bara lite mindre polerat.
 */
object WindowsIntegration {

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

    /** Mörk titelrad. En vit list ovanför en svart app ser ut som ett fel. */
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

    /**
     * Håller skärmen vaken under uppspelning. VLC gör det själv bara när det
     * äger fönstret; här ritar appen bilden, så ansvaret hamnar här.
     *
     * Flaggan gäller per tråd, så den sätts alltid från UI-tråden, som lever
     * lika länge som appen.
     */
    fun keepAwake(enabled: Boolean) {
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

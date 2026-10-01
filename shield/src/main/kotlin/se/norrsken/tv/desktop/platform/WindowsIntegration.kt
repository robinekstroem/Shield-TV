package se.norrsken.tv.desktop.platform

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shield-versionen. Samma namn som Windows-filen, så att den delade koden
 * hittar den: vaken skärm under uppspelning, och bildfrekvensen som följer
 * innehållet.
 */
object WindowsIntegration {
    @Volatile
    private var activity: Activity? = null

    fun attach(activity: Activity?) {
        if (activity == null) resetFrameRate()
        this.activity = activity
    }

    fun keepAwake(enabled: Boolean) {
        val current = activity ?: return
        current.runOnUiThread {
            if (enabled) {
                current.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                current.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    /**
     * Ställer tv:n på en bildfrekvens som går jämnt upp med strömmen: 50 Hz för
     * svenska kanaler i 25 eller 50 bilder per sekund, 23,976 Hz för film. Utan
     * det hackar rörelser när 50 Hz-tv visas på 60 Hz. Byts bara när det
     * behövs; tv:n kan blinka till en gång när det sker.
     */
    fun matchFrameRate(fps: Float?) {
        val current = activity ?: return
        if (fps == null || fps <= 0f) return
        current.runOnUiThread {
            val display = displayOf(current) ?: return@runOnUiThread
            val active = display.mode
            if (score(active.refreshRate, fps) < 0.002f) return@runOnUiThread
            val best = display.supportedModes
                .filter { it.physicalWidth == active.physicalWidth && it.physicalHeight == active.physicalHeight }
                .minByOrNull { score(it.refreshRate, fps) }
                ?.takeIf { score(it.refreshRate, fps) < 0.002f }
                ?: return@runOnUiThread
            val attributes = current.window.attributes
            if (attributes.preferredDisplayModeId != best.modeId) {
                attributes.preferredDisplayModeId = best.modeId
                current.window.attributes = attributes
            }
        }
    }

    private fun resetFrameRate() {
        val current = activity ?: return
        current.runOnUiThread {
            val attributes = current.window.attributes
            attributes.preferredDisplayModeId = 0
            current.window.attributes = attributes
        }
    }

    /** Hur väl en uppdateringsfrekvens passar: 0 är en jämn multipel. Lägre multipel vinner vid lika. */
    private fun score(refresh: Float, fps: Float): Float {
        val multiple = (refresh / fps).roundToInt().coerceIn(1, 3)
        return abs(refresh - fps * multiple) / refresh + (multiple - 1) * 0.0001f
    }

    @Suppress("DEPRECATION")
    private fun displayOf(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
}

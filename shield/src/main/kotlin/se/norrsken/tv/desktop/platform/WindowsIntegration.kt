package se.norrsken.tv.desktop.platform

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import se.norrsken.tv.shield.TvDisplay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shield-versionen. Samma namn som Windows-filen, så att den delade koden
 * hittar den: vaken skärm under uppspelning, och tv:ns bildfrekvens som
 * följer det som spelas.
 *
 * Bildfrekvensen: en film i 23,976 bilder per sekund visas på 23,976 Hz,
 * svensk tv och fotboll (25 eller 50 bilder) på 50 Hz. På tv:ns vanliga
 * 60 Hz hackar rörelserna annars, eftersom en bild då och då visas en gång
 * för mycket. Bland lägen som går jämnt upp väljs det snabbaste, så att
 * menyerna ovanpå bilden rör sig mjukt: 50 Hz hellre än 25.
 *
 * Läget gäller bara medan bilden syns. Stängs spelaren efter en film går
 * tv:n tillbaka till sitt vanliga läge, så att menyerna aldrig fastnar på
 * 24 Hz. Ett läge på 50 eller 60 Hz får vara kvar: menyerna rör sig mjukt i
 * det, och tv:n slipper bli svart en gång till när man stänger. Den lilla
 * bilden i hörnet behåller också 50 och 60 Hz, men inte filmens 24.
 *
 * Med inställningen av (TvDisplay.matchRate) rörs tv:ns läge aldrig.
 *
 * Allt här körs på huvudtråden.
 */
object WindowsIntegration {

    /** Var bilden syns: ingenstans, liten i hörnet eller över hela tv:n. */
    enum class PlayerView { NONE, MINI, FULL }

    private val main = Handler(Looper.getMainLooper())
    private var activity: Activity? = null

    /** Tv:ns läge när appen inte önskar något: dit går den tillbaka. */
    private var uiMode: Display.Mode? = null
    private var view = PlayerView.NONE

    /** Bildfrekvensen i det som spelas, när den är känd. */
    private var contentFps: Float? = null

    /**
     * Anropas med true när tv:n byter läge medan bilden fyller skärmen, och
     * med false när den har ställt om sig. Spelaren låter en film vänta så
     * länge, så att inget hinner spelas medan tv:n är svart.
     */
    var onSwitching: ((Boolean) -> Unit)? = null

    private var settle: Runnable? = null
    private var awaitedMode = 0
    private var modeReached = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            val current = activity ?: return
            val display = displayOf(current) ?: return
            if (display.displayId != displayId) return
            // Ändrar man tv:ns läge i Shields inställningar blir det det nya vanliga.
            if (current.window.attributes.preferredDisplayModeId == 0) uiMode = display.mode
            val done = settle ?: return
            if (modeReached || display.mode.modeId != awaitedMode) return
            // Läget är bytt. Tv:n behöver ändå en stund innan bilden syns igen.
            modeReached = true
            main.removeCallbacks(done)
            main.postDelayed(done, SETTLE_MS)
        }
    }

    fun attach(activity: Activity?) {
        val previous = this.activity
        if (activity == null) {
            finishSwitch()
            contentFps = null
            view = PlayerView.NONE
            if (previous != null) {
                runCatching { previous.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener) }
                runCatching {
                    val attributes = previous.window.attributes
                    if (attributes.preferredDisplayModeId != 0) {
                        attributes.preferredDisplayModeId = 0
                        previous.window.attributes = attributes
                    }
                }
            }
            this.activity = null
            return
        }
        if (previous != null && previous !== activity) {
            runCatching { previous.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener) }
        }
        this.activity = activity
        uiMode = displayOf(activity)?.mode
        runCatching { activity.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, main) }
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
     * Bildfrekvensen i det som spelas: angiven av strömmen, eller uppmätt när
     * den inte säger något (direktsänd tv). Samma värde två gånger gör inget.
     */
    fun matchFrameRate(fps: Float?) {
        if (fps == null || fps <= 0f) return
        onMain {
            contentFps = fps
            apply()
        }
    }

    /** Inställningen ändrades: gäller direkt, också för det som spelas. */
    fun refresh() = onMain { apply() }

    /** Var bilden syns. Utan spelare glöms bildfrekvensen och tv:n går tillbaka. */
    fun playerView(next: PlayerView) {
        onMain {
            if (next == PlayerView.NONE) contentFps = null
            view = next
            apply()
        }
    }

    private fun apply() {
        val current = activity ?: return
        val display = displayOf(current) ?: return
        val attributes = current.window.attributes
        if (attributes.preferredDisplayModeId == 0) uiMode = display.mode
        val follow = runCatching { TvDisplay.matchRate }.getOrDefault(true)
        val fps = contentFps
        // Spelaren stängd och tv:n står på 50 eller 60 Hz: så får det vara. Menyerna rör
        // sig mjukt där, och ett byte till skulle göra tv:n svart en gång till.
        if (follow && view == PlayerView.NONE && attributes.preferredDisplayModeId != 0) {
            val kept = display.supportedModes.firstOrNull { it.modeId == attributes.preferredDisplayModeId }
            if (kept != null && kept.refreshRate >= SMOOTH_HZ) {
                finishSwitch()
                return
            }
        }
        val target = if (!follow || fps == null || view == PlayerView.NONE) {
            null
        } else {
            // Liten bild i hörnet: menyerna går före, så 24 Hz gäller bara i helbild.
            bestMode(display, fps)?.takeIf { view == PlayerView.FULL || it.refreshRate >= SMOOTH_HZ }
        }
        val id = target?.modeId ?: 0
        if (attributes.preferredDisplayModeId == id) return
        attributes.preferredDisplayModeId = id
        current.window.attributes = attributes
        // Tv:n blir svart en stund när läget byts. Fyller bilden skärmen får en film vänta.
        if (target != null && view == PlayerView.FULL && target.modeId != display.mode.modeId) {
            beginSwitch(target.modeId)
        } else {
            finishSwitch()
        }
    }

    /**
     * Det snabbaste läget i tv:ns vanliga upplösning som går jämnt upp med
     * bildfrekvensen. Upplösningen behålls: Shield skalar själv upp till 4K,
     * bättre än många tv-apparater. Finns inget exakt läge duger ett som
     * nästan går jämnt upp (24 bilder på 23,976 Hz) — det är ändå mjukare
     * än 60 Hz.
     */
    private fun bestMode(display: Display, fps: Float): Display.Mode? {
        val base = uiMode ?: display.mode
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == base.physicalWidth && it.physicalHeight == base.physicalHeight
        }
        return sameSize.filter { fits(it.refreshRate, fps, EXACT) }.maxByOrNull { it.refreshRate }
            ?: sameSize.filter { fits(it.refreshRate, fps, NEAR) }.maxByOrNull { it.refreshRate }
    }

    /** Om frekvensen är en jämn multipel av bildfrekvensen: 50 Hz för 25 bilder, 23,976 Hz för 23,976. */
    private fun fits(refresh: Float, fps: Float, tolerance: Float): Boolean {
        val multiple = (refresh / fps).roundToInt()
        return multiple >= 1 && abs(refresh - fps * multiple) / refresh < tolerance
    }

    private fun beginSwitch(modeId: Int) {
        settle?.let { main.removeCallbacks(it) }
        val done = Runnable { finishSwitch() }
        settle = done
        awaitedMode = modeId
        modeReached = false
        onSwitching?.invoke(true)
        // Säger tv:n aldrig att den bytt fortsätter filmen ändå efter en stund.
        main.postDelayed(done, SWITCH_TIMEOUT_MS)
    }

    private fun finishSwitch() {
        val pending = settle ?: return
        main.removeCallbacks(pending)
        settle = null
        onSwitching?.invoke(false)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    @Suppress("DEPRECATION")
    private fun displayOf(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
}

/** 48 Hz och uppåt räcker för att menyerna ska röra sig mjukt. */
private const val SMOOTH_HZ = 47f

/** 23,976 och 24 skiljer en promille: exakt betyder under en halv. */
private const val EXACT = 0.0005f
private const val NEAR = 0.0015f

/** Efter bytet: så länge brukar tv:n vara svart innan bilden kommer tillbaka. */
private const val SETTLE_MS = 1_800L
private const val SWITCH_TIMEOUT_MS = 4_500L

package se.norrsken.tv.desktop.platform

import android.app.Activity
import android.view.WindowManager

/**
 * Shield-versionen. Samma namn som Windows-filen, så att den delade koden
 * hittar den: vaken skärm under uppspelning.
 *
 * Tv:ns bildfrekvens byter appen inte själv. Varje byte gör tv:n svart en
 * stund när något startar och när det stängs, och det känns trögt. Spelaren
 * ber i stället om filmens bildfrekvens bara när tv:n kan byta utan att bli
 * svart (se VideoPlayer); annars står tv:n kvar i sitt läge.
 */
object WindowsIntegration {
    private var activity: Activity? = null

    fun attach(activity: Activity?) {
        val previous = this.activity
        if (activity == null && previous != null) {
            // Från en äldre version kan ett läge ligga kvar: tv:n får sitt vanliga igen.
            runCatching {
                val attributes = previous.window.attributes
                if (attributes.preferredDisplayModeId != 0) {
                    attributes.preferredDisplayModeId = 0
                    previous.window.attributes = attributes
                }
            }
        }
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
}

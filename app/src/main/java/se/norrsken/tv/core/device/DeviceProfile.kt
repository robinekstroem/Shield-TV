package se.norrsken.tv.core.device

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
data class DeviceProfile(
    val isTv: Boolean,
    /** Sant när ingen touchskärm finns — då måste varje flöde gå med D-pad. */
    val dpadOnly: Boolean,
) {
    companion object {
        val Handheld = DeviceProfile(isTv = false, dpadOnly = false)
    }
}

/**
 * Två oberoende signaler, för de går isär i praktiken: en Chromecast med
 * Google TV rapporterar TV-läge, vissa billiga boxar gör det inte men saknar
 * touch. Vi vill ha rätt svar på båda.
 */
fun detectDeviceProfile(context: Context): DeviceProfile {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val leanback = context.packageManager
        .hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val televisionMode =
        uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    val hasTouch = context.packageManager
        .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    return DeviceProfile(
        isTv = leanback || televisionMode,
        dpadOnly = !hasTouch,
    )
}

val LocalDeviceProfile = staticCompositionLocalOf { DeviceProfile.Handheld }

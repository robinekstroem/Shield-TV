package se.norrsken.tv.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import se.norrsken.tv.core.device.DeviceProfile
import se.norrsken.tv.core.device.LocalDeviceProfile
import se.norrsken.tv.core.device.detectDeviceProfile
import se.norrsken.tv.data.settings.AppPreferences

/**
 * Enda temat i appen. TV och mobil delar palett men far olika matt,
 * typografi och rorelsebudget.
 *
 * [preferences] gor utseendeinstallningarna verkliga: textstorlek,
 * accentfarg och genomskinlighet laser hit och slar igenom direkt, utan
 * omstart, eftersom hela tradet komponeras om nar varderna andras.
 */
@Composable
fun NorrskenTheme(
    preferences: AppPreferences = AppPreferences(),
    profile: DeviceProfile? = null,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val resolved = profile ?: remember(context) { detectDeviceProfile(context) }

    val palette = remember(preferences.accent, preferences.uiOpacity) {
        val base = Palette(signal = Color(preferences.accent.rgb))
        if (preferences.uiOpacity >= 100) {
            base
        } else {
            // Genomskinligheten galler ytorna, aldrig texten. Halvgenomskinlig
            // text over en backdrop blir olaslig, vilket ar ett annat problem
            // an "lite luftigare".
            val alpha = preferences.uiOpacity / 100f
            base.copy(
                surface = base.surface.copy(alpha = alpha),
                surfaceRaised = base.surfaceRaised.copy(alpha = alpha),
                ambient = base.ambient.copy(alpha = alpha),
            )
        }
    }

    val metrics = if (resolved.isTv) Metrics.Tv else Metrics.Handheld
    val motion = remember(reducedMotion) { Motion(reduced = reducedMotion) }

    val typography = remember(resolved.isTv, preferences.textScale) {
        if (resolved.isTv) {
            tvTypography(preferences.textScale.factor)
        } else {
            handheldTypography(preferences.textScale.factor)
        }
    }

    val colorScheme = remember(palette) {
        darkColorScheme(
            primary = palette.signal,
            onPrimary = palette.background,
            secondary = palette.focus,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceRaised,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.hairline,
            error = palette.alert,
        )
    }

    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalMetrics provides metrics,
        LocalMotion provides motion,
        LocalDeviceProfile provides resolved,
        LocalPreferences provides preferences,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}

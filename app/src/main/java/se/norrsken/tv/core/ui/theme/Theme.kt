package se.norrsken.tv.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import se.norrsken.tv.core.device.DeviceProfile
import se.norrsken.tv.core.device.LocalDeviceProfile
import se.norrsken.tv.core.device.detectDeviceProfile

/**
 * Enda temat i appen. TV och mobil delar palett men får olika mått,
 * typografi och rörelsebudget.
 *
 * Material 3 ligger kvar under så färdiga komponenter (Slider, Switch,
 * TextField) ser rätt ut utan att behöva byggas om. Allt eget läser
 * [LocalPalette], [LocalMetrics] och [LocalMotion] i stället.
 */
@Composable
fun NorrskenTheme(
    profile: DeviceProfile? = null,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val resolved = profile ?: remember(context) { detectDeviceProfile(context) }
    val palette = remember { Palette() }
    val metrics = if (resolved.isTv) Metrics.Tv else Metrics.Handheld
    val motion = remember(reducedMotion) { Motion(reduced = reducedMotion) }

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
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = if (resolved.isTv) TvTypography else HandheldTypography,
            content = content,
        )
    }
}

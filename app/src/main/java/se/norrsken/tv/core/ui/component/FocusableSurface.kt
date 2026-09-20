package se.norrsken.tv.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalMotion
import se.norrsken.tv.core.ui.theme.LocalPalette

/**
 * Basen för allt klickbart i appen. Finns för att fokus aldrig får bli
 * osynligt: varje kort, rad och knapp går genom den här, så en D-pad-användare
 * alltid ser var den står.
 *
 * Markeringen är ljusstyrka plus en aning skala. Ingen kulörändring — den
 * försvinner mot en ljus backdrop.
 */
@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusScale: Float = LocalMetrics.current.focusScaleWide,
    cornerRadius: Dp = LocalMetrics.current.radiusCard,
    background: Boolean = true,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val motion = LocalMotion.current

    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(cornerRadius)

    val scale by animateFloatAsState(
        targetValue = if (focused) focusScale else 1f,
        animationSpec = tween(motion.scale(motion.focusMs)),
        label = "focusScale",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .then(
                if (background) Modifier.background(palette.surface, shape) else Modifier
            )
            // Bara fokusringen ritas. En hairline i vilolage ger varje
            // poster och varje menypost en ram, vilket gor rutnatet rutigt
            // och drar blicken dit ingenting hander.
            .border(
                width = if (focused) metrics.focusRingWidth else 0.dp,
                color = if (focused) palette.focus else Color.Transparent,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        content(focused)
    }
}

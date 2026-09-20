package se.norrsken.tv.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * En typsnittsfamilj, tre roller: display för titlar, body för text,
 * label för metadata. Fler vikter än så gör att en TV-skärm ser rörig ut.
 *
 * Systemfamiljen används tills du lägger in en egen. När du gör det: byt bara
 * [appFontFamily] här, resten av skalan följer med.
 *
 * TV-skalan är inte mobilskalan uppskalad. Titlar sitter tätare (negativ
 * tracking) eftersom stora grader annars ser glesa ut, och brödtext sitter
 * glesare eftersom den läses på avstånd.
 */
private val appFontFamily = FontFamily.Default

private fun scale(
    factor: Float = 1f,
    display: Int,
    headline: Int,
    title: Int,
    body: Int,
    label: Int,
    displayTracking: Float,
    bodyTracking: Float,
) = Typography(
    displayLarge = TextStyle(
        fontFamily = appFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = (display * factor).sp,
        lineHeight = (display * factor * 1.15f).sp,
        letterSpacing = displayTracking.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = appFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = (headline * factor).sp,
        lineHeight = (headline * factor * 1.25f).sp,
        letterSpacing = (displayTracking / 2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = appFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = (title * factor).sp,
        lineHeight = (title * factor * 1.3f).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = appFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = (body * factor).sp,
        lineHeight = (body * factor * 1.5f).sp,
        letterSpacing = bodyTracking.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = appFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = (label * factor).sp,
        lineHeight = (label * factor * 1.4f).sp,
        letterSpacing = 0.2.sp,
    ),
)

fun tvTypography(factor: Float): Typography = scale(
    factor = factor,
    display = 40,
    headline = 28,
    title = 20,
    body = 16,
    label = 13,
    displayTracking = -0.6f,
    bodyTracking = 0.1f,
)

fun handheldTypography(factor: Float): Typography = scale(
    factor = factor,
    display = 30,
    headline = 22,
    title = 17,
    body = 15,
    label = 12,
    displayTracking = -0.4f,
    bodyTracking = 0.0f,
)

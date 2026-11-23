package se.norrsken.tv.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Ikonerna, som vektorbanor. Material-ikonernas former (Apache 2.0), inritade
 * här i stället för att dra in hela ikonbiblioteket för tjugo symboler.
 */
object Glyphs {
    val Live = glyph("M21 3H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 5c0-1.1-.9-2-2-2zm0 14H3V5h18v12z")
    val Movies = glyph("M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z")
    val Series = glyph("M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8 12.5v-9l6 4.5-6 4.5z")
    val Settings = glyph("M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z")
    val Search = glyph("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z")
    val Play = glyph("M8 5v14l11-7z")
    val Pause = glyph("M6 19h4V5H6v14zm8-14v14h4V5h-4z")
    val Back = glyph("M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z")
    val ChevronDown = glyph("M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z")
    val OpenInFull = glyph("M21 11V3h-8l3.29 3.29-10 10L3 13v8h8l-3.29-3.29 10-10z")
    /** Fotboll: en boll. */
    val Football = glyph("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 3.3l1.35-.95c1.82.56 3.37 1.76 4.38 3.34l-.39 1.34-1.35.46L13 6.7V5.3zm-3.35-.95L11 5.3v1.4L7.01 9.49l-1.35-.46-.39-1.34c1.01-1.57 2.56-2.77 4.38-3.34zM7.08 17.11l-1.14.1C4.73 15.81 4 13.99 4 12c0-.12.01-.23.02-.35l1-.73 1.38.48 1.46 4.34-.78 1.37zm7.42 2.48c-.79.26-1.63.41-2.5.41s-1.71-.15-2.5-.41l-.69-1.49.64-1.1h5.11l.64 1.11-.7 1.48zM14.27 15H9.73l-1.35-4.02L12 8.44l3.63 2.54L14.27 15zm3.79 2.21l-1.14-.1-.79-1.37 1.46-4.34 1.39-.47 1 .73c.01.11.02.22.02.34 0 1.99-.73 3.81-1.94 5.21z")
    val Genres = glyph("M12 2l-5.5 9h11L12 2zm5.5 11c-2.49 0-4.5 2.01-4.5 4.5s2.01 4.5 4.5 4.5 4.5-2.01 4.5-4.5-2.01-4.5-4.5-4.5zM3 13.5h8v8H3z")
    val History = glyph("M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z")
    val Close = glyph("M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z")
    val Fullscreen = glyph("M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z")
    val FullscreenExit = glyph("M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z")
    val Volume = glyph("M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z")
    val VolumeOff = glyph("M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z")
    val SkipNext = glyph("M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z")
    val SkipPrevious = glyph("M6 6h2v12H6zm3.5 6l8.5 6V6z")
    val Forward10 = glyph("M18 13c0 3.31-2.69 6-6 6s-6-2.69-6-6 2.69-6 6-6v4l5-5-5-5v4c-4.42 0-8 3.58-8 8s3.58 8 8 8 8-3.58 8-8h-2z")
    val Replay10 = glyph("M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z")
    val Add = glyph("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val Refresh = glyph("M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z")
    val Delete = glyph("M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z")
    val Subtitles = glyph("M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM4 12h4v2H4v-2zm10 6H4v-2h10v2zm6 0h-4v-2h4v2zm0-4H10v-2h10v2z")
    val Check = glyph("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z")
    val Info = glyph("M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z")
    val Guide = glyph("M17 10H7v2h10v-2zm2-7h-1V1h-2v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V8h14v11zm-5-5H7v2h7v-2z")
    val Star = glyph("M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z")
    val StarOutline = glyph("M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z")
    val Home = glyph("M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z")
    /** Samlingar: en bunt kort. */
    val Stack = glyph("M14 2H4c-1.1 0-2 .9-2 2v10h2V4h10V2zm4 4H8c-1.1 0-2 .9-2 2v10h2V8h10V6zm2 4h-8c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2v-8c0-1.1-.9-2-2-2z")

    /**
     * Appens märke i menyn: samma tv med antenner och play-knapp, men platt
     * och i en färg (den färg den tonas i), utan färgövergångar och sken.
     * Play-knappen är ett hål i rutan.
     */
    val AppSymbol: ImageVector = ImageVector.Builder(
        name = "TvSymbol",
        defaultWidth = 48.dp,
        defaultHeight = 48.dp,
        viewportWidth = 48f,
        viewportHeight = 48f,
    )
        .addPath(
            pathData = addPathNodes("M24,12.5 L15.5,4.5 M24,12.5 L32.5,4.5"),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 3.4f,
            strokeLineCap = StrokeCap.Round,
        )
        .addPath(
            pathData = addPathNodes(
                "M11,12 H37 A8,8 0 0 1 45,20 V34 A8,8 0 0 1 37,42 H11 A8,8 0 0 1 3,34 V20 A8,8 0 0 1 11,12 Z " +
                    "M20,19.5 V34.5 L32.5,27 Z",
            ),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.White),
        )
        .build()

    /**
     * Appens märke: en tv med antenner och en play-knapp, i norrskenets
     * färger — från grönt över blått till violett, som appens första namn.
     * Samma form som Shield-ikonen och bannern (shield/src/main/res/drawable)
     * och Windows-ikonen.
     */
    val AppMark: ImageVector = ImageVector.Builder(
        name = "Tv",
        defaultWidth = 96.dp,
        defaultHeight = 96.dp,
        viewportWidth = 96f,
        viewportHeight = 96f,
    )
        // Antennerna, med en kula i toppen.
        .addPath(
            pathData = addPathNodes("M48,26 L34,10 M48,26 L62,10"),
            stroke = Brush.linearGradient(listOf(Brand.teal, Brand.sky), start = Offset(30f, 0f), end = Offset(66f, 0f)),
            strokeLineWidth = 5.5f,
            strokeLineCap = StrokeCap.Round,
        )
        .addPath(pathData = addPathNodes("M34,5.6 a4.4,4.4 0 1 0 0.01,0 z"), fill = SolidColor(Brand.teal))
        .addPath(pathData = addPathNodes("M62,5.6 a4.4,4.4 0 1 0 0.01,0 z"), fill = SolidColor(Brand.sky))
        // Rutan.
        .addPath(
            pathData = addPathNodes(SCREEN),
            fill = Brush.linearGradient(
                0f to Brand.teal,
                0.42f to Brand.blue,
                0.78f to Brand.violet,
                1f to Brand.magenta,
                start = Offset(8f, 22f),
                end = Offset(88f, 88f),
            ),
        )
        // Ett norrskensband och ljus uppifrån, inne i rutan.
        .addGroup(clipPathData = addPathNodes(SCREEN))
        .addPath(
            pathData = addPathNodes("M6,62 C22,50 36,70 54,58 C68,49 80,52 90,46 V60 C78,66 66,62 54,72 C36,86 22,66 6,76 Z"),
            fill = Brush.linearGradient(
                0f to Color.White.copy(alpha = 0f),
                0.35f to Color.White.copy(alpha = 0.22f),
                1f to Color.White.copy(alpha = 0.04f),
                start = Offset(6f, 0f),
                end = Offset(90f, 0f),
            ),
        )
        .addPath(
            pathData = addPathNodes("M6,22 H90 V50 H6 Z"),
            fill = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0f)), startY = 22f, endY = 50f),
        )
        .clearGroup()
        // Play-knappen, med en mjuk skugga.
        .addPath(pathData = addPathNodes(PLAY_SHADOW), fill = SolidColor(Color(0x4D14093A)))
        .addPath(pathData = addPathNodes(PLAY), fill = SolidColor(Color.White))
        .build()
}

/** Märkets färger: norrsken från grönt till violett. Används också i menyn. */
internal object Brand {
    val teal = Color(0xFF2EE6C5)
    val sky = Color(0xFF5B8CFF)
    val blue = Color(0xFF3D7BFF)
    val violet = Color(0xFF7B4DFF)
    val magenta = Color(0xFFC13DF0)

    /** Strecket vid fliken man är i, uppifrån och ned. */
    val stripe: Brush = Brush.verticalGradient(listOf(teal, blue, violet))
}

private const val SCREEN = "M26,22 H70 A20,20 0 0 1 90,42 V68 A20,20 0 0 1 70,88 H26 A20,20 0 0 1 6,68 V42 A20,20 0 0 1 26,22 Z"
private const val PLAY = "M40,44 L40,66 Q40,70 43.46,68 L62.54,57 Q66,55 62.54,53 L43.46,42 Q40,40 40,44 Z"
private const val PLAY_SHADOW = "M40,45.6 L40,67.6 Q40,71.6 43.46,69.6 L62.54,58.6 Q66,56.6 62.54,54.6 L43.46,43.6 Q40,41.6 40,45.6 Z"

private fun glyph(pathData: String): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.White))
        .build()

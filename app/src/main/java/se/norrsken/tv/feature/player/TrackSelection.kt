package se.norrsken.tv.feature.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/**
 * Ett valbart ljud- eller textspar.
 *
 * [group] ar null for "Av" — det enda alternativet som inte ar ett spar
 * utan en frånvaro av spar.
 */
data class TrackOption(
    val label: String,
    val group: Tracks.Group?,
    val index: Int,
    val selected: Boolean,
)

private val SWEDISH: Locale = Locale.forLanguageTag("sv")

fun audioOptions(tracks: Tracks): List<TrackOption> =
    optionsOfType(tracks, C.TRACK_TYPE_AUDIO)

/**
 * Undertexter borjar alltid med "Av". Ljud har inget sadant val — att stanga
 * av ljudet ar volymknappens jobb, inte sparvalets.
 */
fun subtitleOptions(tracks: Tracks): List<TrackOption> {
    val real = optionsOfType(tracks, C.TRACK_TYPE_TEXT)
    val noneSelected = real.none { it.selected }
    return listOf(TrackOption("Av", null, -1, noneSelected)) + real
}

private fun optionsOfType(tracks: Tracks, type: Int): List<TrackOption> {
    val result = mutableListOf<TrackOption>()
    for (group in tracks.groups) {
        if (group.type != type) continue
        for (index in 0 until group.length) {
            // Spar enheten inte kan avkoda visas inte. Att erbjuda ett val som
            // ger tystnad eller svart bild ar en knapp utan funktion.
            if (!group.isTrackSupported(index)) continue
            result += TrackOption(
                label = describe(group.getTrackFormat(index), result.size),
                group = group,
                index = index,
                selected = group.isTrackSelected(index),
            )
        }
    }
    return result
}

@OptIn(UnstableApi::class)
fun selectTrack(player: Player, type: Int, option: TrackOption) {
    val builder = player.trackSelectionParameters.buildUpon()
    val group = option.group
    if (group == null) {
        builder.setTrackTypeDisabled(type, true)
    } else {
        builder
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.index))
    }
    player.trackSelectionParameters = builder.build()
}

/**
 * "Svenska · 5.1 · Dolby Digital+" i stallet for "Spar 2". Leverantorer
 * satter sallan namn pa sparen, men sprak, kanaler och format finns nastan
 * alltid i sjalva strommen.
 */
private fun describe(format: Format, position: Int): String {
    val language = format.language
        ?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { tag ->
            Locale.forLanguageTag(tag).getDisplayLanguage(SWEDISH)
                .takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) }
                ?.replaceFirstChar { it.uppercase(SWEDISH) }
                ?: tag.uppercase(SWEDISH)
        }

    val name = format.label?.takeIf { it.isNotBlank() }

    val channels = when (format.channelCount) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }

    val forced = if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) "Tvingad" else null

    val parts = listOfNotNull(
        name ?: language,
        channels,
        codecName(format.sampleMimeType),
        forced,
    )
    return parts.joinToString(" · ").ifEmpty { "Spår ${position + 1}" }
}

private fun codecName(mime: String?): String? = when (mime) {
    "audio/ac3" -> "Dolby Digital"
    "audio/eac3", "audio/eac3-joc" -> "Dolby Digital+"
    "audio/true-hd" -> "Dolby TrueHD"
    "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
    "audio/mp4a-latm" -> "AAC"
    "audio/opus" -> "Opus"
    "audio/mpeg" -> "MP3"
    "application/x-subrip" -> "SRT"
    "text/vtt" -> "WebVTT"
    "text/x-ssa" -> "SSA"
    else -> null
}

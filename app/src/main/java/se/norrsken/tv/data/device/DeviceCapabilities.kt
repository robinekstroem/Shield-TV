package se.norrsken.tv.data.device

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.view.Display
import kotlin.math.roundToInt

/**
 * Vad skärmen faktiskt klarar, som Android rapporterar det.
 *
 * Det här är information, inte inställningar. HDR och upplösning styrs av
 * enheten och TV:n — en app kan inte slå på HDR. Men att se att Shielden
 * skickar 1080p till en 4K-TV, eller att TV:n inte rapporterar Dolby
 * Vision, är exakt det man behöver veta för att optimera sin uppsättning.
 */
data class DisplayInfo(
    val width: Int,
    val height: Int,
    val refreshRate: Int,
    val hdrFormats: List<String>,
)

data class AudioOutputInfo(
    /** Format som HDMI-kedjan uppger att den tar emot okodat. */
    val passthroughFormats: List<String>,
    val hasHdmi: Boolean,
)

object DeviceCapabilities {

    fun display(context: Context): DisplayInfo? {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return null
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val mode = display.mode

        @Suppress("DEPRECATION")
        val hdrTypes = display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()

        return DisplayInfo(
            width = mode.physicalWidth,
            height = mode.physicalHeight,
            refreshRate = mode.refreshRate.roundToInt(),
            // toList() först: mapNotNull finns inte för IntArray. Primitiva
            // arrayer kan inte innehålla null, så Kotlin definierar den inte
            // där.
            hdrFormats = hdrTypes.toList().mapNotNull(::hdrName).distinct(),
        )
    }

    fun audio(context: Context): AudioOutputInfo {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return AudioOutputInfo(emptyList(), hasHdmi = false)

        val hdmi = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_HDMI || it.type == AudioDeviceInfo.TYPE_HDMI_ARC }

        val formats = hdmi
            .flatMap { it.encodings.toList() }
            .mapNotNull(::encodingName)
            .distinct()

        return AudioOutputInfo(passthroughFormats = formats, hasHdmi = hdmi.isNotEmpty())
    }

    private fun hdrName(type: Int): String? = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> null
    }

    private fun encodingName(encoding: Int): String? = when (encoding) {
        AudioFormat.ENCODING_AC3 -> "Dolby Digital"
        AudioFormat.ENCODING_E_AC3 -> "Dolby Digital+"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
        ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
        else -> null
    }

    // Konstanterna finns först i nyare API-nivåer. Värdena är fasta i
    // Android och används här för att slippa versionsvillkor.
    private const val HDR_TYPE_HDR10_PLUS = 4
    private const val ENCODING_DOLBY_TRUEHD = 14
}

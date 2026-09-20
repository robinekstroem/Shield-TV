package se.norrsken.tv.data.settings

import se.norrsken.tv.data.remote.PlaylistDownloader

/**
 * Val som faktiskt styr nagot i appen.
 *
 * Regeln for den har filen: ett falt far bara finnas har om det finns kod
 * som laser det. Ett varde som ingen anvander ar en dummy-knapp med extra
 * steg — den ser ut som en funktion i menyn och gor ingenting.
 */
data class AppPreferences(
    // --- Uppspelning ---
    val userAgent: String = PlaylistDownloader.DEFAULT_USER_AGENT,
    val buffer: BufferProfile = BufferProfile.AUTO,
    val decoder: DecoderMode = DecoderMode.AUTO,
    val matchFrameRate: Boolean = true,
    val preferSurround: Boolean = true,

    // --- Spelarens overlay ---
    val infoPanelSeconds: Int = 4,
    val showClock: Boolean = true,

    // --- Allmant ---
    val confirmExit: Boolean = true,

    // --- Utseende ---
    val textScale: TextScale = TextScale.MEDIUM,
    val accent: AccentChoice = AccentChoice.BLUE,
    val uiOpacity: Int = 100,

    // --- Metadata ---
    val tmdbKey: String? = null,
    val metadataLanguage: String = "sv-SE",
)

/**
 * Buffertstrategier i millisekunder: min, max, start, start efter avbrott.
 *
 * AUTO later ExoPlayer valja. Stor buffert ger stabilare bild men langsammare
 * kanalbyte — det ar avvagningen, inte "battre" eller "samre".
 */
enum class BufferProfile(
    val label: String,
    val minMs: Int,
    val maxMs: Int,
    val playbackMs: Int,
    val rebufferMs: Int,
) {
    AUTO("Automatisk", 0, 0, 0, 0),
    SMALL("Liten", 5_000, 15_000, 1_000, 2_000),
    NORMAL("Normal", 15_000, 50_000, 2_500, 5_000),
    LARGE("Stor", 30_000, 120_000, 5_000, 10_000),
    ;

    val isAutomatic: Boolean get() = this == AUTO
}

/**
 * Ren mjukvaruavkodning kraver FFmpeg-tillagget, som inte ligger i appen —
 * darfor finns inget "Mjukvara"-val. Det hade varit en knapp som inte gor
 * nagot. AUTO anvander hardvara med fallback till enhetens egna
 * mjukvaruavkodare nar hardvaran sager nej.
 */
enum class DecoderMode(val label: String, val allowFallback: Boolean) {
    AUTO("Automatisk", true),
    HARDWARE_ONLY("Endast hardvara", false),
}

enum class TextScale(val label: String, val factor: Float) {
    SMALL("Liten", 0.88f),
    MEDIUM("Mellan", 1.0f),
    LARGE("Stor", 1.14f),
    HUGE("Mycket stor", 1.3f),
}

enum class AccentChoice(val label: String, val rgb: Long) {
    BLUE("Isbla", 0xFF4C8DFF),
    TEAL("Glaciar", 0xFF5FC9B8),
    AMBER("Rav", 0xFFE0A34A),
    VIOLET("Norrsken", 0xFF9B7CFF),
}

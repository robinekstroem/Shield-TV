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

    // --- Sortering ---
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val channelSort: ChannelSort = ChannelSort.PROVIDER,
    val categorySort: CategorySort = CategorySort.PROVIDER,

    // --- Utseende ---
    val theme: ThemeChoice = ThemeChoice.NORRSKEN,
    val textScale: TextScale = TextScale.MEDIUM,
    val accent: AccentChoice = AccentChoice.THEME,
    val uiOpacity: Int = 100,

    // --- Bild och undertext ---
    val videoScale: VideoScale = VideoScale.FIT,
    val subtitleSize: SubtitleSize = SubtitleSize.NORMAL,
    val subtitleBackground: SubtitleBackground = SubtitleBackground.SHADOW,

    // --- Dolda kategorier ---
    /**
     * Enskilt dolda kategorier, nycklade pa kalla + typ + namn. Namn i
     * stallet for databas-id: id:t byts vid varje uppdatering av
     * spellistan, namnet gor det inte. Dolt forblir alltsa dolt.
     */
    val hiddenCategories: Set<String> = emptySet(),
    /** Ord som doljer varje kategori vars namn innehaller dem. */
    val hiddenKeywords: List<String> = emptyList(),

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
    HARDWARE_ONLY("Endast hårdvara", false),
}

enum class TextScale(val label: String, val factor: Float) {
    SMALL("Liten", 0.88f),
    MEDIUM("Mellan", 1.0f),
    LARGE("Stor", 1.14f),
    HUGE("Mycket stor", 1.3f),
}

/**
 * Sortering av filmer och serier.
 *
 * [code] ar vardet som skickas till SQL. Ordningen far darfor aldrig kastas
 * om utan att fragorna andras — enum-namnen lagras, men koderna styr.
 */
enum class LibrarySort(val label: String, val code: Int) {
    RECENT("Senast tillagda", 0),
    TITLE("A–Ö", 1),
    RATING("Betyg", 2),
    /**
     * TMDB:s globala popularitet. Betyder "populart i varlden", inte
     * "populart hos dig" — appen har ingen tittarhistorik an. Titlar som
     * inte matchats saknar vardet och hamnar sist.
     */
    POPULAR("Populära på TMDB", 3),
}

enum class ChannelSort(val label: String, val code: Int) {
    PROVIDER("Leverantörens ordning", 0),
    NAME("A–Ö", 1),
}

enum class CategorySort(val label: String, val code: Int) {
    PROVIDER("Leverantörens ordning", 0),
    NAME("A–Ö", 1),
}

enum class ThemeChoice(val label: String) {
    NORRSKEN("Norrsken"),
    TITAN("Titan"),
    MIDNIGHT("Midnatt"),
}

/**
 * Hur bilden fyller skarmen. Ingen "Stracka": den forvranger bilden, och
 * det finns ingen situation dar ett tillplattat ansikte ar ratt svar.
 */
enum class VideoScale(val label: String) {
    FIT("Anpassa"),
    ZOOM("Zooma"),
}

enum class SubtitleSize(val label: String, val factor: Float) {
    SMALL("Liten", 0.8f),
    NORMAL("Normal", 1.0f),
    LARGE("Stor", 1.25f),
    HUGE("Mycket stor", 1.5f),
}

enum class SubtitleBackground(val label: String) {
    NONE("Kontur"),
    SHADOW("Skugga"),
    BOX("Ruta"),
}

/** [rgb] 0 betyder "anvand temats egen accent". */
enum class AccentChoice(val label: String, val rgb: Long) {
    THEME("Temats egen", 0L),
    BLUE("Isblå", 0xFF4C8DFF),
    TEAL("Glaciär", 0xFF5FC9B8),
    AMBER("Bärnsten", 0xFFE0A34A),
    VIOLET("Norrsken", 0xFF9B7CFF),
}

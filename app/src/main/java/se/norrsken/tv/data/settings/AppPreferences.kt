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
    val interfaceStyle: InterfaceStyle = InterfaceStyle.STANDARD,
    val textScale: TextScale = TextScale.MEDIUM,
    val accent: AccentChoice = AccentChoice.THEME,
    val uiOpacity: Int = 100,

    // --- Bild ---
    val videoScale: VideoScale = VideoScale.FIT,
    val videoQuality: VideoQuality = VideoQuality.AUTO,
    val tunneling: Boolean = false,

    // --- Ljud ---
    val audioLanguage: MediaLanguage = MediaLanguage.SWEDISH,
    val volumeBoost: VolumeBoost = VolumeBoost.OFF,

    // --- Undertext ---
    val subtitleSize: SubtitleSize = SubtitleSize.NORMAL,
    val subtitleBackground: SubtitleBackground = SubtitleBackground.SHADOW,
    val subtitleLanguage: MediaLanguage = MediaLanguage.SWEDISH,
    /** Av: bara tvingade undertexter visas, t.ex. när någon talar ett tredje språk. */
    val autoSubtitles: Boolean = false,

    // --- Effekter ---
    val effects: EffectLevel = EffectLevel.FULL,

    // --- Tablå ---
    val epgDays: EpgDays = EpgDays.THREE,
    /**
     * Förskjutning i minuter. XMLTV bär sin egen tidszon, så rätt fil
     * behöver ingen — men leverantörer med fel tidszon i filen finns, och
     * då ligger hela tablån en jämn timme fel.
     */
    val epgOffsetMinutes: Int = 0,
    val epgLastUpdateUtc: Long? = null,

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

/**
 * Hur mycket gränssnittet säger.
 *
 * Minimal tar bort text som förklarar det uppenbara — underrubriker i menyn,
 * etiketter ovanför värden, beskrivningar under inställningar — och låter
 * innehållet stå för sig. Inga funktioner försvinner, bara orden runt dem.
 */
enum class InterfaceStyle(val label: String) {
    STANDARD("Standard"),
    MINIMAL("Minimal"),
}

enum class ThemeChoice(val label: String) {
    NORRSKEN("Norrsken"),
    TITAN("Titan"),
    SNOW("Snö"),
    MIDNIGHT("Midnatt"),
}

/**
 * Bakgrundseffekternas nivå. Reducerad halverar antalet partiklar och
 * ritar med 30 bilder i sekunden — för telefoner och svagare boxar.
 */
enum class EffectLevel(val label: String, val particleFactor: Float) {
    FULL("Full", 1f),
    REDUCED("Reducerad", 0.45f),
    OFF("Av", 0f),
}

/**
 * Bildkvalitet för strömmar med flera kvalitetsnivåer (de flesta HLS).
 *
 * Automatisk börjar lågt och klättrar när nätet visat sig klara det — det
 * ger snabb start men suddig bild de första sekunderna. Högsta hoppar
 * direkt till bästa nivån enheten kan avkoda.
 */
enum class VideoQuality(val label: String) {
    AUTO("Automatisk"),
    HIGHEST("Högsta möjliga"),
    DATA_SAVER("Spara data (720p)"),
}

/**
 * Hur långt fram tablån sparas. Fler dagar betyder fler rader i databasen —
 * med tusentals kanaler blir sju dagar miljontals program, vilket en TV-box
 * märker.
 */
enum class EpgDays(val label: String, val days: Int) {
    ONE("1 dag", 1),
    THREE("3 dagar", 3),
    SEVEN("7 dagar", 7),
}

/** [code] är ISO 639-1. null betyder "låt strömmen välja". */
enum class MediaLanguage(val label: String, val code: String?) {
    SWEDISH("Svenska", "sv"),
    ENGLISH("Engelska", "en"),
    NORWEGIAN("Norska", "no"),
    DANISH("Danska", "da"),
    FINNISH("Finska", "fi"),
    ORIGINAL("Originalspråk", null),
}

/**
 * Förstärkning i millibel. Många IPTV-kanaler sänds med låg nivå, och att
 * behöva vrida upp TV:n till max för en kanal och sedan få nästa i ansiktet
 * är ett vanligt klagomål.
 */
enum class VolumeBoost(val label: String, val millibels: Int) {
    OFF("Av", 0),
    LOW("+3 dB", 300),
    MEDIUM("+6 dB", 600),
    HIGH("+9 dB", 900),
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

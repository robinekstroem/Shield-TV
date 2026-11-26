package se.norrsken.tv.desktop

import se.norrsken.tv.desktop.data.VersionIndex
import se.norrsken.tv.desktop.data.CollectionIndex
import se.norrsken.tv.desktop.data.DisneyIndex
import se.norrsken.tv.desktop.data.Franchise
import se.norrsken.tv.desktop.data.FranchiseFilm
import se.norrsken.tv.desktop.data.DisneyShelf
import se.norrsken.tv.desktop.data.LibraryFilter
import se.norrsken.tv.desktop.data.SeriesMark
import se.norrsken.tv.desktop.data.SeriesMarksStore
import se.norrsken.tv.desktop.data.TmdbExtrasStore
import se.norrsken.tv.desktop.data.TrendingTitle
import se.norrsken.tv.desktop.data.VersionTags
import se.norrsken.tv.desktop.data.FootballClient
import se.norrsken.tv.desktop.data.FootballMatch
import se.norrsken.tv.desktop.data.League
import se.norrsken.tv.desktop.data.Lineup
import se.norrsken.tv.desktop.data.MatchDetail
import se.norrsken.tv.desktop.data.MatchState
import se.norrsken.tv.desktop.data.TableRow
import se.norrsken.tv.desktop.data.findBroadcasts
import se.norrsken.tv.desktop.data.isMalmoFF
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import se.norrsken.tv.desktop.data.MyListStore
import se.norrsken.tv.desktop.data.ListKind
import se.norrsken.tv.desktop.data.ListEntry
import se.norrsken.tv.desktop.ui.Images
import se.norrsken.tv.desktop.data.AppDirs
import java.io.File
import se.norrsken.tv.desktop.data.weightedScore
import se.norrsken.tv.desktop.data.TitleCleaner
import se.norrsken.tv.desktop.data.TmdbBusyException
import se.norrsken.tv.desktop.data.TmdbKeyException
import se.norrsken.tv.desktop.data.TmdbClient
import se.norrsken.tv.desktop.data.TmdbStore
import se.norrsken.tv.desktop.data.TmdbFacts
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import se.norrsken.tv.desktop.data.MovieFactsStore
import se.norrsken.tv.desktop.data.MovieFacts
import se.norrsken.tv.desktop.data.HistoryStore
import se.norrsken.tv.desktop.data.WatchKind
import se.norrsken.tv.desktop.data.WatchEntry
import se.norrsken.tv.desktop.ui.AppTheme
import se.norrsken.tv.desktop.ui.WallStyle
import kotlinx.coroutines.delay
import se.norrsken.tv.desktop.data.GuideIndex
import se.norrsken.tv.desktop.data.GuideStore
import se.norrsken.tv.desktop.data.SourceGuide
import se.norrsken.tv.desktop.data.XmltvParser
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.pow
import se.norrsken.tv.desktop.ui.tvLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import se.norrsken.tv.data.playlist.ImportState
import se.norrsken.tv.data.playlist.XtreamCredentials
import se.norrsken.tv.data.playlist.extractXtreamCredentials
import se.norrsken.tv.data.playlist.normalizeBaseUrl
import se.norrsken.tv.data.remote.PlaylistDownloader
import se.norrsken.tv.data.remote.PlaylistException
import se.norrsken.tv.data.security.sanitizeUrl
import se.norrsken.tv.data.xtream.XtreamClient
import se.norrsken.tv.data.xtream.XtreamEpisode
import se.norrsken.tv.data.xtream.XtreamVodInfo
import se.norrsken.tv.desktop.data.Importer
import se.norrsken.tv.desktop.data.Library
import se.norrsken.tv.desktop.data.PersonalKeys
import se.norrsken.tv.desktop.data.IntroReelItem
import se.norrsken.tv.desktop.data.IntroReelStore
import se.norrsken.tv.desktop.data.LiveChannel
import se.norrsken.tv.desktop.data.Movie
import se.norrsken.tv.desktop.data.SecretBox
import se.norrsken.tv.desktop.data.Series
import se.norrsken.tv.desktop.data.Settings
import se.norrsken.tv.desktop.data.Source
import se.norrsken.tv.desktop.data.SourceContent
import se.norrsken.tv.desktop.data.SourceType
import se.norrsken.tv.desktop.data.Storage
import se.norrsken.tv.desktop.platform.WindowsIntegration
import se.norrsken.tv.desktop.player.VideoPlayer
import se.norrsken.tv.desktop.player.VlcRuntime
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.text.Collator
import java.util.UUID
import javax.net.ssl.SSLException

enum class Screen { HOME, LIVE, GUIDE, FOOTBALL, MOVIES, SERIES, GENRES, HISTORY, SETTINGS }

enum class SortOrder(val label: String) {
    PROVIDER("Standard"),
    POPULAR("Populärast"),
    ADDED("Senast tillagda"),
    RATING("Högst betyg"),
    YEAR("Nyast"),
    TITLE("A–Ö"),
}

private const val HOUR = 3_600_000L
/** Så länge de tunga bakgrundsjobben som längst väntar på att startsidan ska bli klar. */
private const val STARTUP_GRACE_MS = 12_000L
/** Kommer appen fram igen efter så här länge hämtas kanalerna och tablån om. */
private const val CHANNELS_FRESH_MS = 20 * 60_000L
/** Står appen framme hela tiden hämtas kanalerna om så här ofta. */
private const val CHANNELS_STALE_MS = 3 * HOUR
/** Bilderna bredvid kategorierna på tv:n: så många omslag per kategori, och så många titlar som mest gås igenom. */
private const val COVERS_PER_GROUP = 3
private const val COVER_SCAN_LIMIT = 8_000
private val SWEDISH: Locale = Locale.forLanguageTag("sv-SE")
private val GUIDE_STAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM 'kl.' HH:mm", SWEDISH).withZone(ZoneId.systemDefault())

sealed interface EpisodesState {
    data object Loading : EpisodesState
    data class Loaded(val episodes: List<XtreamEpisode>) : EpisodesState
    data class Failed(val message: String) : EpisodesState
}

sealed interface MovieInfoState {
    data object Loading : MovieInfoState
    /** [info] är null när leverantören inte har något om filmen. */
    data class Loaded(val info: XtreamVodInfo?) : MovieInfoState
    data class Failed(val message: String) : MovieInfoState
}

data class NowPlaying(
    val title: String,
    val subtitle: String?,
    val live: Boolean,
    /** Satt för livekanaler: var i listan vi är, för kanalbyte. */
    val channel: LiveChannel? = null,
    val queue: List<LiveChannel> = emptyList(),
    /** Posten i historiken; bär också det som behövs för att fortsätta. */
    val entry: WatchEntry? = null,
    /** Var uppspelningen börjar: där man slutade förra gången. */
    val startMs: Long = 0L,
    /** Namnet som leverantören skrev det, med märkningar som "4K HDR". */
    val name: String? = null,
)

/**
 * Allt appen vet och gör, samlat. Motsvarar ViewModels och repositories
 * på Android, men utan Hilt: skrivbordsappen är liten nog att kopplas ihop
 * för hand.
 *
 * Tillståndet ändras bara på UI-tråden. Nätverk och disk går via
 * Dispatchers.IO och resultatet skrivs tillbaka här.
 */
class AppState {
    // Ett fel i en bakgrundsuppgift ska aldrig stänga appen. På Android gör ett
    // ofångat fel det, på Windows inte; här blir det ett meddelande i stället.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, error ->
            error.printStackTrace()
            notice = "Något gick fel: ${error.message ?: error.javaClass.simpleName}"
        },
    )
    private val storage = Storage()
    private val downloader = PlaylistDownloader()
    private val xtream = XtreamClient(downloader)
    private val importer = Importer(downloader, xtream)

    /** Samma User-Agent som Android-appens standard. Många paneler svarar 403 annars. */
    val userAgent: String = PlaylistDownloader.DEFAULT_USER_AGENT

    private val contents = mutableStateMapOf<String, SourceContent>()

    /** Källor utan sparat bibliotek, som hämtas en i taget efter start. */
    private val refreshQueue = ArrayDeque<String>()

    /** Hemligheter som inte gick att skydda med DPAPI. Lever bara tills appen stängs. */
    private val sessionSecrets = HashMap<String, String>()

    // --- Navigering och bibliotek -------------------------------------------

    var screen by mutableStateOf(Screen.HOME)
    var loading by mutableStateOf(true)
        private set
    var sources by mutableStateOf<List<Source>>(emptyList())
        private set
    var library by mutableStateOf(Library())
        private set

    var liveGroup by mutableStateOf<String?>(null)
    var liveQuery by mutableStateOf("")
    var movieGroup by mutableStateOf<String?>(null)

    /**
     * Inställningar: kategorisidan för live-tv, filmer eller serier, om den är
     * öppen. Här och inte i skärmen, så att Tillbaka på tv:n kan stänga den.
     */
    var managingCategories by mutableStateOf<Char?>(null)

    // --- Disney under Filmer ----------------------------------------------------------

    /** Vilka filmer och serier som hör till Disneys samlingar. */
    /** Filmserierna man har något ur: Marvel, Star Wars och de andra (se Collections.kt). */
    var collections by mutableStateOf(CollectionIndex.EMPTY)
        private set
    /** Delen av samlingen som visas, som Marvels faser. Null: hela samlingen. */
    var collectionPart by mutableStateOf<Int?>(null)
    /** Berättelsens ordning i stället för utgivningens, där samlingen har en. */
    var collectionStory by mutableStateOf(false)
    var disney by mutableStateOf(DisneyIndex.EMPTY)
        private set
    /** Egna val på filmsidan: titeln och året → samlingen, eller "inte Disney". */
    var disneyChoices by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /** Kortfilmerna: bara en figur, eller alla. */
    var disneyCharacter by mutableStateOf<String?>(null)
    private var disneyJob: Job? = null
    var movieQuery by mutableStateOf("")
    var seriesGroup by mutableStateOf<String?>(null)
    var seriesQuery by mutableStateOf("")

    /** Genrefliken: vald genre (namnet på en Genre, null = topplistan), filmer eller serier, rangordning, sökning. */
    var genre by mutableStateOf<String?>(null)
    var genreShowsSeries by mutableStateOf(false)
    var genreSort by mutableStateOf(SortOrder.RATING)
    var genreQuery by mutableStateOf("")

    // --- Historik -----------------------------------------------------------
    var history by mutableStateOf<List<WatchEntry>>(emptyList())
        private set
    /** Något i historiken som inte längre finns i biblioteket. */
    var historyMessage by mutableStateOf<String?>(null)
        private set
    private val historyStore = HistoryStore()

    /** Min lista: filmer, serier och favoritkanaler. */
    var myList by mutableStateOf<List<ListEntry>>(emptyList())
        private set
    private val myListStore = MyListStore()

    /** Avsnitt vars nästa avsnitt redan är föreslaget, så att det görs en gång. */
    private val nextQueued = HashSet<String>()
    private var historyJob: Job? = null

    // --- Genre och år per film, hämtade i bakgrunden --------------------------
    var movieFacts by mutableStateOf<Map<String, MovieFacts>>(emptyMap())
        private set
    /** Genrehämtningen: hur många filmer som är klara av alla. Null: ingen att hämta. */
    var factsProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    private val factsStore = MovieFactsStore()
    private val factsSaving = Mutex()
    private var factsJob: Job? = null
    private var factsLoaded = false

    // --- TMDB: genrer, betyg med röster, popularitet, svenska beskrivningar ---
    var tmdbFacts by mutableStateOf<Map<String, TmdbFacts>>(emptyMap())
        private set
    /** Hämtningen från TMDB: klara titlar av alla. Null: inget att hämta. */
    var tmdbProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    /** Senaste besked om nyckeln, för inställningarna. */
    var tmdbStatus by mutableStateOf<String?>(null)
        private set
    var tmdbConnected by mutableStateOf(false)
        private set
    private var tmdbKey: String? = null
    private val tmdbStore = TmdbStore()
    private val tmdbSaving = Mutex()
    private var tmdbJob: Job? = null
    private var tmdbLoaded = false
    /** Titel till TMDB-nyckel, per film och serie: titelstädningen körs en gång. */
    // Listorna räknas fram i bakgrunden, så nycklarna kan slås upp från flera trådar.
    private val tmdbKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    // --- Versioner: samma film i flera utföranden (4K, svensk text, andra språk) ---
    /**
     * Dubbletter som en titel med versioner. Byggs i bakgrunden när biblioteket
     * eller TMDB ändras; tills det första är klart visas listorna som de är.
     */
    var versions by mutableStateOf(VersionIndex.EMPTY)
        private set
    private var versionsJob: Job? = null
    private var versionsDirty = false

    // --- Loggor, trender och nytt sedan sist -----------------------------------------
    /** Filmernas och seriernas loggor från TMDB, per "movie:id" eller "tv:id". Tom text: ingen logga. */
    var tmdbLogos by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /** Dagens trendlista från TMDB. */
    var trending by mutableStateOf<List<TrendingTitle>>(emptyList())
        private set
    private var trendingAt = 0L
    private val extrasStore = TmdbExtrasStore()
    private val logoRequests = HashSet<String>()
    private val logoGate = Semaphore(2)
    private var extrasSaveJob: Job? = null
    /** Det som kommit till efter detta är nytt: sedan förra besöket, högst en månad bakåt. */
    var newSinceUtc by mutableStateOf(0L)
        private set
    private var lastVisitUtc = 0L

    // --- Biblioteket: språk och dolda kategorier ---------------------------------------
    /** Dölj det som bara finns på andra språk än svenska, nordiska och engelska. */
    var hideForeign by mutableStateOf(false)
        private set
    /** Dolda kategorier, som LibraryFilter.groupKey. */
    var hiddenGroups by mutableStateOf<Set<String>>(emptySet())
        private set

    // --- Serier: intro, eftertexter och nästa avsnitt -----------------------------------
    /** Avsnittet som spelas efter det här. Null: inget, eller inte klart ännu. */
    var nextUp by mutableStateOf<NextUp?>(null)
        private set
    /** Rutan Nästa avsnitt är bortvald för avsnittet som spelas. */
    var nextUpDismissed by mutableStateOf(false)
        private set
    /** Introt i serien som spelas, om appen lärt sig var det ligger. */
    var introMark by mutableStateOf<SeriesMark?>(null)
        private set
    private var playingSeries: Series? = null
    private var seriesMarks: Map<String, SeriesMark> = emptyMap()
    private val marksStore = SeriesMarksStore()
    private val episodeCache = HashMap<String, List<XtreamEpisode>>()
    private var nextUpJob: Job? = null
    private var learnJob: Job? = null
    private var seekFrom = -1L
    private var seekTarget = 0L
    private var seekAt = 0L

    // --- Fotboll ----------------------------------------------------------------------
    /** Matcherna från en vecka bakåt till två veckor framåt, i tidsordning. */
    var footballMatches by mutableStateOf<List<FootballMatch>>(emptyList())
        private set
    var footballTables by mutableStateOf<Map<League, List<TableRow>>>(emptyMap())
        private set
    /** Ligornas officiella loggor från ESPN. */
    var footballLogos by mutableStateOf<Map<League, String>>(emptyMap())
        private set
    /** Tabellerna har hämtats minst en gång, lyckat eller inte. */
    var footballTablesTried by mutableStateOf(false)
        private set
    /** När matcherna senast hämtades. 0: aldrig. */
    var footballUpdatedAt by mutableStateOf(0L)
        private set
    var footballError by mutableStateOf<String?>(null)
        private set
    /** Vald liga, eller null för översikten. */
    var footballLeague by mutableStateOf<League?>(null)
    var selectedMatch by mutableStateOf<FootballMatch?>(null)
        private set
    var matchDetail by mutableStateOf<MatchDetail?>(null)
        private set
    var matchDetailLoading by mutableStateOf(false)
        private set
    /** Förra matchens startelvor, per lag-id, medan den öppna matchens elva inte är släppt. */
    var previousLineups by mutableStateOf<Map<String, Lineup>>(emptyMap())
        private set
    /** Kommande matcher vars startelvor är släppta. */
    var lineupsReady by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Kanalerna som visar matchen enligt tablån, per match-id. */
    var matchChannels by mutableStateOf<Map<String, List<LiveChannel>>>(emptyMap())
        private set
    private val footballClient = FootballClient()
    private var tablesAt = 0L
    /**
     * Ligornas matcher och lagens egna (Malmö FF, landslaget) var för sig.
     * Tillsammans blir de [footballMatches], där en match som finns i båda
     * står en gång, från sin liga.
     */
    private var leagueMatches: List<FootballMatch> = emptyList()
    private var teamMatches: Map<League, List<FootballMatch>> = emptyMap()
    /** Lagens matcher hämtas sällan: de ligger glest, månader framåt och bakåt. */
    private var teamsAt = 0L
    /** När hela perioden senast hämtades; däremellan hämtas bara dagarna kring idag. */
    private var footballFullAt = 0L
    private var footballTriedAt = 0L
    private var lineupsCheckedAt = 0L
    private val footballMutex = Mutex()
    private val channelsMutex = Mutex()
    private var channelsKey: BroadcastKey? = null
    /** Fliken Fotboll syns: då håller footballLoop allt färskt. */
    private var footballVisible = false
    /** Appen syns. Shield sätter false när man gått till hemskärmen. */
    var appVisible = true

    /** Omslag som sparas i bakgrunden, för inställningarna. Null: inget att säga. */
    var coverStatus by mutableStateOf<String?>(null)

    // --- Egna bakgrundsbilder ------------------------------------------------
    var backgrounds by mutableStateOf<List<String>>(emptyList())
        private set
    var backgroundMessage by mutableStateOf<String?>(null)
        private set
    var movieSort by mutableStateOf(SortOrder.PROVIDER)
        private set
    var seriesSort by mutableStateOf(SortOrder.PROVIDER)
        private set
    var theme by mutableStateOf(AppTheme.GRAPHITE)
        private set
    /** Omslagsväggen bakom sidorna: Filmväggen eller Filmsiden i någon av färgerna. */
    var wallStyle by mutableStateOf(WallStyle.WALL)
        private set

    var selectedSeries by mutableStateOf<Series?>(null)
        private set
    var episodes by mutableStateOf<EpisodesState>(EpisodesState.Loading)
        private set
    private var episodesJob: Job? = null

    var selectedMovie by mutableStateOf<Movie?>(null)
        private set
    var movieInfo by mutableStateOf<MovieInfoState>(MovieInfoState.Loading)
        private set
    private var movieInfoJob: Job? = null
    private val movieInfoCache = HashMap<String, XtreamVodInfo?>()

    // --- Import --------------------------------------------------------------

    var importState by mutableStateOf<ImportState>(ImportState.Idle)
        private set
    var importingId by mutableStateOf<String?>(null)
        private set
    /** Källan som senaste import- eller felmeddelandet gäller. Null: formuläret. */
    var lastImportedId by mutableStateOf<String?>(null)
        private set
    /** Upplysning som inte är ett fel, t.ex. att en M3U-länk visade sig vara Xtream. */
    var notice by mutableStateOf<String?>(null)
        private set
    /** Ökar när en ny källa lagts till, så formuläret vet att det kan tömmas. */
    var addedCount by mutableStateOf(0)
        private set

    // --- Spelaren --------------------------------------------------------------

    var nowPlaying by mutableStateOf<NowPlaying?>(null)
        private set
    var videoPlayer by mutableStateOf<VideoPlayer?>(null)
        private set
    var playerError by mutableStateOf<String?>(null)
        private set
    /** Statusen från spelaren gäller bara om den bär det här id:t. */
    var playbackSession by mutableStateOf(-1L)
        private set
    var fullscreen by mutableStateOf(false)
    /** Spelaren krympt till ett hörn: den spelar vidare medan man bläddrar. */
    var miniPlayer by mutableStateOf(false)
        private set

    /** En film eller kanal fyller skärmen. Då vilar de tunga bakgrundsjobben. */
    val watching: Boolean
        get() = nowPlaying != null && !miniPlayer
    var volume by mutableStateOf(80)
        private set
    var muted by mutableStateOf(false)
        private set
    /** Panelen för ljud och undertext i spelaren. */
    var trackPanelOpen by mutableStateOf(false)
    /** Bikubisk uppskalning (skarpast). Av: bilinjär, för äldre datorer. */
    var sharpScaling by mutableStateOf(true)
        private set
    /** Ljudspråk som väljs automatiskt när en ström startar. Null: VLC väljer. */
    var preferredAudio by mutableStateOf<String?>(null)
        private set
    /** Undertextspråk som väljs automatiskt. "": av. Null: VLC väljer. */
    var preferredSubtitle by mutableStateOf<String?>(null)
        private set
    /** Senaste mus- eller tangentaktivitet i spelaren; styr när kontrollerna tonar bort. */
    var lastActivity by mutableStateOf(0L)
        private set

    /** Ökar när bilden ska få markeringen igen, till exempel när en lista över den stängs. */
    var playerFocusTick by mutableStateOf(0)
        private set

    /** Tv: markeringen står på en av spelarens knappar, inte på bilden. */
    var playerControlsFocused by mutableStateOf(false)

    /** Tv: markeringen står på tidslinjen; där spolar pil vänster och höger. */
    var playerTimelineFocused by mutableStateOf(false)

    /** Tv: kanallistan ovanpå bilden (OK eller pil vänster i direktsänt). */
    var channelListOpen by mutableStateOf(false)

    /** Tv: kanallistan fram. Ett kanalbyte på gång görs först, och knapparna går undan. */
    fun openChannelList() {
        commitZap()
        lastActivity = 0L
        channelListOpen = true
    }

    /** Ökar när spelarens knappar ska få markeringen (pil upp eller ner på tv:n). */
    var controlsFocusTick by mutableStateOf(0)
        private set
    private var controlsFocusPending = false

    /** Tv: knapparna fram, med markeringen på Spela/Paus. */
    fun focusPlayerControls() {
        touchPlayer(force = true)
        controlsFocusPending = true
        controlsFocusTick++
    }

    /** Sant en gång per begäran: knapparna tar markeringen när de visas. */
    fun takeControlsFocus(): Boolean {
        val pending = controlsFocusPending
        controlsFocusPending = false
        return pending
    }

    /** Tv: från knapparna tillbaka till bilden. Falskt om ingen knapp hade markeringen. */
    fun leavePlayerControls(): Boolean {
        if (!playerControlsFocused) return false
        playerControlsFocused = false
        playerTimelineFocused = false
        playerFocusTick++
        return true
    }

    /** Ger bilden markeringen igen, så att fjärrkontrollen styr spelaren direkt. */
    fun focusPlayer() {
        playerFocusTick++
    }

    /** Tv: markeringen står på själva bilden (den stora eller den lilla i hörnet). */
    var playerBoxFocused by mutableStateOf(false)

    /** Ökar när den lilla bilden i hörnet ska få markeringen. */
    var miniFocusTick by mutableStateOf(0)
        private set

    /** Tv: markeringen till den lilla bilden i hörnet, där OK förstorar och Tillbaka stänger. */
    fun focusMiniPlayer() {
        if (nowPlaying != null && miniPlayer) miniFocusTick++
    }

    // --- Tablå -------------------------------------------------------------------

    /** Kanal → program, för alla källor. Byggs om när bibliotek eller tablå ändras. */
    var guide by mutableStateOf(GuideIndex.EMPTY)
        private set
    /** Läget per källa i klartext, för Inställningar och tablåskärmen. */
    val guideStatus = mutableStateMapOf<String, String>()
    var guideLoading by mutableStateOf(false)
        private set
    var guideOnlyWithData by mutableStateOf(true)
    /** Tickar var trettionde sekund, så att "nu" i listor och tablå följer med. */
    var clock by mutableStateOf(System.currentTimeMillis())
        private set

    private val guideStore = GuideStore()
    private val guides = HashMap<String, SourceGuide>()
    private val guideQueue = ArrayDeque<String>()
    private val guideAttempts = HashMap<String, Long>()
    private var guideJob: Job? = null
    private var guideBuild: Job? = null

    private var playbackJob: Job? = null
    private var playerCreation: Deferred<VideoPlayer?>? = null
    private var lastLaunch: Pair<NowPlaying, suspend () -> String?>? = null

    init {
        scope.launch { loadFromDisk() }
        scope.launch { footballBackground() }
        scope.launch {
            var ticks = 0
            while (true) {
                delay(30_000)
                clock = System.currentTimeMillis()
                // Var tionde minut: är någon tablå äldre än tolv timmar hämtas den om,
                // och står appen framme länge hämtas kanalerna om var tredje timme.
                // Inte medan något fyller skärmen: en stor tablå tar kraft från bilden.
                if (++ticks % 20 == 0 && !watching) {
                    queueStaleGuides()
                    if (appVisible) refreshChannelsAndGuide(CHANNELS_STALE_MS)
                }
            }
        }
    }

    private suspend fun loadFromDisk() {
        val loaded = withContext(Dispatchers.IO) {
            val savedSources = storage.loadSources()
            Triple(
                savedSources,
                savedSources.mapNotNull { source -> storage.loadContent(source.id)?.let { source.id to it } },
                storage.loadSettings(),
            )
        }
        sources = loaded.first
        loaded.second.forEach { (id, content) -> contents[id] = content }
        volume = loaded.third.volume
        sharpScaling = loaded.third.sharpScaling
        preferredAudio = loaded.third.audioLanguage
        preferredSubtitle = loaded.third.subtitleLanguage
        movieSort = sortOrderOf(loaded.third.movieSort)
        seriesSort = sortOrderOf(loaded.third.seriesSort)
        theme = AppTheme.entries.firstOrNull { it.name == loaded.third.theme } ?: AppTheme.GRAPHITE
        wallStyle = WallStyle.entries.firstOrNull { it.name == loaded.third.wallStyle } ?: WallStyle.WALL
        tmdbKey = SecretBox.unsealText(loaded.third.tmdbKey)
        tmdbConnected = tmdbKey != null
        backgrounds = loaded.third.backgrounds.filter { File(it).isFile }
        hideForeign = loaded.third.hideForeign
        hiddenGroups = loaded.third.hiddenGroups.toSet()
        disneyChoices = loaded.third.disneyChoices
        lastVisitUtc = loaded.third.lastVisitUtc
        val visitStart = System.currentTimeMillis()
        newSinceUtc = if (lastVisitUtc > 0L) lastVisitUtc.coerceAtLeast(visitStart - NEW_MAX_AGE_MS) else visitStart - NEW_FIRST_RUN_MS
        // Besöket räknas först efter en stund, så att en snabb omstart inte tömmer "Nytt sedan sist".
        scope.launch {
            delay(VISIT_COUNTS_AFTER_MS)
            lastVisitUtc = visitStart
            saveSettings()
        }
        rebuildLibrary()
        loading = false
        history = withContext(Dispatchers.IO) { historyStore.load() }
        myList = withContext(Dispatchers.IO) { myListStore.load() }
        movieFacts = withContext(Dispatchers.IO) { factsStore.load() }
        tmdbFacts = withContext(Dispatchers.IO) { tmdbStore.load() }
        val extras = withContext(Dispatchers.IO) { extrasStore.load() }
        tmdbLogos = extras.logos
        trending = extras.trending
        trendingAt = extras.trendingAt
        // Startens filmvägg, om det inte fanns någon sparad: räknas fram ur
        // biblioteket så fort det finns, medan starten fortfarande visas.
        scope.launch {
            // Finns en sparad vägg visar starten den; då behöver inget räknas nu.
            if (withContext(Dispatchers.IO) { IntroReelStore.load().size } >= INTRO_REEL_MIN) return@launch
            val reel = withContext(Dispatchers.Default) { runCatching { computeIntroReel() }.getOrDefault(emptyList()) }
            if (reel.isNotEmpty()) introReel = reel
        }
        seriesMarks = withContext(Dispatchers.IO) { marksStore.load() }
        scope.launch {
            while (true) {
                refreshTrending()
                delay(TRENDING_CHECK_MS)
            }
        }
        scheduleVersions(urgent = true)
        factsLoaded = true
        tmdbLoaded = true
        startTmdbScan()
        startFactsScan()
        // Tablåerna från disken, och sedan kanalerna och tablån på nytt, när
        // startsidan har fått sina rader: det tunga ska inte göra starten seg.
        scope.launch {
            afterStartup()
            loadGuides()
            refreshChannelsAndGuide()
        }

        if (sources.isEmpty()) {
            screen = Screen.SETTINGS
        } else {
            // Källor utan sparat bibliotek — första starten efter en uppdatering
            // av appen, eller en cache som inte gick att läsa — hämtas om, en i taget.
            refreshQueue.addAll(sources.filter { it.id !in contents }.map { it.id })
            refreshNextQueued()
        }
    }

    /** Startsidan har fått sina rader. Tunga bakgrundsjobb väntar på den, högst en stund. */
    private val homeReady = CompletableDeferred<Unit>()

    /** Startsidan har visats med sina rader: startanimationen kan tona bort. */
    var homeShown by mutableStateOf(false)
        private set

    /** Startanimationen har spelats klart. En gång per start av appen. */
    var introDone by mutableStateOf(false)

    /** Startanimationen tonar bort: startsidan kommer fram och dess omslag vänds fram. */
    var introRevealing by mutableStateOf(false)

    fun markHomeReady() {
        homeReady.complete(Unit)
        homeShown = true
        // En stund senare, när filmserierna och det som trendar finns: filmväggen
        // till nästa start sparas, så att den har sina omslag direkt.
        if (!introReelSaving) {
            introReelSaving = true
            scope.launch {
                delay(INTRO_REEL_SAVE_DELAY_MS)
                val reel = withContext(Dispatchers.Default) { runCatching { computeIntroReel() }.getOrDefault(emptyList()) }
                if (reel.size >= INTRO_REEL_MIN) withContext(Dispatchers.IO) { IntroReelStore.save(reel) }
            }
        }
    }

    /** Startens filmvägg, räknad ur biblioteket under den här körningen. Tom: inte ännu. */
    var introReel by mutableStateOf<List<IntroReelItem>>(emptyList())
        private set
    private var introReelSaving = false

    /**
     * Startens filmvägg: de häftigaste filmerna man har just nu — de som trendar,
     * filmserierna (Marvel, Star Wars …) och nya filmer med högt betyg och
     * många tittare. En per film, med samma omslag som appen visar (de ligger
     * redan på disken). Körs utanför UI-tråden, med kopior av faktan.
     */
    private fun computeIntroReel(): List<IntroReelItem> {
        val index = versions
        val lib = library
        val facts = tmdbFacts
        if (lib.movies.isEmpty()) return emptyList()
        val trendingRank = HashMap<Int, Int>()
        trending.filter { it.kind == "movie" }.forEachIndexed { rank, title -> trendingRank.putIfAbsent(title.id, rank) }
        val owned = collections
        val inFranchise = HashSet<String>()
        for (franchise in owned.present()) for (movie in owned.ownedIn(franchise).values) inFranchise += movie.key
        val thisYear = LocalDate.now().year
        fun usable(url: String?): String? = url?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
        fun tmdbOf(movie: Movie): TmdbFacts? {
            if (facts.isEmpty()) return null
            val (title, year) = TitleCleaner.searchTitle(movie.name)
            return facts[TmdbStore.keyOf("movie", title, year)]?.takeIf { it.found }
        }
        val scored = ArrayList<Triple<Movie, TmdbFacts?, Double>>()
        for (movie in lib.movies) {
            val year = index.yearOf(movie)
            val franchise = movie.key in inFranchise
            val recent = year != null && year >= thisYear - INTRO_REEL_YEARS
            val tmdb = tmdbOf(movie)
            // Klassikerna från alla år också — Jurassic Park, Ensam hemma, Tillbaka till framtiden:
            // filmer som väldigt många har sett och tyckt om.
            val classic = tmdb != null && tmdb.votes >= INTRO_CLASSIC_VOTES && (tmdb.rating ?: 0.0) >= INTRO_CLASSIC_RATING
            if (!franchise && !recent && !classic) continue
            var score = 0.0
            tmdb?.let { hit -> trendingRank[hit.id]?.let { rank -> score += 1_000.0 - rank * 12.0 } }
            if (franchise) score += 320.0
            if (classic) score += 300.0
            val shownYear = year ?: tmdb?.year
            if (shownYear != null) score += (shownYear - (thisYear - INTRO_REEL_YEARS)).coerceIn(0, INTRO_REEL_YEARS) * 45.0
            if (tmdb != null) {
                score += minOf(tmdb.popularity, 250.0)
                if ((tmdb.rating ?: 0.0) >= 7.0 && tmdb.votes >= 500) score += 80.0
            }
            if (score > 0.0) scored += Triple(movie, tmdb, score)
        }
        // Samma film bara en gång: inte via en annan version, en annan titel eller samma omslag.
        val seen = HashSet<String>()
        val seenTitles = HashSet<String>()
        val seenTmdb = HashSet<Int>()
        val seenPosters = HashSet<String>()
        val result = ArrayList<IntroReelItem>()
        for ((movie, tmdb, _) in scored.sortedByDescending { it.third }) {
            if (result.size >= INTRO_REEL_SIZE) break
            if (index.groupKeyOf(movie) in seen) continue
            if (tmdb != null && tmdb.id in seenTmdb) continue
            val title = index.titleOf(movie)
            val titleKey = title.lowercase().filter { it.isLetterOrDigit() }
            if (titleKey.isNotEmpty() && titleKey in seenTitles) continue
            val poster = usable(tmdb?.takeIf { it.sure }?.poster)
                ?: usable(movie.poster)
                ?: index.versionsOf(movie).firstNotNullOfOrNull { usable(it.poster) }
                ?: usable(tmdb?.poster)
                ?: continue
            if (!seenPosters.add(poster)) continue
            seen += index.groupKeyOf(movie)
            if (tmdb != null) seenTmdb += tmdb.id
            if (titleKey.isNotEmpty()) seenTitles += titleKey
            result += IntroReelItem(title, poster, usable(tmdb?.backdrop))
        }
        return result
    }

    /** Väntar tills startsidan är klar, eller som längst [STARTUP_GRACE_MS]: starten går först. */
    private suspend fun afterStartup() {
        withTimeoutOrNull(STARTUP_GRACE_MS) { homeReady.await() }
    }

    private fun rebuildLibrary() {
        val merged = Library.merge(sources.mapNotNull { contents[it.id] })
        library = merged
        // En vald kategori som försvann med en borttagen källa ska inte lämna en tom lista.
        // Samlingarna, Disney och de egna kategorierna (nycklar som börjar med \u0001)
        // finns inte bland leverantörens kategorier.
        if (liveGroup?.let { it.startsWith('\u0001') || merged.liveCounts.containsKey(it) } == false) liveGroup = null
        if (movieGroup?.let { it.startsWith('\u0001') || merged.movieCounts.containsKey(it) } == false) movieGroup = null
        if (seriesGroup?.let { it.startsWith('\u0001') || merged.seriesCounts.containsKey(it) } == false) seriesGroup = null
        val movie = selectedMovie
        if (movie != null && movie.sourceId !in contents) selectedMovie = null
        val series = selectedSeries
        if (series != null && series.sourceId !in contents) selectedSeries = null
        rebuildGuide()
        // Nya filmer i biblioteket: genrehämtningen tar med dem.
        if (factsLoaded) restartFactsScan()
        tmdbKeys.clear()
        if (tmdbLoaded) restartTmdbScan()
        scheduleVersions(urgent = true)
    }

    fun contentFor(sourceId: String): SourceContent? = contents[sourceId]

    fun filteredChannels(): List<LiveChannel> {
        val query = liveQuery.trim().lowercase()
        val group = liveGroup
        if (PersonalKeys.isPersonal(group)) return personalChannels(group, query)
        val hidden = hiddenGroups
        return library.channels.filter { channel ->
            (group == null || channel.group == group) &&
                (query.isEmpty() || query in channel.searchKey) &&
                (group != null || hidden.isEmpty() || LibraryFilter.groupKey(LibraryFilter.LIVE, channel.group) !in hidden)
        }
    }

    /** Filmerna i vald kategori och sökning, en gång per film: versionerna väljs på filmens sida. */
    fun filteredMovies(): List<Movie> {
        val query = movieQuery.trim().lowercase()
        val group = movieGroup
        val index = versions
        if (DisneyShelf.isDisney(group)) return disneyMovies(DisneyShelf.of(group), query)
        if (PersonalKeys.isPersonal(group)) return personalMovies(group, query)
        return library.movies
            .filter { (group == null || it.group == group) && (query.isEmpty() || query in it.searchKey) }
            .ordered(movieSort, { index.titleOf(it) }, { it.addedUtc }, { scoreOf(it) }, { index.yearOf(it) }, { tmdbFor(it)?.popularity })
            .let { index.collapseMovies(it, preferDefault = group == null && query.isEmpty()) }
    }

    /**
     * Filmerna i en Disneysamling, eller i alla tre ([shelf] null), samlingarna
     * i ordning. Standard: klassikerna och Pixar i nummerordning, kortfilmerna efter år.
     */
    private fun disneyMovies(shelf: DisneyShelf?, query: String): List<Movie> {
        val index = versions
        val shelves = disney
        val character = disneyCharacter
        val list = library.movies.filter { movie ->
            val found = shelves.shelfOf(movie) ?: return@filter false
            (shelf == null || found == shelf) &&
                (query.isEmpty() || query in movie.searchKey) &&
                (character == null || shelves.characterOf(movie) == character)
        }
        val sorted = if (movieSort == SortOrder.PROVIDER) {
            list.sortedWith(
                compareBy<Movie>(
                    { shelves.shelfOf(it)?.ordinal ?: Int.MAX_VALUE },
                    { shelves.numberOf(it) ?: Int.MAX_VALUE },
                    { index.yearOf(it) ?: Int.MAX_VALUE },
                    { index.titleOf(it) },
                ),
            )
        } else {
            // Annan sortering inom varje samling; samlingarna står kvar i sin ordning.
            list.ordered(movieSort, { index.titleOf(it) }, { it.addedUtc }, { scoreOf(it) }, { index.yearOf(it) }, { tmdbFor(it)?.popularity })
                .sortedBy { shelves.shelfOf(it)?.ordinal ?: Int.MAX_VALUE }
        }
        return index.collapseMovies(sorted, preferDefault = true)
    }

    /** Kortfilmerna som ligger som serier, i Disneys samlingar. Efter år. */
    fun disneySeries(shelf: DisneyShelf?): List<Series> {
        if (shelf != null && shelf != DisneyShelf.SHORTS) return emptyList()
        val query = movieQuery.trim().lowercase()
        val index = versions
        val shelves = disney
        val character = disneyCharacter
        val list = library.series.filter { series ->
            shelves.shelfOf(series) != null &&
                (query.isEmpty() || query in series.searchKey) &&
                (character == null || shelves.characterOf(series) == character)
        }.sortedWith(compareBy<Series>({ index.yearOf(it) ?: Int.MAX_VALUE }, { index.titleOf(it) }))
        return index.collapseSeries(list, preferDefault = true)
    }

    fun disneyShelfOf(movie: Movie): DisneyShelf? = disney.shelfOf(movie)

    /** Den första filmen i en av Disneys samlingar, för bakgrundsbilden i galleriet. */
    fun disneyFeature(shelf: DisneyShelf): Movie? {
        val shelves = disney
        val index = versions
        return library.movies.asSequence()
            .filter { shelves.shelfOf(it) == shelf && !index.isHidden(it) }
            .minWithOrNull(compareBy<Movie>({ shelves.numberOf(it) ?: Int.MAX_VALUE }, { index.yearOf(it) ?: Int.MAX_VALUE }))
    }

    /**
     * Omslagen på Disneys samlingskort: de första titlarna i samlingen som har
     * omslag — klassikerna och Pixar i nummerordning, kortfilmerna äldst först.
     * En per film, hur många versioner den än finns i.
     */
    fun disneyCovers(shelf: DisneyShelf, count: Int): List<List<String>> {
        val shelves = disney
        val index = versions
        return library.movies.asSequence()
            .filter { shelves.shelfOf(it) == shelf }
            .sortedWith(compareBy<Movie>({ shelves.numberOf(it) ?: Int.MAX_VALUE }, { index.yearOf(it) ?: Int.MAX_VALUE }))
            .distinctBy { index.groupKeyOf(it) }
            .map { posterCandidates(it) }
            .filter { it.isNotEmpty() }
            .take(count)
            .toList()
    }

    /** Visas valet Disney på filmsidan. */
    fun isDisneyCandidate(movie: Movie): Boolean =
        disney.shelfOf(movie) != null || DisneyIndex.isCandidate(movie.group, versions.titleOf(movie))

    /** Eget val på filmsidan: filmen i en samling, eller ([shelf] null) inte Disney. Gäller alla versioner. */
    fun setDisneyShelf(movie: Movie, shelf: DisneyShelf?) {
        val key = DisneyIndex.choiceKey(versions.titleOf(movie), versions.yearOf(movie))
        disneyChoices = disneyChoices + (key to (shelf?.name ?: DisneyIndex.NONE))
        saveSettings()
        scheduleDisney()
    }

    /** Bygger om Disneys samlingar i bakgrunden. */
    private fun scheduleDisney() {
        disneyJob?.cancel()
        disneyJob = scope.launch {
            afterStartup()
            val lib = library
            val index = versions
            val choices = disneyChoices
            disney = withContext(Dispatchers.Default) { DisneyIndex.build(lib, index, choices) }
            collections = withContext(Dispatchers.Default) { CollectionIndex.build(lib, index) }
        }
    }

    /** En samlings filmer i ordning, med numret i den ordningen och filmen om man har den. */
    fun collectionSlots(franchise: Franchise): List<CollectionSlot> {
        val owned = collections.ownedIn(franchise)
        val order = (if (collectionStory) franchise.story else null) ?: franchise.films
        val part = collectionPart?.let { franchise.parts.getOrNull(it) }?.films?.toSet()
        return order
            .filter { part == null || it in part }
            .mapIndexed { index, film -> CollectionSlot(index + 1, film, owned[franchise.positionOf(film)]) }
    }

    /** Öppnar en samling, ett av Disneys kort eller galleriet, från början. */
    fun openCollection(group: String) {
        movieGroup = group
        collectionPart = null
        disneyCharacter = null
    }

    fun filteredSeries(): List<Series> {
        val query = seriesQuery.trim().lowercase()
        val group = seriesGroup
        val index = versions
        if (PersonalKeys.isPersonal(group)) return personalSeries(group, query)
        return library.series
            .filter { (group == null || it.group == group) && (query.isEmpty() || query in it.searchKey) }
            .ordered(seriesSort, { index.titleOf(it) }, { it.addedUtc }, { scoreOf(it) }, { index.yearOf(it) }, { tmdbFor(it)?.popularity })
            .let { index.collapseSeries(it, preferDefault = group == null && query.isEmpty()) }
    }

    // --- Egna kategorier: Fortsätt titta, Min lista, Favoriter, Senast sedda -----------

    /** Uppslag från historiken och Min lista till biblioteket. Byggs en gång per bibliotek. */
    private class PersonalIndex(val library: Library) {
        val movieByStream: Map<String, Movie> = library.movies.associateBy { "${it.sourceId}|${it.streamId}" }
        val movieByKey: Map<String, Movie> = library.movies.associateBy { it.key }
        val seriesById: Map<String, Series> = library.series.associateBy { "${it.sourceId}|${it.seriesId}" }
        val seriesByKey: Map<String, Series> = library.series.associateBy { it.key }
        val channelByKey: Map<String, LiveChannel> = library.channels.associateBy { it.key }
        val channelByHistoryId: Map<String, LiveChannel> =
            library.channels.associateBy { "${it.sourceId}|${it.streamId?.toString() ?: "namn:${it.name}"}" }
    }

    @Volatile
    private var personalIndex: PersonalIndex? = null

    private fun personalIndex(): PersonalIndex {
        val current = personalIndex
        val lib = library
        if (current != null && current.library === lib) return current
        return PersonalIndex(lib).also { personalIndex = it }
    }

    /** Filmerna i en egen kategori: den man såg senast först, en gång per film. */
    private fun personalMovies(group: String?, query: String): List<Movie> {
        val index = personalIndex()
        val list = when (group) {
            PersonalKeys.RESUME -> history.asSequence()
                .filter { it.kind == WatchKind.MOVIE && it.resumable }
                .mapNotNull { index.movieByStream["${it.sourceId}|${it.itemId}"] }
                .toList()
            PersonalKeys.MY_LIST -> myList.filter { it.kind == ListKind.MOVIE }
                .sortedByDescending { it.addedUtc }
                .mapNotNull { index.movieByKey[it.key] }
            else -> emptyList()
        }
        val versionIndex = versions
        return list.filter { query.isEmpty() || query in it.searchKey }.distinctBy { versionIndex.groupKeyOf(it) }
    }

    /** Serierna i en egen kategori: den man såg senast först, en gång per serie. */
    private fun personalSeries(group: String?, query: String): List<Series> {
        val index = personalIndex()
        val list = when (group) {
            PersonalKeys.RESUME -> history.asSequence()
                .filter { it.kind == WatchKind.EPISODE && (it.resumable || it.upNext) }
                .mapNotNull { entry -> entry.seriesId?.let { index.seriesById["${entry.sourceId}|$it"] } }
                .toList()
            PersonalKeys.MY_LIST -> myList.filter { it.kind == ListKind.SERIES }
                .sortedByDescending { it.addedUtc }
                .mapNotNull { index.seriesByKey[it.key] }
            else -> emptyList()
        }
        val versionIndex = versions
        return list.filter { query.isEmpty() || query in it.searchKey }.distinctBy { versionIndex.groupKeyOf(it) }
    }

    /** Kanalerna i en egen kategori: favoriterna, eller de senast sedda. */
    private fun personalChannels(group: String?, query: String): List<LiveChannel> {
        val index = personalIndex()
        val list = when (group) {
            PersonalKeys.FAVORITES -> myList.filter { it.kind == ListKind.CHANNEL }.mapNotNull { index.channelByKey[it.key] }
            PersonalKeys.RECENT -> history.asSequence()
                .filter { it.kind == WatchKind.LIVE }
                .mapNotNull { index.channelByHistoryId["${it.sourceId}|${it.itemId}"] }
                .distinctBy { it.key }
                .take(RECENT_CHANNELS)
                .toList()
            else -> emptyList()
        }
        return list.filter { query.isEmpty() || query in it.searchKey }.distinctBy { it.key }
    }

    /**
     * De egna kategorierna som har något i sig, för kategorilistan: Fortsätt
     * titta och Min lista bland filmer och serier, Favoriter och Senast sedda
     * bland kanalerna. [kind]: LibraryFilter.MOVIE, SERIES eller LIVE.
     */
    fun personalGroups(kind: Char): List<Pair<String, Int>> {
        val keys = if (kind == LibraryFilter.LIVE) {
            listOf(PersonalKeys.FAVORITES, PersonalKeys.RECENT)
        } else {
            listOf(PersonalKeys.RESUME, PersonalKeys.MY_LIST)
        }
        return keys.mapNotNull { key ->
            val count = when (kind) {
                LibraryFilter.LIVE -> personalChannels(key, "").size
                LibraryFilter.SERIES -> personalSeries(key, "").size
                else -> personalMovies(key, "").size
            }
            if (count > 0) key to count else null
        }
    }

    // --- Versioner -----------------------------------------------------------------

    /**
     * Bygger om versionerna i bakgrunden. [urgent]: biblioteket har ändrats,
     * så direkt. Annars (nya TMDB-svar) högst var femte sekund.
     */
    private fun scheduleVersions(urgent: Boolean) {
        versionsDirty = true
        if (versionsJob?.isActive == true) {
            if (!urgent) return
            versionsJob?.cancel()
        }
        versionsJob = scope.launch {
            if (!urgent) delay(VERSIONS_PAUSE_MS)
            while (versionsDirty) {
                versionsDirty = false
                val lib = library
                val tmdb = tmdbFacts
                val names = sources.associate { it.id to it.name }
                val filter = LibraryFilter(hideForeign, hiddenGroups)
                versions = withContext(Dispatchers.Default) { VersionIndex.build(lib, tmdb, names, filter) }
                if (versionsDirty) delay(VERSIONS_PAUSE_MS)
            }
            scheduleDisney()
        }
    }

    fun versionsOf(movie: Movie): List<Movie> = versions.versionsOf(movie)

    fun versionsOf(series: Series): List<Series> = versions.versionsOf(series)

    /** Titeln utan leverantörens taggar: "Pressure", inte "Pressure [PRE] [2026] [SWE-SUB]". */
    fun titleOf(movie: Movie): String = versions.titleOf(movie)

    fun titleOf(series: Series): String = versions.titleOf(series)

    // --- Fotboll ----------------------------------------------------------------------

    /**
     * Håller fliken Fotboll färsk medan den syns: varje minut när något
     * är live, varannan minut nära avspark (startelvorna), annars var tionde.
     * Hela perioden hämtas varje halvtimme, däremellan bara dagens matcher.
     */
    suspend fun footballLoop() {
        footballVisible = true
        var failures = 0
        try {
            while (true) {
                // Appen syns inte (Shield vilar eller står på hemskärmen): inget hämtas.
                if (!appVisible) {
                    delay(15_000L)
                    continue
                }
                val full = footballMatches.isEmpty() || System.currentTimeMillis() - footballFullAt > FOOTBALL_FULL_MS
                safely { refreshFootball(full = full, foreground = true) }
                // Gick hämtningen inte: nytt försök snart, sedan allt glesare upp till var tionde
                // minut, så att appen inte fortsätter fråga ESPN i samma takt om de spärrar.
                if (footballError != null) {
                    failures++
                    delay((30_000L shl (failures - 1).coerceAtMost(4)).coerceAtMost(600_000L))
                    continue
                }
                failures = 0
                val now = System.currentTimeMillis()
                val live = footballMatches.any { it.state == MatchState.LIVE || it.shouldHaveStarted(now) }
                val soon = footballMatches.any { it.state == MatchState.UPCOMING && it.kickoff - now in 0L..LINEUP_WATCH_MS }
                delay(
                    when {
                        live -> 60_000L
                        soon -> 120_000L
                        else -> 600_000L
                    },
                )
            }
        } finally {
            footballVisible = false
        }
    }

    /**
     * Utan fliken öppen räcker det att ställningen stämmer för siffran vid
     * Fotboll i menyn: dagarna kring idag var femte minut medan matcher pågår,
     * hela perioden var tredje timme. Inget hämtas medan en film eller kanal
     * fyller skärmen, eller när appen inte syns.
     */
    private suspend fun footballBackground() {
        delay(FOOTBALL_START_DELAY_MS)
        while (true) {
            val now = System.currentTimeMillis()
            // Malmö FF eller Sverige spelar: ställningen varje minut, också under en film
            // eller kanal, så att ett mål syns i hörnet direkt (se matchNotice).
            val oursOn = footballMatches.any {
                it.isOurs() && (it.state == MatchState.LIVE || (it.shouldHaveStarted(now) && it.state != MatchState.FINISHED))
            }
            if (oursOn && !footballVisible && appVisible && now - footballUpdatedAt >= OURS_LIVE_MS) {
                safely { refreshFootball(full = false, foreground = false) }
            } else if (!footballVisible && appVisible && !watching) {
                val ongoing = footballMatches.any { it.state == MatchState.LIVE || it.shouldHaveStarted(now) }
                when {
                    now - footballFullAt > FOOTBALL_BACKGROUND_FULL_MS && now - footballTriedAt > FOOTBALL_RETRY_MS ->
                        safely { refreshFootball(full = true, foreground = false) }
                    ongoing && now - footballUpdatedAt >= FOOTBALL_BACKGROUND_LIVE_MS ->
                        safely { refreshFootball(full = false, foreground = false) }
                }
            }
            remindKickoffs(System.currentTimeMillis())
            delay(if (oursOn) 15_000L else 30_000L)
        }
    }

    // --- Rutan i hörnet: Malmö FF:s och landslagets matcher -------------------------

    enum class MatchNoticeKind { GOAL, CONCEDED, KICKOFF_SOON, FULL_TIME }

    /**
     * Något som hände i Malmö FF:s eller landslagets match, i en ruta i hörnet
     * var man än är i appen — också under en film eller kanal: mål för eller
     * emot, att matchen börjar om en stund (med kanalen som visar den), och
     * slutresultatet.
     */
    class MatchNotice(
        val kind: MatchNoticeKind,
        val match: FootballMatch,
        val player: String?,
        val minute: String?,
        val channel: LiveChannel?,
    )

    var matchNotice by mutableStateOf<MatchNotice?>(null)
        private set

    private val knownScores = HashMap<String, Pair<Int, Int>>()
    private val seenLive = HashSet<String>()
    private val reminded = HashSet<String>()
    private var noticeJob: Job? = null

    /** Malmö FF eller herrlandslaget spelar. */
    private fun FootballMatch.isOurs(): Boolean = isMalmoFF() || home.id == SWEDEN_TEAM || away.id == SWEDEN_TEAM

    /** Malmö FF eller landslaget är hemmalaget. */
    private fun FootballMatch.oursAtHome(): Boolean = home.isMalmoFF() || home.id == SWEDEN_TEAM

    /** Efter varje hämtning: nya mål och matcher som just tagit slut. Första gången en match syns räknas inget. */
    private fun noticeMatchEvents(matches: List<FootballMatch>) {
        for (match in matches) {
            if (!match.isOurs()) continue
            val score = (match.homeScore ?: 0) to (match.awayScore ?: 0)
            val before = knownScores.put(match.id, score)
            if (match.state == MatchState.LIVE) {
                seenLive += match.id
                if (before != null) {
                    val homeScored = score.first > before.first
                    val awayScored = score.second > before.second
                    if (homeScored || awayScored) {
                        val ours = if (match.oursAtHome()) homeScored else awayScored
                        val goal = match.goals.lastOrNull { it.home == homeScored }
                        showNotice(
                            MatchNotice(
                                kind = if (ours) MatchNoticeKind.GOAL else MatchNoticeKind.CONCEDED,
                                match = match,
                                player = goal?.player?.takeIf { it.isNotBlank() },
                                minute = goal?.minute ?: match.status,
                                channel = null,
                            ),
                        )
                    }
                }
            } else if (match.state == MatchState.FINISHED && seenLive.remove(match.id)) {
                showNotice(MatchNotice(MatchNoticeKind.FULL_TIME, match, null, null, null))
            }
        }
    }

    /** En kvart före avspark: rutan säger att matchen börjar, och var den visas. */
    private fun remindKickoffs(now: Long) {
        for (match in footballMatches) {
            if (!match.isOurs() || match.state != MatchState.UPCOMING || match.id in reminded) continue
            val left = match.kickoff - now
            if (left in 1L..KICKOFF_REMINDER_MS) {
                reminded += match.id
                showNotice(MatchNotice(MatchNoticeKind.KICKOFF_SOON, match, null, null, matchChannels[match.id]?.firstOrNull()))
            }
        }
    }

    private fun showNotice(notice: MatchNotice) {
        matchNotice = notice
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(if (notice.kind == MatchNoticeKind.KICKOFF_SOON) NOTICE_LONG_MS else NOTICE_MS)
            if (matchNotice === notice) matchNotice = null
        }
    }

    /** Förhandsbilder (windows/src/preview): en ruta i hörnet utan någon match som pågår. */
    internal fun previewNotice(notice: MatchNotice?) {
        noticeJob?.cancel()
        matchNotice = notice
    }

    /** Varför fotbollen inte gick att hämta, kort och på svenska. */
    private fun footballReason(error: Throwable): String = when (error) {
        is UnknownHostException -> "ingen anslutning till ESPN"
        is SocketTimeoutException -> "ESPN svarade inte i tid"
        is ConnectException -> "kunde inte ansluta till ESPN"
        is SSLException -> "den säkra anslutningen till ESPN misslyckades"
        else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    /** Ett oväntat fel i en hämtning ska inte stoppa slingan; nästa varv försöker igen. */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.printStackTrace()
        }
    }

    /** Avspark har varit, men matchen står fortfarande som kommande: den har nog börjat. */
    private fun FootballMatch.shouldHaveStarted(now: Long): Boolean =
        state == MatchState.UPCOMING && kickoff in (now - FOOTBALL_LIVE_WINDOW_MS)..now

    private suspend fun refreshFootball(full: Boolean, foreground: Boolean) {
        footballMutex.withLock { refreshFootballLocked(full, foreground) }
    }

    private suspend fun refreshFootballLocked(full: Boolean, foreground: Boolean) {
        val today = LocalDate.now()
        val started = System.currentTimeMillis()
        if (full) footballTriedAt = started
        // Mellan de hela hämtningarna räcker idag, och dagen då en pågående match började.
        val days = (
            listOf(today) + footballMatches
                .filter { it.state == MatchState.LIVE || it.shouldHaveStarted(started) }
                .map { Instant.ofEpochMilli(it.kickoff).atZone(ZoneId.systemDefault()).toLocalDate() }
            ).distinct()
        val daySet = days.toSet()
        // Lagen (Malmö FF, landslaget) hämtas för sig, efter ligorna (se fetchTeams).
        // Mellan gångerna bara de dagar de spelar, och bara ur tävlingarna de
        // matcherna hör till. En match som också finns i en liga följer ligan.
        val teamsDue = full && started - teamsAt > TEAMS_EVERY_MS
        val inLeagues = leagueMatches.mapTo(HashSet()) { it.id }
        val teamSources: Map<League, List<String>> = if (full) {
            emptyMap()
        } else {
            teamMatches.mapValues { (_, list) ->
                list.filter { it.id !in inLeagues && dayOf(it.kickoff) in daySet }.map { it.slug }.distinct()
            }.filterValues { it.isNotEmpty() }
        }
        val leagues = League.entries.filter { !it.isTeam } + teamSources.keys
        val results = coroutineScope {
            leagues.map { league ->
                async(Dispatchers.IO) {
                    league to runCatching {
                        if (full) {
                            footballClient.matches(league, today.minusDays(7), today.plusDays(14))
                        } else {
                            footballClient.matchesOn(league, days, teamSources[league] ?: league.sources)
                        }
                    }
                }
            }.awaitAll()
        }
        val fetched = results.filter { it.second.isSuccess }
        if (fetched.isEmpty()) {
            // Skälet står med, så att det går att se om det är nätet eller ESPN.
            val reason = results.firstNotNullOfOrNull { it.second.exceptionOrNull() }?.let { footballReason(it) }
            footballError = "Matcherna gick inte att hämta just nu" + (reason?.let { " ($it)" } ?: "") +
                ". Appen försöker igen om en stund."
            return
        }
        footballError = null
        val fresh = fetched.filter { !it.first.isTeam }.flatMap { it.second.getOrDefault(emptyList()) }
        leagueMatches = if (full) {
            // En liga som inte gick att hämta den här gången behåller sina matcher.
            val failed = results.filter { it.second.isFailure }.map { it.first }.toSet()
            fresh + leagueMatches.filter { it.league in failed }
        } else {
            // Bara dagarna kring idag: de nya ersätter sina gamla, resten står kvar.
            val ids = fresh.mapTo(HashSet()) { it.id }
            leagueMatches.filter { it.id !in ids } + fresh
        }
        // Lagens matcher de här dagarna ersätter sina gamla på samma sätt.
        for ((league, result) in fetched) {
            if (!league.isTeam) continue
            val found = result.getOrDefault(emptyList())
            val ids = found.mapTo(HashSet()) { it.id }
            teamMatches = teamMatches + (league to (teamMatches[league].orEmpty().filter { it.id !in ids } + found))
        }
        publishMatches()
        val logos = League.entries.mapNotNull { league -> footballClient.logoOf(league)?.let { league to it } }.toMap()
        if (logos != footballLogos) footballLogos = logos
        val now = System.currentTimeMillis()
        footballUpdatedAt = now
        if (full) footballFullAt = now
        if (!foreground) {
            if (teamsDue) fetchTeams(today)
            return
        }

        if (footballTables.isEmpty() || now - tablesAt > TABLES_MAX_AGE_MS) {
            val tables = coroutineScope {
                League.entries.filter { it.hasTable }.map { league ->
                    async(Dispatchers.IO) { league to runCatching { footballClient.table(league) }.getOrNull() }
                }.awaitAll()
            }
            footballTables = footballTables + tables.mapNotNull { (league, rows) -> rows?.takeIf { it.isNotEmpty() }?.let { league to it } }
            tablesAt = System.currentTimeMillis()
            footballTablesTried = true
        }

        // Den öppna matchen: ny ställning, och startelvorna så fort de släpps.
        selectedMatch?.let { open ->
            footballMatches.firstOrNull { it.id == open.id }?.let { selectedMatch = it }
            loadMatchDetail(quiet = true)
        }
        checkLineups()
        refreshMatchChannels()
        if (teamsDue && fetchTeams(today)) refreshMatchChannels()
    }

    /** Ligornas och lagens matcher i en lista, i tidsordning: varje match en gång, helst från sin liga. */
    private fun publishMatches() {
        val byId = LinkedHashMap<String, FootballMatch>()
        for (match in leagueMatches) byId[match.id] = match
        for (list in teamMatches.values) for (match in list) byId.putIfAbsent(match.id, match)
        footballMatches = byId.values.sortedBy { it.kickoff }
        noticeMatchEvents(footballMatches)
    }

    private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    /**
     * Malmö FF:s och herrlandslagets matcher: ett halvår runt idag, ur alla
     * tävlingar de spelar i. Många frågor, så de görs efter ligorna och bara
     * var sjätte timme; ligorna syns under tiden. Svarar med om något hämtades.
     */
    private suspend fun fetchTeams(today: LocalDate): Boolean {
        val found = coroutineScope {
            League.entries.filter { it.isTeam }.map { league ->
                async(Dispatchers.IO) {
                    league to runCatching {
                        footballClient.matches(league, today.minusDays(TEAM_BACK_DAYS), today.plusDays(TEAM_AHEAD_DAYS))
                    }.getOrNull()
                }
            }.awaitAll()
        }.mapNotNull { (league, list) -> list?.let { league to it } }
        if (found.isEmpty()) return false
        teamsAt = System.currentTimeMillis()
        teamMatches = teamMatches + found
        publishMatches()
        return true
    }

    /** Kommande matcher inom 75 minuter: är startelvan släppt? Högst var femte minut. */
    private suspend fun checkLineups() {
        val now = System.currentTimeMillis()
        if (now - lineupsCheckedAt < LINEUP_CHECK_MS) return
        lineupsCheckedAt = now
        val candidates = footballMatches.filter {
            it.state == MatchState.UPCOMING && it.kickoff - now in 0L..LINEUP_WATCH_MS && it.id !in lineupsReady
        }.take(6)
        if (candidates.isEmpty()) return
        val ready = coroutineScope {
            candidates.map { match ->
                async(Dispatchers.IO) {
                    val released = runCatching { footballClient.detail(match.slug, match.id, match.home.id).lineupsReleased }.getOrDefault(false)
                    match.id.takeIf { released }
                }
            }.awaitAll()
        }.filterNotNull()
        if (ready.isNotEmpty()) lineupsReady = lineupsReady + ready
    }

    /**
     * Kanalerna som visar matcherna enligt tablån. Räknas om bara när
     * matcherna, kanalerna, de dolda kategorierna eller tablån har ändrats.
     */
    suspend fun refreshMatchChannels() {
        channelsMutex.withLock {
            val now = System.currentTimeMillis()
            // Tablåerna brukar inte nå längre än några dygn fram.
            val matches = footballMatches.filter {
                it.state == MatchState.LIVE ||
                    (it.state == MatchState.UPCOMING && it.kickoff in (now - FOOTBALL_LIVE_WINDOW_MS)..(now + BROADCAST_HORIZON_MS))
            }
            val key = BroadcastKey(guide, library.channels, hiddenGroups, matches.map { it.id to it.kickoff })
            if (key.sameAs(channelsKey)) return
            val found = withContext(Dispatchers.Default) {
                runCatching {
                    val visible = key.channels.filter { LibraryFilter.groupKey(LibraryFilter.LIVE, it.group) !in key.hidden }
                    // Svenska kanaler först (V Sport, Viaplay, TV4 …), sedan nordiska; se rankBroadcasts.
                    findBroadcasts(matches, visible, key.guide)
                }.getOrNull()
            } ?: return
            matchChannels = found
            channelsKey = key
        }
    }

    fun openMatch(match: FootballMatch) {
        selectedMatch = match
        matchDetail = null
        previousLineups = emptyMap()
        scope.launch { loadMatchDetail(quiet = false) }
    }

    fun closeMatch() {
        selectedMatch = null
        matchDetail = null
        previousLineups = emptyMap()
    }

    private suspend fun loadMatchDetail(quiet: Boolean) {
        val match = selectedMatch ?: return
        if (!quiet) matchDetailLoading = true
        val detail = withContext(Dispatchers.IO) {
            runCatching { footballClient.detail(match.slug, match.id, match.home.id) }.getOrNull()
        }
        if (selectedMatch?.id != match.id) return
        matchDetailLoading = false
        if (detail != null) matchDetail = detail
        if (detail?.lineupsReleased == true) {
            if (match.state == MatchState.UPCOMING) lineupsReady = lineupsReady + match.id
        } else if (previousLineups.isEmpty() && match.state == MatchState.UPCOMING) {
            // Före släppet: lagens startelvor från förra matchen, som en försmak.
            val previous = loadPreviousLineups(match)
            if (selectedMatch?.id == match.id) previousLineups = previous
        }
    }

    private suspend fun loadPreviousLineups(match: FootballMatch): Map<String, Lineup> {
        val result = HashMap<String, Lineup>()
        for (team in listOf(match.home, match.away)) {
            val last = footballMatches
                .filter { it.state == MatchState.FINISHED && it.kickoff < match.kickoff && (it.home.id == team.id || it.away.id == team.id) }
                .maxByOrNull { it.kickoff } ?: continue
            val detail = withContext(Dispatchers.IO) {
                runCatching { footballClient.detail(last.slug, last.id, last.home.id) }.getOrNull()
            } ?: continue
            detail.lineups.firstOrNull { it.teamId == team.id && it.starters.isNotEmpty() }?.let { result[team.id] = it }
        }
        return result
    }

    /** Titta på matchen: kanalen tablån säger visar den. */
    fun watchMatch(match: FootballMatch) {
        val channels = matchChannels[match.id].orEmpty()
        val first = channels.firstOrNull() ?: return
        // Visas matchen redan i den lilla rutan: den växer upp, med ljud, utan att börja om.
        if (nowPlaying?.channel?.key == first.key && miniPlayer) {
            expandPlayer()
            return
        }
        playChannel(first, channels)
    }

    // --- Loggor från TMDB --------------------------------------------------------------

    /** TMDB-id för filmen eller serien, när titeln hittats där. */
    fun tmdbIdOf(movie: Movie): Int? = tmdbFor(movie)?.id?.takeIf { it > 0 }

    fun tmdbIdOf(series: Series): Int? = tmdbFor(series)?.id?.takeIf { it > 0 }

    /** Loggans adress, eller null: ingen logga, eller inte hämtad ännu. kind: "movie" eller "tv". */
    fun logoUrl(kind: String, tmdbId: Int): String? = tmdbLogos["$kind:$tmdbId"]?.ifEmpty { null }

    /** Hämtar loggan om den inte redan finns. Anropas när titeln visas stort. */
    fun ensureLogo(kind: String, tmdbId: Int) {
        val apiKey = tmdbKey ?: return
        val key = "$kind:$tmdbId"
        if (tmdbId <= 0 || tmdbLogos.containsKey(key) || !logoRequests.add(key)) return
        scope.launch {
            val result = logoGate.withPermit {
                withContext(Dispatchers.IO) { runCatching { TmdbClient(apiKey).logo(kind, tmdbId) } }
            }
            // Ett nätfel provas igen nästa gång titeln visas. "Ingen logga" sparas.
            result.onSuccess { found ->
                tmdbLogos = tmdbLogos + (key to (found ?: ""))
                saveExtrasSoon()
            }
            logoRequests.remove(key)
        }
    }

    private fun saveExtrasSoon() {
        extrasSaveJob?.cancel()
        extrasSaveJob = scope.launch {
            delay(3_000)
            val snapshot = TmdbExtrasStore.Saved(tmdbLogos, trending, trendingAt)
            withContext(Dispatchers.IO) { extrasStore.save(snapshot) }
        }
    }

    // --- Trender ------------------------------------------------------------------------

    /** Dagens trendlista från TMDB, högst var sjätte timme. */
    private fun refreshTrending(force: Boolean = false) {
        val apiKey = tmdbKey ?: return
        val fresh = System.currentTimeMillis() - trendingAt < TRENDING_MAX_AGE_MS
        if (!force && fresh && trending.isNotEmpty()) return
        scope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { TmdbClient(apiKey).trending() }.getOrNull() }
            if (list.isNullOrEmpty()) return@launch
            trending = list
            trendingAt = System.currentTimeMillis()
            saveExtrasSoon()
        }
    }

    // --- Nytt sedan sist -----------------------------------------------------------------

    /** Tillagd sedan förra besöket, i någon version. */
    fun isNew(movie: Movie): Boolean = newSinceUtc > 0L && versions.versionsOf(movie).any { it.addedUtc >= newSinceUtc }

    fun isNew(series: Series): Boolean = newSinceUtc > 0L && versions.versionsOf(series).any { it.addedUtc >= newSinceUtc }

    /** Serien har fått nya avsnitt sedan man senast tittade på den. */
    fun hasNewEpisode(series: Series): Boolean {
        val seen = seriesSeen()
        if (seen.isEmpty()) return false
        val all = versions.versionsOf(series)
        val watched = all.maxOf { seen["${it.sourceId}:${it.seriesId}"] ?: 0L }
        return watched > 0L && all.any { it.addedUtc > watched }
    }

    /** Skylten på omslaget: "Ny", "Nytt avsnitt", "4K" eller "Ny · 4K". */
    fun badgeFor(movie: Movie): String? = listOfNotNull(
        if (isNew(movie)) "Ny" else null,
        if (versions.has4k(movie)) "4K" else null,
    ).joinToString(" · ").ifEmpty { null }

    fun badgeFor(series: Series): String? = listOfNotNull(
        when {
            hasNewEpisode(series) -> "Nytt avsnitt"
            isNew(series) -> "Ny"
            else -> null
        },
        if (versions.has4k(series)) "4K" else null,
    ).joinToString(" · ").ifEmpty { null }

    private var seriesSeenCache: Pair<List<WatchEntry>, Map<String, Long>>? = null

    /** När man senast tittade på varje serie, per "källa:serie-id". */
    private fun seriesSeen(): Map<String, Long> {
        val list = history
        seriesSeenCache?.let { (cachedFor, map) -> if (cachedFor === list) return map }
        val map = HashMap<String, Long>()
        for (entry in list) {
            if (entry.kind != WatchKind.EPISODE) continue
            val key = "${entry.sourceId}:${entry.seriesId}"
            if ((map[key] ?: 0L) < entry.watchedAtUtc) map[key] = entry.watchedAtUtc
        }
        seriesSeenCache = list to map
        return map
    }

    // --- Biblioteket: språk och dolda kategorier ---------------------------------------

    /** Kategorierna som visas i listan. kind: LibraryFilter.LIVE, MOVIE eller SERIES. */
    fun visibleGroups(kind: Char, groups: List<String>): List<String> {
        val hidden = hiddenGroups
        // Med språkvalet försvinner också film- och seriekategorierna på andra språk: de vore tomma.
        val foreign = hideForeign && kind != LibraryFilter.LIVE
        if (hidden.isEmpty() && !foreign) return groups
        return groups.filter { group ->
            LibraryFilter.groupKey(kind, group) !in hidden && !(foreign && isForeignGroup(group))
        }
    }

    private val foreignGroupCache = HashMap<String, Boolean>()

    private fun isForeignGroup(group: String): Boolean = foreignGroupCache.getOrPut(group) { VersionTags.isForeignGroup(group) }

    fun isGroupHidden(kind: Char, group: String): Boolean = LibraryFilter.groupKey(kind, group) in hiddenGroups

    fun hiddenCount(kind: Char): Int = hiddenGroups.count { it.startsWith("$kind|") }

    fun setGroupHidden(kind: Char, group: String, hidden: Boolean) {
        val key = LibraryFilter.groupKey(kind, group)
        hiddenGroups = if (hidden) hiddenGroups + key else hiddenGroups - key
        libraryFilterChanged()
    }

    /** Döljer kategorierna på andra språk: "PL | Filmy", "AR | أفلام", "TURKISH". */
    fun hideForeignGroups(kind: Char, groups: List<String>) {
        hiddenGroups = hiddenGroups + groups.filter { VersionTags.isForeignGroup(it) }.map { LibraryFilter.groupKey(kind, it) }
        libraryFilterChanged()
    }

    fun showAllGroups(kind: Char) {
        val prefix = "$kind|"
        hiddenGroups = hiddenGroups.filterNot { it.startsWith(prefix) }.toSet()
        libraryFilterChanged()
    }

    fun updateHideForeign(hide: Boolean) {
        if (hideForeign == hide) return
        hideForeign = hide
        libraryFilterChanged()
    }

    private fun libraryFilterChanged() {
        movieGroup?.let { if (isGroupHidden(LibraryFilter.MOVIE, it)) movieGroup = null }
        seriesGroup?.let { if (isGroupHidden(LibraryFilter.SERIES, it)) seriesGroup = null }
        liveGroup?.let { if (isGroupHidden(LibraryFilter.LIVE, it)) liveGroup = null }
        saveSettings()
        scheduleVersions(urgent = true)
    }

    // --- Serier: hoppa över introt och nästa avsnitt -------------------------------------

    private fun currentStatus(): VideoPlayer.Status? = videoPlayer?.status?.value?.takeIf { it.session == playbackSession }

    /** Spola relativt. Spolar man i ett avsnitt lär sig appen var introt och eftertexterna ligger. */
    fun seekBy(deltaMs: Long) {
        val player = videoPlayer ?: return
        val status = currentStatus()
        player.seekBy(deltaMs)
        if (status != null) noteSeek(status.timeMs, (status.timeMs + deltaMs).coerceAtLeast(0L), status.lengthMs)
    }

    fun seekTo(fraction: Float) {
        val player = videoPlayer ?: return
        val status = currentStatus()
        player.seekTo(fraction)
        if (status != null && status.lengthMs > 0L) {
            noteSeek(status.timeMs, (status.lengthMs * fraction.coerceIn(0f, 1f)).toLong(), status.lengthMs)
        }
    }

    private fun noteSeek(fromMs: Long, toMs: Long, lengthMs: Long) {
        val series = playingSeries ?: return
        val now = System.currentTimeMillis()
        // Flera tryck i följd (10 s, 10 s, 10 s …) räknas som ett hopp.
        if (seekFrom < 0L || now - seekAt > SEEK_GROUP_MS) seekFrom = fromMs
        seekTarget = toMs
        seekAt = now
        learnJob?.cancel()
        learnJob = scope.launch {
            delay(SEEK_GROUP_MS)
            learn(series, seekFrom, seekTarget, lengthMs)
            seekFrom = -1L
        }
    }

    // --- Tv: spola och byta kanal med fjärren --------------------------------------

    /**
     * Tv: spolningen samlas ihop. Varje tryck flyttar målet direkt — det syns
     * på tidslinjen — och bilden hoppar dit först när trycken slutat: ett hopp
     * i stället för ett per tryck, så att strömmen inte laddar om för var
     * tionde sekund. -1: ingen spolning på gång.
     */
    var scrubTarget by mutableStateOf(-1L)
        private set

    /** Var bilden var när spolningen började; tidslinjen visar hur långt hoppet blir. */
    var scrubOrigin by mutableStateOf(-1L)
        private set

    private var scrubJob: Job? = null
    private var scrubHeldSince = 0L
    private var scrubLastPress = 0L

    /** En spolning är på gång. */
    val scrubbing: Boolean get() = scrubTarget >= 0L

    /**
     * Tv: pil vänster ([direction] −1) eller höger (1). Ett tryck flyttar tio
     * sekunder, [big] trettio (snabbspolningsknapparna). Hålls knappen nere
     * ([held]) går det allt fortare: från tjugo sekunder i sekunden till som
     * mest hela filmen på en kvarts minut.
     */
    fun scrub(direction: Int, held: Boolean = false, big: Boolean = false) {
        val status = currentStatus() ?: return
        val length = status.lengthMs
        if (length <= 0L || !status.seekable) return
        val now = System.currentTimeMillis()
        val step = if (held && scrubTarget >= 0L) {
            if (scrubHeldSince == 0L) scrubHeldSince = now
            val elapsed = (now - scrubLastPress).coerceIn(16L, 250L)
            (scrubSpeed(now - scrubHeldSince, length) * elapsed).toLong().coerceAtLeast(1_000L)
        } else {
            scrubHeldSince = 0L
            if (big) 30_000L else 10_000L
        }
        scrubLastPress = now
        if (scrubTarget < 0L) scrubOrigin = status.timeMs
        val from = if (scrubTarget >= 0L) scrubTarget else status.timeMs
        // Aldrig ända fram till slutet: då tar filmen slut och spelaren stängs.
        val last = (length - SCRUB_END_GUARD_MS).coerceAtLeast(0L)
        scrubTarget = (from + direction * step).coerceIn(0L, last)
        touchPlayer(force = true)
        scrubJob?.cancel()
        scrubJob = scope.launch {
            delay(SCRUB_SETTLE_MS)
            commitScrub()
        }
    }

    /** Hur fort målet flyttas medan knappen hålls nere, i filmens sekunder per sekund. */
    private fun scrubSpeed(heldFor: Long, length: Long): Float {
        val top = maxOf(SCRUB_TOP_SPEED, length / SCRUB_WHOLE_FILM_MS.toFloat())
        val speed = SCRUB_START_SPEED * 2.0.pow(heldFor / SCRUB_DOUBLING_MS.toDouble()).toFloat()
        return speed.coerceAtMost(top)
    }

    /** Spolningen klar: bilden hoppar till målet. Falskt om ingen spolning var på gång. */
    fun commitScrub(): Boolean {
        val target = scrubTarget
        if (target < 0L) return false
        val origin = scrubOrigin
        clearScrub()
        touchPlayer(force = true)
        val status = currentStatus() ?: return true
        val player = videoPlayer ?: return true
        if (status.lengthMs <= 0L) return true
        player.seekTo(target.toFloat() / status.lengthMs)
        noteSeek(if (origin >= 0L) origin else status.timeMs, target, status.lengthMs)
        return true
    }

    /** Tillbaka under spolningen: bilden stannar där den var. Falskt om ingen spolning var på gång. */
    fun cancelScrub(): Boolean {
        if (scrubTarget < 0L) return false
        clearScrub()
        touchPlayer(force = true)
        return true
    }

    private fun clearScrub() {
        scrubJob?.cancel()
        scrubJob = null
        scrubTarget = -1L
        scrubOrigin = -1L
        scrubHeldSince = 0L
    }

    /**
     * Tv: kanalen man bläddrat fram till med pil upp och ner, innan den
     * startats. Kanalrutan visar den direkt; bytet görs först när trycken
     * slutat, så att snabba tryck går förbi kanaler utan att starta var och
     * en. Null: inget byte på gång.
     */
    var zapTarget by mutableStateOf<LiveChannel?>(null)
        private set

    /** När kanalrutan senast visades, så att den står kvar en stund efter bytet. */
    var zapShownAt by mutableStateOf(0L)
        private set

    private var zapJob: Job? = null

    /**
     * Tv: ett steg upp (−1) eller ner (1) i listan kanalen startades från.
     * Med pilarna går knapparna undan för kanalrutan; från knapparna
     * ([keepControls]) står de kvar och visar kanalen man är på väg till.
     */
    fun zap(delta: Int, keepControls: Boolean = false) {
        val current = nowPlaying ?: return
        val channel = current.channel ?: return
        val queue = current.queue
        if (queue.size < 2) {
            // Ingen annan kanal i listan: rutan visar ändå vad som sänds.
            zapShownAt = System.currentTimeMillis()
            return
        }
        val from = zapTarget ?: channel
        val index = queue.indexOfFirst { it.key == from.key }.coerceAtLeast(0)
        zapTarget = queue[(index + delta).mod(queue.size)]
        zapShownAt = System.currentTimeMillis()
        if (keepControls) {
            touchPlayer(force = true)
        } else {
            lastActivity = 0L
        }
        zapJob?.cancel()
        zapJob = scope.launch {
            delay(ZAP_SETTLE_MS)
            commitZap()
        }
    }

    /** Bytet görs nu: OK, eller en stund efter sista trycket. Falskt om inget byte var på gång. */
    fun commitZap(): Boolean {
        val target = zapTarget ?: return false
        zapJob?.cancel()
        zapJob = null
        zapTarget = null
        zapShownAt = System.currentTimeMillis()
        val current = nowPlaying ?: return true
        if (target.key != current.channel?.key) playChannel(target, current.queue)
        return true
    }

    /** Tillbaka innan bytet: kanalen som spelas står kvar. Falskt om inget byte var på gång. */
    fun cancelZap(): Boolean {
        if (zapTarget == null) return false
        clearZap()
        zapShownAt = 0L
        return true
    }

    private fun clearZap() {
        zapJob?.cancel()
        zapJob = null
        zapTarget = null
    }

    /**
     * Det som spelas, för spelarens rubrik, laddningsbild och pausbild: TMDB:s
     * id (för titelns logga), handlingen, genrerna, året, betyget och en
     * bakgrundsbild. Null för kanaler och sådant som inte finns i biblioteket.
     */
    class PlayingFacts(
        val kind: String,
        val tmdbId: Int?,
        val overview: String?,
        val genres: String?,
        val year: Int?,
        val rating: Double?,
        val backdrop: String?,
    )

    fun playingFacts(item: NowPlaying): PlayingFacts? {
        val entry = item.entry ?: return null
        return when (entry.kind) {
            WatchKind.MOVIE -> {
                val movie = library.movies.firstOrNull { it.sourceId == entry.sourceId && it.streamId.toString() == entry.itemId }
                    ?: return null
                val facts = tmdbFor(movie)
                PlayingFacts(
                    kind = "movie",
                    tmdbId = facts?.id,
                    overview = facts?.overview?.takeIf { it.isNotBlank() },
                    genres = facts?.genres?.takeIf { it.isNotBlank() } ?: movieFacts[movie.key]?.genre,
                    year = facts?.year ?: movie.year,
                    rating = facts?.rating ?: movie.rating,
                    backdrop = facts?.backdrop,
                )
            }
            WatchKind.EPISODE -> {
                val seriesId = entry.seriesId ?: return null
                val series = library.series.firstOrNull { it.sourceId == entry.sourceId && it.seriesId == seriesId } ?: return null
                val facts = tmdbFor(series)
                PlayingFacts(
                    kind = "tv",
                    tmdbId = facts?.id,
                    overview = (facts?.overview ?: series.plot)?.takeIf { it.isNotBlank() },
                    genres = facts?.genres?.takeIf { it.isNotBlank() } ?: series.genre,
                    year = facts?.year ?: series.year?.toIntOrNull(),
                    rating = facts?.rating ?: series.rating,
                    backdrop = facts?.backdrop ?: series.backdrop,
                )
            }
            WatchKind.LIVE -> null
        }
    }

    /**
     * Förhandsbilder (windows/src/preview): ett påhittat bibliotek och en tablå,
     * direkt i minnet. Utan DPAPI (som där) går biblioteket inte att spara på disken.
     */
    internal fun previewLibrary(source: Source, content: SourceContent, guide: SourceGuide, tmdb: Map<String, TmdbFacts> = emptyMap()) {
        if (tmdb.isNotEmpty()) tmdbFacts = tmdbFacts + tmdb
        if (sources.none { it.id == source.id }) sources = sources + source
        refreshQueue.remove(source.id)
        channelsFetchedAt[source.id] = System.currentTimeMillis()
        contents[source.id] = content
        guides[source.id] = guide
        rebuildLibrary()
        loading = false
    }

    /** Förhandsbilder: filmväggen med just de här omslagen, som i en app med ett stort bibliotek. */
    internal fun previewReel(reel: List<IntroReelItem>) {
        introReel = reel
    }

    /**
     * Förhandsbilder (windows/src/preview): spelarens läge utan någon spelare —
     * vad som spelas, en spolning eller ett kanalbyte på gång.
     */
    internal fun previewPlayer(item: NowPlaying?, scrubTo: Long = -1L, scrubFrom: Long = -1L, zapTo: LiveChannel? = null) {
        nowPlaying = item
        miniPlayer = false
        scrubTarget = scrubTo
        scrubOrigin = scrubFrom
        zapTarget = zapTo
        zapShownAt = if (zapTo != null) System.currentTimeMillis() else 0L
    }

    /** Ett hopp framåt i början är introt; ett hopp förbi slutet är eftertexterna. */
    private fun learn(series: Series, from: Long, to: Long, length: Long) {
        if (from < 0L) return
        val key = SeriesMarksStore.keyOf(titleOf(series))
        val old = seriesMarks[key] ?: SeriesMark()
        val jump = to - from
        val updated = when {
            from <= INTRO_LATEST_START_MS && jump in INTRO_MIN_MS..INTRO_MAX_MS && to <= INTRO_LATEST_END_MS ->
                old.copy(introFromMs = from, introToMs = to)
            length > 0L && from >= length - OUTRO_WINDOW_MS && to >= length - 15_000L && length - from >= 20_000L ->
                old.copy(outroLeadMs = length - from)
            else -> return
        }
        seriesMarks = seriesMarks + (key to updated)
        if (playingSeries?.let { SeriesMarksStore.keyOf(titleOf(it)) } == key) introMark = updated.takeIf { it.hasIntro }
        val snapshot = seriesMarks
        scope.launch(Dispatchers.IO) { marksStore.save(snapshot) }
    }

    /** Ett avsnitt har börjat: introt som appen lärt sig, och vilket avsnitt som kommer sedan. */
    private fun startSeriesExtras(series: Series, episodeId: String) {
        playingSeries = series
        introMark = seriesMarks[SeriesMarksStore.keyOf(titleOf(series))]?.takeIf { it.hasIntro }
        nextUpJob?.cancel()
        nextUpJob = scope.launch {
            val list = episodesFor(series) ?: return@launch
            val ordered = list.sortedWith(compareBy<XtreamEpisode> { it.season }.thenBy { it.episode })
            val index = ordered.indexOfFirst { it.id == episodeId }
            val next = if (index >= 0) ordered.getOrNull(index + 1) else null
            // Bara om samma avsnitt fortfarande spelas.
            if (nowPlaying?.entry?.itemId == episodeId) nextUp = next?.let { NextUp(series, it) }
        }
    }

    private fun resetSeriesExtras() {
        nextUpJob?.cancel()
        nextUp = null
        nextUpDismissed = false
        introMark = null
        playingSeries = null
    }

    private suspend fun episodesFor(series: Series): List<XtreamEpisode>? {
        val key = series.key
        episodeCache[key]?.let { return it }
        val shown = episodes
        if (selectedSeries?.key == key && shown is EpisodesState.Loaded) {
            episodeCache[key] = shown.episodes
            return shown.episodes
        }
        val account = credentials(series.sourceId) ?: return null
        val loaded = withContext(Dispatchers.IO) {
            runCatching { xtream.episodes(account.second, series.seriesId) }.getOrNull()
        } ?: return null
        episodeCache[key] = loaded
        return loaded
    }

    /** Ska knappen Hoppa över intro synas nu? */
    fun showsIntroSkip(status: VideoPlayer.Status): Boolean {
        val mark = introMark ?: return false
        if (nowPlaying?.live != false || status.lengthMs <= 0L || miniPlayer) return false
        return status.timeMs in (mark.introFromMs - 3_000L)..(mark.introToMs - 4_000L)
    }

    /** Till där introt slutar. Direkt i spelaren, så att hoppet inte lärs in på nytt. */
    fun skipIntro() {
        val mark = introMark ?: return
        val status = currentStatus() ?: return
        clearScrub()
        videoPlayer?.seekBy(mark.introToMs - status.timeMs)
        touchPlayer(force = true)
    }

    /** Ska rutan Nästa avsnitt synas nu? Mot slutet, eller när avsnittet tagit slut. */
    fun showsNextUp(status: VideoPlayer.Status): Boolean {
        if (nextUp == null || nextUpDismissed || miniPlayer) return false
        if (status.phase == VideoPlayer.Phase.ENDED) return true
        if (status.lengthMs <= 60_000L || status.phase == VideoPlayer.Phase.OPENING) return false
        val learned = playingSeries?.let { seriesMarks[SeriesMarksStore.keyOf(titleOf(it))]?.outroLeadMs } ?: 0L
        val lead = if (learned > 0L) learned else DEFAULT_OUTRO_LEAD_MS
        return status.lengthMs - status.timeMs in 0L..lead
    }

    fun playNextUp() {
        val next = nextUp ?: return
        playEpisode(next.series, next.episode)
    }

    fun dismissNextUp() {
        nextUpDismissed = true
    }

    /** OK eller Enter i spelaren: hoppa över introt, eller spela nästa avsnitt, om någon av dem visas. */
    fun acceptPlayerPrompt(): Boolean {
        val status = currentStatus() ?: return false
        return when {
            showsIntroSkip(status) -> {
                skipIntro()
                true
            }
            showsNextUp(status) -> {
                playNextUp()
                true
            }
            else -> false
        }
    }

    /** Tillbaka eller Esc: stänger rutan Nästa avsnitt om den visas. */
    fun dismissPlayerPrompt(): Boolean {
        val status = currentStatus() ?: return false
        if (!showsNextUp(status)) return false
        dismissNextUp()
        return true
    }

    /**
     * Omslagen att prova, i ordning: TMDB:s när träffen är säker, den
     * visade versionens, de andra versionernas, och sist TMDB:s ändå.
     * TMDB:s omslag är skarpa och lika för alla titlar; leverantörernas är
     * ofta små, beskurna eller borta. Saknas eller fungerar inte ett, tas nästa.
     */
    fun posterCandidates(movie: Movie): List<String> {
        val list = ArrayList<String>(4)
        val tmdb = tmdbFor(movie)?.takeIf { it.poster != null }
        if (tmdb != null && tmdb.sure) list += tmdb.poster!!
        movie.poster?.let { list += it }
        for (version in versions.versionsOf(movie)) version.poster?.let { list += it }
        tmdb?.poster?.let { list += it }
        return list.distinct()
    }

    fun posterCandidates(series: Series): List<String> {
        val list = ArrayList<String>(4)
        val tmdb = tmdbFor(series)?.takeIf { it.poster != null }
        if (tmdb != null && tmdb.sure) list += tmdb.poster!!
        series.cover?.let { list += it }
        for (version in versions.versionsOf(series)) version.cover?.let { list += it }
        tmdb?.poster?.let { list += it }
        return list.distinct()
    }

    /**
     * För en stor yta — filmens egen sida, startsidans stora omslag: TMDB:s
     * omslag i större storlek först, så att det inte förstoras och blir mjukt.
     */
    fun largePosters(candidates: List<String>): List<String> {
        val large = candidates.firstOrNull()?.let { TmdbClient.largePoster(it) } ?: return candidates
        return listOf(large) + candidates
    }

    /**
     * Omslagen som sparas i bakgrunden: ett per film och serie, de senast
     * tillagda först, så att nya filmer får sina omslag först. Samma omslag
     * som visas: TMDB:s när träffen är säker, annars titelns eget, sist
     * TMDB:s ändå. Högst [limit].
     *
     * Körs utanför UI-tråden, så TMDB slås upp direkt i en kopia av
     * faktan, inte via [tmdbFor] och dess cache.
     */
    fun coverPrefetchList(limit: Int): List<String> {
        val index = versions
        val lib = library
        val facts = tmdbFacts
        fun usable(url: String?): String? = url?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
        fun tmdbOf(kind: String, name: String, fallbackYear: Int?): TmdbFacts? {
            if (facts.isEmpty()) return null
            val (title, year) = TitleCleaner.searchTitle(name)
            return facts[TmdbStore.keyOf(kind, title, year ?: fallbackYear)]?.takeIf { it.found }
        }
        val movies = index.collapseMovies(lib.movies.sortedByDescending { it.addedUtc }, preferDefault = true)
        val series = index.collapseSeries(lib.series.sortedByDescending { it.addedUtc }, preferDefault = true)
        val result = LinkedHashSet<String>()
        var m = 0
        var s = 0
        // Varannan film och serie, så att båda får sina nyaste omslag tidigt.
        while (result.size < limit && (m < movies.size || s < series.size)) {
            if (m < movies.size) {
                val movie = movies[m++]
                val tmdb = tmdbOf("movie", movie.name, null)
                val cover = usable(tmdb?.takeIf { it.sure }?.poster)
                    ?: usable(movie.poster)
                    ?: index.versionsOf(movie).firstNotNullOfOrNull { usable(it.poster) }
                    ?: usable(tmdb?.poster)
                if (cover != null) result += cover
            }
            if (s < series.size && result.size < limit) {
                val item = series[s++]
                val tmdb = tmdbOf("tv", item.name, item.year?.toIntOrNull())
                val cover = usable(tmdb?.takeIf { it.sure }?.poster)
                    ?: usable(item.cover)
                    ?: index.versionsOf(item).firstNotNullOfOrNull { usable(it.cover) }
                    ?: usable(tmdb?.poster)
                if (cover != null) result += cover
            }
        }
        return result.toList()
    }

    /**
     * Bilderna bredvid kategorierna på tv:n, kategorins namn → adresser. För
     * filmer och serier upp till tre omslag ur varje kategori, de senast
     * tillagda först — samma omslag som visas annars, och de som oftast redan
     * sparats. För kanalerna den första kanalens logga.
     *
     * Körs utanför UI-tråden, så TMDB slås upp direkt i en kopia av faktan.
     */
    fun categoryCovers(kind: Char): Map<String, List<String>> {
        val lib = library
        val facts = tmdbFacts
        fun usable(url: String?): String? = url?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
        fun surePoster(tmdbKind: String, name: String, fallbackYear: Int?): String? {
            if (facts.isEmpty()) return null
            val (title, year) = TitleCleaner.searchTitle(name)
            return facts[TmdbStore.keyOf(tmdbKind, title, year ?: fallbackYear)]?.takeIf { it.found && it.sure }?.poster
        }
        val result = HashMap<String, ArrayList<String>>()
        // Lägger till ett omslag i kategorin; sant när den därmed blev full.
        fun add(group: String, url: String?): Boolean {
            val cover = url ?: return false
            val list = result.getOrPut(group) { ArrayList(COVERS_PER_GROUP) }
            if (list.size >= COVERS_PER_GROUP || cover in list) return false
            list += cover
            return list.size == COVERS_PER_GROUP
        }
        when (kind) {
            LibraryFilter.LIVE -> for (channel in lib.channels) {
                if (result[channel.group]?.isNotEmpty() == true) continue
                usable(channel.logo)?.let { logo -> result.getOrPut(channel.group) { ArrayList(1) } += logo }
            }
            LibraryFilter.MOVIE -> {
                val groups = lib.movieGroups.size
                var full = 0
                var looked = 0
                for (movie in lib.movies.sortedByDescending { it.addedUtc }) {
                    if (full >= groups || looked >= COVER_SCAN_LIMIT) break
                    if ((result[movie.group]?.size ?: 0) >= COVERS_PER_GROUP) continue
                    looked++
                    if (add(movie.group, usable(surePoster("movie", movie.name, null)) ?: usable(movie.poster))) full++
                }
            }
            LibraryFilter.SERIES -> {
                val groups = lib.seriesGroups.size
                var full = 0
                var looked = 0
                for (item in lib.series.sortedByDescending { it.addedUtc }) {
                    if (full >= groups || looked >= COVER_SCAN_LIMIT) break
                    if ((result[item.group]?.size ?: 0) >= COVERS_PER_GROUP) continue
                    looked++
                    if (add(item.group, usable(surePoster("tv", item.name, item.year?.toIntOrNull())) ?: usable(item.cover))) full++
                }
            }
        }
        return result.filterValues { it.isNotEmpty() }
    }

    fun sortMovies(order: SortOrder) {
        movieSort = order
        saveSettings()
    }

    fun sortSeries(order: SortOrder) {
        seriesSort = order
        saveSettings()
    }

    fun selectTheme(option: AppTheme) {
        theme = option
        saveSettings()
    }

    fun selectWallStyle(option: WallStyle) {
        wallStyle = option
        saveSettings()
    }

    private fun sortOrderOf(name: String?): SortOrder =
        SortOrder.entries.firstOrNull { it.name == name } ?: SortOrder.PROVIDER

    // --- Källor ------------------------------------------------------------------

    /**
     * En get.php-länk är ett Xtream-konto i förklädnad. Då byter appen själv
     * till API:et, precis som på Android — den platta M3U-filen ger bara kanaler.
     */
    fun addM3u(nameInput: String, urlInput: String) {
        notice = null
        val url = urlInput.trim()
        if (url.isEmpty()) {
            formError("Klistra in adressen till spellistan.")
            return
        }
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            formError("Adressen ska börja med http:// eller https://.")
            return
        }

        val xtreamAccount = extractXtreamCredentials(url)
        if (xtreamAccount != null) {
            notice = "Länken är ett Xtream-konto. Appen använder API:et i stället — det ger kategorier, filmer och serier."
            addXtreamSource(nameInput, xtreamAccount.baseUrl, xtreamAccount.username, xtreamAccount.password)
            return
        }

        val source = Source(
            id = UUID.randomUUID().toString(),
            name = nameInput.trim().ifEmpty { hostOf(url) },
            type = SourceType.M3U,
            displayUrl = sanitizeUrl(url),
        )
        val sealed = sealOrRemember(url, source.id)
        runImport(source.copy(secret = sealed), plainSecret = url, isNew = true)
    }

    fun addXtream(nameInput: String, server: String, username: String, password: String) {
        notice = null
        if (server.isBlank() || username.isBlank() || password.isBlank()) {
            formError("Fyll i server, användarnamn och lösenord.")
            return
        }
        addXtreamSource(nameInput, normalizeBaseUrl(server), username.trim(), password)
    }

    private fun formError(message: String) {
        if (importingId != null) return
        lastImportedId = null
        importState = ImportState.Failed(message)
    }

    private fun addXtreamSource(nameInput: String, baseUrl: String, username: String, password: String) {
        val source = Source(
            id = UUID.randomUUID().toString(),
            name = nameInput.trim().ifEmpty { hostOf(baseUrl) },
            type = SourceType.XTREAM,
            displayUrl = baseUrl,
            username = username,
            secret = null,
        )
        val sealed = sealOrRemember(password, source.id)
        runImport(source.copy(secret = sealed), plainSecret = password, isNew = true)
    }

    fun refresh(source: Source) {
        scope.launch {
            val secret = plainSecret(source)
            if (secret == null) {
                if (importingId != null) return@launch
                lastImportedId = source.id
                importState = ImportState.Failed(
                    "Inloggningen för ${source.name} kunde inte läsas. Ta bort källan och lägg till den igen.",
                )
                refreshNextQueued()
                return@launch
            }
            runImport(source, secret, isNew = false)
        }
    }

    fun remove(source: Source) {
        guides.remove(source.id)
        guideStatus.remove(source.id)
        guideQueue.remove(source.id)
        scope.launch(Dispatchers.IO) { guideStore.delete(source.id) }
        contents.remove(source.id)
        sessionSecrets.remove(source.id)
        sources = sources.filterNot { it.id == source.id }
        persistSources()
        scope.launch(Dispatchers.IO) { storage.deleteContent(source.id) }
        rebuildLibrary()
        if (importingId == null) importState = ImportState.Idle
    }

    fun setLiveExtension(source: Source, extension: String) {
        sources = sources.map { if (it.id == source.id) it.copy(liveExtension = extension) else it }
        persistSources()
    }

    private fun runImport(source: Source, plainSecret: String, isNew: Boolean) {
        if (importingId != null) return
        importingId = source.id
        lastImportedId = source.id
        importState = ImportState.Fetching("källan")

        scope.launch {
            val report: (ImportState) -> Unit = { state -> scope.launch { importState = state } }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (source.type) {
                        SourceType.M3U -> importer.importM3u(source.id, plainSecret, userAgent, report)
                        SourceType.XTREAM -> importer.importXtream(
                            source.id,
                            XtreamCredentials(
                                baseUrl = source.displayUrl,
                                username = source.username.orEmpty(),
                                password = plainSecret,
                                userAgent = userAgent,
                            ),
                            report,
                        )
                    }
                }
            }

            result.onSuccess { content ->
                val wasEmpty = sources.isEmpty()
                val updated = source.copy(lastSyncUtc = System.currentTimeMillis())
                val before = contents[source.id]?.channels.orEmpty()
                contents[source.id] = content
                followMovedChannels(before, content.channels)
                channelsFetchedAt[source.id] = System.currentTimeMillis()
                sources = if (isNew) sources + updated else sources.map { if (it.id == updated.id) updated else it }
                persistSources()
                rebuildLibrary()
                importState = ImportState.Done(content.channels.size, content.movies.size, content.series.size)
                if (isNew) addedCount++
                if (wasEmpty && isNew) screen = Screen.LIVE
                // Kanalerna kan ha ändrats; tablån hämtas om för källan.
                queueGuide(source.id, force = true)
                withContext(Dispatchers.IO) { storage.saveContent(source.id, content) }
            }.onFailure { error ->
                // En ny källa som inte gick att hämta läggs inte till.
                if (isNew) sessionSecrets.remove(source.id)
                importState = ImportState.Failed(explain(error))
            }
            importingId = null
            refreshNextQueued()
        }
    }

    // --- Kanaler och tablå vid start -------------------------------------------------

    /** När kanalerna senast hämtades, per källa. Tomt vid varje start: då hämtas de. */
    private val channelsFetchedAt = HashMap<String, Long>()
    private var channelsJob: Job? = null

    /**
     * Kanallistan och tablån på nytt för alla källor: vid varje start, när
     * appen kommer fram igen efter en stund ([maxAgeMs]) och var tredje timme
     * medan den står framme. Evenemangskanalerna byter namn efter dagens
     * matcher, så Fotboll hittar då rätt kanaler direkt. Filmerna och serierna
     * står kvar — de hämtas om som förut, med Uppdatera i Inställningar.
     */
    fun refreshChannelsAndGuide(maxAgeMs: Long = CHANNELS_FRESH_MS) {
        if (channelsJob?.isActive == true) return
        val now = System.currentTimeMillis()
        val due = sources.filter { it.id in contents && now - (channelsFetchedAt[it.id] ?: 0L) > maxAgeMs }
        if (due.isEmpty()) return
        channelsJob = scope.launch {
            afterStartup()
            for (source in due) {
                // En hel hämtning av källan pågår redan: den tar med kanalerna och tablån.
                if (importingId == source.id) continue
                channelsFetchedAt[source.id] = System.currentTimeMillis()
                val fresh = fetchChannels(source)
                val current = contents[source.id]
                if (fresh != null && current != null && importingId != source.id) {
                    contents[source.id] = SourceContent(
                        channels = fresh.channels,
                        movies = current.movies,
                        series = current.series,
                        liveGroups = fresh.groups,
                        movieGroups = current.movieGroups,
                        seriesGroups = current.seriesGroups,
                        epgUrl = fresh.epgUrl ?: current.epgUrl,
                    )
                    followMovedChannels(current.channels, fresh.channels)
                    rebuildChannels()
                    val saved = contents[source.id]
                    if (saved != null) withContext(Dispatchers.IO) { runCatching { storage.saveContent(source.id, saved) } }
                }
                // Tablån hämtas om även när kanalerna inte gick att hämta.
                queueGuide(source.id, force = true)
            }
        }
    }

    private class ChannelList(val channels: List<LiveChannel>, val groups: List<String>, val epgUrl: String?)

    /** Bara kanalerna för en källa. Null om de inte gick att hämta, eller om listan kom tom. */
    private suspend fun fetchChannels(source: Source): ChannelList? {
        val secret = plainSecret(source) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                when (source.type) {
                    SourceType.M3U -> importer.importM3u(source.id, secret, userAgent) { }.let {
                        ChannelList(it.channels, it.liveGroups, it.epgUrl)
                    }
                    SourceType.XTREAM -> importer.importXtreamLive(
                        source.id,
                        XtreamCredentials(
                            baseUrl = source.displayUrl,
                            username = source.username.orEmpty(),
                            password = secret,
                            userAgent = userAgent,
                        ),
                    ).let { (channels, groups) -> ChannelList(channels, groups, null) }
                }
            }.getOrNull()?.takeIf { it.channels.isNotEmpty() }
        }
    }

    /** Som rebuildLibrary när bara kanalerna ändrats: filmerna och serierna behöver inte gås igenom igen. */
    private fun rebuildChannels() {
        val merged = Library.merge(sources.mapNotNull { contents[it.id] })
        library = merged
        if (liveGroup?.let { merged.liveCounts.containsKey(it) } == false) liveGroup = null
        rebuildGuide()
    }

    /**
     * Kanalerna kan ha fått nya platser i listan. Kanaler i Min lista följer
     * med, efter stream-id (Xtream) eller adress (M3U), i stället för att
     * hamna på kanalen som nu står på deras gamla plats.
     */
    private fun followMovedChannels(old: List<LiveChannel>, new: List<LiveChannel>) {
        if (old.isEmpty() || myList.none { it.kind == ListKind.CHANNEL }) return
        val newKeys = HashMap<String, String>(new.size * 2)
        for (channel in new) newKeys.putIfAbsent(channelIdentity(channel), channel.key)
        val moved = HashMap<String, String>()
        for (channel in old) {
            val target = newKeys[channelIdentity(channel)] ?: continue
            if (target != channel.key) moved[channel.key] = target
        }
        if (moved.isEmpty()) return
        myList = myList.map { entry ->
            if (entry.kind == ListKind.CHANNEL) moved[entry.key]?.let { entry.copy(key = it) } ?: entry else entry
        }
        val snapshot = myList
        scope.launch(Dispatchers.IO) { myListStore.save(snapshot) }
    }

    private fun channelIdentity(channel: LiveChannel): String =
        channel.streamId?.let { "s$it" } ?: channel.url ?: ("n" + channel.name)

    private fun refreshNextQueued() {
        if (importingId != null) return
        while (refreshQueue.isNotEmpty()) {
            val id = refreshQueue.removeFirst()
            val next = sources.firstOrNull { it.id == id } ?: continue
            refresh(next)
            return
        }
    }

    private fun persistSources() {
        runCatching { storage.saveSources(sources) }
    }

    private fun sealOrRemember(secret: String, sourceId: String): String? {
        val sealed = SecretBox.sealText(secret)
        if (sealed == null) {
            notice = "Windows skydd för lösenord (DPAPI) gick inte att använda. Källan fungerar tills appen stängs, men sparas utan inloggning."
            sessionSecrets[sourceId] = secret
        }
        return sealed
    }

    private suspend fun plainSecret(source: Source): String? =
        sessionSecrets[source.id] ?: withContext(Dispatchers.IO) { SecretBox.unsealText(source.secret) }

    private suspend fun credentials(sourceId: String): Pair<Source, XtreamCredentials>? {
        val source = sources.firstOrNull { it.id == sourceId } ?: return null
        val password = plainSecret(source) ?: return null
        return source to XtreamCredentials(
            baseUrl = source.displayUrl,
            username = source.username.orEmpty(),
            password = password,
            userAgent = userAgent,
        )
    }

    // --- Serier ------------------------------------------------------------------

    /** Avsnitten hämtas först nu, per serie — aldrig vid import. */
    /** Seriens sida. Som för filmer: versionen man senast tittade på, om inte [exact]. */
    fun showSeries(requested: Series, exact: Boolean = false) {
        val series = if (exact) requested else watchedVersion(requested) ?: requested
        selectedSeries = series
        episodes = EpisodesState.Loading
        episodesJob?.cancel()
        episodesJob = scope.launch {
            val account = credentials(series.sourceId)
            if (account == null) {
                episodes = EpisodesState.Failed("Inloggningen för källan saknas. Lägg till källan igen under Inställningar.")
                return@launch
            }
            val loaded: EpisodesState = withContext(Dispatchers.IO) {
                runCatching { xtream.episodes(account.second, series.seriesId) }.fold(
                    onSuccess = { list ->
                        if (list.isEmpty()) EpisodesState.Failed("Leverantören har inga avsnitt för den här serien.")
                        else EpisodesState.Loaded(list)
                    },
                    onFailure = { error -> EpisodesState.Failed(explain(error)) },
                )
            }
            if (selectedSeries?.key == series.key) episodes = loaded
        }
    }

    fun closeSeries() {
        episodesJob?.cancel()
        selectedSeries = null
    }

    // --- Filmer ----------------------------------------------------------------------

    /**
     * Filmens sida. Leverantörens information hämtas en gång per film och sparas under körningen.
     *
     * Har man börjat titta på en annan version av filmen öppnas den, så att
     * Fortsätt hamnar rätt. [exact]: just den här versionen, vald på sidan.
     */
    fun showMovie(requested: Movie, exact: Boolean = false) {
        val movie = if (exact) requested else startedVersion(requested) ?: requested
        selectedMovie = movie
        movieInfoJob?.cancel()
        if (movieInfoCache.containsKey(movie.key)) {
            movieInfo = MovieInfoState.Loaded(movieInfoCache[movie.key])
            return
        }
        movieInfo = MovieInfoState.Loading
        movieInfoJob = scope.launch {
            val account = credentials(movie.sourceId)
            if (account == null) {
                movieInfo = MovieInfoState.Failed("Inloggningen för källan saknas.")
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { xtream.vodInfo(account.second, movie.streamId) }
            }
            if (selectedMovie?.key != movie.key) return@launch
            val next: MovieInfoState = result.fold(
                onSuccess = { info ->
                    movieInfoCache[movie.key] = info
                    // Leverantören kan svara utan info; då finns inget att spara.
                    if (info != null) {
                        publishFacts(
                            mapOf(MovieFactsStore.keyOf(movie.sourceId, movie.streamId) to MovieFacts(info.genre.orEmpty(), releaseYear(info.releaseDate))),
                            save = false,
                        )
                    }
                    MovieInfoState.Loaded(info)
                },
                onFailure = { error -> MovieInfoState.Failed(explain(error)) },
            )
            movieInfo = next
        }
    }

    fun closeMovie() {
        movieInfoJob?.cancel()
        selectedMovie = null
    }

    /** Den version av filmen man senast började titta på och kan fortsätta, om någon. */
    private fun startedVersion(movie: Movie): Movie? {
        val all = versions.versionsOf(movie)
        if (all.size < 2) return null
        val byStream = all.associateBy { it.sourceId + ":" + it.streamId }
        val entry = history.firstOrNull { it.kind == WatchKind.MOVIE && it.resumable && (it.sourceId + ":" + it.itemId) in byStream } ?: return null
        return byStream[entry.sourceId + ":" + entry.itemId]
    }

    /** Den version av serien man senast tittade på, om någon. */
    private fun watchedVersion(series: Series): Series? {
        val all = versions.versionsOf(series)
        if (all.size < 2) return null
        val byId = all.associateBy { it.sourceId + ":" + it.seriesId }
        val entry = history.firstOrNull { it.kind == WatchKind.EPISODE && (it.sourceId + ":" + it.seriesId) in byId } ?: return null
        return byId[entry.sourceId + ":" + entry.seriesId]
    }

    // --- Uppspelning -------------------------------------------------------------

    /**
     * [preview]: Fotbollens lilla ruta — kanalen startar i hörnet utan ljud och
     * räknas inte som sedd förrän man förstorar den. [docked]: Live TV:s stora
     * ruta — kanalen byts i rutan, och bilden stannar där.
     */
    fun playChannel(channel: LiveChannel, queue: List<LiveChannel>, preview: Boolean = false, docked: Boolean = false) {
        val entry = WatchEntry(
            kind = WatchKind.LIVE,
            sourceId = channel.sourceId,
            itemId = channel.streamId?.toString() ?: "namn:${channel.name}",
            title = channel.name,
            subtitle = channel.group,
            image = channel.logo,
            watchedAtUtc = System.currentTimeMillis(),
        )
        val item = NowPlaying(title = channel.name, subtitle = channel.group, live = true, channel = channel, queue = queue, entry = entry, name = channel.name)
        launchPlayback(item, preview, docked) {
            channel.url ?: run {
                val streamId = channel.streamId ?: return@run null
                val (source, account) = credentials(channel.sourceId) ?: return@run null
                xtream.liveUrl(account, streamId, source.liveExtension)
            }
        }
    }

    fun playMovie(movie: Movie) {
        val earlier = history.firstOrNull { it.kind == WatchKind.MOVIE && it.sourceId == movie.sourceId && it.itemId == movie.streamId.toString() }
        val start = if (earlier?.resumable == true) earlier.positionMs else 0L
        val entry = WatchEntry(
            kind = WatchKind.MOVIE,
            sourceId = movie.sourceId,
            itemId = movie.streamId.toString(),
            title = titleOf(movie),
            subtitle = versions.labelOf(movie).ifEmpty { movie.group },
            image = movie.poster,
            watchedAtUtc = System.currentTimeMillis(),
            positionMs = start,
            durationMs = earlier?.durationMs ?: 0L,
            extension = movie.extension,
        )
        val item = NowPlaying(
            title = titleOf(movie),
            subtitle = versions.labelOf(movie).ifEmpty { movie.group },
            live = false,
            entry = entry,
            startMs = start,
            name = movie.name,
        )
        launchPlayback(item) {
            val account = credentials(movie.sourceId) ?: return@launchPlayback null
            xtream.movieUrl(account.second, movie.streamId, movie.extension)
        }
    }

    fun playEpisode(series: Series, episode: XtreamEpisode) {
        val subtitle = buildString {
            append("Säsong ").append(episode.season).append(" · Avsnitt ").append(episode.episode)
            if (episode.title.isNotBlank()) append(" · ").append(episode.title)
        }
        val earlier = history.firstOrNull { it.kind == WatchKind.EPISODE && it.sourceId == series.sourceId && it.itemId == episode.id }
        val start = if (earlier?.resumable == true) earlier.positionMs else 0L
        val entry = WatchEntry(
            kind = WatchKind.EPISODE,
            sourceId = series.sourceId,
            itemId = episode.id,
            title = titleOf(series),
            subtitle = subtitle,
            image = episode.thumbnail ?: series.cover,
            watchedAtUtc = System.currentTimeMillis(),
            positionMs = start,
            durationMs = earlier?.durationMs ?: 0L,
            seriesId = series.seriesId,
            season = episode.season,
            episode = episode.episode,
            extension = episode.containerExtension,
        )
        val item = NowPlaying(
            title = titleOf(series),
            subtitle = subtitle,
            live = false,
            entry = entry,
            startMs = start,
            name = series.name + " " + episode.title,
        )
        launchPlayback(item) {
            val account = credentials(series.sourceId) ?: return@launchPlayback null
            xtream.episodeUrl(account.second, episode.id, episode.containerExtension)
        }
        startSeriesExtras(series, episode.id)
    }

    fun retryPlayback() {
        val (item, resolve) = lastLaunch ?: return
        launchPlayback(item, resolve = resolve)
    }

    private fun launchPlayback(item: NowPlaying, preview: Boolean = false, docked: Boolean = false, resolve: suspend () -> String?) {
        // Samma sak spelas redan i hörnet (Spela på filmens sida, kanalen i listan):
        // bilden växer upp i stället för att börja om.
        val current = nowPlaying
        val identity = item.entry?.identity
        if (!preview && current != null && miniPlayer && identity != null && current.entry?.identity == identity && playerError == null) {
            expandPlayer()
            return
        }
        // Det som spelades nyss: spara hur långt man kom innan det byts.
        savePosition()
        historyJob?.cancel()
        lastLaunch = item to resolve
        nowPlaying = item
        // Nästa avsnitt och introt gäller avsnittet som spelades; ett avsnitt sätter dem igen.
        resetSeriesExtras()
        // En förhandsvisning räknas inte som sedd (se expandPlayer).
        if (!preview) item.entry?.let { recordWatch(it) }
        previewMuted = preview
        if (!preview) footballPreview = null
        // Live TV:s ruta på tv:n: en kanal man själv startat är vald; en som bara visas är det inte.
        stageChosen = if (!preview && item.live) item.channel?.key else null
        // Något nytt att titta på: spelaren växer upp igen om den låg i hörnet.
        // Fotbollens förhandsvisning börjar i stället i hörnet, och i Live TV:s
        // stora ruta byts kanalen där bilden står.
        miniPlayer = preview || (docked && miniPlayer)
        playerError = null
        trackPanelOpen = false
        playbackSession = -1L
        clearScrub()
        clearZap()
        // Tv: en kanal börjar med kanalrutan nederst, en film med tidslinjen.
        if (tvLayout && item.live) {
            zapShownAt = System.currentTimeMillis()
        } else {
            touchPlayer(force = true)
        }
        playbackJob?.cancel()
        playbackJob = scope.launch {
            val url = resolve()
            if (url == null) {
                playerError = "Adressen till strömmen kunde inte byggas. Uppdatera källan under Inställningar och försök igen."
                return@launch
            }
            val player = ensurePlayer()
            if (player == null) {
                playerError = vlcMissingMessage()
                return@launch
            }
            // Bytte användaren kanal medan VLC startade gäller den nya kanalen.
            ensureActive()
            player.setVolume(volume)
            player.setMuted(muted || previewMuted)
            player.preferredAudio = preferredAudio
            player.preferredSubtitle = preferredSubtitle
            // Windows behöver veta om bilden är HDR för att visa rätt färger; Shield sköter det själv.
            player.rangeHint = rangeHintOf(item.name)
            playbackSession = player.play(url, userAgent, live = item.live, startMs = item.startMs)
            trackPosition(item)
            WindowsIntegration.keepAwake(true)
        }
    }

    /**
     * Spelaren skapas en gång, första gången något spelas — libvlc tar en
     * sekund eller två att starta. Skapandet hör till appen, inte till den
     * uppspelning som råkade starta det: byter man kanal under tiden blir
     * det ändå bara en spelare.
     */
    private suspend fun ensurePlayer(): VideoPlayer? {
        videoPlayer?.let { return it }
        val creation = playerCreation ?: scope.async {
            val created = withContext(Dispatchers.IO) {
                VlcRuntime.factory()?.let { factory -> runCatching { VideoPlayer(factory) }.getOrNull() }
            }
            videoPlayer = created
            playerCreation = null
            created
        }.also { playerCreation = it }
        return creation.await()
    }

    private fun vlcMissingMessage(): String = buildString {
        append("Uppspelningen använder VLC:s motor, och den gick inte att starta. ")
        append("Den ska följa med appen — gör den inte det, installera VLC (64-bit) från videolan.org och starta om appen.")
        VlcRuntime.failure?.let { append("\n\nTekniskt fel: ").append(it) }
    }

    fun closePlayer() {
        savePosition()
        historyJob?.cancel()
        playbackJob?.cancel()
        videoPlayer?.stop()
        // Rutan som visades behålls mellan fönster; nu behövs den inte längre.
        videoPlayer?.let { player ->
            player.surfaceState?.close()
            player.surfaceState = null
        }
        // Stängde man fotbollens ruta själv startar den inte igen förrän man lämnat Fotboll.
        if (footballPreview != null) footballPreviewDismissed = true
        nowPlaying = null
        playerError = null
        fullscreen = false
        miniPlayer = false
        footballPreview = null
        previewMuted = false
        stageChosen = null
        trackPanelOpen = false
        playerBoxFocused = false
        playerControlsFocused = false
        playerTimelineFocused = false
        channelListOpen = false
        clearScrub()
        clearZap()
        zapShownAt = 0L
        resetSeriesExtras()
        WindowsIntegration.keepAwake(false)
        saveSettings()
    }

    /** Valt ljudspår. Språket kommer ihåg till nästa kanal och film. */
    fun selectAudio(track: VideoPlayer.Track) {
        videoPlayer?.setAudioTrack(track.id)
        val language = track.language ?: return
        preferredAudio = language
        videoPlayer?.preferredAudio = language
        saveSettings()
    }

    /** Vald undertext. "Av" kommer också ihåg — då stängs de av i nästa ström med. */
    fun selectSubtitle(track: VideoPlayer.Track) {
        videoPlayer?.setSubtitleTrack(track.id)
        val preference = if (track.id < 0) "" else track.language ?: return
        preferredSubtitle = preference
        videoPlayer?.preferredSubtitle = preference
        saveSettings()
    }

    fun forgetLanguages() {
        preferredAudio = null
        preferredSubtitle = null
        videoPlayer?.preferredAudio = null
        videoPlayer?.preferredSubtitle = null
        saveSettings()
    }

    fun updateSharpScaling(sharp: Boolean) {
        sharpScaling = sharp
        saveSettings()
    }

    /** Kanalbyte i den lista kanalen startades från — kategorin och sökningen följer med. */
    fun switchChannel(delta: Int) {
        val current = nowPlaying ?: return
        val channel = current.channel ?: return
        val queue = current.queue
        if (queue.size < 2) return
        val index = queue.indexOfFirst { it.key == channel.key }.coerceAtLeast(0)
        playChannel(queue[(index + delta).mod(queue.size)], queue)
    }

    fun togglePause() {
        videoPlayer?.togglePause()
    }

    fun setVolumeLevel(level: Int) {
        volume = level.coerceIn(0, 100)
        if (muted && volume > 0) {
            muted = false
            videoPlayer?.setMuted(false)
        }
        videoPlayer?.setVolume(volume)
    }

    fun toggleMute() {
        muted = !muted
        previewMuted = false
        videoPlayer?.setMuted(muted)
    }

    /** Helskärm finns bara för spelaren. */
    fun toggleFullscreen() {
        fullscreen = nowPlaying != null && !fullscreen
        if (fullscreen) miniPlayer = false
    }

    /** Bilden glider ner i hörnet och spelar vidare; resten av appen går att använda. */
    fun minimizePlayer() {
        if (nowPlaying == null) return
        fullscreen = false
        trackPanelOpen = false
        playerControlsFocused = false
        playerTimelineFocused = false
        channelListOpen = false
        clearScrub()
        clearZap()
        zapShownAt = 0L
        miniPlayer = true
        miniSince = System.currentTimeMillis()
    }

    /** När bilden senast krympte till hörnet: en stund efteråt visar rutan hur man förstorar den. */
    var miniSince by mutableStateOf(0L)
        private set

    fun expandPlayer() {
        miniPlayer = false
        // Fotbollens förhandsvisning, och kanalen som bara visades i Live TV:s ruta,
        // blir en vanlig kanal: ljudet på, och sedd.
        val wasPreview = previewMuted || footballPreview != null
        if (previewMuted) {
            previewMuted = false
            videoPlayer?.setMuted(muted)
        }
        footballPreview = null
        if (wasPreview) nowPlaying?.entry?.let { recordWatch(it.copy(watchedAtUtc = System.currentTimeMillis())) }
        nowPlaying?.takeIf { it.live }?.channel?.let { stageChosen = it.key }
        touchPlayer(force = true)
    }

    // --- Live TV:s stora ruta på tv:n -----------------------------------------------

    /**
     * Live TV på tv:n: kanalen man valt med OK och som spelas i den stora
     * rutan med ljud. Null: rutan visar bara kanalen man står på, utan ljud.
     */
    var stageChosen by mutableStateOf<String?>(null)
        private set

    /** Kanalen i den stora rutan (den lilla bilden), om det som spelas där är en kanal. */
    fun stagedChannelKey(): String? {
        val playing = nowPlaying ?: return null
        return if (playing.live && miniPlayer && footballPreview == null) playing.channel?.key else null
    }

    /**
     * Live TV på tv:n: kanalen man står på visas i den stora rutan, utan ljud,
     * tills man väljer den med OK. Visas redan den kanalen händer inget, och en
     * film eller ett avsnitt i den lilla bilden får vara i fred.
     */
    fun previewInStage(channel: LiveChannel, queue: List<LiveChannel>) {
        val playing = nowPlaying
        if (playing != null && (!playing.live || !miniPlayer || footballPreview != null)) return
        if (stagedChannelKey() == channel.key) return
        playChannel(channel, queue, preview = true)
    }

    /**
     * OK i Live TV på tv:n. Första gången blir kanalen vald och stannar i
     * rutan — ljudet på, och den räknas som sedd. Andra gången: helskärm.
     */
    fun chooseInStage(channel: LiveChannel, queue: List<LiveChannel>) {
        val staged = stagedChannelKey() == channel.key
        if (staged && stageChosen == channel.key) {
            expandPlayer()
            return
        }
        // Rutan visade en annan kanal (eller inget, eller en film): den här startar i rutan.
        if (!staged) playChannel(channel, queue, preview = true)
        if (previewMuted) {
            previewMuted = false
            videoPlayer?.setMuted(muted)
        }
        nowPlaying?.entry?.let { recordWatch(it.copy(watchedAtUtc = System.currentTimeMillis())) }
        stageChosen = channel.key
    }

    /** Live TV på tv:n lämnas: en kanal som bara visades i rutan, utan att man valt den, stängs. */
    fun leaveStage() {
        val key = stagedChannelKey() ?: return
        if (previewMuted && stageChosen != key) closePlayer()
    }

    // --- Fotbollens lilla ruta ----------------------------------------------------

    /** Fotboll: matchen som visas i den lilla rutan i hörnet, utan ljud. Null: ingen. */
    var footballPreview by mutableStateOf<String?>(null)
        private set

    /** Förhandsvisningen spelar utan ljud tills man förstorar den. */
    private var previewMuted = false

    /**
     * Fotboll: visar en match som pågår i den lilla rutan i hörnet, utan ljud.
     * Bara om inget annat spelas — en film eller kanal man själv startat får
     * vara i fred. Samma kanal som redan visas startas inte om.
     */
    fun previewMatch(match: FootballMatch) {
        val channels = matchChannels[match.id].orEmpty()
        val channel = channels.firstOrNull() ?: return
        val playing = nowPlaying
        if (playing != null && footballPreview == null) return
        if (playing != null && !miniPlayer) return
        if (playing?.channel?.key == channel.key) {
            footballPreview = match.id
            return
        }
        footballPreview = match.id
        miniSince = System.currentTimeMillis()
        playChannel(channel, channels, preview = true)
    }

    /** Lämnar man Fotboll stängs rutan — om man inte förstorat den och gjort den till sin. */
    fun stopFootballPreview() {
        if (footballPreview != null && nowPlaying != null && miniPlayer) closePlayer()
        footballPreview = null
        footballPreviewDismissed = false
    }

    /** Fotbollens ruta stängdes av användaren under det här besöket i Fotboll. */
    var footballPreviewDismissed by mutableStateOf(false)
        private set

    fun touchPlayer(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastActivity > 200) lastActivity = now
    }

    /**
     * Tangenterna i spelaren. Utanför spelaren tas bara F11 om hand, så att
     * sökfälten får sina tangenter i fred.
     */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val playing = nowPlaying ?: return false
        if (event.key == Key.F11) {
            toggleFullscreen()
            return true
        }
        // Minispelaren: tangenterna hör till det man bläddrar i, t.ex. sökfälten.
        if (miniPlayer) return false
        touchPlayer(force = true)
        return when (event.key) {
            Key.Spacebar, Key.K -> { togglePause(); true }
            Key.Escape -> {
                when {
                    trackPanelOpen -> trackPanelOpen = false
                    dismissPlayerPrompt() -> Unit
                    fullscreen -> fullscreen = false
                    else -> minimizePlayer()
                }
                true
            }
            Key.Enter, Key.NumPadEnter -> acceptPlayerPrompt()
            Key.Backspace -> { closePlayer(); true }
            Key.F -> { toggleFullscreen(); true }
            Key.M -> { toggleMute(); true }
            Key.DirectionLeft -> { if (!playing.live) seekBy(-10_000); true }
            Key.DirectionRight -> { if (!playing.live) seekBy(10_000); true }
            Key.DirectionUp -> { if (playing.live) switchChannel(-1) else setVolumeLevel(volume + 5); true }
            Key.DirectionDown -> { if (playing.live) switchChannel(1) else setVolumeLevel(volume - 5); true }
            Key.PageUp -> { switchChannel(-1); true }
            Key.PageDown -> { switchChannel(1); true }
            else -> false
        }
    }

    // --- Tablå ---------------------------------------------------------------------

    /** Hämtar om tablån för alla källor, från knappen i Inställningar eller tablåskärmen. */
    fun refreshGuides() {
        sources.forEach { queueGuide(it.id, force = true) }
    }

    private suspend fun loadGuides() {
        val ids = sources.map { it.id }
        val keepAfter = System.currentTimeMillis() - 4 * HOUR
        val loaded = withContext(Dispatchers.IO) {
            ids.mapNotNull { id -> guideStore.load(id, keepAfter)?.let { id to it } }
        }
        loaded.forEach { (id, guide) ->
            guides[id] = guide
            guideStatus[id] = describeGuide(guide)
        }
        rebuildGuide()
        // Tablåerna hämtas om vid starten, efter kanalerna (refreshChannelsAndGuide).
    }

    /** Tablåer som saknas eller är äldre än tolv timmar. Misslyckade försök väntar en timme. */
    private fun queueStaleGuides() {
        val now = System.currentTimeMillis()
        for (source in sources) {
            if (source.id !in contents) continue
            val fetched = guides[source.id]?.fetchedAt ?: 0L
            val attempted = guideAttempts[source.id] ?: 0L
            if (now - fetched > 12 * HOUR && now - attempted > HOUR) queueGuide(source.id, force = false)
        }
    }

    private fun queueGuide(sourceId: String, force: Boolean) {
        if (force) guideAttempts.remove(sourceId)
        if (sourceId !in guideQueue) guideQueue.addLast(sourceId)
        processGuideQueue()
    }

    private fun processGuideQueue() {
        if (guideJob?.isActive == true) return
        while (guideQueue.isNotEmpty()) {
            val id = guideQueue.removeFirst()
            val source = sources.firstOrNull { it.id == id } ?: continue
            val channels = contents[id]?.channels ?: continue
            guideLoading = true
            guideJob = scope.launch {
                fetchGuide(source, channels)
                guideJob = null
                processGuideQueue()
            }
            return
        }
        guideLoading = false
    }

    private suspend fun fetchGuide(source: Source, channels: List<LiveChannel>) {
        guideAttempts[source.id] = System.currentTimeMillis()
        val urls = guideUrls(source)
        if (urls.isEmpty()) {
            guideStatus[source.id] = when (source.type) {
                SourceType.M3U -> "Spellistan anger ingen tablå."
                SourceType.XTREAM -> "Inloggningen för källan kunde inte läsas."
            }
            return
        }
        guideStatus[source.id] = "Hämtar tablån…"
        val now = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                var merged: SourceGuide? = null
                var lastError: Throwable? = null
                for (url in urls) {
                    try {
                        val part = downloader.openStream(url, userAgent).use { stream ->
                            XmltvParser.parse(stream, channels, now - 4 * HOUR, now + 48 * HOUR)
                        }
                        merged = merged?.let { SourceGuide.merge(it, part) } ?: part
                    } catch (e: IOException) {
                        // XML-fel kommer också hit: läsaren gör om dem till IOException.
                        lastError = e
                    }
                }
                merged ?: throw (lastError ?: IOException("Tablån var tom."))
            }
        }
        result.onSuccess { guide ->
            guides[source.id] = guide
            guideStatus[source.id] = describeGuide(guide)
            rebuildGuide()
            withContext(Dispatchers.IO) { guideStore.save(source.id, guide) }
        }.onFailure { error ->
            guideStatus[source.id] = "Tablån gick inte att hämta. " + explain(error)
        }
    }

    private suspend fun guideUrls(source: Source): List<String> = when (source.type) {
        SourceType.XTREAM -> credentials(source.id)?.let { listOf(xtream.xmltvUrl(it.second)) }.orEmpty()
        SourceType.M3U -> contents[source.id]?.epgUrl
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .orEmpty()
    }

    /** Matchningen av tusentals kanalnamn tar en stund; den görs vid sidan av. */
    private fun rebuildGuide() {
        val sourcesNow = sources
        val contentsNow = HashMap(contents)
        val guidesNow = HashMap(guides)
        guideBuild?.cancel()
        guideBuild = scope.launch {
            guide = withContext(Dispatchers.Default) { GuideIndex.build(sourcesNow, contentsNow, guidesNow) }
        }
    }

    private fun describeGuide(guide: SourceGuide): String =
        "Tablå för " + String.format(SWEDISH, "%,d", guide.programmes.size) + " kanaler, hämtad " +
            GUIDE_STAMP.format(Instant.ofEpochMilli(guide.fetchedAt))

    // --- Livscykel --------------------------------------------------------------

    private fun currentSettings() = Settings(
        volume = volume,
        sharpScaling = sharpScaling,
        movieSort = movieSort.name,
        seriesSort = seriesSort.name,
        theme = theme.name,
        wallStyle = wallStyle.name,
        tmdbKey = tmdbKey?.let { SecretBox.sealText(it) },
        backgrounds = backgrounds,
        audioLanguage = preferredAudio,
        subtitleLanguage = preferredSubtitle,
        lastVisitUtc = lastVisitUtc,
        hideForeign = hideForeign,
        hiddenGroups = hiddenGroups.toList(),
        disneyChoices = disneyChoices,
    )

    private fun saveSettings() {
        val snapshot = currentSettings()
        scope.launch(Dispatchers.IO) { storage.saveSettings(snapshot) }
    }

    // --- Historik ---------------------------------------------------------------

    /** Spela något ur historiken igen. Filmer och avsnitt fortsätter där man slutade. */
    fun playFromHistory(entry: WatchEntry) {
        historyMessage = null
        when (entry.kind) {
            WatchKind.LIVE -> {
                val channel = library.channels.firstOrNull {
                    it.sourceId == entry.sourceId && (it.streamId?.toString() ?: "namn:${it.name}") == entry.itemId
                }
                if (channel == null) {
                    historyMessage = "${entry.title} finns inte längre i källan."
                    return
                }
                playChannel(channel, library.channels.filter { it.sourceId == channel.sourceId && it.group == channel.group })
            }
            WatchKind.MOVIE -> {
                val movie = library.movies.firstOrNull { it.sourceId == entry.sourceId && it.streamId.toString() == entry.itemId }
                if (movie == null) {
                    historyMessage = "${entry.title} finns inte längre i källan."
                    return
                }
                playMovie(movie)
            }
            WatchKind.EPISODE -> {
                val start = if (entry.resumable) entry.positionMs else 0L
                val series = library.series.firstOrNull { it.sourceId == entry.sourceId && it.seriesId == entry.seriesId }
                val item = NowPlaying(
                    title = entry.title,
                    subtitle = entry.subtitle,
                    live = false,
                    entry = entry.copy(watchedAtUtc = System.currentTimeMillis(), upNext = false),
                    startMs = start,
                    name = series?.name,
                )
                launchPlayback(item) {
                    val account = credentials(entry.sourceId) ?: return@launchPlayback null
                    xtream.episodeUrl(account.second, entry.itemId, entry.extension ?: "mp4")
                }
                series?.let { startSeriesExtras(it, entry.itemId) }
            }
        }
    }

    fun removeFromHistory(entry: WatchEntry) {
        history = history.filterNot { it.identity == entry.identity }
        persistHistory()
    }

    fun clearHistory() {
        history = emptyList()
        historyMessage = null
        persistHistory()
    }

    private fun recordWatch(entry: WatchEntry) {
        history = (listOf(entry) + history.filterNot { it.identity == entry.identity }).take(MAX_HISTORY)
        persistHistory()
    }

    /** Var tionde sekund under film och avsnitt: hur långt man kommit. */
    private fun trackPosition(item: NowPlaying) {
        historyJob?.cancel()
        val entry = item.entry ?: return
        if (entry.kind == WatchKind.LIVE) return
        historyJob = scope.launch {
            while (true) {
                delay(10_000)
                savePosition()
            }
        }
    }

    private fun savePosition() {
        val entry = nowPlaying?.entry ?: return
        if (entry.kind == WatchKind.LIVE) return
        val status = videoPlayer?.status?.value ?: return
        if (status.session != playbackSession || status.timeMs <= 0L) return
        history = history.map {
            if (it.identity == entry.identity) it.copy(positionMs = status.timeMs, durationMs = maxOf(status.lengthMs, it.durationMs)) else it
        }
        persistHistory()
        // Nästan slut på ett avsnitt: nästa avsnitt föreslås i Fortsätt titta.
        if (entry.kind == WatchKind.EPISODE && status.lengthMs > 0 && status.timeMs >= status.lengthMs * 0.95) queueNextEpisode(entry)
    }

    private fun persistHistory() {
        historyStore.save(history)
    }

    /** Sparar positionen direkt, till exempel när appen läggs undan. */
    fun saveProgressNow() = savePosition()

    /** Pausar när appen läggs undan, så att ljudet inte fortsätter i bakgrunden. */
    fun pausePlayback() {
        val player = videoPlayer ?: return
        if (player.status.value.phase == VideoPlayer.Phase.PLAYING) player.togglePause()
    }

    // --- Min lista -------------------------------------------------------------------

    fun inMyList(key: String): Boolean = myList.any { it.key == key }

    fun inMyList(movie: Movie): Boolean = versions.versionsOf(movie).any { inMyList(it.key) }

    fun inMyList(series: Series): Boolean = versions.versionsOf(series).any { inMyList(it.key) }

    /** Sparar filmen, eller tar bort alla dess versioner om någon redan är sparad. */
    fun toggleMyList(movie: Movie) {
        val keys = versions.versionsOf(movie).map { it.key }.toSet()
        if (myList.any { it.key in keys }) removeFromMyList(keys) else toggleMyList(ListKind.MOVIE, movie.key)
    }

    fun toggleMyList(series: Series) {
        val keys = versions.versionsOf(series).map { it.key }.toSet()
        if (myList.any { it.key in keys }) removeFromMyList(keys) else toggleMyList(ListKind.SERIES, series.key)
    }

    private fun removeFromMyList(keys: Set<String>) {
        myList = myList.filterNot { it.key in keys }
        val snapshot = myList
        scope.launch(Dispatchers.IO) { myListStore.save(snapshot) }
    }

    fun toggleMyList(kind: ListKind, key: String) {
        myList = if (inMyList(key)) {
            myList.filterNot { it.key == key }
        } else {
            listOf(ListEntry(kind, key, System.currentTimeMillis())) + myList
        }
        val snapshot = myList
        scope.launch(Dispatchers.IO) { myListStore.save(snapshot) }
    }

    // --- Nästa avsnitt -----------------------------------------------------------------

    /**
     * Ett avsnitt är nästan slut: nästa avsnitt hämtas ur seriens lista och
     * läggs först i Fortsätt titta, redo att startas direkt.
     */
    private fun queueNextEpisode(entry: WatchEntry) {
        if (entry.kind != WatchKind.EPISODE || !nextQueued.add(entry.identity)) return
        val seriesId = entry.seriesId ?: return
        val season = entry.season ?: return
        val number = entry.episode ?: return
        val series = library.series.firstOrNull { it.sourceId == entry.sourceId && it.seriesId == seriesId } ?: return
        scope.launch {
            val account = credentials(entry.sourceId) ?: return@launch
            val list = withContext(Dispatchers.IO) {
                runCatching { xtream.episodes(account.second, seriesId) }.getOrNull()
            } ?: return@launch
            val ordered = list.sortedWith(compareBy({ it.season }, { it.episode }))
            val index = ordered.indexOfFirst { it.season == season && it.episode == number }
            val next = (if (index >= 0) ordered.getOrNull(index + 1) else null)
                ?: ordered.firstOrNull { it.season > season || (it.season == season && it.episode > number) }
                ?: return@launch
            // Redan påbörjat: det står redan i Fortsätt titta med sin position.
            val earlier = history.firstOrNull { it.kind == WatchKind.EPISODE && it.sourceId == entry.sourceId && it.itemId == next.id }
            if (earlier?.resumable == true) return@launch
            val subtitle = buildString {
                append("Säsong ").append(next.season).append(" · Avsnitt ").append(next.episode)
                if (next.title.isNotBlank()) append(" · ").append(next.title)
            }
            recordWatch(
                WatchEntry(
                    kind = WatchKind.EPISODE,
                    sourceId = entry.sourceId,
                    itemId = next.id,
                    title = series.name,
                    subtitle = subtitle,
                    image = next.thumbnail ?: series.cover,
                    watchedAtUtc = System.currentTimeMillis(),
                    positionMs = 0L,
                    durationMs = (next.durationSeconds ?: 0) * 1_000L,
                    seriesId = seriesId,
                    season = next.season,
                    episode = next.episode,
                    extension = next.containerExtension,
                    upNext = true,
                ),
            )
        }
    }

    // --- Genre och år per film ---------------------------------------------------

    private fun restartFactsScan() {
        factsJob?.cancel()
        factsJob = null
        startFactsScan()
    }

    /**
     * Hämtar varje films infosida hos leverantören, en i taget och i lugn takt
     * — ungefär två i sekunden — så att servern inte ser det som ett angrepp.
     * Högst betyg först, så att topplistorna i Genrer blir användbara tidigt.
     * Det som hämtats sparas; nästa gång tas bara nya filmer.
     */
    private fun startFactsScan() {
        if (!factsLoaded || factsJob?.isActive == true) return
        // Med TMDB kommer genrerna därifrån, och mycket fortare. Leverantörens
        // infosidor behövs då bara för det TMDB inte hittade — och först när
        // TMDB är klart; hämtningen därifrån startar den här igen när den är färdig.
        if (tmdbConnected && (!tmdbLoaded || tmdbJob?.isActive == true)) return
        val xtreamSources = sources.filter { it.type == SourceType.XTREAM }.map { it.id }.toSet()
        val movies = library.movies
        val useTmdb = tmdbConnected
        val matched = tmdbFacts
        factsJob = scope.launch {
            afterStartup()
            val wanted = withContext(Dispatchers.Default) {
                movies
                    .filter { it.sourceId in xtreamSources }
                    .filter { movie ->
                        !useTmdb || matched[
                            TitleCleaner.searchTitle(movie.name).let { (title, year) -> TmdbStore.keyOf("movie", title, year) }
                        ]?.found != true
                    }
                    .distinctBy { it.sourceId to it.streamId }
                    .sortedWith(compareByDescending<Movie> { it.rating ?: -1.0 }.thenByDescending { it.addedUtc })
            }
            if (wanted.isEmpty()) {
                factsProgress = null
                return@launch
            }
            val known = movieFacts
            val missing = wanted.filter { MovieFactsStore.keyOf(it.sourceId, it.streamId) !in known }
            var done = wanted.size - missing.size
            factsProgress = done to wanted.size
            val accounts = HashMap<String, XtreamCredentials?>()
            val fresh = HashMap<String, MovieFacts>()
            var failures = 0
            var batches = 0
            var lastPublish = System.currentTimeMillis()
            for (movie in missing) {
                restWhileWatching()
                val account = accounts.getOrPut(movie.sourceId) { credentials(movie.sourceId)?.second } ?: continue
                val result = withContext(Dispatchers.IO) {
                    runCatching { xtream.vodInfo(account, movie.streamId) }
                }
                // Fel är när anropet misslyckas. En film utan info är inget fel:
                // den sparas som tom, så att den inte hämtas om och om igen.
                if (result.isFailure) {
                    failures++
                    // Svarar servern inte: vänta längre, och sluta för den här gången
                    // efter många fel i rad. Nästa start fortsätter där det slutade.
                    if (failures >= 20) break
                    delay(if (failures >= 5) 30_000L else 3_000L)
                    continue
                }
                failures = 0
                val info = result.getOrNull()
                fresh[MovieFactsStore.keyOf(movie.sourceId, movie.streamId)] =
                    MovieFacts(info?.genre.orEmpty(), releaseYear(info?.releaseDate))
                done++
                // Skärmen uppdateras sällan och i stora klumpar — var tjugonde
                // sekund — och inte mitt i bläddrandet, så att listorna inte
                // räknas om medan man trycker.
                if (timeToPublish(fresh.size, lastPublish)) {
                    batches++
                    lastPublish = System.currentTimeMillis()
                    publishFacts(HashMap(fresh), save = batches % 3 == 0)
                    fresh.clear()
                    factsProgress = done to wanted.size
                }
                delay(FACTS_PACE_MS)
            }
            publishFacts(fresh, save = true)
            factsProgress = done to wanted.size
        }
    }

    /**
     * Medan en film eller kanal fyller skärmen väntar hämtningarna: varje ny
     * omgång räknar om listor och sparar filer, och det ska aldrig ta kraft
     * från bilden. De fortsätter när spelaren stängs eller krymps till hörnet.
     */
    /**
     * Senaste trycket på fjärrkontrollen (Shield sätter den). Det som hittas i
     * bakgrunden visas inte medan man bläddrar: att listorna räknas om mitt i
     * ett tryck känns som att appen hackar.
     */
    @Volatile
    var lastInputAt = 0L
        private set

    fun noteInput() {
        lastInputAt = System.currentTimeMillis()
    }

    /** Dags att visa det som hittats: en stor klump, eller en stund sedan sist — men inte mitt i bläddrandet. */
    private fun timeToPublish(waiting: Int, lastPublish: Long): Boolean {
        if (waiting == 0) return false
        val now = System.currentTimeMillis()
        val due = waiting >= PUBLISH_BATCH || now - lastPublish >= PUBLISH_EVERY_MS
        if (!due) return false
        // Väntar det väldigt mycket visas det ändå, så att inget dröjer för länge.
        return now - lastInputAt >= INPUT_QUIET_MS || waiting >= PUBLISH_BATCH * 4
    }

    private suspend fun restWhileWatching() {
        while (watching) delay(WATCH_REST_MS)
    }

    private fun publishFacts(fresh: Map<String, MovieFacts>, save: Boolean) {
        if (fresh.isEmpty()) return
        movieFacts = movieFacts + fresh
        if (save) {
            val snapshot = movieFacts
            scope.launch(Dispatchers.IO) { factsSaving.withLock { factsStore.save(snapshot) } }
        }
    }

    private fun releaseYear(date: String?): Int? = date?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1880..2100 }

    private fun hasOwnArt(url: String?): Boolean = url?.trim()?.startsWith("http", ignoreCase = true) == true

    // --- TMDB ---------------------------------------------------------------------

    fun tmdbFor(movie: Movie): TmdbFacts? {
        if (tmdbFacts.isEmpty()) return null
        val key = tmdbKeys.getOrPut(movie.key) {
            TitleCleaner.searchTitle(movie.name).let { (title, year) -> TmdbStore.keyOf("movie", title, year) }
        }
        return tmdbFacts[key]?.takeIf { it.found }
    }

    fun tmdbFor(series: Series): TmdbFacts? {
        if (tmdbFacts.isEmpty()) return null
        val key = tmdbKeys.getOrPut(series.key) {
            TitleCleaner.searchTitle(series.name).let { (title, year) -> TmdbStore.keyOf("tv", title, year ?: series.year?.toIntOrNull()) }
        }
        return tmdbFacts[key]?.takeIf { it.found }
    }

    /**
     * Betyget att rangordna efter: TMDB:s, vägt med antal röster som IMDb:s
     * topplista. Utan TMDB leverantörens, vägt som om få hade röstat.
     */
    fun scoreOf(movie: Movie): Double? =
        tmdbFor(movie)?.let { weightedScore(it.rating, it.votes) } ?: weightedScore(movie.rating, PROVIDER_VOTES)

    fun scoreOf(series: Series): Double? =
        tmdbFor(series)?.let { weightedScore(it.rating, it.votes) } ?: weightedScore(series.rating, PROVIDER_VOTES)

    fun connectTmdb(input: String) {
        val key = input.trim()
        if (key.isEmpty()) {
            tmdbStatus = "Klistra in nyckeln först."
            return
        }
        tmdbStatus = "Kontrollerar nyckeln…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { TmdbClient(key).check() } }
            result.onSuccess {
                tmdbKey = key
                tmdbConnected = true
                tmdbStatus = "Nyckeln fungerar. Hämtningen har börjat."
                saveSettings()
                restartTmdbScan()
                refreshTrending(force = true)
            }.onFailure { error ->
                tmdbStatus = if (error is TmdbKeyException) {
                    "TMDB godkände inte nyckeln. Kontrollera att du kopierade API-nyckeln (32 tecken)."
                } else {
                    "TMDB gick inte att nå: ${explain(error)}"
                }
            }
        }
    }

    fun disconnectTmdb() {
        tmdbJob?.cancel()
        tmdbJob = null
        tmdbKey = null
        tmdbConnected = false
        tmdbProgress = null
        tmdbStatus = null
        saveSettings()
    }

    private fun restartTmdbScan() {
        tmdbJob?.cancel()
        tmdbJob = null
        startTmdbScan()
    }

    /**
     * Slår upp filmer och serier hos TMDB i bakgrunden, bäst betyg först och
     * några i sekunden. Samma film i flera kategorier slås upp en gång. Det
     * som hämtats sparas; nästa gång tas bara nya titlar.
     */
    private fun startTmdbScan() {
        val key = tmdbKey ?: return
        if (!tmdbLoaded || tmdbJob?.isActive == true) return
        val movies = library.movies
        val series = library.series
        tmdbJob = scope.launch {
            afterStartup()
            val wanted: List<Triple<String, String, Int?>> = withContext(Dispatchers.Default) {
                // Titlar utan eget omslag först: för dem är TMDB enda chansen till en bild.
                val movieTitles = movies
                    .sortedWith(compareBy<Movie> { if (hasOwnArt(it.poster)) 1 else 0 }.thenByDescending { it.rating ?: -1.0 })
                    .map { m -> TitleCleaner.searchTitle(m.name).let { (title, year) -> Triple("movie", title, year) } }
                val seriesTitles = series
                    .sortedWith(compareBy<Series> { if (hasOwnArt(it.cover)) 1 else 0 }.thenByDescending { it.rating ?: -1.0 })
                    .map { s -> TitleCleaner.searchTitle(s.name).let { (title, year) -> Triple("tv", title, year ?: s.year?.toIntOrNull()) } }
                (movieTitles + seriesTitles)
                    .filter { it.second.isNotBlank() }
                    .distinctBy { TmdbStore.keyOf(it.first, it.second, it.third) }
            }
            if (wanted.isEmpty()) {
                tmdbProgress = null
                return@launch
            }
            val client = TmdbClient(key)
            val genres = withContext(Dispatchers.IO) {
                runCatching { mapOf("movie" to client.genres("movie"), "tv" to client.genres("tv")) }
            }.getOrElse { error ->
                tmdbStatus = if (error is TmdbKeyException) {
                    "TMDB godkände inte nyckeln. Ta bort den och lägg in den igen."
                } else {
                    "TMDB gick inte att nå just nu. Appen försöker igen nästa gång den startar."
                }
                // Utan TMDB får leverantörens infosidor sköta genrerna den här gången.
                tmdbJob = null
                restartFactsScan()
                return@launch
            }
            val known = tmdbFacts
            // Nya titlar först. Sedan de som hittades med en äldre sökning: de kontrolleras en gång
            // till, och ger den nya sökningen ingenting behålls det gamla svaret, så inget går förlorat.
            val (missing, outdated) = withContext(Dispatchers.Default) {
                val keyed = wanted.map { it to TmdbStore.keyOf(it.first, it.second, it.third) }
                val unseen = keyed.filter { (_, key) -> key !in known }.map { it.first }
                val older = keyed.filter { (_, key) -> known[key]?.let { f -> f.found && f.matcher < TmdbClient.MATCHER } == true }.map { it.first }
                unseen to older
            }
            var done = wanted.size - missing.size - outdated.size
            tmdbProgress = done to wanted.size
            val fresh = HashMap<String, TmdbFacts>()
            var failures = 0
            var stopped = false
            var lastPublish = System.currentTimeMillis()
            // Flera uppslag samtidigt. Kön, räknarna och resultaten hanteras på
            // UI-tråden, så inget behöver låsas; bara själva anropet går på nätet.
            val queue = (missing + outdated).iterator()
            coroutineScope {
                repeat(TMDB_WORKERS) {
                    launch {
                        while (!stopped && queue.hasNext()) {
                            restWhileWatching()
                            // Under pausen kan de andra ha tagit det sista.
                            if (stopped || !queue.hasNext()) break
                            val (kind, title, year) = queue.next()
                            val earlier = known[TmdbStore.keyOf(kind, title, year)]
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    // Hittad med förra versionen: bara omslaget väljs om, svenska först.
                                    if (earlier != null && earlier.found && earlier.matcher == TmdbClient.BEFORE_POSTER_LANGUAGE) {
                                        client.withPreferredPoster(kind, earlier)
                                    } else {
                                        client.search(kind, title, year, genres[kind].orEmpty())
                                    }
                                }
                            }
                            val facts = result.getOrNull()
                            if (facts == null) {
                                val error = result.exceptionOrNull()
                                if (error is TmdbKeyException) {
                                    tmdbStatus = "TMDB godkände inte nyckeln längre. Ta bort den och lägg in den igen."
                                    stopped = true
                                    break
                                }
                                failures++
                                if (failures >= 30) {
                                    stopped = true
                                    break
                                }
                                delay(if (error is TmdbBusyException) 10_000L else 3_000L)
                                continue
                            }
                            failures = 0
                            val factsKey = TmdbStore.keyOf(kind, title, year)
                            val before = known[factsKey]
                            fresh[factsKey] = if (!facts.found && before != null && before.found) {
                                before.copy(matcher = TmdbClient.MATCHER, sure = false)
                            } else {
                                facts
                            }
                            done++
                            // Sällan och i stora klumpar, och inte mitt i bläddrandet, så att
                            // listorna inte räknas om medan man trycker.
                            if (timeToPublish(fresh.size, lastPublish)) {
                                lastPublish = System.currentTimeMillis()
                                // Sparas varje gång: det som hittats finns kvar även om appen stängs mitt i.
                                publishTmdb(HashMap(fresh), save = true)
                                fresh.clear()
                                tmdbProgress = done to wanted.size
                            }
                        }
                    }
                }
            }
            publishTmdb(fresh, save = true)
            tmdbProgress = done to wanted.size
            // Det TMDB inte hittade: leverantörens egna infosidor får fylla i.
            tmdbJob = null
            restartFactsScan()
        }
    }

    private fun publishTmdb(fresh: Map<String, TmdbFacts>, save: Boolean) {
        if (fresh.isEmpty()) return
        tmdbFacts = tmdbFacts + fresh
        scheduleVersions(urgent = false)
        if (save) {
            val snapshot = tmdbFacts
            scope.launch(Dispatchers.IO) { tmdbSaving.withLock { tmdbStore.save(snapshot) } }
        }
    }

    // --- Egna bakgrundsbilder ------------------------------------------------------

    /**
     * Kopierar valda bilder till appens egen mapp, så att de finns kvar även om
     * originalen flyttas. Högst tre; en bild som inte går att visa sparas inte.
     */
    fun addBackgrounds(files: List<File>) {
        backgroundMessage = null
        if (files.isEmpty()) return
        val room = MAX_BACKGROUNDS - backgrounds.size
        if (room <= 0) {
            backgroundMessage = "Det får plats tre bilder. Ta bort en först."
            return
        }
        val chosen = files.take(room)
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                val folder = File(AppDirs.cache, "bakgrunder").also { it.mkdirs() }
                chosen.mapNotNull { file ->
                    runCatching {
                        val bytes = file.readBytes()
                        Images.validate(bytes)
                        val safeName = file.name.filter { it.isLetterOrDigit() || it == '.' || it == '-' }
                        val target = File(folder, "bild-${System.nanoTime()}-$safeName")
                        target.writeBytes(bytes)
                        target.absolutePath
                    }.getOrNull()
                }
            }
            backgroundMessage = when {
                added.size < chosen.size -> "Någon av bilderna gick inte att läsa. JPG, PNG och WebP fungerar."
                files.size > room -> "Det får plats tre bilder; de första lades till."
                else -> null
            }
            backgrounds = backgrounds + added
            saveSettings()
        }
    }

    fun removeBackground(path: String) {
        backgrounds = backgrounds - path
        backgroundMessage = null
        runCatching { File(path).delete() }
        saveSettings()
    }

    fun shutdown() {
        savePosition()
        persistHistory()
        runCatching { factsStore.save(movieFacts) }
        runCatching { tmdbStore.save(tmdbFacts) }
        runCatching { extrasStore.save(TmdbExtrasStore.Saved(tmdbLogos, trending, trendingAt)) }
        storage.saveSettings(currentSettings())
        WindowsIntegration.keepAwake(false)
        val released = videoPlayer?.release() ?: true
        // Hänger en ström kvar i VLC får biblioteket inte släppas under den.
        if (released) VlcRuntime.release()
        scope.cancel()
    }

    // --- Hjälp ---------------------------------------------------------------------

    private fun hostOf(url: String): String =
        runCatching { URI(url.trim()).host }.getOrNull()?.removePrefix("www.") ?: "Källa"

    /** Fel som går att visa. Leverantörsfelen är redan på svenska; nätfelen översätts här. */
    private fun explain(error: Throwable): String = when (error) {
        is PlaylistException -> error.message ?: "Servern svarade med ett fel."
        is UnknownHostException -> "Servern hittades inte. Kontrollera adressen och att datorn är ansluten."
        is SocketTimeoutException -> "Servern svarade inte i tid. Försök igen om en stund."
        is ConnectException -> "Det gick inte att ansluta till servern. Kontrollera adressen och porten."
        is SSLException -> "Den säkra anslutningen misslyckades. Prova http:// i stället för https://."
        is IOException -> "Nätverksfel: ${error.message ?: error.javaClass.simpleName}"
        else -> error.message ?: error.javaClass.simpleName
    }
}

/** Svensk bokstavsordning. En ny för varje sortering: en Collator klarar inte flera trådar samtidigt. */
private fun swedishOrder(): Collator = Collator.getInstance(Locale.forLanguageTag("sv-SE")).apply { strength = Collator.SECONDARY }

/** "SE - Titel" och "[4K] Titel" sorteras på Titel. */
private val sortPrefix = Regex("""^\s*(?:\[[^\]]*\]\s*|[A-Za-z]{2,4}\s*[-|:]\s+)""")

/**
 * Sortering av filmer och serier. Stabil: lika värden behåller leverantörens
 * ordning. Saknas ett värde (inget betyg, inget årtal) hamnar titeln sist.
 */
private fun <T> List<T>.ordered(
    order: SortOrder,
    name: (T) -> String,
    added: (T) -> Long,
    rating: (T) -> Double?,
    year: (T) -> Int?,
    popularity: (T) -> Double?,
): List<T> = when (order) {
    SortOrder.PROVIDER -> this
    SortOrder.POPULAR -> sortedByDescending { popularity(it) ?: -1.0 }
    SortOrder.ADDED -> sortedByDescending { added(it) }
    SortOrder.RATING -> sortedByDescending { rating(it) ?: -1.0 }
    SortOrder.YEAR -> sortedByDescending { year(it) ?: -1 }
    SortOrder.TITLE -> swedishOrder().let { collator -> map { it to collator.getCollationKey(name(it).replaceFirst(sortPrefix, "").trim()) } }
        .sortedBy { it.second }
        .map { it.first }
}

/**
 * Vad namnet säger om bilden: "Dune [4K HDR]", "… DV", "… HLG". "SDR" betyder
 * uttryckligen vanlig bild. Null när namnet inte säger något — då avgör
 * spelaren själv, efter vad strömmen är.
 */
private fun rangeHintOf(name: String?): VideoPlayer.Range? {
    if (name.isNullOrBlank()) return null
    return when {
        HLG_TAG.containsMatchIn(name) -> VideoPlayer.Range.HLG
        HDR_TAG.containsMatchIn(name) -> VideoPlayer.Range.PQ
        SDR_TAG.containsMatchIn(name) -> VideoPlayer.Range.SDR
        else -> null
    }
}

private val HDR_TAG = Regex("""(?i)(?<![a-z0-9])(hdr10\+?|hdr|dovi|dolby[\s._-]?vision|dv)(?![a-z0-9])""")
private val HLG_TAG = Regex("""(?i)(?<![a-z0-9])hlg(?![a-z0-9])""")
private val SDR_TAG = Regex("""(?i)(?<![a-z0-9])sdr(?![a-z0-9])""")

private const val MAX_HISTORY = 300
private const val FACTS_PACE_MS = 500L
/** Hur ofta en vilande hämtning tittar om spelaren stängts. */
private const val WATCH_REST_MS = 3_000L
/** Nya TMDB-svar och infosidor visas i klumpar: så här många, eller så här ofta. */
private const val PUBLISH_BATCH = 500
private const val PUBLISH_EVERY_MS = 20_000L
/** Så länge efter senaste trycket räknas man som att man bläddrar. */
private const val INPUT_QUIET_MS = 4_000L
/** Leverantörens betyg saknar röster; det väger som om så här många röstat. */
private const val PROVIDER_VOTES = 30
/** Uppslag hos TMDB samtidigt. TMDB tål runt 50 i sekunden; sex ger ett par dussin. */
private const val TMDB_WORKERS = 6
/** Minsta tid mellan två ombyggnader av versionerna medan TMDB-svar strömmar in. */
private const val VERSIONS_PAUSE_MS = 5_000L
/** Startelvan släpps oftast en timme före avspark: appen börjar titta efter den lite innan. */
private const val LINEUP_WATCH_MS = 75 * 60_000L
/** Startelvorna kollas högst så här ofta: varje koll hämtar en hel matchsida per match. */
private const val LINEUP_CHECK_MS = 5 * 60_000L
private const val TABLES_MAX_AGE_MS = 30 * 60_000L
/**
 * Medan fliken Fotboll syns hämtas hela perioden, en vecka bakåt och två fram,
 * så här ofta. Det är hela månader hos ESPN; däremellan hämtas bara idag.
 */
private const val FOOTBALL_FULL_MS = 30 * 60_000L
/** Malmö FF:s och landslagets matcher, ett halvår runt idag: var sjätte timme. */
private const val TEAMS_EVERY_MS = 6 * 60 * 60_000L
/** Senast sedda bland kanalerna: så många. */
private const val RECENT_CHANNELS = 30
/** Startens filmvägg: så många filmer, ur de senaste årens; sparas en stund efter starten. */
/** Startens filmvägg: så många filmer att samma omslag aldrig behöver synas två gånger samtidigt. */
private const val INTRO_REEL_SIZE = 72
private const val INTRO_REEL_MIN = 12
private const val INTRO_REEL_YEARS = 5
/** Klassiker i startens filmvägg: så många röster på TMDB, och minst det betyget. */
private const val INTRO_CLASSIC_VOTES = 6_000
private const val INTRO_CLASSIC_RATING = 7.0
private const val INTRO_REEL_SAVE_DELAY_MS = 25_000L
private const val TEAM_BACK_DAYS = 75L
private const val TEAM_AHEAD_DAYS = 150L
/** Utan fliken: hela perioden var tredje timme, ställningen var femte minut medan matcher pågår. */
private const val FOOTBALL_BACKGROUND_FULL_MS = 3 * 60 * 60_000L
private const val FOOTBALL_BACKGROUND_LIVE_MS = 5 * 60_000L
/** Malmö FF eller Sverige spelar: så ofta ställningen hämtas, också under en film. */
private const val OURS_LIVE_MS = 55_000L
/** Så långt före avspark rutan i hörnet påminner om matchen. */
private const val KICKOFF_REMINDER_MS = 15 * 60_000L
/** Så länge rutan i hörnet står kvar: ett mål, och en påminnelse som har en kanal att läsa. */
private const val NOTICE_MS = 9_000L
private const val NOTICE_LONG_MS = 14_000L
/** Herrlandslaget hos ESPN. */
private const val SWEDEN_TEAM = "466"
private const val FOOTBALL_RETRY_MS = 10 * 60_000L
private const val FOOTBALL_START_DELAY_MS = 25_000L
/** En match räknas som pågående upp till tre timmar efter avspark. */
private const val FOOTBALL_LIVE_WINDOW_MS = 3 * 60 * 60_000L

/** Det "Visas på" senast räknades fram för. Allt utom matcherna jämförs som samma objekt. */
private class BroadcastKey(
    val guide: GuideIndex,
    val channels: List<LiveChannel>,
    val hidden: Set<String>,
    val matches: List<Pair<String, Long>>,
) {
    fun sameAs(other: BroadcastKey?): Boolean =
        other != null && other.guide === guide && other.channels === channels && other.hidden === hidden && other.matches == matches
}
private const val DAY_MS = 24L * 60 * 60 * 1000
/** "Visas på" letas för matcherna de närmaste fyra dygnen. */
private const val BROADCAST_HORIZON_MS = 4 * DAY_MS
/** "Ny": högst en månad bakåt, och tre dagar första gången. */
private const val NEW_MAX_AGE_MS = 30 * DAY_MS
private const val NEW_FIRST_RUN_MS = 3 * DAY_MS
private const val VISIT_COUNTS_AFTER_MS = 10 * 60_000L
private const val TRENDING_MAX_AGE_MS = 6 * 60 * 60_000L
private const val TRENDING_CHECK_MS = 60 * 60_000L
/** Spoltryck med kortare mellanrum än så räknas som ett hopp. */
private const val SEEK_GROUP_MS = 4_000L

/** Tv: så länge efter sista trycket bilden hoppar dit spolningen kommit. */
private const val SCRUB_SETTLE_MS = 700L

/** Spolningen stannar så här långt före slutet, så att filmen inte tar slut av den. */
private const val SCRUB_END_GUARD_MS = 2_000L

/** Hållen knapp: filmens sekunder per sekund från början, tiden till dubbel fart, och högsta farten. */
private const val SCRUB_START_SPEED = 20f
private const val SCRUB_DOUBLING_MS = 750L
private const val SCRUB_TOP_SPEED = 120f

/** Som fortast går hela filmen på så här lång tid. */
private const val SCRUB_WHOLE_FILM_MS = 15_000L

/** Tv: så länge efter sista trycket på pil upp eller ner kanalen byts. */
private const val ZAP_SETTLE_MS = 380L
private const val INTRO_LATEST_START_MS = 6 * 60_000L
private const val INTRO_LATEST_END_MS = 10 * 60_000L
private const val INTRO_MIN_MS = 15_000L
private const val INTRO_MAX_MS = 240_000L
private const val OUTRO_WINDOW_MS = 8 * 60_000L
/** Nästa avsnitt visas så här länge före slutet, tills appen lärt sig var eftertexterna börjar. */
private const val DEFAULT_OUTRO_LEAD_MS = 30_000L

/** Avsnittet som spelas efter det här. */
class NextUp(val series: Series, val episode: XtreamEpisode) {
    val heading: String get() = "Säsong ${episode.season} · Avsnitt ${episode.episode}"
}
private const val MAX_BACKGROUNDS = 3

/** En plats i en samling: numret i ordningen, filmen i serien, och filmen i biblioteket om man har den. */
class CollectionSlot(val number: Int, val film: FranchiseFilm, val movie: Movie?)

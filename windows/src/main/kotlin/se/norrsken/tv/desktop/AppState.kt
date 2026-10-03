package se.norrsken.tv.desktop

import se.norrsken.tv.desktop.data.MyListStore
import se.norrsken.tv.desktop.data.ListKind
import se.norrsken.tv.desktop.data.ListEntry
import se.norrsken.tv.desktop.ui.Images
import se.norrsken.tv.desktop.data.AppDirs
import java.io.File
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.delay
import se.norrsken.tv.desktop.data.GuideIndex
import se.norrsken.tv.desktop.data.GuideStore
import se.norrsken.tv.desktop.data.SourceGuide
import se.norrsken.tv.desktop.data.XmltvParser
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

enum class Screen { HOME, LIVE, GUIDE, MOVIES, SERIES, GENRES, HISTORY, SETTINGS }

enum class SortOrder(val label: String) {
    PROVIDER("Standard"),
    POPULAR("Populärast"),
    ADDED("Senast tillagda"),
    RATING("Högst betyg"),
    YEAR("Nyast"),
    TITLE("A–Ö"),
}

private const val HOUR = 3_600_000L
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
    private val tmdbKeys = HashMap<String, String>()

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
        scope.launch {
            var ticks = 0
            while (true) {
                delay(30_000)
                clock = System.currentTimeMillis()
                // Var tionde minut: är någon tablå äldre än tolv timmar hämtas den om.
                if (++ticks % 20 == 0) queueStaleGuides()
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
        tmdbKey = SecretBox.unsealText(loaded.third.tmdbKey)
        tmdbConnected = tmdbKey != null
        backgrounds = loaded.third.backgrounds.filter { File(it).isFile }
        rebuildLibrary()
        loading = false
        loadGuides()
        history = withContext(Dispatchers.IO) { historyStore.load() }
        myList = withContext(Dispatchers.IO) { myListStore.load() }
        movieFacts = withContext(Dispatchers.IO) { factsStore.load() }
        tmdbFacts = withContext(Dispatchers.IO) { tmdbStore.load() }
        factsLoaded = true
        tmdbLoaded = true
        startTmdbScan()
        startFactsScan()

        if (sources.isEmpty()) {
            screen = Screen.SETTINGS
        } else {
            // Källor utan sparat bibliotek — första starten efter en uppdatering
            // av appen, eller en cache som inte gick att läsa — hämtas om, en i taget.
            refreshQueue.addAll(sources.filter { it.id !in contents }.map { it.id })
            refreshNextQueued()
        }
    }

    private fun rebuildLibrary() {
        val merged = Library.merge(sources.mapNotNull { contents[it.id] })
        library = merged
        // En vald kategori som försvann med en borttagen källa ska inte lämna en tom lista.
        if (liveGroup?.let { merged.liveCounts.containsKey(it) } == false) liveGroup = null
        if (movieGroup?.let { merged.movieCounts.containsKey(it) } == false) movieGroup = null
        if (seriesGroup?.let { merged.seriesCounts.containsKey(it) } == false) seriesGroup = null
        val movie = selectedMovie
        if (movie != null && movie.sourceId !in contents) selectedMovie = null
        val series = selectedSeries
        if (series != null && series.sourceId !in contents) selectedSeries = null
        rebuildGuide()
        // Nya filmer i biblioteket: genrehämtningen tar med dem.
        if (factsLoaded) restartFactsScan()
        tmdbKeys.clear()
        if (tmdbLoaded) restartTmdbScan()
    }

    fun contentFor(sourceId: String): SourceContent? = contents[sourceId]

    fun filteredChannels(): List<LiveChannel> {
        val query = liveQuery.trim().lowercase()
        val group = liveGroup
        return library.channels.filter { (group == null || it.group == group) && (query.isEmpty() || query in it.searchKey) }
    }

    fun filteredMovies(): List<Movie> {
        val query = movieQuery.trim().lowercase()
        val group = movieGroup
        return library.movies
            .filter { (group == null || it.group == group) && (query.isEmpty() || query in it.searchKey) }
            .ordered(movieSort, { it.name }, { it.addedUtc }, { scoreOf(it) }, { it.year }, { tmdbFor(it)?.popularity })
    }

    fun filteredSeries(): List<Series> {
        val query = seriesQuery.trim().lowercase()
        val group = seriesGroup
        return library.series
            .filter { (group == null || it.group == group) && (query.isEmpty() || query in it.searchKey) }
            .ordered(seriesSort, { it.name }, { it.addedUtc }, { scoreOf(it) }, { it.year?.toIntOrNull() }, { tmdbFor(it)?.popularity })
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
                contents[source.id] = content
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
    fun showSeries(series: Series) {
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

    /** Filmens sida. Leverantörens information hämtas en gång per film och sparas under körningen. */
    fun showMovie(movie: Movie) {
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

    // --- Uppspelning -------------------------------------------------------------

    fun playChannel(channel: LiveChannel, queue: List<LiveChannel>) {
        val entry = WatchEntry(
            kind = WatchKind.LIVE,
            sourceId = channel.sourceId,
            itemId = channel.streamId?.toString() ?: "namn:${channel.name}",
            title = channel.name,
            subtitle = channel.group,
            image = channel.logo,
            watchedAtUtc = System.currentTimeMillis(),
        )
        val item = NowPlaying(title = channel.name, subtitle = channel.group, live = true, channel = channel, queue = queue, entry = entry)
        launchPlayback(item) {
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
            title = movie.name,
            subtitle = movie.group,
            image = movie.poster,
            watchedAtUtc = System.currentTimeMillis(),
            positionMs = start,
            durationMs = earlier?.durationMs ?: 0L,
            extension = movie.extension,
        )
        val item = NowPlaying(title = movie.name, subtitle = movie.group, live = false, entry = entry, startMs = start)
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
            title = series.name,
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
        val item = NowPlaying(title = series.name, subtitle = subtitle, live = false, entry = entry, startMs = start)
        launchPlayback(item) {
            val account = credentials(series.sourceId) ?: return@launchPlayback null
            xtream.episodeUrl(account.second, episode.id, episode.containerExtension)
        }
    }

    fun retryPlayback() {
        val (item, resolve) = lastLaunch ?: return
        launchPlayback(item, resolve)
    }

    private fun launchPlayback(item: NowPlaying, resolve: suspend () -> String?) {
        // Det som spelades nyss: spara hur långt man kom innan det byts.
        savePosition()
        historyJob?.cancel()
        lastLaunch = item to resolve
        nowPlaying = item
        item.entry?.let { recordWatch(it) }
        // Något nytt att titta på: spelaren växer upp igen om den låg i hörnet.
        miniPlayer = false
        playerError = null
        trackPanelOpen = false
        playbackSession = -1L
        touchPlayer(force = true)
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
            player.setMuted(muted)
            player.preferredAudio = preferredAudio
            player.preferredSubtitle = preferredSubtitle
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
        nowPlaying = null
        playerError = null
        fullscreen = false
        miniPlayer = false
        trackPanelOpen = false
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
        miniPlayer = true
    }

    fun expandPlayer() {
        miniPlayer = false
        touchPlayer(force = true)
    }

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
                    fullscreen -> fullscreen = false
                    else -> minimizePlayer()
                }
                true
            }
            Key.Backspace -> { closePlayer(); true }
            Key.F -> { toggleFullscreen(); true }
            Key.M -> { toggleMute(); true }
            Key.DirectionLeft -> { if (!playing.live) videoPlayer?.seekBy(-10_000); true }
            Key.DirectionRight -> { if (!playing.live) videoPlayer?.seekBy(10_000); true }
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
        queueStaleGuides()
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
        tmdbKey = tmdbKey?.let { SecretBox.sealText(it) },
        backgrounds = backgrounds,
        audioLanguage = preferredAudio,
        subtitleLanguage = preferredSubtitle,
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
                val item = NowPlaying(
                    title = entry.title,
                    subtitle = entry.subtitle,
                    live = false,
                    entry = entry.copy(watchedAtUtc = System.currentTimeMillis(), upNext = false),
                    startMs = start,
                )
                launchPlayback(item) {
                    val account = credentials(entry.sourceId) ?: return@launchPlayback null
                    xtream.episodeUrl(account.second, entry.itemId, entry.extension ?: "mp4")
                }
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
            for (movie in missing) {
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
                // Skärmen uppdateras i klumpar om 50, disken var 500:e.
                if (fresh.size >= 50) {
                    batches++
                    publishFacts(HashMap(fresh), save = batches % 10 == 0)
                    fresh.clear()
                    factsProgress = done to wanted.size
                }
                delay(FACTS_PACE_MS)
            }
            publishFacts(fresh, save = true)
            factsProgress = done to wanted.size
        }
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
            val wanted: List<Triple<String, String, Int?>> = withContext(Dispatchers.Default) {
                val movieTitles = movies.sortedByDescending { it.rating ?: -1.0 }
                    .map { m -> TitleCleaner.searchTitle(m.name).let { (title, year) -> Triple("movie", title, year) } }
                val seriesTitles = series.sortedByDescending { it.rating ?: -1.0 }
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
            val missing = wanted.filter { TmdbStore.keyOf(it.first, it.second, it.third) !in known }
            var done = wanted.size - missing.size
            tmdbProgress = done to wanted.size
            val fresh = HashMap<String, TmdbFacts>()
            var failures = 0
            var batches = 0
            var stopped = false
            // Flera uppslag samtidigt. Kön, räknarna och resultaten hanteras på
            // UI-tråden, så inget behöver låsas; bara själva anropet går på nätet.
            val queue = missing.iterator()
            coroutineScope {
                repeat(TMDB_WORKERS) {
                    launch {
                        while (!stopped && queue.hasNext()) {
                            val (kind, title, year) = queue.next()
                            val result = withContext(Dispatchers.IO) {
                                runCatching { client.search(kind, title, year, genres[kind].orEmpty()) }
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
                            fresh[TmdbStore.keyOf(kind, title, year)] = facts
                            done++
                            if (fresh.size >= 50) {
                                batches++
                                publishTmdb(HashMap(fresh), save = batches % 10 == 0)
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

private val swedishOrder: Collator = Collator.getInstance(Locale.forLanguageTag("sv-SE")).apply { strength = Collator.SECONDARY }

/** "SE - Titel" och "[4K] Titel" sorteras på Titel. */
private val sortPrefix = Regex("""^\s*(?:\[[^\]]*]\s*|[A-Za-z]{2,4}\s*[-|:]\s+)""")

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
    SortOrder.TITLE -> map { it to swedishOrder.getCollationKey(name(it).replaceFirst(sortPrefix, "").trim()) }
        .sortedBy { it.second }
        .map { it.first }
}

private const val MAX_HISTORY = 300
private const val FACTS_PACE_MS = 500L
/** Leverantörens betyg saknar röster; det väger som om så här många röstat. */
private const val PROVIDER_VOTES = 30
/** Uppslag hos TMDB samtidigt. TMDB tål runt 50 i sekunden; sex ger ett par dussin. */
private const val TMDB_WORKERS = 6
private const val MAX_BACKGROUNDS = 3

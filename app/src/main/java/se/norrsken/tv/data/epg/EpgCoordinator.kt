package se.norrsken.tv.data.epg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParserException
import se.norrsken.tv.data.db.dao.ChannelDao
import se.norrsken.tv.data.db.dao.EPG_CHUNK
import se.norrsken.tv.data.db.dao.EpgDao
import se.norrsken.tv.data.db.dao.PlaylistDao
import se.norrsken.tv.data.db.entity.EpgMappingEntity
import se.norrsken.tv.data.db.entity.EpgProgrammeEntity
import se.norrsken.tv.data.db.entity.MappingSource
import se.norrsken.tv.data.db.entity.PlaylistEntity
import se.norrsken.tv.data.db.entity.PlaylistType
import se.norrsken.tv.data.remote.PlaylistDownloader
import se.norrsken.tv.data.remote.PlaylistException
import se.norrsken.tv.data.settings.AppPreferences
import se.norrsken.tv.data.settings.SettingsRepository
import se.norrsken.tv.data.xtream.XtreamClient
import se.norrsken.tv.data.xtream.XtreamCredentialsFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** Verklig status — varje läge motsvarar något som faktiskt händer. */
sealed interface EpgState {
    data object Idle : EpgState
    data class Working(val step: String, val programmes: Int? = null) : EpgState
    data class Done(val programmes: Int, val channels: Int) : EpgState
    data class Failed(val message: String) : EpgState
}

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS
/** Så länge avslutade program ligger kvar — för "vad gick nyss". */
private const val RETAIN_PAST_MS = 12 * HOUR_MS
/**
 * Tablån uppdateras om den är äldre än så. Sex timmar: tillräckligt sällan
 * för att inte belasta leverantören vid varje återkomst till appen, ofta nog
 * för att tablån aldrig hinner ta slut under en kväll.
 */
private const val STALE_MS = 6 * HOUR_MS
private const val BATCH = 1_000

/**
 * Hämtar och sparar tablån för alla aktiva källor.
 *
 * Körs i appens livscykel, som importen — en tablåuppdatering kan ta en
 * minut och får inte avbrytas för att någon backar ur en skärm.
 */
@Singleton
class EpgCoordinator @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val epgDao: EpgDao,
    private val downloader: PlaylistDownloader,
    private val xtream: XtreamClient,
    private val credentialsFactory: XtreamCredentialsFactory,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<EpgState>(EpgState.Idle)
    val state: StateFlow<EpgState> = _state.asStateFlow()

    private val _version = MutableStateFlow(0)
    /** Ökar när en uppdatering är klar. Skärmar som visar tablån läser då om. */
    val version: StateFlow<Int> = _version.asStateFlow()

    val busy: Boolean get() = job?.isActive == true

    fun updateAll() {
        if (busy) return
        job = scope.launch { runUpdate() }
    }

    fun updateIfStale() {
        scope.launch {
            val last = settings.current().epgLastUpdateUtc ?: 0L
            if (System.currentTimeMillis() - last > STALE_MS) updateAll()
        }
    }

    private suspend fun runUpdate() {
        val prefs = settings.current()
        val playlists = playlistDao.enabled()

        var programmes = 0
        var channels = 0
        var anySource = false
        var lastError: String? = null

        for (playlist in playlists) {
            val url = epgUrlFor(playlist) ?: continue
            anySource = true
            try {
                val result = updateSource(playlist, url, prefs)
                programmes += result.first
                channels += result.second
            } catch (e: CancellationException) {
                throw e
            } catch (e: PlaylistException) {
                lastError = e.message
            } catch (e: XmlPullParserException) {
                // Radnumret är det enda som skiljer ett avbrutet svar från
                // en fil som är trasig hela vägen.
                lastError = "Tablån gick inte att läsa (rad ${e.lineNumber}). " +
                    "Filen kan vara avbruten under hämtningen."
            } catch (e: Exception) {
                lastError = "Tablån kunde inte uppdateras."
            }
        }

        epgDao.pruneOlderThan(System.currentTimeMillis() - RETAIN_PAST_MS)

        _state.value = when {
            !anySource -> EpgState.Failed(
                "Ingen källa har någon tablå. Lägg till en tablåadress under Inställningar → Tablå."
            )
            programmes == 0 && lastError != null -> EpgState.Failed(lastError)
            else -> {
                settings.setEpgLastUpdate(System.currentTimeMillis())
                _version.value = _version.value + 1
                EpgState.Done(programmes = programmes, channels = channels)
            }
        }
    }

    /**
     * Var tablån finns. Användarens egen adress vinner; annars källans.
     *
     * Xtream: adressen byggs varje gång ur de krypterade uppgifterna och
     * sparas aldrig. Äldre versioner sparade den med lösenordet i klartext
     * — hittas en sådan tas den bort här.
     */
    private suspend fun epgUrlFor(playlist: PlaylistEntity): String? {
        val stored = playlist.epgUrl?.takeIf { it.isNotBlank() }
        val isXtream = playlist.type == PlaylistType.XTREAM

        if (stored != null && isXtream && stored.contains("xmltv.php")) {
            playlistDao.setEpgUrl(playlist.id, null)
            return xtream.xmltvUrl(credentialsFactory.forPlaylist(playlist))
        }
        return stored ?: if (isXtream) {
            xtream.xmltvUrl(credentialsFactory.forPlaylist(playlist))
        } else {
            null
        }
    }

    /** Första halvkilon av svaret, utan att förbruka det. */
    private fun peek(stream: java.io.BufferedInputStream): String {
        stream.mark(2048)
        val buffer = ByteArray(512)
        val read = stream.read(buffer)
        stream.reset()
        return if (read <= 0) "" else String(buffer, 0, read, Charsets.ISO_8859_1)
    }

    /**
     * Vad som är fel med svaret, i klartext — eller null när det ser ut som
     * en tablå.
     *
     * "Filen är trasig" var sant men oanvändbart. Nästan alltid är felet
     * något annat: en inloggningssida, ett tomt svar eller ett konto utan
     * EPG. Det går att se på de första raderna.
     */
    private fun describeBadResponse(head: String): String? {
        val text = head.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val lower = text.lowercase()
        return when {
            text.isEmpty() ->
                "Leverantören skickade en tom tablå. Kontot kan sakna EPG."
            lower.startsWith("<!doctype html") || lower.startsWith("<html") ->
                "Leverantören svarade med en webbsida i stället för en tablå. " +
                    "Kontrollera tablåadressen och inloggningen."
            text.startsWith("{") || text.startsWith("[") ->
                "Leverantören svarade med JSON i stället för XMLTV."
            !lower.contains("<tv") && !text.startsWith("<?xml") ->
                "Svaret är inte XMLTV. Det börjar med: " +
                    text.take(60).replace(Regex("\\s+"), " ")
            else -> null
        }
    }

    /** Returnerar (sparade program, kopplade kanaler). */
    private suspend fun updateSource(
        playlist: PlaylistEntity,
        url: String,
        prefs: AppPreferences,
    ): Pair<Int, Int> {
        val channels = channelDao.channelsForPlaylist(playlist.id)
        if (channels.isEmpty()) return 0 to 0

        val existing = channels.map { it.id }.chunked(EPG_CHUNK).flatMap { epgDao.mappingsFor(it) }
        // Manuella kopplingar är användarens beslut. De rörs aldrig.
        val manual = existing.filter { it.source == MappingSource.MANUAL }
        val manualIds = manual.map { it.channelId }.toSet()
        val candidates = channels.filterNot { it.id in manualIds }

        val now = System.currentTimeMillis()
        val windowStart = now - RETAIN_PAST_MS
        val windowEnd = now + prefs.epgDays.days * DAY_MS

        val xmltvChannels = mutableListOf<XmltvChannel>()
        // Tablåns kanal-id → det id kopplingen sparats under. Jämförs i
        // gemener: filen och spellistan skriver ofta samma id olika.
        var wanted: Map<String, String>? = null
        var mapped = 0
        var stored = 0
        val batch = ArrayList<EpgProgrammeEntity>(BATCH)

        suspend fun finishMapping(): Map<String, String> {
            _state.value = EpgState.Working("Kopplar kanaler")
            val matched: List<EpgMappingEntity> = matchChannels(candidates, xmltvChannels)
            candidates.map { it.id }.chunked(EPG_CHUNK).forEach { epgDao.clearAutoMappings(it) }
            epgDao.insertMappings(matched)
            val all = matched + manual
            mapped = all.map { it.channelId }.distinct().size
            val ids = all.associate { it.epgChannelId.lowercase() to it.epgChannelId }
            // Bara de här kanalernas gamla program tas bort. Andra källors
            // tablåer rörs inte.
            ids.values.distinct().chunked(EPG_CHUNK).forEach { epgDao.deleteProgrammesFor(it) }
            return ids
        }

        suspend fun flush() {
            if (batch.isEmpty()) return
            epgDao.insertProgrammes(batch.toList())
            stored += batch.size
            batch.clear()
            _state.value = EpgState.Working("Sparar tablån", stored)
        }

        _state.value = EpgState.Working("Hämtar tablån")
        downloader.openStream(url, prefs.userAgent).use { raw ->
            val stream = raw.buffered()
            // Titta på svarets början innan XML-tolken får det. "Filen är
            // trasig" säger ingenting om varför — och den vanligaste
            // orsaken är att leverantören svarat med en inloggningssida
            // eller en tom fil, inte med trasig XML.
            describeBadResponse(peek(stream))?.let { throw PlaylistException(it) }

            val reader = XmltvReader(stream)
            while (true) {
                currentCoroutineContext().ensureActive()
                val item = reader.next() ?: break
                when (item) {
                    // Kanalerna kommer före programmen i XMLTV. När första
                    // programmet dyker upp är kanallistan komplett.
                    is XmltvReader.Item.Channel -> if (wanted == null) xmltvChannels += item.channel
                    is XmltvReader.Item.Programme -> {
                        val ids = wanted ?: finishMapping().also { wanted = it }
                        val p = item.programme
                        val epgId = ids[p.channel.lowercase()] ?: continue
                        // Bara program inom fönstret sparas. Allt utanför är
                        // rader databasen och TV-boxen får bära i onödan.
                        if (p.stop <= windowStart || p.start >= windowEnd) continue
                        batch += EpgProgrammeEntity(
                            epgChannelId = epgId,
                            title = p.title,
                            description = p.description,
                            startUtc = p.start,
                            endUtc = p.stop,
                            category = p.category,
                            iconUrl = p.icon,
                            episodeNumber = p.episode,
                        )
                        if (batch.size >= BATCH) flush()
                    }
                }
            }
        }

        // En fil med bara kanaler — eller helt tom — ska ändå ge kopplingar.
        if (wanted == null) finishMapping()
        flush()
        return stored to mapped
    }
}

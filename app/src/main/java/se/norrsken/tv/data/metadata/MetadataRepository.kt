package se.norrsken.tv.data.metadata

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import se.norrsken.tv.data.db.dao.LibraryDao
import se.norrsken.tv.data.db.entity.MatchState
import se.norrsken.tv.data.settings.AppSettings
import se.norrsken.tv.di.IoDispatcher
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hämtar omslag och fakta för titlar där leverantören inte gav något.
 *
 * Görs när kortet syns på skärmen, inte vid import. Ett bibliotek på 3000
 * filmer skulle annars betyda 3000 anrop innan användaren ser första bilden,
 * och tjänsten stryper kontot långt innan dess. Så här dyker bilden upp
 * medan man scrollar och finns kvar nästa gång.
 */
@Singleton
class MetadataRepository @Inject constructor(
    private val client: TmdbClient,
    private val libraryDao: LibraryDao,
    private val settings: AppSettings,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val inFlight = Collections.synchronizedSet(mutableSetOf<String>())
    private val gate = Semaphore(CONCURRENCY)

    private val _problem = MutableStateFlow<String?>(null)
    /** Visas en gång i Inställningar, inte som 3000 felmeddelanden. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** Sätts när nyckeln avvisats — då slutar vi fråga tills den ändras. */
    @Volatile private var keyRejected = false

    fun clearProblem() {
        _problem.value = null
        keyRejected = false
    }

    suspend fun resolveMovie(id: Long) = resolve(MetadataKind.MOVIE, id)

    suspend fun resolveSeries(id: Long) = resolve(MetadataKind.SERIES, id)

    private suspend fun resolve(kind: MetadataKind, id: Long) {
        if (keyRejected) return

        val token = "${kind.name}:$id"
        if (!inFlight.add(token)) return

        try {
            withContext(io) {
                val apiKey = settings.tmdbKeyNow()
                if (apiKey == null) {
                    _problem.value = "Lägg in en TMDB-nyckel under Inställningar " +
                        "för att fylla i omslag som saknas."
                    return@withContext
                }

                val language = settings.metadataLanguageNow()

                // Hämta aktuell rad igen: den kan ha fyllts i medan kortet
                // väntade i kön.
                val title: String
                val year: Int?
                if (kind == MetadataKind.MOVIE) {
                    val movie = libraryDao.movie(id) ?: return@withContext
                    if (movie.matchState != MatchState.UNMATCHED) return@withContext
                    title = movie.title
                    year = movie.year
                } else {
                    val series = libraryDao.seriesById(id) ?: return@withContext
                    if (series.matchState != MatchState.UNMATCHED) return@withContext
                    title = series.title
                    year = series.year
                }

                val outcome = lookup(apiKey, kind, title, year, language)
                apply(kind, id, outcome)
            }
        } finally {
            inFlight.remove(token)
        }
    }

    private suspend fun lookup(
        apiKey: String,
        kind: MetadataKind,
        title: String,
        year: Int?,
        language: String,
    ): MetadataOutcome = gate.withPermit {
        try {
            val candidates = client.search(apiKey, kind, title, year, language)
            val verdict = pickBestMatch(title, year, candidates)
            if (verdict.accepted() && verdict.candidate != null) {
                MetadataOutcome.Matched(verdict.candidate, verdict.confidence)
            } else {
                MetadataOutcome.NoMatch(verdict.reason)
            }
        } catch (e: MetadataAuthException) {
            keyRejected = true
            _problem.value = "TMDB avvisade nyckeln. Kontrollera den under Inställningar."
            MetadataOutcome.Unavailable(e.message.orEmpty())
        } catch (e: Exception) {
            MetadataOutcome.Unavailable(e.message.orEmpty())
        }
    }

    private suspend fun apply(kind: MetadataKind, id: Long, outcome: MetadataOutcome) {
        when (outcome) {
            // Nätfel eller saknad nyckel lämnar raden som UNMATCHED, så den
            // försöks igen. Ett verkligt "hittade inget" markeras AMBIGUOUS
            // — annars frågar appen om samma titel varje gång den scrollas
            // förbi, i all framtid.
            is MetadataOutcome.Unavailable -> Unit

            is MetadataOutcome.NoMatch -> when (kind) {
                MetadataKind.MOVIE -> libraryDao.movie(id)?.let {
                    libraryDao.updateMovie(it.copy(matchState = MatchState.AMBIGUOUS))
                }
                MetadataKind.SERIES -> libraryDao.seriesById(id)?.let {
                    libraryDao.updateSeries(it.copy(matchState = MatchState.AMBIGUOUS))
                }
            }

            is MetadataOutcome.Matched -> {
                val c = outcome.candidate
                when (kind) {
                    MetadataKind.MOVIE -> libraryDao.movie(id)?.let { movie ->
                        libraryDao.updateMovie(
                            movie.copy(
                                // Leverantörens egna uppgifter vinner där de
                                // finns. Vi fyller luckor, vi skriver inte om.
                                posterUrl = movie.posterUrl ?: c.posterUrl,
                                backdropUrl = movie.backdropUrl ?: c.backdropUrl,
                                overview = movie.overview ?: c.overview,
                                rating = movie.rating ?: c.rating,
                                ratingSource = movie.ratingSource ?: "TMDB",
                                metadataId = c.providerId,
                                matchState = MatchState.MATCHED,
                            )
                        )
                    }
                    MetadataKind.SERIES -> libraryDao.seriesById(id)?.let { series ->
                        libraryDao.updateSeries(
                            series.copy(
                                posterUrl = series.posterUrl ?: c.posterUrl,
                                backdropUrl = series.backdropUrl ?: c.backdropUrl,
                                overview = series.overview ?: c.overview,
                                rating = series.rating ?: c.rating,
                                metadataId = c.providerId,
                                matchState = MatchState.MATCHED,
                            )
                        )
                    }
                }
            }
        }
    }

    private companion object {
        /**
         * Fyra samtidiga anrop. Högre gör inte scrollen snabbare — det som
         * begränsar är att bilderna ska laddas ned efteråt — men ökar risken
         * att tjänsten börjar strypa.
         */
        const val CONCURRENCY = 4
    }
}

package se.norrsken.tv.data.playlist

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.norrsken.tv.data.db.entity.PlaylistEntity
import se.norrsken.tv.data.epg.EpgCoordinator
import se.norrsken.tv.data.repository.PlaylistRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kör importer i appens livscykel, inte i en skärms.
 *
 * Tidigare startades importen i inställningsskärmens ViewModel. Tryckte man
 * Back innan den var klar revs ViewModeln — och importen avbröts mitt i,
 * med halva biblioteket sparat. Här lever den kvar tills den är färdig,
 * vart användaren än går.
 *
 * En import åt gången. Två samtidiga mot samma leverantör hade slagits om
 * samma rader och dessutom kunnat slå i leverantörens anslutningstak.
 */
@Singleton
class ImportCoordinator @Inject constructor(
    private val repository: PlaylistRepository,
    private val importer: SourceImporter,
    private val epg: EpgCoordinator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    val busy: Boolean get() = job?.isActive == true

    fun addAndImport(playlist: PlaylistEntity) {
        if (busy) return
        job = scope.launch {
            val id = repository.add(playlist)
            importer.import(playlist.copy(id = id)).collect { _state.value = it }
            afterImport()
        }
    }

    fun refresh(playlist: PlaylistEntity) {
        if (busy) return
        job = scope.launch {
            importer.import(playlist).collect { _state.value = it }
            afterImport()
        }
    }

    /**
     * Nya kanaler behöver ny koppling mot tablån — annars visar guiden
     * gamla kopplingar till kanaler som bytt id vid importen.
     */
    private fun afterImport() {
        if (_state.value is ImportState.Done) epg.updateAll()
    }

    /** Fel som upptäcks innan någon import startat, t.ex. en tom länk. */
    fun fail(message: String) {
        if (!busy) _state.value = ImportState.Failed(message)
    }

    fun dismiss() {
        if (!busy) _state.value = ImportState.Idle
    }
}

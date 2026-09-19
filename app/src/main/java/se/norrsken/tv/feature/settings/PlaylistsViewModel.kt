package se.norrsken.tv.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.norrsken.tv.data.db.entity.PlaylistEntity
import se.norrsken.tv.data.db.entity.PlaylistType
import se.norrsken.tv.data.playlist.ImportState
import se.norrsken.tv.data.playlist.SourceImporter
import se.norrsken.tv.data.playlist.extractXtreamCredentials
import se.norrsken.tv.data.playlist.normalizeBaseUrl
import se.norrsken.tv.data.metadata.MetadataRepository
import se.norrsken.tv.data.repository.PlaylistRepository
import se.norrsken.tv.data.settings.AppSettings
import javax.inject.Inject

/** Meddelande att visa direkt i formuläret, utan att starta något. */
data class SourceHint(val message: String)

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val importer: SourceImporter,
    private val settings: AppSettings,
    private val metadata: MetadataRepository,
) : ViewModel() {

    val tmdbKey: StateFlow<String> = settings.tmdbKey()
        .map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Ett meddelande om metadatahämtningen, inte ett per titel. */
    val metadataProblem: StateFlow<String?> = metadata.problem

    fun saveTmdbKey(value: String) {
        viewModelScope.launch {
            settings.setTmdbKey(value)
            // Nyckeln kan ha bytts ut efter att den avvisats — släpp spärren
            // så appen börjar fråga igen.
            metadata.clearProblem()
        }
    }

    val playlists: StateFlow<List<PlaylistEntity>> = repository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _hint = MutableStateFlow<SourceHint?>(null)
    val hint: StateFlow<SourceHint?> = _hint.asStateFlow()

    /**
     * En M3U-länk från en leverantör är nästan alltid ett Xtream-konto i
     * förklädnad. Upptäcks det byter vi själva över till API:et — det ger
     * kategorier, omslag och serier som den platta filen saknar helt.
     * Användaren får veta att det hände, inte fråga om det.
     */
    fun addM3u(name: String, url: String) {
        val trimmed = url.trim()
        when {
            trimmed.isEmpty() -> {
                _importState.value = ImportState.Failed("Ingen länk angiven.")
                return
            }
            !trimmed.startsWith("http://") && !trimmed.startsWith("https://") -> {
                _importState.value = ImportState.Failed("Länken måste börja med http:// eller https://")
                return
            }
        }

        val credentials = extractXtreamCredentials(trimmed)
        if (credentials != null) {
            _hint.value = SourceHint(
                "Länken är ett Xtream-konto. Appen använder API:et i stället — " +
                    "det ger kategorier, omslagsbilder och serier."
            )
            start(
                PlaylistEntity(
                    name = name.trim().ifEmpty { credentials.baseUrl.substringAfter("://") },
                    type = PlaylistType.XTREAM,
                    url = credentials.baseUrl,
                    username = credentials.username,
                    secret = credentials.password,
                )
            )
            return
        }

        start(
            PlaylistEntity(
                name = name.trim().ifEmpty { "Min spellista" },
                type = PlaylistType.M3U,
                url = trimmed,
            )
        )
    }

    fun addXtream(name: String, server: String, username: String, password: String) {
        if (server.isBlank() || username.isBlank() || password.isBlank()) {
            _importState.value = ImportState.Failed("Fyll i server, användarnamn och lösenord.")
            return
        }
        start(
            PlaylistEntity(
                name = name.trim().ifEmpty { server.trim() },
                type = PlaylistType.XTREAM,
                url = normalizeBaseUrl(server),
                username = username.trim(),
                secret = password.trim(),
            )
        )
    }

    fun refresh(playlist: PlaylistEntity) {
        viewModelScope.launch { run(playlist) }
    }

    fun remove(playlist: PlaylistEntity) {
        viewModelScope.launch { repository.remove(playlist) }
    }

    fun dismiss() {
        _importState.value = ImportState.Idle
        _hint.value = null
    }

    private fun start(playlist: PlaylistEntity) {
        viewModelScope.launch {
            val id = repository.add(playlist)
            run(playlist.copy(id = id))
        }
    }

    private suspend fun run(playlist: PlaylistEntity) {
        importer.import(playlist).collect { state -> _importState.value = state }
    }
}

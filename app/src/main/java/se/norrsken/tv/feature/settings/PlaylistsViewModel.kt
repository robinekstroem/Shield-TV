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
import se.norrsken.tv.data.epg.EpgCoordinator
import se.norrsken.tv.data.epg.EpgState
import se.norrsken.tv.data.playlist.ImportCoordinator
import se.norrsken.tv.data.settings.AccentChoice
import se.norrsken.tv.data.settings.CategorySort
import se.norrsken.tv.data.settings.ChannelSort
import se.norrsken.tv.data.settings.LibrarySort
import se.norrsken.tv.data.settings.AppPreferences
import se.norrsken.tv.data.settings.BufferProfile
import se.norrsken.tv.data.settings.DecoderMode
import se.norrsken.tv.data.settings.SubtitleBackground
import se.norrsken.tv.data.settings.SubtitleSize
import se.norrsken.tv.data.settings.TextScale
import se.norrsken.tv.data.settings.EffectLevel
import se.norrsken.tv.data.settings.EpgDays
import se.norrsken.tv.data.settings.InterfaceStyle
import se.norrsken.tv.data.settings.MediaLanguage
import se.norrsken.tv.data.settings.VideoQuality
import se.norrsken.tv.data.settings.VideoScale
import se.norrsken.tv.data.settings.VolumeBoost
import se.norrsken.tv.data.playlist.extractXtreamCredentials
import se.norrsken.tv.data.playlist.normalizeBaseUrl
import se.norrsken.tv.data.metadata.MetadataRepository
import se.norrsken.tv.data.repository.PlaylistRepository
import se.norrsken.tv.data.settings.SettingsRepository
import javax.inject.Inject

/** Meddelande att visa direkt i formuläret, utan att starta något. */
data class SourceHint(val message: String)

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val imports: ImportCoordinator,
    private val epg: EpgCoordinator,
    private val settings: SettingsRepository,
    private val metadata: MetadataRepository,
) : ViewModel() {

    val preferences: StateFlow<AppPreferences> = settings.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    val tmdbKey: StateFlow<String> = settings.preferences
        .map { it.tmdbKey.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setUserAgent(value: String) = edit { settings.setUserAgent(value) }
    fun setBuffer(value: BufferProfile) = edit { settings.setBuffer(value) }
    fun setDecoder(value: DecoderMode) = edit { settings.setDecoder(value) }
    fun setMatchFrameRate(value: Boolean) = edit { settings.setMatchFrameRate(value) }
    fun setPreferSurround(value: Boolean) = edit { settings.setPreferSurround(value) }
    fun setInfoPanelSeconds(value: Int) = edit { settings.setInfoPanelSeconds(value) }
    fun setShowClock(value: Boolean) = edit { settings.setShowClock(value) }
    fun setConfirmExit(value: Boolean) = edit { settings.setConfirmExit(value) }
    fun setLibrarySort(value: LibrarySort) = edit { settings.setLibrarySort(value) }
    fun setGridColumns(value: Int) = edit { settings.setGridColumns(value) }
    fun setChannelSort(value: ChannelSort) = edit { settings.setChannelSort(value) }
    fun setCategorySort(value: CategorySort) = edit { settings.setCategorySort(value) }
    fun setInterfaceStyle(value: InterfaceStyle) = edit { settings.setInterfaceStyle(value) }
    fun setVideoScale(value: VideoScale) = edit { settings.setVideoScale(value) }
    fun setVideoQuality(value: VideoQuality) = edit { settings.setVideoQuality(value) }
    fun setTunneling(value: Boolean) = edit { settings.setTunneling(value) }
    fun setAudioLanguage(value: MediaLanguage) = edit { settings.setAudioLanguage(value) }
    fun setVolumeBoost(value: VolumeBoost) = edit { settings.setVolumeBoost(value) }
    fun setSubtitleLanguage(value: MediaLanguage) = edit { settings.setSubtitleLanguage(value) }
    fun setAutoSubtitles(value: Boolean) = edit { settings.setAutoSubtitles(value) }
    fun setEffects(value: EffectLevel) = edit { settings.setEffects(value) }

    val epgState: StateFlow<EpgState> = epg.state
    fun updateEpg() = epg.updateAll()
    fun setEpgDays(value: EpgDays) = edit { settings.setEpgDays(value) }
    fun setEpgOffset(minutes: Int) = edit { settings.setEpgOffset(minutes) }

    /** Ny adress hämtas direkt — annars ser man inte om den fungerar. */
    fun setEpgUrl(playlist: PlaylistEntity, url: String?) = edit {
        repository.setEpgUrl(playlist.id, url)
        epg.updateAll()
    }
    fun setSubtitleSize(value: SubtitleSize) = edit { settings.setSubtitleSize(value) }
    fun setSubtitleBackground(value: SubtitleBackground) = edit { settings.setSubtitleBackground(value) }
    fun setTextScale(value: TextScale) = edit { settings.setTextScale(value) }
    fun setAccent(value: AccentChoice) = edit { settings.setAccent(value) }
    fun setUiOpacity(value: Int) = edit { settings.setUiOpacity(value) }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /** Ett meddelande om metadatahämtningen, inte ett per titel. */
    val metadataProblem: StateFlow<String?> = metadata.problem

    fun saveTmdbKey(value: String) {
        viewModelScope.launch {
            settings.setTmdbKey(value)
            // Slapper sparren OCH nollstaller titlar som markerats som
            // "hittade inget" medan nyckeln saknades.
            metadata.retryUnmatched()
        }
    }

    val playlists: StateFlow<List<PlaylistEntity>> = repository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Importens läge lever i ImportCoordinator, så det överlever navigering. */
    val importState: StateFlow<ImportState> = imports.state

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
                imports.fail("Ingen länk angiven.")
                return
            }
            !trimmed.startsWith("http://") && !trimmed.startsWith("https://") -> {
                imports.fail("Länken måste börja med http:// eller https://")
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
            imports.fail("Fyll i server, användarnamn och lösenord.")
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

    fun refresh(playlist: PlaylistEntity) = imports.refresh(playlist)

    fun remove(playlist: PlaylistEntity) {
        viewModelScope.launch { repository.remove(playlist) }
    }

    fun dismiss() {
        imports.dismiss()
        _hint.value = null
    }

    private fun start(playlist: PlaylistEntity) = imports.addAndImport(playlist)
}

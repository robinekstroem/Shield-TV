package se.norrsken.tv.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.norrsken.tv.data.db.dao.ChannelDao
import se.norrsken.tv.data.db.dao.LibraryDao
import se.norrsken.tv.data.db.entity.MediaKind
import se.norrsken.tv.data.epg.NowNext
import se.norrsken.tv.data.epg.NowPlayingRepository
import se.norrsken.tv.data.player.PlayerFactory
import se.norrsken.tv.data.repository.WatchHistoryRepository
import se.norrsken.tv.data.repository.FavoritesRepository
import se.norrsken.tv.data.settings.SettingsRepository
import se.norrsken.tv.data.settings.VideoScale
import javax.inject.Inject

enum class PlayableKind { CHANNEL, MOVIE, EPISODE }

/**
 * Allt spelaren behover veta. [isLive] styr hela kontrollpanelen: en
 * live-sandning har ingen langd och ingen position att spola i, sa
 * tidslinjen ska inte finnas — inte visas tom.
 */
data class Playable(
    val title: String,
    val subtitle: String?,
    val url: String,
    val isLive: Boolean,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val channelDao: ChannelDao,
    private val libraryDao: LibraryDao,
    val playerFactory: PlayerFactory,
    private val settings: SettingsRepository,
    private val nowPlaying: NowPlayingRepository,
    private val history: WatchHistoryRepository,
    private val favorites: FavoritesRepository,
) : ViewModel() {

    private val _programme = MutableStateFlow<NowNext?>(null)
    /** Vad som sänds på kanalen just nu. Bara för live — null för film. */
    val programme: StateFlow<NowNext?> = _programme.asStateFlow()

    /** Bildformatet sparas, sa nasta film oppnas som den forra. */
    fun cycleVideoScale(current: VideoScale) {
        val order = VideoScale.entries
        val next = order[(order.indexOf(current) + 1) % order.size]
        viewModelScope.launch { settings.setVideoScale(next) }
    }

    private val _playable = MutableStateFlow<Playable?>(null)
    val playable: StateFlow<Playable?> = _playable.asStateFlow()

    private val _missing = MutableStateFlow(false)
    /** Sant nar raden inte gick att hitta — battre an en tom svart skarm. */
    val missing: StateFlow<Boolean> = _missing.asStateFlow()

    private val kind = savedStateHandle.get<String>("kind")
        ?.let { runCatching { PlayableKind.valueOf(it) }.getOrNull() }
    private val id = savedStateHandle.get<String>("id")?.toLongOrNull()

    /** Serien ett avsnitt hör till — så historiken kan grupperas senare. */
    private var seriesId: Long? = null

    private val historyKind: MediaKind? = when (kind) {
        PlayableKind.MOVIE -> MediaKind.MOVIE
        PlayableKind.EPISODE -> MediaKind.EPISODE
        // Live har ingen position att komma ihåg.
        PlayableKind.CHANNEL, null -> null
    }

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    fun toggleChannelFavorite() {
        if (kind != PlayableKind.CHANNEL) return
        val mediaId = id ?: return
        viewModelScope.launch { favorites.toggle(MediaKind.CHANNEL, mediaId, _isFavorite.value) }
    }

    /** Var uppspelningen ska börja. 0 = från början. */
    suspend fun resumePosition(): Long {
        val mediaKind = historyKind ?: return 0L
        val mediaId = id ?: return 0L
        return history.resumePosition(mediaKind, mediaId)
    }

    fun saveProgress(positionMs: Long, durationMs: Long) {
        val mediaKind = historyKind ?: return
        val mediaId = id ?: return
        history.save(mediaKind, mediaId, seriesId, positionMs, durationMs)
    }

    init {
        if (kind == PlayableKind.CHANNEL && id != null) {
            viewModelScope.launch {
                favorites.isFavorite(MediaKind.CHANNEL, id).collect { _isFavorite.value = it }
            }
        }

        viewModelScope.launch {
            val resolved = when {
                kind == null || id == null -> null
                kind == PlayableKind.CHANNEL -> channelDao.byId(id)?.let { channel ->
                    // Tablån följs så länge spelaren är öppen. Programmet
                    // byts mitt i tittandet, och panelen ska visa rätt.
                    viewModelScope.launch {
                        while (true) {
                            _programme.value = nowPlaying
                                .forChannels(listOf(channel), System.currentTimeMillis())[channel.id]
                            delay(30_000)
                        }
                    }
                    Playable(
                        title = channel.name,
                        subtitle = channel.groupTitle,
                        url = channel.streamUrl,
                        isLive = true,
                    )
                }
                kind == PlayableKind.MOVIE -> libraryDao.movie(id)?.let {
                    Playable(
                        title = it.title,
                        subtitle = it.year?.toString(),
                        url = it.streamUrl,
                        isLive = false,
                    )
                }
                else -> libraryDao.episode(id)?.let { episode ->
                    seriesId = episode.seriesId
                    val series = libraryDao.seriesById(episode.seriesId)
                    Playable(
                        title = episode.title,
                        subtitle = buildString {
                            series?.let { append(it.title).append(" · ") }
                            append("S%02dE%02d".format(episode.season, episode.episode))
                        },
                        url = episode.streamUrl,
                        isLive = false,
                    )
                }
            }
            _playable.value = resolved
            _missing.value = resolved == null
        }
    }
}

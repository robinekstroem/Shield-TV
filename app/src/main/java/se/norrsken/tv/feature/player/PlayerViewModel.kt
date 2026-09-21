package se.norrsken.tv.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.norrsken.tv.data.db.dao.ChannelDao
import se.norrsken.tv.data.db.dao.LibraryDao
import se.norrsken.tv.data.player.PlayerFactory
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
) : ViewModel() {

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

    init {
        val kind = savedStateHandle.get<String>("kind")
            ?.let { runCatching { PlayableKind.valueOf(it) }.getOrNull() }
        val id = savedStateHandle.get<String>("id")?.toLongOrNull()

        viewModelScope.launch {
            val resolved = when {
                kind == null || id == null -> null
                kind == PlayableKind.CHANNEL -> channelDao.byId(id)?.let {
                    Playable(
                        title = it.name,
                        subtitle = it.groupTitle,
                        url = it.streamUrl,
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

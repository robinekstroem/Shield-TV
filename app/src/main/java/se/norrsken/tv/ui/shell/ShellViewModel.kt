package se.norrsken.tv.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import se.norrsken.tv.data.db.dao.ChannelDao
import se.norrsken.tv.data.db.dao.LibraryDao
import se.norrsken.tv.data.player.MiniPlayback
import se.norrsken.tv.data.player.PlaybackController
import javax.inject.Inject

/**
 * Siffrorna i menyns underrubriker.
 *
 * Mockupen sager "Over 10 000 kanaler" och "Tusentals titlar". Det ar inte
 * text — det ar pastaenden om anvandarens bibliotek. Darfor raknas de pa
 * riktigt, och visas bara nar det finns nagot att rakna.
 */
data class LibraryCounts(
    val channels: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
)

@HiltViewModel
class ShellViewModel @Inject constructor(
    channelDao: ChannelDao,
    libraryDao: LibraryDao,
    private val playback: PlaybackController,
) : ViewModel() {

    val mini: StateFlow<MiniPlayback?> = playback.mini

    fun heldPlayer() = playback.heldPlayer()

    fun stopPlayback() = playback.stop()

    /** Appen i bakgrunden — ljudet ska inte fortsätta i fickan. */
    fun pausePlayback() = playback.pause()

    val counts: StateFlow<LibraryCounts> = combine(
        channelDao.observeCount(),
        libraryDao.observeMovieCount(),
        libraryDao.observeSeriesCount(),
    ) { channels, movies, series ->
        LibraryCounts(channels, movies, series)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryCounts())
}

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
import se.norrsken.tv.data.db.entity.ChannelEntity
import se.norrsken.tv.data.player.StreamSourceFactory
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val channelDao: ChannelDao,
    val streamSources: StreamSourceFactory,
) : ViewModel() {

    private val _channel = MutableStateFlow<ChannelEntity?>(null)
    val channel: StateFlow<ChannelEntity?> = _channel.asStateFlow()

    init {
        // Kanal-id kommer från navigeringsrutten player/{channelId}.
        val id: Long = savedStateHandle.get<String>("channelId")?.toLongOrNull() ?: 0L
        viewModelScope.launch { _channel.value = channelDao.byId(id) }
    }
}

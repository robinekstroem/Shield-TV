package se.norrsken.tv.feature.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import se.norrsken.tv.data.db.dao.ChannelDao
import se.norrsken.tv.data.db.entity.ChannelEntity
import javax.inject.Inject

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    channelDao: ChannelDao,
) : ViewModel() {

    /**
     * Taket på 500 är tillfälligt. Fas 8 byter till Paging — en LazyColumn
     * med 20 000 rader i minnet fungerar på en telefon men inte på en
     * TV-box med 2 GB RAM.
     */
    val channels: StateFlow<List<ChannelEntity>> =
        channelDao.observeChannels(categoryId = null, limit = 500)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

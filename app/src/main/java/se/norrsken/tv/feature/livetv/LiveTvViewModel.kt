package se.norrsken.tv.feature.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import se.norrsken.tv.data.db.dao.ChannelDao
import se.norrsken.tv.data.db.dao.PlaylistDao
import se.norrsken.tv.data.db.entity.CategoryEntity
import se.norrsken.tv.data.db.entity.ChannelEntity
import se.norrsken.tv.data.db.entity.ContentKind
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val channelDao: ChannelDao,
    playlistDao: PlaylistDao,
) : ViewModel() {

    /**
     * Grupperna ur spellistan — oftast land eller genre, beroende på vad
     * leverantören skrivit i group-title. Namnen hittas aldrig på: de är
     * exakt vad källan angav.
     */
    val categories: StateFlow<List<CategoryEntity>> =
        playlistDao.observeCategories(ContentKind.LIVE)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<Long?>(null)
    /** null = alla kanaler. */
    val selected: StateFlow<Long?> = _selected.asStateFlow()

    /**
     * Taket på 500 gäller per kategori. Det är därför kategorierna spelar
     * roll: en platt lista på 20 000 kanaler går varken att scrolla eller
     * hålla i minnet på en TV-box. Paging kommer i Fas 8.
     */
    val channels: StateFlow<List<ChannelEntity>> = _selected
        .flatMapLatest { categoryId ->
            channelDao.observeChannels(
                categoryId = categoryId,
                limit = se.norrsken.tv.feature.livetv.CHANNEL_PAGE,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(categoryId: Long?) {
        _selected.value = categoryId
    }
}

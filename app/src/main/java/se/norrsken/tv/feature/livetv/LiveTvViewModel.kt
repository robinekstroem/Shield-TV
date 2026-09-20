package se.norrsken.tv.feature.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import se.norrsken.tv.data.db.dao.ChannelDao
import se.norrsken.tv.data.db.dao.PlaylistDao
import se.norrsken.tv.data.db.entity.CategoryEntity
import se.norrsken.tv.data.db.entity.ChannelEntity
import se.norrsken.tv.data.db.entity.ContentKind
import se.norrsken.tv.data.settings.AppPreferences
import se.norrsken.tv.data.settings.CategorySort
import se.norrsken.tv.data.settings.ChannelSort
import se.norrsken.tv.data.settings.SettingsRepository
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val channelDao: ChannelDao,
    private val playlistDao: PlaylistDao,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val preferences = settings.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    fun cycleChannelSort() {
        val order = ChannelSort.entries
        val next = order[(order.indexOf(preferences.value.channelSort) + 1) % order.size]
        viewModelScope.launch { settings.setChannelSort(next) }
    }

    fun cycleCategorySort() {
        val order = CategorySort.entries
        val next = order[(order.indexOf(preferences.value.categorySort) + 1) % order.size]
        viewModelScope.launch { settings.setCategorySort(next) }
    }

    val channelSortLabel: StateFlow<String> = preferences
        .map { it.channelSort.label }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val categorySortLabel: StateFlow<String> = preferences
        .map { it.categorySort.label }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * Grupperna ur spellistan — oftast land eller genre, beroende på vad
     * leverantören skrivit i group-title. Namnen hittas aldrig på: de är
     * exakt vad källan angav.
     */
    val categories: StateFlow<List<CategoryEntity>> =
        preferences
            .flatMapLatest { playlistDao.observeCategories(ContentKind.LIVE, it.categorySort.code) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<Long?>(null)
    /** null = alla kanaler. */
    val selected: StateFlow<Long?> = _selected.asStateFlow()

    /**
     * Taket på 500 gäller per kategori. Det är därför kategorierna spelar
     * roll: en platt lista på 20 000 kanaler går varken att scrolla eller
     * hålla i minnet på en TV-box. Paging kommer i Fas 8.
     */
    val channels: StateFlow<List<ChannelEntity>> =
        combine(_selected, preferences) { categoryId, prefs -> categoryId to prefs.channelSort }
            .flatMapLatest { (categoryId, sort) ->
                channelDao.observeChannels(
                    categoryId = categoryId,
                    sort = sort.code,
                    limit = CHANNEL_PAGE,
                )
            }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(categoryId: Long?) {
        _selected.value = categoryId
    }
}

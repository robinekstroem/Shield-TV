package se.norrsken.tv.data.settings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import se.norrsken.tv.data.db.dao.SettingsDao
import se.norrsken.tv.data.db.entity.SettingEntity
import se.norrsken.tv.data.remote.PlaylistDownloader
import se.norrsken.tv.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enda vagen in och ut ur installningarna.
 *
 * Lagren som efterfragades: UI laser [preferences] och anropar set-metoderna.
 * UI kanner inte till nycklarna, databasen eller hur ett varde tolkas.
 * Motorerna — spelare, importer, tema — laser samma Flow och reagerar.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dao: SettingsDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    val preferences: Flow<AppPreferences> = dao.observeAll().map(::toPreferences)

    suspend fun current(): AppPreferences =
        withContext(io) { toPreferences(dao.observeAllOnce()) }

    /**
     * En enda mappning. Fanns tidigare i tva kopior, vilket gor att nasta
     * falt som laggs till hamnar i den ena och inte i den andra — och felet
     * syns bara pa halva stallena.
     */
    private fun toPreferences(rows: List<SettingEntity>): AppPreferences {
        val map = rows.associate { it.key to it.value }
        return AppPreferences(
            userAgent = map[USER_AGENT]?.takeIf { it.isNotBlank() }
                ?: PlaylistDownloader.DEFAULT_USER_AGENT,
            buffer = map[BUFFER].toEnum(BufferProfile.AUTO),
            decoder = map[DECODER].toEnum(DecoderMode.AUTO),
            matchFrameRate = map[FRAME_RATE]?.toBooleanStrictOrNull() ?: true,
            preferSurround = map[SURROUND]?.toBooleanStrictOrNull() ?: true,
            infoPanelSeconds = map[INFO_PANEL]?.toIntOrNull()?.coerceIn(2, 15) ?: 4,
            showClock = map[CLOCK]?.toBooleanStrictOrNull() ?: true,
            confirmExit = map[CONFIRM_EXIT]?.toBooleanStrictOrNull() ?: true,
            librarySort = map[LIBRARY_SORT].toEnum(LibrarySort.RECENT),
            channelSort = map[CHANNEL_SORT].toEnum(ChannelSort.PROVIDER),
            categorySort = map[CATEGORY_SORT].toEnum(CategorySort.PROVIDER),
            theme = map[THEME].toEnum(ThemeChoice.NORRSKEN),
            interfaceStyle = map[INTERFACE_STYLE].toEnum(InterfaceStyle.STANDARD),
            textScale = map[TEXT_SCALE].toEnum(TextScale.MEDIUM),
            accent = map[ACCENT].toEnum(AccentChoice.THEME),
            videoScale = map[VIDEO_SCALE].toEnum(VideoScale.FIT),
            videoQuality = map[VIDEO_QUALITY].toEnum(VideoQuality.AUTO),
            tunneling = map[TUNNELING]?.toBooleanStrictOrNull() ?: false,
            audioLanguage = map[AUDIO_LANGUAGE].toEnum(MediaLanguage.SWEDISH),
            volumeBoost = map[VOLUME_BOOST].toEnum(VolumeBoost.OFF),
            subtitleLanguage = map[SUBTITLE_LANGUAGE].toEnum(MediaLanguage.SWEDISH),
            autoSubtitles = map[AUTO_SUBTITLES]?.toBooleanStrictOrNull() ?: false,
            effects = map[EFFECTS].toEnum(EffectLevel.FULL),
            epgDays = map[EPG_DAYS].toEnum(EpgDays.THREE),
            epgOffsetMinutes = map[EPG_OFFSET]?.toIntOrNull()?.coerceIn(-720, 720) ?: 0,
            epgLastUpdateUtc = map[EPG_LAST_UPDATE]?.toLongOrNull(),
            subtitleSize = map[SUBTITLE_SIZE].toEnum(SubtitleSize.NORMAL),
            subtitleBackground = map[SUBTITLE_BG].toEnum(SubtitleBackground.SHADOW),
            hiddenCategories = map[HIDDEN_CATEGORIES].toLines().toSet(),
            hiddenKeywords = map[HIDDEN_KEYWORDS].toLines(),
            uiOpacity = map[UI_OPACITY]?.toIntOrNull()?.coerceIn(60, 100) ?: 100,
            tmdbKey = map[TMDB_KEY]?.takeIf { it.isNotBlank() },
            metadataLanguage = map[METADATA_LANGUAGE] ?: "sv-SE",
        )
    }

    suspend fun setUserAgent(value: String) = put(USER_AGENT, value.trim())
    suspend fun setBuffer(value: BufferProfile) = put(BUFFER, value.name)
    suspend fun setDecoder(value: DecoderMode) = put(DECODER, value.name)
    suspend fun setMatchFrameRate(value: Boolean) = put(FRAME_RATE, value.toString())
    suspend fun setPreferSurround(value: Boolean) = put(SURROUND, value.toString())
    suspend fun setInfoPanelSeconds(value: Int) = put(INFO_PANEL, value.toString())
    suspend fun setShowClock(value: Boolean) = put(CLOCK, value.toString())
    suspend fun setConfirmExit(value: Boolean) = put(CONFIRM_EXIT, value.toString())
    suspend fun setLibrarySort(value: LibrarySort) = put(LIBRARY_SORT, value.name)
    suspend fun setChannelSort(value: ChannelSort) = put(CHANNEL_SORT, value.name)
    suspend fun setCategorySort(value: CategorySort) = put(CATEGORY_SORT, value.name)
    suspend fun setTheme(value: ThemeChoice) = put(THEME, value.name)
    suspend fun setInterfaceStyle(value: InterfaceStyle) = put(INTERFACE_STYLE, value.name)
    suspend fun setVideoScale(value: VideoScale) = put(VIDEO_SCALE, value.name)
    suspend fun setVideoQuality(value: VideoQuality) = put(VIDEO_QUALITY, value.name)
    suspend fun setTunneling(value: Boolean) = put(TUNNELING, value.toString())
    suspend fun setAudioLanguage(value: MediaLanguage) = put(AUDIO_LANGUAGE, value.name)
    suspend fun setVolumeBoost(value: VolumeBoost) = put(VOLUME_BOOST, value.name)
    suspend fun setSubtitleLanguage(value: MediaLanguage) = put(SUBTITLE_LANGUAGE, value.name)
    suspend fun setAutoSubtitles(value: Boolean) = put(AUTO_SUBTITLES, value.toString())
    suspend fun setEffects(value: EffectLevel) = put(EFFECTS, value.name)
    suspend fun setEpgDays(value: EpgDays) = put(EPG_DAYS, value.name)
    suspend fun setEpgOffset(minutes: Int) = put(EPG_OFFSET, minutes.coerceIn(-720, 720).toString())
    suspend fun setEpgLastUpdate(utc: Long) = put(EPG_LAST_UPDATE, utc.toString())
    suspend fun setSubtitleSize(value: SubtitleSize) = put(SUBTITLE_SIZE, value.name)
    suspend fun setSubtitleBackground(value: SubtitleBackground) = put(SUBTITLE_BG, value.name)

    suspend fun setCategoryHidden(key: String, hidden: Boolean) {
        val current = current().hiddenCategories
        val next = if (hidden) current + key else current - key
        put(HIDDEN_CATEGORIES, next.joinToString(LINE))
    }

    /** Visar alla enskilt dolda kategorier med ett visst prefix (kalla + typ). */
    suspend fun unhideCategoriesWithPrefix(prefix: String) {
        val next = current().hiddenCategories.filterNot { it.startsWith(prefix) }
        put(HIDDEN_CATEGORIES, next.joinToString(LINE))
    }

    suspend fun addHiddenKeyword(value: String) {
        val word = value.trim()
        if (word.isEmpty()) return
        val current = current().hiddenKeywords
        if (current.any { it.equals(word, ignoreCase = true) }) return
        put(HIDDEN_KEYWORDS, (current + word).joinToString(LINE))
    }

    suspend fun removeHiddenKeyword(value: String) {
        val next = current().hiddenKeywords.filterNot { it.equals(value, ignoreCase = true) }
        put(HIDDEN_KEYWORDS, next.joinToString(LINE))
    }

    suspend fun setTextScale(value: TextScale) = put(TEXT_SCALE, value.name)
    suspend fun setAccent(value: AccentChoice) = put(ACCENT, value.name)
    suspend fun setUiOpacity(value: Int) = put(UI_OPACITY, value.coerceIn(60, 100).toString())

    suspend fun setTmdbKey(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) withContext(io) { dao.clear(TMDB_KEY) } else put(TMDB_KEY, trimmed)
    }

    suspend fun setMetadataLanguage(value: String) = put(METADATA_LANGUAGE, value)

    private suspend fun put(key: String, value: String) = withContext(io) {
        dao.put(SettingEntity(key, value))
    }

    private companion object {
        const val USER_AGENT = "playback.user_agent"
        const val BUFFER = "playback.buffer"
        const val DECODER = "playback.decoder"
        const val FRAME_RATE = "playback.match_frame_rate"
        const val SURROUND = "playback.prefer_surround"
        const val INFO_PANEL = "player.info_panel_seconds"
        const val CLOCK = "player.show_clock"
        const val CONFIRM_EXIT = "general.confirm_exit"
        const val LIBRARY_SORT = "sort.library"
        const val CHANNEL_SORT = "sort.channels"
        const val CATEGORY_SORT = "sort.categories"
        const val THEME = "appearance.theme"
        const val INTERFACE_STYLE = "appearance.interface_style"
        const val VIDEO_SCALE = "player.video_scale"
        const val VIDEO_QUALITY = "picture.quality"
        const val TUNNELING = "picture.tunneling"
        const val AUDIO_LANGUAGE = "audio.language"
        const val VOLUME_BOOST = "audio.volume_boost"
        const val SUBTITLE_LANGUAGE = "subtitles.language"
        const val AUTO_SUBTITLES = "subtitles.auto"
        const val EFFECTS = "appearance.effects"
        const val EPG_DAYS = "epg.days"
        const val EPG_OFFSET = "epg.offset_minutes"
        const val EPG_LAST_UPDATE = "epg.last_update"
        const val SUBTITLE_SIZE = "subtitles.size"
        const val SUBTITLE_BG = "subtitles.background"
        const val HIDDEN_CATEGORIES = "categories.hidden"
        const val HIDDEN_KEYWORDS = "categories.hidden_keywords"
        const val LINE = "\n"
        const val TEXT_SCALE = "appearance.text_scale"
        const val ACCENT = "appearance.accent"
        const val UI_OPACITY = "appearance.ui_opacity"
        const val TMDB_KEY = "metadata.tmdb_key"
        const val METADATA_LANGUAGE = "metadata.language"
    }
}

private fun String?.toLines(): List<String> =
    this.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
    this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

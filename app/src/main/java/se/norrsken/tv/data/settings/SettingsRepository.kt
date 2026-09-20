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
            textScale = map[TEXT_SCALE].toEnum(TextScale.MEDIUM),
            accent = map[ACCENT].toEnum(AccentChoice.BLUE),
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
        const val TEXT_SCALE = "appearance.text_scale"
        const val ACCENT = "appearance.accent"
        const val UI_OPACITY = "appearance.ui_opacity"
        const val TMDB_KEY = "metadata.tmdb_key"
        const val METADATA_LANGUAGE = "metadata.language"
    }
}

private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
    this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

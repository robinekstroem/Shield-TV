package se.norrsken.tv.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import se.norrsken.tv.data.db.dao.SettingsDao
import se.norrsken.tv.data.db.entity.SettingEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettings @Inject constructor(
    private val dao: SettingsDao,
) {
    /**
     * Användarens egen TMDB-nyckel.
     *
     * Ingen nyckel följer med appen. En API-nyckel i en APK är inte hemlig —
     * vem som helst kan packa upp filen och läsa den — och då är det mitt
     * konto som får kvoten och avstängningen. Därför registrerar användaren
     * en egen gratis nyckel och klistrar in den här.
     */
    fun tmdbKey(): Flow<String?> = dao.observe(KEY_TMDB).map { it?.takeIf(String::isNotBlank) }

    suspend fun tmdbKeyNow(): String? = dao.get(KEY_TMDB)?.takeIf(String::isNotBlank)

    suspend fun setTmdbKey(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) dao.clear(KEY_TMDB)
        else dao.put(SettingEntity(KEY_TMDB, trimmed))
    }

    fun metadataLanguage(): Flow<String> =
        dao.observe(KEY_LANGUAGE).map { it ?: DEFAULT_LANGUAGE }

    suspend fun metadataLanguageNow(): String = dao.get(KEY_LANGUAGE) ?: DEFAULT_LANGUAGE

    suspend fun setMetadataLanguage(value: String) {
        dao.put(SettingEntity(KEY_LANGUAGE, value))
    }

    private companion object {
        const val KEY_TMDB = "metadata.tmdb_key"
        const val KEY_LANGUAGE = "metadata.language"
        const val DEFAULT_LANGUAGE = "sv-SE"
    }
}

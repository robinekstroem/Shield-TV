package se.norrsken.tv.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import se.norrsken.tv.data.db.dao.PlaylistDao
import se.norrsken.tv.data.db.entity.CategoryEntity
import se.norrsken.tv.data.db.entity.ContentKind
import se.norrsken.tv.data.settings.AppPreferences
import se.norrsken.tv.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nyckeln en dold kategori sparas under.
 *
 * Kalla + typ + namn, inte databas-id: id:t byts vid varje uppdatering av
 * spellistan, namnet gor det inte. Nagot som dolts forblir dolt efter synk.
 */
fun CategoryEntity.visibilityKey(): String = "$playlistId:${kind.name}:$name"

/** Prefixet for alla nycklar fran en viss kalla och typ. */
fun visibilityPrefix(playlistId: Long, kind: ContentKind): String = "$playlistId:${kind.name}:"

/**
 * Forsta ordfilter som traffar namnet, eller null.
 *
 * Skiftlagesokansligt och pa delstrang: "arab" traffar "Arabic",
 * "ARABIC MOVIES" och "|AR| Arabiska". Forutsagbart — anvandaren skriver
 * det hon ser.
 */
fun matchingKeyword(name: String, keywords: List<String>): String? =
    keywords.firstOrNull { keyword ->
        val word = keyword.trim()
        word.isNotEmpty() && name.contains(word, ignoreCase = true)
    }

fun isCategoryHidden(category: CategoryEntity, preferences: AppPreferences): Boolean =
    category.visibilityKey() in preferences.hiddenCategories ||
        matchingKeyword(category.name, preferences.hiddenKeywords) != null

/**
 * Vilka kategorier som ar dolda, som id:n redo att skickas till SQL.
 *
 * Innehallet i dolda kategorier ska bort overallt — aven under "Alla" och
 * pa hemskarmen. Att bara ta bort raden i kategorilistan hade lamnat
 * filmerna kvar i allt annat.
 */
@Singleton
class CategoryVisibility @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val settings: SettingsRepository,
) {
    fun hiddenIds(kind: ContentKind): Flow<List<Long>> =
        combine(playlistDao.observeAllCategories(kind), settings.preferences) { categories, prefs ->
            categories.filter { isCategoryHidden(it, prefs) }.map { it.id }
        }
}

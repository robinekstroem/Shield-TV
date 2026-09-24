package se.norrsken.tv.data.repository

import kotlinx.coroutines.flow.Flow
import se.norrsken.tv.data.db.dao.FavoriteChannel
import se.norrsken.tv.data.db.dao.FavoriteMovie
import se.norrsken.tv.data.db.dao.FavoriteSeries
import se.norrsken.tv.data.db.dao.UserDataDao
import se.norrsken.tv.data.db.entity.FavoriteEntity
import se.norrsken.tv.data.db.entity.MediaKind
import javax.inject.Inject
import javax.inject.Singleton

private const val PROFILE = 0L

@Singleton
class FavoritesRepository @Inject constructor(private val dao: UserDataDao) {
    fun isFavorite(kind: MediaKind, refId: Long): Flow<Boolean> =
        dao.observeIsFavorite(PROFILE, kind, refId)

    fun movies(): Flow<List<FavoriteMovie>> = dao.observeFavoriteMovies(PROFILE)
    fun series(): Flow<List<FavoriteSeries>> = dao.observeFavoriteSeries(PROFILE)
    fun channels(): Flow<List<FavoriteChannel>> = dao.observeFavoriteChannels(PROFILE)

    /**
     * [currentlyFavorite] är läget just nu, inte det önskade. Hette
     * parametern "favorite" läste man den som "gör till favorit", och nästa
     * anropare hade fått tvärtom utan att något syntes.
     */
    suspend fun toggle(kind: MediaKind, refId: Long, currentlyFavorite: Boolean) {
        if (currentlyFavorite) {
            dao.removeFavorite(PROFILE, kind, refId)
        } else {
            dao.addFavorite(
                FavoriteEntity(
                    profileId = PROFILE,
                    kind = kind,
                    refId = refId,
                    addedUtc = System.currentTimeMillis(),
                )
            )
        }
    }
}

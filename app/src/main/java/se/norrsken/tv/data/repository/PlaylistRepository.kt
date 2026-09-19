package se.norrsken.tv.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import se.norrsken.tv.data.db.dao.PlaylistDao
import se.norrsken.tv.data.db.entity.PlaylistEntity
import se.norrsken.tv.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mönstret som resten av datalagret följer: ViewModels ser bara repositoryt,
 * aldrig Room. Byter vi lagring senare rör det inga skärmar.
 */
interface PlaylistRepository {
    fun observePlaylists(): Flow<List<PlaylistEntity>>
    suspend fun add(playlist: PlaylistEntity): Long
    suspend fun setEnabled(playlist: PlaylistEntity, enabled: Boolean)
    suspend fun remove(playlist: PlaylistEntity)
}

@Singleton
class RoomPlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : PlaylistRepository {

    override fun observePlaylists(): Flow<List<PlaylistEntity>> = dao.observeAll()

    override suspend fun add(playlist: PlaylistEntity): Long =
        withContext(io) { dao.insert(playlist) }

    override suspend fun setEnabled(playlist: PlaylistEntity, enabled: Boolean) =
        withContext(io) { dao.update(playlist.copy(enabled = enabled)) }

    override suspend fun remove(playlist: PlaylistEntity) =
        withContext(io) { dao.delete(playlist) }
}

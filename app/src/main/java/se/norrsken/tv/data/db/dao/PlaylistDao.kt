package se.norrsken.tv.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import se.norrsken.tv.data.db.entity.CategoryEntity
import se.norrsken.tv.data.db.entity.ContentKind
import se.norrsken.tv.data.db.entity.PlaylistEntity

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY priority ASC, name ASC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE enabled = 1 ORDER BY priority ASC")
    suspend fun enabled(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    /**
     * Det enda en import får skriva på spellistan.
     *
     * Tidigare skrev importen tillbaka hela raden från sin egen kopia i
     * minnet. Den kopian hade lösenordet i klartext — så första importen
     * efter att ett konto lagts till skrev över det krypterade lösenordet
     * med klartext. En riktad UPDATE kan inte göra det, och kan heller inte
     * skriva över något användaren ändrat medan importen pågick.
     *
     * COALESCE: användarens egen EPG-adress vinner alltid över
     * leverantörens.
     */
    @Query(
        """
        UPDATE playlists
        SET epgUrl = COALESCE(epgUrl, :providerEpgUrl), lastSyncUtc = :syncedAt
        WHERE id = :id
        """
    )
    suspend fun markSynced(id: Long, providerEpgUrl: String?, syncedAt: Long)

    /** Användarens egen tablåadress. null återgår till källans egen. */
    @Query("UPDATE playlists SET epgUrl = :url WHERE id = :id")
    suspend fun setEpgUrl(id: Long, url: String?)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    /** Alla källors kategorier av ett slag, sammanslagna. */
    @Query(
        """
        SELECT * FROM categories WHERE kind = :kind AND hidden = 0
        ORDER BY
            CASE WHEN :sort = 0 THEN sortOrder END ASC,
            CASE WHEN :sort = 1 THEN LOWER(name) END ASC,
            LOWER(name) ASC
        """
    )
    fun observeCategories(kind: ContentKind, sort: Int): Flow<List<CategoryEntity>>

    /** Alla kategorier av en typ, aven dolda — for hanteringsskarmen. */
    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY LOWER(name)")
    fun observeAllCategories(kind: ContentKind): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE playlistId = :playlistId AND kind = :kind ORDER BY sortOrder, name")
    fun observeCategories(playlistId: Long, kind: ContentKind): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>): List<Long>

    @Query("DELETE FROM categories WHERE playlistId = :playlistId")
    suspend fun clearCategories(playlistId: Long)
}

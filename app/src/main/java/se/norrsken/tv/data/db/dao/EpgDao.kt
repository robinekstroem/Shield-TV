package se.norrsken.tv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import se.norrsken.tv.data.db.entity.EpgMappingEntity
import se.norrsken.tv.data.db.entity.EpgProgrammeEntity

@Dao
interface EpgDao {

    /** Allt som överlappar fönstret, inte bara det som börjar i det. */
    @Query(
        """
        SELECT * FROM epg_programmes
        WHERE epgChannelId IN (:epgChannelIds)
          AND endUtc > :fromUtc AND startUtc < :toUtc
        ORDER BY epgChannelId, startUtc
        """
    )
    suspend fun window(
        epgChannelIds: List<String>,
        fromUtc: Long,
        toUtc: Long,
    ): List<EpgProgrammeEntity>

    @Query(
        """
        SELECT * FROM epg_programmes
        WHERE epgChannelId = :epgChannelId AND endUtc > :nowUtc
        ORDER BY startUtc LIMIT 2
        """
    )
    suspend fun nowAndNext(epgChannelId: String, nowUtc: Long): List<EpgProgrammeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgrammes(programmes: List<EpgProgrammeEntity>)

    /** Körs efter varje synk. Utan den växer tabellen tills appen kryper. */
    @Query("DELETE FROM epg_programmes WHERE endUtc < :cutoffUtc")
    suspend fun pruneOlderThan(cutoffUtc: Long)

    @Query("SELECT * FROM epg_mappings WHERE channelId = :channelId")
    suspend fun mappingFor(channelId: Long): EpgMappingEntity?

    /** Manuella kopplingar överlever synken — därför IGNORE, inte REPLACE. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMappings(mappings: List<EpgMappingEntity>)

    @Query("DELETE FROM epg_mappings WHERE channelId = :channelId AND source != 'MANUAL'")
    suspend fun clearAutoMapping(channelId: Long)
}

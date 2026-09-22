package se.norrsken.tv.data.epg

import se.norrsken.tv.data.db.dao.EPG_CHUNK
import se.norrsken.tv.data.db.dao.EpgDao
import se.norrsken.tv.data.db.entity.ChannelEntity
import se.norrsken.tv.data.db.entity.EpgProgrammeEntity
import se.norrsken.tv.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Det som sänds nu och härnäst på en kanal. Tiderna är redan förskjutna. */
data class NowNext(
    val current: EpgProgrammeEntity?,
    val next: EpgProgrammeEntity?,
) {
    fun progress(now: Long): Float? = current?.let {
        val length = (it.endUtc - it.startUtc).coerceAtLeast(1)
        ((now - it.startUtc).toFloat() / length).coerceIn(0f, 1f)
    }
}

private const val LOOKAHEAD_MS = 8 * 3_600_000L

/**
 * Nu och nästa för många kanaler i ett svep.
 *
 * Två frågor oavsett antal kanaler — kopplingar, sedan program — i stället
 * för två per kanal. Med femhundra kanaler i listan är det skillnaden mellan
 * en lista som svarar och en som hackar.
 */
@Singleton
class NowPlayingRepository @Inject constructor(
    private val epgDao: EpgDao,
    private val settings: SettingsRepository,
) {
    suspend fun forChannels(channels: List<ChannelEntity>, now: Long): Map<Long, NowNext> {
        if (channels.isEmpty()) return emptyMap()

        val mappings = channels.map { it.id }.chunked(EPG_CHUNK).flatMap { epgDao.mappingsFor(it) }
        if (mappings.isEmpty()) return emptyMap()

        // Tiderna lagras i UTC. Förskjutningen läggs på vid visning, aldrig
        // i databasen — annars hade hela tablån fått skrivas om vid varje
        // ändring av inställningen.
        val offset = settings.current().epgOffsetMinutes * 60_000L
        val queryNow = now - offset

        val byChannel = mappings.map { it.epgChannelId }.distinct()
            .chunked(EPG_CHUNK)
            .flatMap { epgDao.window(it, queryNow, queryNow + LOOKAHEAD_MS) }
            .groupBy { it.epgChannelId }

        return mappings.associate { mapping ->
            val list = byChannel[mapping.epgChannelId].orEmpty().sortedBy { it.startUtc }
            val index = list.indexOfFirst { it.startUtc <= queryNow && it.endUtc > queryNow }
            val current = list.getOrNull(index)
            val next = if (index >= 0) {
                list.getOrNull(index + 1)
            } else {
                list.firstOrNull { it.startUtc > queryNow }
            }
            mapping.channelId to NowNext(current?.shifted(offset), next?.shifted(offset))
        }
    }
}

internal fun EpgProgrammeEntity.shifted(offsetMs: Long): EpgProgrammeEntity =
    if (offsetMs == 0L) this else copy(startUtc = startUtc + offsetMs, endUtc = endUtc + offsetMs)

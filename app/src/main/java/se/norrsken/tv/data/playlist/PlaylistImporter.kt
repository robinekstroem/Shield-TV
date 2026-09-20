package se.norrsken.tv.data.playlist

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import se.norrsken.tv.data.db.dao.ChannelDao
import se.norrsken.tv.data.db.dao.PlaylistDao
import se.norrsken.tv.data.db.entity.CategoryEntity
import se.norrsken.tv.data.db.entity.ChannelEntity
import se.norrsken.tv.data.db.entity.ContentKind
import se.norrsken.tv.data.db.entity.PlaylistEntity
import se.norrsken.tv.data.remote.PlaylistDownloader
import se.norrsken.tv.data.remote.PlaylistException
import se.norrsken.tv.data.settings.SettingsRepository
import se.norrsken.tv.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistImporter @Inject constructor(
    private val downloader: PlaylistDownloader,
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun import(playlist: PlaylistEntity): Flow<ImportState> = channelFlow {
        send(ImportState.Fetching("spellistan"))

        val parsed = try {
            downloader.open(playlist.url, settings.current().userAgent) { reader ->
                // channelFlow i stället för flow: trySend går att anropa
                // från parserns vanliga lambda, så räknaren tickar upp medan
                // filen läses i stället för att hoppa från 0 till allt.
                parseM3u(reader) { count -> trySend(ImportState.Parsing("kanaler", count)) }
            }
        } catch (e: PlaylistException) {
            send(ImportState.Failed(e.message ?: "Kunde inte hämta spellistan."))
            return@channelFlow
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            send(ImportState.Failed("Kunde inte läsa spellistan. Kontrollera att länken pekar på en M3U-fil."))
            return@channelFlow
        }

        if (parsed.entries.isEmpty()) {
            send(ImportState.Failed("Filen hämtades men innehöll inga kanaler."))
            return@channelFlow
        }

        send(ImportState.Parsing("kanaler", parsed.entries.size))

        // Gammalt innehåll bort först, annars dubbleras allt vid nästa synk.
        playlistDao.clearCategories(playlist.id)
        channelDao.clearForPlaylist(playlist.id)

        val groups = parsed.entries
            .mapNotNull { it.group }
            .distinct()
            .sorted()

        val categoryIds = playlistDao.insertCategories(
            groups.mapIndexed { index, group ->
                CategoryEntity(
                    playlistId = playlist.id,
                    kind = ContentKind.LIVE,
                    externalId = group,
                    name = group,
                    sortOrder = index,
                )
            }
        )
        val categoryByGroup = groups.zip(categoryIds).toMap()

        val total = parsed.entries.size
        var saved = 0

        // Chunkat: en enda insert med 20 000 rader bygger en enorm sats i
        // minnet, och användaren får ingen återkoppling förrän den är klar.
        parsed.entries.chunked(CHUNK).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            channelDao.insertAll(
                chunk.mapIndexed { indexInChunk, entry ->
                    entry.toChannel(
                        playlistId = playlist.id,
                        categoryId = entry.group?.let(categoryByGroup::get),
                        sortOrder = saved + indexInChunk,
                    )
                }
            )
            saved += chunk.size
            send(ImportState.Saving("kanaler", saved, total))
        }

        playlistDao.update(
            playlist.copy(
                // Leverantörens egen EPG-adress används bara om användaren
                // inte redan satt en. Användarens val vinner alltid.
                epgUrl = playlist.epgUrl ?: parsed.epgUrl,
                lastSyncUtc = System.currentTimeMillis(),
            )
        )

        send(ImportState.Done(channels = total, movies = 0, series = 0))
    }.flowOn(io)

    private companion object {
        const val CHUNK = 500
    }
}

private fun M3uEntry.toChannel(
    playlistId: Long,
    categoryId: Long?,
    sortOrder: Int,
) = ChannelEntity(
    playlistId = playlistId,
    categoryId = categoryId,
    name = name,
    normalizedName = normalizeChannelName(name),
    streamUrl = url,
    logoUrl = logoUrl,
    tvgId = tvgId,
    tvgName = tvgName,
    groupTitle = group,
    channelNumber = channelNumber,
    catchupDays = catchupDays,
    catchupSource = catchupSource,
    sortOrder = channelNumber ?: sortOrder,
)

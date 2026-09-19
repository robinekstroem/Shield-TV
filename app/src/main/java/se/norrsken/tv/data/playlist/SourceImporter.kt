package se.norrsken.tv.data.playlist

import kotlinx.coroutines.flow.Flow
import se.norrsken.tv.data.db.entity.PlaylistEntity
import se.norrsken.tv.data.db.entity.PlaylistType
import se.norrsken.tv.data.xtream.XtreamImporter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enda ingången till import. Skärmarna vet inte om källan är en M3U-fil
 * eller ett Xtream-konto — de får samma [ImportState] oavsett.
 */
@Singleton
class SourceImporter @Inject constructor(
    private val m3u: PlaylistImporter,
    private val xtream: XtreamImporter,
) {
    fun import(playlist: PlaylistEntity): Flow<ImportState> = when (playlist.type) {
        PlaylistType.M3U -> m3u.import(playlist)
        PlaylistType.XTREAM -> xtream.import(playlist)
    }
}

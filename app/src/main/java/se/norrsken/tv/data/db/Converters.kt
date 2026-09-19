package se.norrsken.tv.data.db

import androidx.room.TypeConverter
import se.norrsken.tv.data.db.entity.ContentKind
import se.norrsken.tv.data.db.entity.MappingSource
import se.norrsken.tv.data.db.entity.MatchState
import se.norrsken.tv.data.db.entity.MediaKind
import se.norrsken.tv.data.db.entity.PlaylistType

/**
 * Enums lagras som text, inte ordinal. Ordinal går sönder så fort någon
 * lägger till ett värde mitt i en enum.
 */
class Converters {
    @TypeConverter fun fromPlaylistType(v: PlaylistType): String = v.name
    @TypeConverter fun toPlaylistType(v: String): PlaylistType = PlaylistType.valueOf(v)

    @TypeConverter fun fromContentKind(v: ContentKind): String = v.name
    @TypeConverter fun toContentKind(v: String): ContentKind = ContentKind.valueOf(v)

    @TypeConverter fun fromMappingSource(v: MappingSource): String = v.name
    @TypeConverter fun toMappingSource(v: String): MappingSource = MappingSource.valueOf(v)

    @TypeConverter fun fromMatchState(v: MatchState): String = v.name
    @TypeConverter fun toMatchState(v: String): MatchState = MatchState.valueOf(v)

    @TypeConverter fun fromMediaKind(v: MediaKind): String = v.name
    @TypeConverter fun toMediaKind(v: String): MediaKind = MediaKind.valueOf(v)
}

package se.norrsken.tv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import se.norrsken.tv.data.db.dao.ChannelDao
import se.norrsken.tv.data.db.dao.EpgDao
import se.norrsken.tv.data.db.dao.LibraryDao
import se.norrsken.tv.data.db.dao.PlaylistDao
import se.norrsken.tv.data.db.dao.SettingsDao
import se.norrsken.tv.data.db.dao.UserDataDao
import se.norrsken.tv.data.db.entity.CategoryEntity
import se.norrsken.tv.data.db.entity.ChannelEntity
import se.norrsken.tv.data.db.entity.EpgMappingEntity
import se.norrsken.tv.data.db.entity.EpgProgrammeEntity
import se.norrsken.tv.data.db.entity.EpisodeEntity
import se.norrsken.tv.data.db.entity.FavoriteEntity
import se.norrsken.tv.data.db.entity.MovieEntity
import se.norrsken.tv.data.db.entity.PlaylistEntity
import se.norrsken.tv.data.db.entity.ProfileEntity
import se.norrsken.tv.data.db.entity.SeriesEntity
import se.norrsken.tv.data.db.entity.SettingEntity
import se.norrsken.tv.data.db.entity.WatchHistoryEntity

@Database(
    entities = [
        PlaylistEntity::class,
        CategoryEntity::class,
        ChannelEntity::class,
        EpgProgrammeEntity::class,
        EpgMappingEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        ProfileEntity::class,
        FavoriteEntity::class,
        WatchHistoryEntity::class,
        SettingEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NorrskenDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun epgDao(): EpgDao
    abstract fun libraryDao(): LibraryDao
    abstract fun userDataDao(): UserDataDao
    abstract fun settingsDao(): SettingsDao
}

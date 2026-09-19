package se.norrsken.tv.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import se.norrsken.tv.data.db.NorrskenDatabase
import se.norrsken.tv.data.db.dao.ChannelDao
import se.norrsken.tv.data.db.dao.EpgDao
import se.norrsken.tv.data.db.dao.LibraryDao
import se.norrsken.tv.data.db.dao.PlaylistDao
import se.norrsken.tv.data.db.dao.SettingsDao
import se.norrsken.tv.data.db.dao.UserDataDao
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): NorrskenDatabase =
        Room.databaseBuilder(context, NorrskenDatabase::class.java, "norrsken.db")
            // Import och tablåuppdatering skriver stora mängder på en gång.
            // WAL låter läsningar fortsätta medan det pågår, så listan slutar
            // inte svara mitt under en synk.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // Schemat ändras fortfarande i varje fas. Tills version 1.0 är
            // spikad kastas databasen i stället för att migreras — annars
            // måste du avinstallera appen efter varje ändring.
            //
            // MÅSTE bort innan release. Då är det riktiga migrationer som
            // gäller, annars tappar användare favoriter och historik.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun playlistDao(db: NorrskenDatabase): PlaylistDao = db.playlistDao()
    @Provides fun channelDao(db: NorrskenDatabase): ChannelDao = db.channelDao()
    @Provides fun epgDao(db: NorrskenDatabase): EpgDao = db.epgDao()
    @Provides fun libraryDao(db: NorrskenDatabase): LibraryDao = db.libraryDao()
    @Provides fun userDataDao(db: NorrskenDatabase): UserDataDao = db.userDataDao()
    @Provides fun settingsDao(db: NorrskenDatabase): SettingsDao = db.settingsDao()

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
}

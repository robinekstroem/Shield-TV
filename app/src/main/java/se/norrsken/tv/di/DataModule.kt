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
import se.norrsken.tv.data.db.NorrskenMigrations
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
            // Ingen destruktiv migration. Anvandarens favoriter, historik,
            // spellistor och EPG-kopplingar ska overleva en uppdatering.
            //
            // Version 1 ar nu fryst. Varje andring av en entitet kraver
            // hadanefter en ny version OCH en Migration i NorrskenMigrations
            // — annars vagrar Room starta, vilket ar ratt beteende: ett
            // kraschande bygge ar battre an tyst dataforlust.
            .addMigrations(*NorrskenMigrations.ALL)
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

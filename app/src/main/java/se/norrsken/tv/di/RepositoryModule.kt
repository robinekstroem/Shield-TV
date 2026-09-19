package se.norrsken.tv.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.norrsken.tv.data.repository.PlaylistRepository
import se.norrsken.tv.data.repository.RoomPlaylistRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun playlistRepository(impl: RoomPlaylistRepository): PlaylistRepository
}

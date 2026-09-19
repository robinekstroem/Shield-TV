package se.norrsken.tv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp

/**
 * Bildladdaren registreras explicit.
 *
 * Coil kan hitta nätverkshämtaren själv, men om den av någon anledning inte
 * gör det laddas inga bilder alls — varken kanallogotyper eller omslag — och
 * det syns bara som att allt är tomt. Ett kompileringsfel är lättare att
 * åtgärda än ett tyst fel.
 *
 * Klagar bygget på de här importerna: ta bort hela newImageLoader-metoden
 * och Factory-interfacet. Då sköter Coil registreringen på egen hand.
 */
@HiltAndroidApp
class NorrskenApp : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
}

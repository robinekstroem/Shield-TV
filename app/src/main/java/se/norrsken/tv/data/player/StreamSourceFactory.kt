package se.norrsken.tv.data.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import se.norrsken.tv.data.remote.PlaylistDownloader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nätverkslagret för uppspelning.
 *
 * Två inställningar är avgörande för IPTV och båda är avstängda som standard
 * i ExoPlayer:
 *
 * setAllowCrossProtocolRedirects — leverantörernas servrar skickar nästan
 * alltid vidare mellan http och https. Utan den dör strömmen direkt, med ett
 * fel som ser ut som om kanalen vore nere.
 *
 * User-Agent — många paneler svarar 403 om anropet inte ser ut som en
 * mediaspelare. Samma sak som vid spellisthämtningen, alltså samma sträng.
 */
@Singleton
class StreamSourceFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @OptIn(UnstableApi::class)
    fun dataSourceFactory(): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(PlaylistDownloader.DEFAULT_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        return DefaultDataSource.Factory(context, http)
    }

    /**
     * Låter ExoPlayer välja HLS, DASH eller rå TS utifrån innehållet i
     * stället för filändelsen — IPTV-URL:er ljuger ofta om formatet.
     */
    @OptIn(UnstableApi::class)
    fun mediaSourceFactory(): DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(dataSourceFactory())
}

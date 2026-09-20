package se.norrsken.tv.data.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import se.norrsken.tv.data.settings.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bygger spelaren utifran anvandarens val.
 *
 * Har landar uppspelningsinstallningarna pa riktigt. Varje varde nedan
 * andrar hur ExoPlayer beter sig — inget av det ar kosmetik.
 */
@Singleton
class PlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @OptIn(UnstableApi::class)
    fun create(preferences: AppPreferences): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            // Faller tillbaka till enhetens mjukvaruavkodare nar hardvaran
            // sager nej. Utan den ar en kanal med ovanlig profil bara svart.
            .setEnableDecoderFallback(preferences.decoder.allowFallback)

        val builder = ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(mediaSourceFactory(preferences))
            .setVideoChangeFrameRateStrategy(
                if (preferences.matchFrameRate) {
                    // Byter skarmens uppdateringsfrekvens nar det gar
                    // somloöst. 25 fps pa en 60 Hz-panel ger annars ryck.
                    C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                } else {
                    C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
                }
            )

        if (!preferences.buffer.isAutomatic) {
            val profile = preferences.buffer
            builder.setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        profile.minMs,
                        profile.maxMs,
                        profile.playbackMs,
                        profile.rebufferMs,
                    )
                    .build()
            )
        }

        return builder.build().apply {
            playWhenReady = true
            // Obegransat antal kanaler ar redan ExoPlayers standard, sa
            // "pa" behover inte satta nagot. Det ar "av" som maste gora
            // jobbet — annars vore reglaget utan funktion.
            if (!preferences.preferSurround) {
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setMaxAudioChannelCount(2)
                    .build()
            }
        }
    }

    /**
     * Tva installningar ar avgorande for IPTV och bada ar avstangda som
     * standard i ExoPlayer: korsprotokoll-omdirigering (leverantorerna
     * skickar standigt mellan http och https) och en spelarlik User-Agent
     * (manga paneler svarar annars 403).
     */
    @OptIn(UnstableApi::class)
    fun dataSourceFactory(preferences: AppPreferences): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(preferences.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        return DefaultDataSource.Factory(context, http)
    }

    @OptIn(UnstableApi::class)
    private fun mediaSourceFactory(preferences: AppPreferences): DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(dataSourceFactory(preferences))
}

package se.norrsken.tv.data.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dagger.hilt.android.qualifiers.ApplicationContext
import se.norrsken.tv.data.settings.AppPreferences
import se.norrsken.tv.data.settings.VideoQuality
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
            .setTrackSelector(trackSelector(preferences))
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
            // Egen ljudsession, satt direkt. Volymförstärkningen i spelaren
            // kopplas till sessionen, och utan ett fast id finns det inget
            // att koppla till förrän ljudet redan börjat.
            setAudioSessionId(Util.generateAudioSessionIdV21(context))
        }
    }

    /**
     * Spårvalet — där bild- och ljudinställningarna faktiskt landar.
     */
    @OptIn(UnstableApi::class)
    private fun trackSelector(preferences: AppPreferences): DefaultTrackSelector {
        val selector = DefaultTrackSelector(context)
        val params = selector.buildUponParameters()

        when (preferences.videoQuality) {
            VideoQuality.AUTO -> Unit
            // Hoppar över den adaptiva uppvärmningen. Ger skarp bild direkt,
            // men kräver att nätet faktiskt klarar högsta nivån.
            VideoQuality.HIGHEST -> params.setForceHighestSupportedBitrate(true)
            VideoQuality.DATA_SAVER -> params.setMaxVideoSize(1280, 720)
        }

        // Tunnlad uppspelning låter TV-hårdvaran synka ljud och bild själv.
        // Jämnare på Android TV — men bara där enheten stödjer det, och
        // ExoPlayer slår själv av det för spår som inte går att tunnla.
        params.setTunnelingEnabled(preferences.tunneling)

        preferences.audioLanguage.code?.let { params.setPreferredAudioLanguages(it) }

        if (preferences.autoSubtitles) {
            preferences.subtitleLanguage.code?.let { params.setPreferredTextLanguages(it) }
        } else {
            // Utan det här väljer ExoPlayer ändå spår som strömmen märkt som
            // standard. "Av" ska betyda av — bara tvingade undertexter, som
            // när någon i filmen talar ett tredje språk.
            params.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        }

        // Obegränsat antal kanaler är redan standard, så "på" behöver inget.
        // Det är "av" som måste göra jobbet.
        if (!preferences.preferSurround) {
            params.setMaxAudioChannelCount(2)
        }

        selector.setParameters(params.build())
        return selector
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

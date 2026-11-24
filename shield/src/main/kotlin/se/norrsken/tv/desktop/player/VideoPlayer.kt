package se.norrsken.tv.desktop.player

import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.norrsken.tv.desktop.data.AppDirs
import java.util.concurrent.atomic.AtomicLong

/**
 * Shield-versionen av spelaren: Media3 (ExoPlayer) med Shields
 * hårdvaruavkodare — 4K, HDR och ljud som Dolby och DTS skickas vidare till
 * tv:n eller förstärkaren när den klarar det. Klarar den inte ljudet avkodas
 * det med FFmpeg, i stället för att filmen spelas utan ljud. Samma namn och
 * samma gränssnitt som Windows-spelaren, så att den delade spelarskärmen
 * fungerar oförändrad.
 *
 * Bildfrekvensen: tv:n byter till filmens bara om den kan göra det utan att
 * bli svart. Annars står den kvar i sitt läge, så att start och stopp går
 * direkt.
 *
 * Allt mot ExoPlayer sker på huvudtråden, som Media3 kräver; anrop från
 * andra trådar läggs dit.
 */
@OptIn(UnstableApi::class)
class VideoPlayer(@Suppress("UNUSED_PARAMETER") engine: PlayerEngine) {

    enum class Phase { IDLE, OPENING, PLAYING, PAUSED, ENDED, ERROR }

    /** Hur bilden är kodad. Shield och tv:n sköter HDR själva; Windows använder det. */
    enum class Range { SDR, PQ, HLG }

    data class Track(val id: Int, val name: String, val language: String?)

    data class Status(
        val phase: Phase = Phase.IDLE,
        val timeMs: Long = 0L,
        val lengthMs: Long = 0L,
        val seekable: Boolean = false,
        val sampleAspect: Float = 1f,
        val hasVideo: Boolean = false,
        val stalled: Boolean = false,
        val session: Long = 0L,
        val audioTracks: List<Track> = emptyList(),
        val audioTrack: Int = -1,
        val subtitleTracks: List<Track> = emptyList(),
        val subtitleTrack: Int = -1,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
    )

    /** Det gränssnittet behåller mellan fönster. Bara UI-tråden rör det. */
    var surfaceState: AutoCloseable? = null

    @Volatile var preferredAudio: String? = null
    @Volatile var preferredSubtitle: String? = null

    /** Vad titeln säger om HDR. Behövs inte här: Shield och tv:n visar HDR som det är. */
    @Volatile var rangeHint: Range? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _cues = MutableStateFlow<List<String>>(emptyList())
    /** Undertexten just nu, rad för rad. Ritas av videoytan. */
    val cues: StateFlow<List<String>> = _cues.asStateFlow()

    private var exo: ExoPlayer? = null
    private val sessions = AtomicLong(0)
    private var currentSession = 0L
    private var desiredVolume = 80
    private var desiredMute = false
    private var poller: Job? = null
    private var firstFrame = false
    private var failed = false
    private var audioChosen = false
    private var subtitleChosen = false
    private var audioTracks: List<Track> = emptyList()
    private var subtitleTracks: List<Track> = listOf(Track(-1, "Av", null))
    private var audioTrack = -1
    private var subtitleTrack = -1
    private val trackRefs = HashMap<Int, Pair<TrackGroup, Int>>()

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            readTracks(tracks)
            applyPreferences()
        }

        override fun onCues(cueGroup: CueGroup) {
            _cues.value = cueGroup.cues.mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
        }

        override fun onRenderedFirstFrame() {
            firstFrame = true
        }

        override fun onPlayerError(error: PlaybackException) {
            failed = true
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun player(): ExoPlayer = exo ?: ExoPlayer.Builder(
        AppDirs.context,
        DefaultRenderersFactory(AppDirs.context)
            // Klarar inte Shields första avkodare en ström provas nästa, i stället för en svart bild.
            .setEnableDecoderFallback(true)
            // Ljud som varken Shield eller tv:n klarar — DTS och Dolby TrueHD i många
            // 4K-filmer — avkodas med FFmpeg. Det tv:n eller förstärkaren tar emot
            // skickas fortfarande vidare orört, med Atmos och allt.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
    )
        .setLooper(Looper.getMainLooper())
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Lite mer i förväg än Media3:s standard innan bilden startar, och
                // efter ett avbrott: IPTV-servrar levererar ojämnt.
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_AFTER_REBUFFER_MS,
                )
                .build(),
        )
        .build()
        .also { created ->
            created.addListener(listener)
            // Filmens bildfrekvens bara när tv:n kan byta utan att bli svart.
            created.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            // Spolning i 4K: närmaste nyckelbild upp till fyra sekunder före målet
            // visas direkt, i stället för att avkoda fram till exakt rätt bildruta.
            // Aldrig efter målet, så att Hoppa över intro inte missar något.
            created.setSeekParameters(SeekParameters(SEEK_TOLERANCE_US, 0L))
            // Wifi och processor hålls vakna medan något spelas: ingen strömsparpaus mitt i en 4K-film.
            created.setWakeMode(C.WAKE_MODE_NETWORK)
            exo = created
        }

    /** Videoytan kopplar sin SurfaceView hit. */
    fun attachSurface(view: SurfaceView) = onMain { player().setVideoSurfaceView(view) }

    fun detachSurface(view: SurfaceView) = onMain { exo?.clearVideoSurfaceView(view) }

    /** Startar strömmen och svarar med ett id som statusen för just den bär. */
    fun play(url: String, userAgent: String, live: Boolean, startMs: Long = 0L): Long {
        val session = sessions.incrementAndGet()
        onMain {
            val p = player()
            poller?.cancel()
            currentSession = session
            firstFrame = false
            failed = false
            audioChosen = false
            subtitleChosen = false
            audioTracks = emptyList()
            subtitleTracks = listOf(Track(-1, "Av", null))
            audioTrack = -1
            subtitleTrack = -1
            trackRefs.clear()
            _cues.value = emptyList()
            _status.value = Status(phase = Phase.OPENING, session = session)
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
            val builder = MediaItem.Builder().setUri(url)
            if (url.contains(".m3u8", ignoreCase = true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(AppDirs.context, http))
                .createMediaSource(builder.build())
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverrides()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                // Som "adaptive-logic=highest" i Windows-spelaren: bästa kvaliteten
                // direkt, i stället för en försiktig start som klättrar uppåt.
                .setForceHighestSupportedBitrate(true)
                // Ingen gräns vid menyernas 1080p: en 4K-ström spelas i 4K.
                .clearViewportSizeConstraints()
                .build()
            p.setMediaSource(source, startMs.coerceAtLeast(0L))
            p.prepare()
            p.playWhenReady = true
            applyAudio()
            poller = scope.launch {
                while (isActive) {
                    publish()
                    delay(250)
                }
            }
        }
        return session
    }

    fun togglePause() = onMain {
        exo?.let { it.playWhenReady = !it.playWhenReady }
    }

    fun seekBy(deltaMs: Long) = onMain {
        exo?.let {
            if (it.isCurrentMediaItemSeekable) {
                it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0L))
                // Tidslinjen visar den nya platsen direkt, inte den gamla en kvarts sekund till.
                publish()
            }
        }
    }

    fun seekTo(fraction: Float) = onMain {
        exo?.let {
            val duration = it.duration
            if (it.isCurrentMediaItemSeekable && duration != C.TIME_UNSET && duration > 0) {
                it.seekTo((duration * fraction.coerceIn(0f, 1f).toDouble()).toLong())
                publish()
            }
        }
    }

    fun setVolume(volume: Int) {
        desiredVolume = volume.coerceIn(0, 100)
        onMain { applyAudio() }
    }

    fun setMuted(muted: Boolean) {
        desiredMute = muted
        onMain { applyAudio() }
    }

    fun setAudioTrack(id: Int) = onMain { select(id, C.TRACK_TYPE_AUDIO) }

    fun setSubtitleTrack(id: Int) = onMain { if (id < 0) disableText() else select(id, C.TRACK_TYPE_TEXT) }

    fun stop() = onMain {
        poller?.cancel()
        poller = null
        exo?.stop()
        exo?.clearMediaItems()
        _cues.value = emptyList()
        _status.value = Status()
    }

    fun release(): Boolean {
        onMain {
            poller?.cancel()
            exo?.release()
            exo = null
            scope.cancel()
        }
        return true
    }

    private fun applyAudio() {
        exo?.volume = if (desiredMute) 0f else desiredVolume / 100f
    }

    private fun publish() {
        val p = exo ?: return
        val state = p.playbackState
        val phase = when {
            failed || p.playerError != null -> Phase.ERROR
            state == Player.STATE_ENDED -> Phase.ENDED
            state == Player.STATE_READY -> if (p.playWhenReady) Phase.PLAYING else Phase.PAUSED
            state == Player.STATE_BUFFERING && firstFrame -> if (p.playWhenReady) Phase.PLAYING else Phase.PAUSED
            state == Player.STATE_IDLE && currentSession == 0L -> Phase.IDLE
            else -> Phase.OPENING
        }
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        _status.value = Status(
            phase = phase,
            timeMs = p.currentPosition.coerceAtLeast(0L),
            lengthMs = if (p.isCurrentMediaItemLive) 0L else duration,
            seekable = p.isCurrentMediaItemSeekable,
            sampleAspect = p.videoFormat?.pixelWidthHeightRatio?.takeIf { it > 0f } ?: 1f,
            hasVideo = firstFrame,
            stalled = firstFrame && state == Player.STATE_BUFFERING && p.playWhenReady,
            session = currentSession,
            audioTracks = audioTracks,
            audioTrack = audioTrack,
            subtitleTracks = subtitleTracks,
            subtitleTrack = subtitleTrack,
            videoWidth = p.videoSize.width,
            videoHeight = p.videoSize.height,
        )
    }

    private fun readTracks(tracks: Tracks) {
        val audio = ArrayList<Track>()
        val text = ArrayList<Track>()
        trackRefs.clear()
        audioTrack = -1
        subtitleTrack = -1
        tracks.groups.forEachIndexed { groupIndex, group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val id = groupIndex * 100 + index
                val language = format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> {
                        val name = format.label ?: language?.let { languageName(it) } ?: "Spår ${audio.size + 1}"
                        val channels = when (format.channelCount) {
                            1 -> "  ·  mono"
                            2 -> "  ·  stereo"
                            6 -> "  ·  5.1"
                            8 -> "  ·  7.1"
                            else -> ""
                        }
                        val codec = codecName(format.sampleMimeType)?.let { "  ·  $it" }.orEmpty()
                        audio += Track(id, name + channels + codec, language)
                        if (group.isTrackSelected(index)) audioTrack = id
                    }
                    C.TRACK_TYPE_TEXT -> {
                        text += Track(id, format.label ?: language?.let { languageName(it) } ?: "Undertext ${text.size + 1}", language)
                        if (group.isTrackSelected(index)) subtitleTrack = id
                    }
                    else -> continue
                }
                trackRefs[id] = group.mediaTrackGroup to index
            }
        }
        audioTracks = audio
        subtitleTracks = listOf(Track(-1, "Av", null)) + text
    }

    /** Samma språk som förra gången, en gång per ström; därefter bestämmer användaren. */
    private fun applyPreferences() {
        if (!audioChosen && audioTracks.isNotEmpty()) {
            audioChosen = true
            val wanted = preferredAudio
            val match = if (wanted == null) null else audioTracks.firstOrNull { it.language.equals(wanted, ignoreCase = true) }
            if (match != null && match.id != audioTrack) select(match.id, C.TRACK_TYPE_AUDIO)
        }
        if (!subtitleChosen) {
            val wanted = preferredSubtitle
            val real = subtitleTracks.filter { it.id >= 0 }
            when {
                wanted == null -> subtitleChosen = true
                wanted.isEmpty() -> if (real.isNotEmpty()) {
                    subtitleChosen = true
                    disableText()
                }
                else -> real.firstOrNull { it.language.equals(wanted, ignoreCase = true) }?.let {
                    subtitleChosen = true
                    select(it.id, C.TRACK_TYPE_TEXT)
                }
            }
        }
    }

    private fun select(id: Int, type: Int) {
        val p = exo ?: return
        val (group, index) = trackRefs[id] ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group, index))
            .build()
        if (type == C.TRACK_TYPE_AUDIO) audioTrack = id else subtitleTrack = id
    }

    private fun disableText() {
        val p = exo ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        subtitleTrack = -1
        _cues.value = emptyList()
    }
}

/** Ljudformatets namn, som det står på skivomslag. Null: inget särskilt att säga. */
@OptIn(UnstableApi::class)
private fun codecName(mime: String?): String? = when (mime) {
    MimeTypes.AUDIO_AC3 -> "Dolby Digital"
    MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
    MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
    MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
    MimeTypes.AUDIO_DTS_X -> "DTS:X"
    else -> null
}

private const val BUFFER_FOR_PLAYBACK_MS = 1_500
private const val BUFFER_AFTER_REBUFFER_MS = 4_000

/** Hur långt före målet en nyckelbild får ligga vid spolning. */
private const val SEEK_TOLERANCE_US = 4_000_000L

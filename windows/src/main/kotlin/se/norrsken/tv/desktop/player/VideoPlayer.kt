package se.norrsken.tv.desktop.player

import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import java.awt.GraphicsEnvironment
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * VLC-spelaren, med bilden ritad av appen själv.
 *
 * VLC avkodar och lämnar varje bildruta i minnet; [FrameSink] lämnar den
 * vidare till Compose, som ritar den. Så kan kontrollerna ligga ovanpå
 * bilden, precis som spelarpanelen på Android — ett inbäddat VLC-fönster
 * hamnar alltid överst.
 *
 * Alla anrop till libvlc går via en egen tråd. stop() och play() väntar på
 * att den förra strömmen stängts, och en leverantör som inte svarar kan
 * hålla kvar det i sekunder — på UI-tråden hade fönstret frusit.
 */
class VideoPlayer(factory: MediaPlayerFactory) {

    enum class Phase { IDLE, OPENING, PLAYING, PAUSED, ENDED, ERROR }

    /**
     * Hur bilden är kodad: vanlig bild (SDR), HDR10 och Dolby Vision (PQ),
     * eller HLG. HDR görs om till vanlig bild på grafikkortet (se [YuvRenderer]);
     * utan det blir 4K-filmer bleka och gråa.
     */
    enum class Range { SDR, PQ, HLG }

    /** Ett ljud- eller undertextspår. [language] är VLC:s språknamn, för att hitta samma språk nästa gång. */
    data class Track(val id: Int, val name: String, val language: String?)

    data class Status(
        val phase: Phase = Phase.IDLE,
        val timeMs: Long = 0L,
        val lengthMs: Long = 0L,
        val seekable: Boolean = false,
        /** Pixelformen. 64/45 för 16:9-SD i 720x576 — utan den blir bilden hoptryckt. */
        val sampleAspect: Float = 1f,
        /** Minst en bildruta har kommit. */
        val hasVideo: Boolean = false,
        /** Spelar enligt VLC men inga nya bildrutor på två sekunder. */
        val stalled: Boolean = false,
        /** Vilken play() statusen hör till. */
        val session: Long = 0L,
        val audioTracks: List<Track> = emptyList(),
        val audioTrack: Int = -1,
        val subtitleTracks: List<Track> = emptyList(),
        val subtitleTrack: Int = -1,
        /** Bildens upplösning som den kommer från VLC. */
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vlc-kontroll").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    /** YUV-plan till grafikkortet när det går, annars färdig RGB från VLC. */
    /**
     * Det gränssnittet behåller mellan fönster, i praktiken rutan som visas.
     * Bara UI-tråden rör det; stängs när spelaren stängs.
     */
    var surfaceState: AutoCloseable? = null

    val frames = FrameSink(yuv = YuvRenderer.available)

    private val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var desiredVolume = 80
    @Volatile private var desiredMute = false

    /** Ljudspråk att välja när en ström startar. Null låter VLC välja. */
    @Volatile var preferredAudio: String? = null

    /** Undertextspråk att välja när en ström startar. "" betyder av, null låter VLC välja. */
    @Volatile var preferredSubtitle: String? = null

    /** Vad titeln säger om HDR, sätts före play(). Null: spelaren avgör efter strömmen. */
    @Volatile var rangeHint: Range? = null

    private val sessions = AtomicLong(0)
    private var currentSession = 0L
    private var poller: Job? = null
    private var sampleAspect: Float? = null
    private var aspectReads = 0
    private var audioTracks = emptyList<Track>()
    private var audioTrack = -1
    private var subtitleTracks = emptyList<Track>()
    private var subtitleTrack = -1
    private var audioChosen = false
    private var subtitleChosen = false
    private var playingSince = 0L
    private var rangeKnown = false
    private var rangeReads = 0

    init {
        player.videoSurface().set(
            factory.videoSurfaces().newVideoSurface(frames.formatCallback, frames.renderCallback, false),
        )
    }

    /** Startar strömmen och svarar med ett id som statusen för just den bär. */
    fun play(url: String, userAgent: String, live: Boolean, startMs: Long = 0L): Long {
        val session = sessions.incrementAndGet()
        val hint = rangeHint
        scope.launch {
            poller?.cancelAndJoin()
            frames.reset()
            frames.range = hint ?: Range.SDR
            rangeKnown = hint != null
            rangeReads = 0
            sampleAspect = null
            aspectReads = 0
            audioTracks = emptyList()
            audioTrack = -1
            subtitleTracks = emptyList()
            subtitleTrack = -1
            audioChosen = false
            subtitleChosen = false
            playingSince = 0L
            currentSession = session
            _status.value = Status(phase = Phase.OPENING, session = session)
            val options = listOfNotNull(
                // Fortsätt där man slutade förra gången.
                if (startMs > 0L) ":start-time=${startMs / 1000.0}" else null,
                ":http-user-agent=$userAgent",
                // Lite mer buffert än VLC:s standard: IPTV-servrar hackar.
                ":network-caching=${if (live) 2000 else 3000}",
                // Tappar servern anslutningen mitt i en ström försöker VLC igen.
                ":http-reconnect",
                // HLS med flera kvaliteter: alltid den högsta, inte den VLC gissar
                // att bandbredden räcker till.
                ":adaptive-logic=highest",
            )
            if (!player.media().play(url, *options.toTypedArray())) {
                // Adressen gick inte ens att tolka som media.
                _status.value = Status(phase = Phase.ERROR, session = session)
                return@launch
            }
            poller = launch { pollStatus() }
        }
        return session
    }

    fun togglePause() {
        scope.launch { player.controls().pause() }
    }

    fun seekBy(deltaMs: Long) {
        scope.launch {
            if (player.status().isSeekable) player.controls().skipTime(deltaMs)
        }
    }

    fun seekTo(fraction: Float) {
        scope.launch {
            if (player.status().isSeekable) player.controls().setPosition(fraction.coerceIn(0f, 1f))
        }
    }

    fun setVolume(volume: Int) {
        desiredVolume = volume.coerceIn(0, 100)
        scope.launch { applyAudio() }
    }

    fun setMuted(muted: Boolean) {
        desiredMute = muted
        scope.launch { applyAudio() }
    }

    fun setAudioTrack(id: Int) {
        scope.launch {
            runCatching { player.audio().setTrack(id) }
            audioChosen = true
            readTracks()
            _status.value = _status.value.copy(audioTracks = audioTracks, audioTrack = audioTrack)
        }
    }

    fun setSubtitleTrack(id: Int) {
        scope.launch {
            runCatching { player.subpictures().setTrack(id) }
            subtitleChosen = true
            readTracks()
            _status.value = _status.value.copy(subtitleTracks = subtitleTracks, subtitleTrack = subtitleTrack)
        }
    }

    fun stop() {
        scope.launch {
            poller?.cancelAndJoin()
            poller = null
            player.controls().stop()
            frames.reset()
            audioTracks = emptyList()
            subtitleTracks = emptyList()
            _status.value = Status()
        }
    }

    /**
     * Vid avslut. Ger upp efter tre sekunder — en ström som hänger ska inte
     * hindra appen från att stänga. Svarar true om spelaren hann släppas.
     */
    fun release(): Boolean {
        val done = runBlocking {
            withTimeoutOrNull(3_000) {
                scope.launch {
                    poller?.cancelAndJoin()
                    player.controls().stop()
                    player.release()
                }.join()
                true
            } ?: false
        }
        frames.reset()
        executor.shutdownNow()
        return done
    }

    private suspend fun pollStatus() {
        var lastFrames = -1L
        var lastFrameAt = System.nanoTime()
        var tick = 0
        while (currentCoroutineContext().isActive) {
            val s = player.status()
            val phase = when (s.state()) {
                State.PLAYING -> Phase.PLAYING
                State.PAUSED -> Phase.PAUSED
                State.ENDED -> Phase.ENDED
                State.ERROR -> Phase.ERROR
                else -> Phase.OPENING
            }
            if (phase == Phase.PLAYING) {
                if (playingSince == 0L) playingSince = System.nanoTime()
                // Volymen går bara att sätta när ljudutgången finns, alltså
                // först när strömmen väl spelar.
                applyAudio()
                // Pixelformen är ofta okänd de första sekunderna i en direktsändning,
                // så den läses om en stund innan appen nöjer sig med kvadratiska pixlar.
                if ((sampleAspect == null || sampleAspect == 1f) && aspectReads < 20) {
                    aspectReads++
                    readSampleAspect()?.let { sampleAspect = it }
                }
                // Sa namnet inget om HDR: titta på strömmen själv.
                if (!rangeKnown && rangeReads < 20) {
                    rangeReads++
                    readRange()?.let {
                        rangeKnown = true
                        frames.range = it
                    }
                }
            }
            // Spåren ändras sällan; en gång i sekunden räcker.
            if (tick % 4 == 0 && (phase == Phase.PLAYING || phase == Phase.PAUSED)) {
                readTracks()
                applyPreferences()
            }
            tick++

            val count = frames.count
            val now = System.nanoTime()
            if (count != lastFrames) {
                lastFrames = count
                lastFrameAt = now
            }

            _status.value = Status(
                phase = phase,
                timeMs = s.time().coerceAtLeast(0L),
                lengthMs = s.length().coerceAtLeast(0L),
                seekable = s.isSeekable,
                sampleAspect = sampleAspect ?: 1f,
                hasVideo = count > 0,
                stalled = phase == Phase.PLAYING && count > 0 && now - lastFrameAt > 2_000_000_000L,
                session = currentSession,
                audioTracks = audioTracks,
                audioTrack = audioTrack,
                subtitleTracks = subtitleTracks,
                subtitleTrack = subtitleTrack,
                videoWidth = if (count > 0) frames.width else 0,
                videoHeight = if (count > 0) frames.height else 0,
            )
            delay(250)
        }
    }

    private fun applyAudio() {
        runCatching {
            val audio = player.audio()
            if (audio.isMute != desiredMute) audio.setMute(desiredMute)
            if (audio.volume() != desiredVolume) audio.setVolume(desiredVolume)
        }
    }

    private fun readTracks() {
        runCatching {
            audioTracks = player.audio().trackDescriptions().map { describeTrack(it.id(), it.description() ?: "") }
            audioTrack = player.audio().track()
            subtitleTracks = player.subpictures().trackDescriptions().map { describeTrack(it.id(), it.description() ?: "") }
            subtitleTrack = player.subpictures().track()
        }
    }

    /**
     * Väljer samma språk som förra gången, en gång per ström. Därefter
     * bestämmer användaren: ett eget val i spelaren skrivs aldrig över.
     */
    private fun applyPreferences() {
        if (!audioChosen) {
            val real = audioTracks.filter { it.id >= 0 }
            if (real.isNotEmpty()) {
                audioChosen = true
                val wanted = preferredAudio
                val match = if (wanted == null) null else real.firstOrNull { it.language.equals(wanted, ignoreCase = true) }
                if (match != null && match.id != audioTrack) {
                    runCatching { player.audio().setTrack(match.id) }
                    audioTrack = match.id
                }
            }
        }
        if (!subtitleChosen) {
            val wanted = preferredSubtitle
            val real = subtitleTracks.filter { it.id >= 0 }
            if (wanted == null) {
                subtitleChosen = true
            } else if (wanted.isEmpty()) {
                if (real.isNotEmpty()) {
                    subtitleChosen = true
                    if (subtitleTrack >= 0) {
                        runCatching { player.subpictures().setTrack(-1) }
                        subtitleTrack = -1
                    }
                }
            } else {
                val match = real.firstOrNull { it.language.equals(wanted, ignoreCase = true) }
                if (match != null) {
                    subtitleChosen = true
                    if (match.id != subtitleTrack) {
                        runCatching { player.subpictures().setTrack(match.id) }
                        subtitleTrack = match.id
                    }
                } else if (secondsPlaying() > 15) {
                    // DVB-undertexter dyker upp först efter några sekunder. Efter
                    // en kvart minut finns de inte.
                    subtitleChosen = true
                }
            }
        }
    }

    private fun secondsPlaying(): Long =
        if (playingSince == 0L) 0L else (System.nanoTime() - playingSince) / 1_000_000_000L

    /**
     * VLC lämnar bildrutorna i lagrad storlek. En 16:9-kanal i SD är 720x576
     * med breda pixlar; ritas den som den är blir den hoptryckt till 5:4.
     */
    private fun readSampleAspect(): Float? = runCatching {
        val track = player.media().info().videoTracks().firstOrNull() ?: return null
        val num = track.sampleAspectRatio()
        val den = track.sampleAspectRatioBase()
        if (num > 0 && den > 0) num.toFloat() / den else 1f
    }.getOrNull()

    /**
     * HDR utan märkning i namnet: 4K i HEVC Main 10 (eller VP9 med tio bitar)
     * är i praktiken alltid HDR10 eller Dolby Vision. Allt annat är vanlig
     * bild. Null så länge VLC inte vet än — då frågar spelaren igen.
     */
    private fun readRange(): Range? = runCatching {
        val track = player.media().info().videoTracks().firstOrNull() ?: return null
        if (track.width() <= 0) return null
        val codec = fourcc(track.codec())
        val hevc = codec.equals("hevc", ignoreCase = true) || codec.equals("h265", ignoreCase = true)
        val vp9 = codec.equals("vp90", ignoreCase = true) || codec.equals("vp09", ignoreCase = true)
        val profile = track.profile()
        if ((hevc || vp9) && profile <= 0) return null
        val tenBit = (hevc && profile == 2) || (vp9 && profile >= 2)
        if (tenBit && track.width() >= 3200) Range.PQ else Range.SDR
    }.getOrNull()

    /** VLC:s codec-kod som de fyra bokstäverna den består av: "hevc", "VP90". */
    private fun fourcc(code: Int): String = String(CharArray(4) { ((code shr (8 * it)) and 0xFF).toChar() })
}

/** En bildruta från VLC. Den som har den äger den och stänger den när en ny tar över. */
sealed class Frame(val width: Int, val height: Int) {
    abstract fun close()

    class Rgba(width: Int, height: Int, val bitmap: Bitmap) : Frame(width, height) {
        override fun close() {
            bitmap.close()
        }
    }

    class Yuv(width: Int, height: Int, val y: Bitmap, val u: Bitmap, val v: Bitmap) : Frame(width, height) {
        override fun close() {
            y.close()
            u.close()
            v.close()
        }
    }
}

/**
 * Tar emot bildrutorna från VLC:s tråd och lämnar över dem till UI-tråden.
 *
 * Med [yuv] kommer rutan som tre plan i full upplösning (I420) och blir färg
 * först på grafikkortet, se [YuvRenderer]. Halva datamängden mot RGB, så även
 * 4K klarar sig utan nedskalning. Utan den gör VLC om till RGB, begränsat
 * till skärmens upplösning.
 *
 * Överlämningen sker via [take], som ger UI-tråden ensam äganderätt — en ruta
 * som aldrig hann hämtas stängs av VLC-tråden när nästa kommer. Så kan ingen
 * tråd stänga en bild som den andra ritar.
 */
class FrameSink(val yuv: Boolean) {
    private val pending = AtomicReference<Frame?>(null)
    private val counter = AtomicLong(0)
    private val scratch = arrayOfNulls<ByteArray>(3) // bara VLC:s rendertråd rör dem

    /** Ökar för varje ny ruta. Compose läser den i ritsteget och ritar om. */
    val signal = mutableLongStateOf(0L)

    val count: Long get() = counter.get()

    @Volatile
    var width = 0
        private set

    @Volatile
    var height = 0
        private set

    /** Hur rutorna är kodade: HDR görs om till vanlig bild när de ritas. */
    @Volatile
    var range: VideoPlayer.Range = VideoPlayer.Range.SDR

    val formatCallback: BufferFormatCallback = object : BufferFormatCallbackAdapter() {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            val size = limit(sourceWidth, sourceHeight)
            val w = size.first
            val h = size.second
            this@FrameSink.width = w
            this@FrameSink.height = h
            return if (yuv) {
                val cw = (w + 1) / 2
                val ch = (h + 1) / 2
                BufferFormat("I420", w, h, intArrayOf(align(w), align(cw), align(cw)), intArrayOf(h, ch, ch))
            } else {
                BufferFormat("RGBA", w, h, intArrayOf(w * 4), intArrayOf(h))
            }
        }
    }

    val renderCallback: RenderCallback = object : RenderCallback {
        override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<ByteBuffer>, bufferFormat: BufferFormat) {
            val w = bufferFormat.width
            val h = bufferFormat.height
            if (w <= 0 || h <= 0 || nativeBuffers.isEmpty()) return
            val frame = if (yuv) yuvFrame(nativeBuffers, w, h) else rgbaFrame(nativeBuffers[0], w, h)
            if (frame != null) {
                pending.getAndSet(frame)?.close()
                signal.longValue = counter.incrementAndGet()
            }
        }
    }

    /** Senaste rutan, om en ny kommit. Den som tar den ansvarar för att stänga den. */
    fun take(): Frame? = pending.getAndSet(null)

    fun reset() {
        pending.getAndSet(null)?.close()
        counter.set(0)
        range = VideoPlayer.Range.SDR
    }

    private fun yuvFrame(buffers: Array<ByteBuffer>, w: Int, h: Int): Frame? {
        if (buffers.size < 3) return null
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val y = plane(buffers[0], 0, w, h, align(w), ColorType.GRAY_8) ?: return null
        val u = plane(buffers[1], 1, cw, ch, align(cw), ColorType.GRAY_8)
        val v = plane(buffers[2], 2, cw, ch, align(cw), ColorType.GRAY_8)
        if (u == null || v == null) {
            y.close()
            u?.close()
            v?.close()
            return null
        }
        return Frame.Yuv(w, h, y, u, v)
    }

    // RGB_888X: fjärde byten ignoreras, oavsett vad VLC lägger i alfakanalen.
    private fun rgbaFrame(buffer: ByteBuffer, w: Int, h: Int): Frame? =
        plane(buffer, 0, w, h, w * 4, ColorType.RGB_888X)?.let { Frame.Rgba(w, h, it) }

    private fun plane(buffer: ByteBuffer, index: Int, w: Int, h: Int, pitch: Int, type: ColorType): Bitmap? {
        val size = pitch * h
        if (buffer.capacity() < size) return null
        var bytes = scratch[index]
        if (bytes == null || bytes.size != size) {
            bytes = ByteArray(size)
            scratch[index] = bytes
        }
        buffer.get(0, bytes, 0, size)
        val bitmap = Bitmap()
        if (!bitmap.installPixels(ImageInfo(w, h, type, ColorAlphaType.OPAQUE), bytes, pitch)) {
            bitmap.close()
            return null
        }
        bitmap.setImmutable()
        return bitmap
    }

    /**
     * Full upplösning i YUV, upp till 4K. RGB är fyra byte per pixel och kopieras
     * två gånger per ruta, så där stannar bilden vid skärmens upplösning.
     */
    private fun limit(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 2 to 2
        val max = if (yuv) MAX_YUV else screenSize
        val scale = min(1.0, min(max.first.toDouble() / sourceWidth, max.second.toDouble() / sourceHeight))
        if (scale >= 1.0) return sourceWidth to sourceHeight
        return even((sourceWidth * scale).roundToInt()) to even((sourceHeight * scale).roundToInt())
    }

    private fun even(value: Int): Int = (value and 1.inv()).coerceAtLeast(2)

    private fun align(value: Int): Int = (value + 31) and 31.inv()

    private companion object {
        val MAX_YUV = 4096 to 2304

        /** Största anslutna skärmen, i fysiska pixlar. */
        val screenSize: Pair<Int, Int> by lazy {
            runCatching {
                val modes = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.displayMode }
                modes.maxOf { it.width } to modes.maxOf { it.height }
            }.getOrNull()?.takeIf { it.first > 0 && it.second > 0 } ?: (1920 to 1080)
        }
    }
}

/** Gör VLC:s spårbeskrivning ("Track 2 - [English]") läsbar: "Engelska · spår 2". */
internal fun describeTrack(id: Int, raw: String): VideoPlayer.Track {
    if (id < 0) return VideoPlayer.Track(id, "Av", null)
    val language = Regex("""\[([^\]]+)]""").find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    val number = Regex("""^Track\s+(\d+)""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
    val name = when {
        language != null -> languageName(language) + (if (number != null) "  ·  spår $number" else "")
        raw.startsWith("Closed captions", ignoreCase = true) -> "Textning (CC) " + raw.substring("Closed captions".length).trim()
        number != null -> "Spår $number"
        raw.isNotBlank() -> raw.trim()
        else -> "Spår $id"
    }
    return VideoPlayer.Track(id, name.trim(), language)
}


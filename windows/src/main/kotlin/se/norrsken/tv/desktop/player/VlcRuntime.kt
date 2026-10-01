package se.norrsken.tv.desktop.player

import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import java.io.File

/**
 * Hittar VLC och startar libvlc — en gång, första gången något ska spelas.
 *
 * I första hand den VLC som CI-bygget packat med appen, i
 * <app>/resources/vlc. Saknas den faller vlcj tillbaka på en installerad
 * VLC (64-bit) via sin vanliga sökning: registret, Program Files, PATH.
 *
 * Plugin-mappen behöver inte pekas ut: libvlccore letar efter plugins bredvid
 * sig själv, så dll-filerna och plugins/ ska bara ligga i samma mapp.
 */
object VlcRuntime {

    private val bundledDir: File? by lazy {
        System.getProperty("compose.application.resources.dir")
            ?.let { File(it, "vlc") }
            ?.takeIf { File(it, "libvlc.dll").isFile && File(it, "libvlccore.dll").isFile }
    }

    /** Kort beskrivning för inställningarna. */
    @Volatile
    var description: String = "Startas vid första uppspelningen"
        private set

    /** Varför VLC inte gick att starta, om det inte gick. */
    @Volatile
    var failure: String? = null
        private set

    private var factory: MediaPlayerFactory? = null

    /**
     * Anropas först av allt i main(). JNA läser jna.library.path en enda gång,
     * när biblioteket används första gången — efter det är det för sent.
     */
    fun prepare() {
        val dir = bundledDir ?: return
        val existing = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (existing.isNullOrBlank()) dir.absolutePath else dir.absolutePath + File.pathSeparator + existing,
        )
    }

    /** Blockerar medan libvlc laddar sina plugins. Anropas inte på UI-tråden. */
    @Synchronized
    fun factory(): MediaPlayerFactory? {
        factory?.let { return it }
        if (failure != null) return null
        return try {
            val dir = bundledDir
            if (dir != null) {
                // Före den installerade, om det finns en sådan också.
                NativeLibrary.addSearchPath("libvlc", dir.absolutePath)
                NativeLibrary.addSearchPath("libvlccore", dir.absolutePath)
            }
            MediaPlayerFactory(*ARGUMENTS).also {
                factory = it
                description = if (dir != null) "VLC som följer med appen" else "VLC installerad på datorn"
            }
        } catch (t: Throwable) {
            failure = t.message ?: t.javaClass.simpleName
            description = "VLC hittades inte"
            null
        }
    }

    @Synchronized
    fun release() {
        runCatching { factory?.release() }
        factory = null
    }

    private val ARGUMENTS = arrayOf(
        // Bilden ritas av appen, så allt VLC själv skulle skriva ovanpå den —
        // titel, volymstapel — hamnar annars inbränt i varje bildruta.
        "--no-video-title-show",
        "--no-osd",
        // Radflätade kanaler (576i, 1080i) avflätas med yadif i dubbel
        // bildfrekvens: 50 hela bilder i sekunden i stället för 25 kammade.
        "--deinterlace=-1",
        "--deinterlace-mode=yadif2x",
        // Om VLC någon gång måste skala själv (8K, eller RGB-reservvägen): Lanczos.
        "--swscale-mode=9",
        "--no-snapshot-preview",
        "--no-stats",
        "--quiet",
    )
}

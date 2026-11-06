package se.norrsken.tv.desktop.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import java.io.File

/**
 * Hittar VLC och startar libvlc — en gång, första gången något ska spelas.
 *
 * I första hand den VLC som bygget packat med appen, i <app>/resources/vlc.
 * Saknas den faller vlcj tillbaka på en installerad VLC via sin vanliga
 * sökning: i Windows registret, Program Files och PATH, på Mac
 * /Applications/VLC.app.
 *
 * Windows: dll-filerna och plugins/ ligger i samma mapp, och libvlccore
 * hittar plugins bredvid sig själv.
 *
 * Mac: VLC:s egen uppdelning, lib/ med dylib-filerna och plugins/ bredvid.
 * Där måste plugin-mappen pekas ut och libvlccore laddas före libvlc —
 * precis det vlcj gör för en installerad VLC på Mac.
 */
object VlcRuntime {

    private val isMac: Boolean = Platform.isMac()

    /** Den medföljande VLC-mappen, om den finns och är hel. */
    private val bundledDir: File? by lazy {
        candidateRoots().map { File(it, "vlc") }.firstOrNull { dir ->
            if (isMac) {
                File(dir, "lib/libvlc.dylib").exists() && File(dir, "lib/libvlccore.dylib").exists() &&
                    File(dir, "plugins").isDirectory
            } else {
                File(dir, "libvlc.dll").isFile && File(dir, "libvlccore.dll").isFile
            }
        }
    }

    /** Mappen med själva biblioteken: vlc/ i Windows, vlc/lib på Mac. */
    private val libraryDir: File?
        get() = bundledDir?.let { if (isMac) File(it, "lib") else it }

    /**
     * Där appens resurser ligger. Compose talar om det; på Mac räknas det
     * dessutom fram från programfilen i Tv.app, ifall den uppgiften saknas.
     */
    private fun candidateRoots(): List<File> = buildList {
        System.getProperty("compose.application.resources.dir")?.let { add(File(it)) }
        if (isMac) {
            // jpackage: …/Tv.app/Contents/MacOS/Tv → …/Tv.app/Contents/app/resources
            System.getProperty("jpackage.app-path")?.let { launcher ->
                File(launcher).parentFile?.parentFile?.let { contents -> add(File(contents, "app/resources")) }
            }
        }
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
        val dir = libraryDir ?: return
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
            val dir = libraryDir
            val created = when {
                dir != null && isMac -> {
                    // Pekas ut innan libvlc startar: det är då VLC läser in sina plugins.
                    setEnvironment("VLC_PLUGIN_PATH", File(bundledDir, "plugins").absolutePath)
                    NativeLibrary.addSearchPath("vlccore", dir.absolutePath)
                    NativeLibrary.addSearchPath("vlc", dir.absolutePath)
                    // Utan det här går libvlc inte att ladda på senare macOS.
                    NativeLibrary.getInstance("vlccore")
                    // Ingen sökning: en installerad VLC i /Applications får inte
                    // blandas ihop med den som följer med appen.
                    MediaPlayerFactory(null as NativeDiscovery?, *ARGUMENTS)
                }
                dir != null -> {
                    // Före den installerade, om det finns en sådan också.
                    NativeLibrary.addSearchPath("libvlc", dir.absolutePath)
                    NativeLibrary.addSearchPath("libvlccore", dir.absolutePath)
                    MediaPlayerFactory(*ARGUMENTS)
                }
                else -> MediaPlayerFactory(*ARGUMENTS)
            }
            factory = created
            description = if (dir != null) "VLC som följer med appen" else "VLC installerad på datorn"
            created
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

    /** setenv i C-biblioteket: libvlc läser miljön där, inte i Java. */
    interface CLibrary : Library {
        fun setenv(name: String, value: String, overwrite: Int): Int
    }

    private fun setEnvironment(name: String, value: String) {
        runCatching { Native.load("c", CLibrary::class.java).setenv(name, value, 1) }
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

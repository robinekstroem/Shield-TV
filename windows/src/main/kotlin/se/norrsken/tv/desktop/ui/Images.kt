package se.norrsken.tv.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import se.norrsken.tv.data.remote.PlaylistDownloader
import se.norrsken.tv.desktop.data.AppDirs
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import org.jetbrains.skia.Image as SkiaImage

/**
 * Laddar logotyper och omslag, och sparar dem på disken.
 *
 * - Disken: varje bild hämtas en gång och sparas i mappen "bilder" bredvid
 *   biblioteket. Sedan läses den därifrån, även efter en omstart. Mappen
 *   rensas bara om den blir mycket stor, och då på det som inte visats på
 *   längst tid.
 * - Minnet: avkodade bilder, nedskalade till den storlek de visas i.
 * - Nätet: sex hämtningar åt gången för det som syns, senast efterfrågat
 *   först, så att det man scrollat fram till går före det man scrollat förbi.
 *   Två till i bakgrunden för omslag som sparas i förväg; de pausas medan
 *   något spelas.
 * - Fel: ett tillfälligt fel (tidsgräns, upptagen server) provas igen om en
 *   stund. En bild som servern inte har provas igen först efter en vecka.
 *   Svarar en bildserver inte alls får den vila en stund, så att den inte
 *   håller upp omslagen från andra.
 */
object Images {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    // --- Minnet --------------------------------------------------------------------
    private val memory = LinkedHashMap<String, ImageBitmap>(256, 0.75f, true)
    private var memoryBytes = 0L
    private val decoding = HashMap<String, Deferred<ImageBitmap?>>()

    // --- Nätet ---------------------------------------------------------------------
    private class Download(val url: String, val name: String) {
        /** Bildens bytes, eller null om den inte gick att hämta. */
        val result = CompletableDeferred<ByteArray?>()
        var foreground = false
        var started = false
    }

    private val downloads = HashMap<String, Download>()
    /** Det som syns. Det senast efterfrågade tas först. */
    private val foregroundQueue = ArrayDeque<Download>()
    /** Det som sparas i förväg, i tur och ordning. */
    private val backgroundQueue = ArrayDeque<Download>()
    private val foregroundWake = Channel<Unit>(Channel.UNLIMITED)
    private val backgroundWake = Channel<Unit>(Channel.UNLIMITED)
    private var prefetchJob: Job? = null
    /** Efter ett tillfälligt fel: när adressen får provas igen, och hur många gånger det gått fel. */
    private val retryAt = HashMap<String, Long>()
    private val failures = HashMap<String, Int>()
    /** Bildservrar som inte svarar: hur många gånger i rad, och hur länge de får vila. */
    private val hostFailures = ConcurrentHashMap<String, Int>()
    private val hostResting = ConcurrentHashMap<String, Long>()

    /** Sätts medan något spelas: förhandshämtningen väntar, så att strömmen får bandbredden. */
    @Volatile
    var backgroundPaused = false

    // --- Disken ----------------------------------------------------------------------
    private val folder: File by lazy { File(AppDirs.cache, "bilder").also { it.mkdirs() } }
    /** Namnen på bilderna som finns i mappen. */
    private val onDisk = ConcurrentHashMap.newKeySet<String>()
    /** Bilder servern inte har: när det konstaterades. */
    private val missing = ConcurrentHashMap<String, Long>()
    /** Visade bilder får färsk tid på disken, en gång per körning; rensningen tar de äldsta. */
    private val touched = ConcurrentHashMap.newKeySet<String>()
    private val scanned = CompletableDeferred<Unit>()
    @Volatile
    private var scanDone = false
    private val tempCounter = AtomicInteger()
    @Volatile
    private var spaceCheckedAt = 0L
    @Volatile
    private var spaceLeft = true

    init {
        repeat(FOREGROUND_WORKERS) { scope.launch { foregroundWorker() } }
        repeat(BACKGROUND_WORKERS) { scope.launch { backgroundWorker() } }
        scope.launch {
            scanFolder()
            delay(PRUNE_DELAY_MS)
            prune()
        }
    }

    fun cached(url: String, maxSide: Int): ImageBitmap? = synchronized(lock) { memory[key(url, maxSide)] }

    suspend fun load(url: String, maxSide: Int): ImageBitmap? {
        val key = key(url, maxSide)
        val job = synchronized(lock) {
            memory[key]?.let { return it }
            decoding.getOrPut(key) {
                scope.async {
                    val image = runCatching { obtain(url)?.let { decodeChecked(it, url, maxSide) } }.getOrNull()
                    synchronized(lock) {
                        decoding.remove(key)
                        if (image != null) keep(key, image)
                    }
                    image
                }
            }
        }
        return job.await()
    }

    /**
     * Sparar omslag på disken i förväg, utan att avkoda dem. Det som redan
     * finns där hoppas över, så efter första gången är det bara nya filmers
     * omslag som hämtas. Ersätter förra listan.
     */
    fun prefetch(urls: List<String>) {
        synchronized(lock) {
            prefetchJob?.cancel()
            prefetchJob = scope.launch {
                scanned.await()
                val wanted = ArrayList<Download>()
                val seen = HashSet<String>()
                for (raw in urls) {
                    ensureActive()
                    val url = raw.trim()
                    if (!url.startsWith("http", ignoreCase = true) || !seen.add(url)) continue
                    val name = hash(url)
                    if (name in onDisk || isMissing(name)) continue
                    wanted += Download(url, name)
                }
                ensureActive()
                var queued = 0
                synchronized(lock) {
                    // Förra listans ännu inte påbörjade hämtningar ersätts av den nya.
                    for (old in backgroundQueue) {
                        downloads.remove(old.url)
                        old.result.complete(null)
                    }
                    backgroundQueue.clear()
                    val now = System.currentTimeMillis()
                    for (download in wanted) {
                        if (downloads.containsKey(download.url)) continue
                        if ((retryAt[download.url] ?: 0L) > now) continue
                        downloads[download.url] = download
                        backgroundQueue.addLast(download)
                        queued++
                    }
                }
                repeat(queued) { backgroundWake.trySend(Unit) }
            }
        }
    }

    /** För inställningarna: hur många omslag som är sparade, och om nya hämtas just nu. */
    fun status(): String? {
        if (!scanDone) return null
        val count = onDisk.size
        val waiting = synchronized(lock) { backgroundQueue.size }
        val saved = if (count == 1) "1 sparat på disken" else "${count.grouped()} sparade på disken"
        return when {
            !spaceLeft -> "$saved · disken är nästan full, nya sparas inte"
            waiting == 0 -> saved
            backgroundPaused -> "$saved · ${waiting.grouped()} nya hämtas när du inte tittar"
            else -> "$saved · hämtar ${waiting.grouped()} nya"
        }
    }

    private fun key(url: String, maxSide: Int) = "$maxSide|$url"

    private fun keep(key: String, image: ImageBitmap) {
        memory.put(key, image)?.let { memoryBytes -= it.byteCount() }
        memoryBytes += image.byteCount()
        val iterator = memory.entries.iterator()
        while (memoryBytes > MAX_MEMORY && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key == key) continue
            memoryBytes -= entry.value.byteCount()
            iterator.remove()
        }
    }

    private fun ImageBitmap.byteCount(): Long = width.toLong() * height * 4

    /** Bildens bytes: från disken om den finns där, annars från nätet. */
    private suspend fun obtain(raw: String): ByteArray? {
        val url = raw.trim()
        val name = hash(url)
        readStored(name)?.let { return it }
        if (isMissing(name)) return null
        var wake = false
        val download = synchronized(lock) {
            if ((retryAt[url] ?: 0L) > System.currentTimeMillis()) return null
            val existing = downloads[url]
            when {
                existing == null -> Download(url, name).also {
                    it.foreground = true
                    downloads[url] = it
                    foregroundQueue.addLast(it)
                    wake = true
                }
                !existing.foreground && !existing.started -> existing.also {
                    // Låg i förhandshämtningen men syns nu: före allt annat.
                    backgroundQueue.remove(it)
                    it.foreground = true
                    foregroundQueue.addLast(it)
                    wake = true
                }
                else -> existing
            }
        }
        if (wake) foregroundWake.trySend(Unit)
        return download.result.await()
    }

    private suspend fun foregroundWorker() {
        for (ignored in foregroundWake) {
            val next = synchronized(lock) { foregroundQueue.removeLastOrNull()?.also { it.started = true } } ?: continue
            finish(next)
        }
    }

    private suspend fun backgroundWorker() {
        for (ignored in backgroundWake) {
            while (backgroundPaused) delay(2_000)
            val next = synchronized(lock) { backgroundQueue.removeFirstOrNull()?.also { it.started = true } } ?: continue
            finish(next)
            // Leverantörernas bildservrar är ofta små: ett lugnt tempo.
            delay(BACKGROUND_PACE_MS)
        }
    }

    private suspend fun finish(download: Download) {
        var bytes: ByteArray? = null
        try {
            bytes = fetchToDisk(download)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            bytes = null
        } finally {
            synchronized(lock) { downloads.remove(download.url) }
            download.result.complete(bytes)
        }
    }

    // --- Hämtning ------------------------------------------------------------------

    private sealed class Outcome {
        class Picture(val bytes: ByteArray) : Outcome()
        /** Finns inte, eller är ingen bild: provas igen om en vecka. */
        object Gone : Outcome()
        /** Tillfälligt fel: provas igen om en stund. */
        object Later : Outcome()
    }

    private suspend fun fetchToDisk(download: Download): ByteArray? {
        val url = download.url
        val name = download.name
        readStored(name)?.let { return it }
        if (synchronized(lock) { (retryAt[url] ?: 0L) > System.currentTimeMillis() }) return null
        return when (val outcome = fetch(url)) {
            is Outcome.Picture -> {
                store(name, outcome.bytes)
                synchronized(lock) {
                    failures.remove(url)
                    retryAt.remove(url)
                }
                outcome.bytes
            }
            Outcome.Gone -> {
                markMissing(name)
                null
            }
            Outcome.Later -> {
                synchronized(lock) {
                    val count = (failures[url] ?: 0) + 1
                    failures[url] = count
                    retryAt[url] = System.currentTimeMillis() + RETRY_DELAYS[(count - 1).coerceIn(0, RETRY_DELAYS.lastIndex)]
                }
                null
            }
        }
    }

    /**
     * Två försök vid tillfälliga fel. Stänger servern ute spelarens namn
     * provas en vanlig webbläsares.
     */
    private suspend fun fetch(url: String): Outcome {
        val host = runCatching { URL(url).host.lowercase() }.getOrDefault("")
        var agent = PlaylistDownloader.DEFAULT_USER_AGENT
        var triedBrowser = false
        var attempt = 0
        while (true) {
            if ((hostResting[host] ?: 0L) > System.currentTimeMillis()) return Outcome.Later
            attempt++
            val response = try {
                request(url, agent)
            } catch (e: Exception) {
                null
            }
            if (response == null) {
                // Servern svarade inte alls. Flera gånger i rad: den får vila.
                val inARow = hostFailures.merge(host, 1) { a, b -> a + b } ?: 1
                if (inARow >= HOST_FAILURE_LIMIT) {
                    hostResting[host] = System.currentTimeMillis() + HOST_REST_MS
                    hostFailures.remove(host)
                }
            } else {
                hostFailures.remove(host)
            }
            val code = response?.code ?: -1
            val bytes = response?.bytes
            when {
                bytes != null -> return if (looksLikeImage(bytes)) Outcome.Picture(bytes) else Outcome.Gone
                code == 403 && !triedBrowser -> {
                    triedBrowser = true
                    agent = BROWSER_AGENT
                    continue
                }
                code == 400 || code == 401 || code == 404 || code == 410 || code == 413 || code in 300..399 ->
                    return Outcome.Gone
            }
            // Inget svar, 403, 429 eller 5xx: vänta lite och försök en gång till.
            if (attempt >= 2) return Outcome.Later
            delay(if (code == 429 || code == 503) 4_000L else 1_500L)
        }
    }

    private class Response(val code: Int, val bytes: ByteArray?)

    /** Kastar vid nätverksfel. */
    private fun request(url: String, agent: String): Response {
        var current = safeUrl(url)
        repeat(5) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 6_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", agent)
            connection.setRequestProperty("Accept", "image/webp,image/jpeg,image/png,image/*;q=0.8,*/*;q=0.5")
            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw e
            }
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) return Response(code, null)
                // Omdirigering mellan http och https följer Java inte av sig själv.
                current = safeUrl(URL(URL(current), location).toString())
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                return Response(code, null)
            }
            if (connection.contentLengthLong > MAX_DOWNLOAD) {
                connection.disconnect()
                return Response(413, null)
            }
            val bytes = connection.inputStream.use { it.readNBytes(MAX_DOWNLOAD + 1) }
            if (bytes.size > MAX_DOWNLOAD) return Response(413, null)
            return Response(code, bytes)
        }
        return Response(310, null)
    }

    /** Mellanslag och tecken utanför ASCII görs om, så att servern förstår adressen. */
    private fun safeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.all { it.code in 33..126 && it !in "\"<>\\^`{|}" }) return trimmed
        return runCatching {
            val url = URL(trimmed)
            URI(url.protocol, url.userInfo, url.host, url.port, url.path, url.query, url.ref).toASCIIString()
        }.getOrDefault(trimmed)
    }

    /** JPEG, PNG, GIF, WebP, BMP, ICO, AVIF och HEIC. En felsida i HTML är ingen bild. */
    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        fun at(index: Int) = bytes[index].toInt() and 0xFF
        return (at(0) == 0xFF && at(1) == 0xD8) ||
            (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) ||
            (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46) ||
            (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 && at(8) == 0x57 && at(9) == 0x45) ||
            (at(0) == 0x42 && at(1) == 0x4D) ||
            (at(0) == 0x00 && at(1) == 0x00 && at(2) == 0x01 && at(3) == 0x00) ||
            (at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70)
    }

    // --- Disken ----------------------------------------------------------------------

    private fun hash(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    }

    /** Den sparade bilden, eller null om den inte finns på disken. */
    private fun readStored(name: String): ByteArray? {
        val file = File(folder, name)
        if (name !in onDisk && file.length() == 0L) return null
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            onDisk.remove(name)
            return null
        }
        onDisk += name
        if (touched.add(name)) runCatching { file.setLastModified(System.currentTimeMillis()) }
        return bytes
    }

    private fun isMissing(name: String): Boolean {
        val since = missing[name] ?: return false
        return System.currentTimeMillis() - since < MISSING_RETRY_MS
    }

    private fun markMissing(name: String) {
        val now = System.currentTimeMillis()
        missing[name] = now
        runCatching {
            val marker = File(folder, name + MISSING_SUFFIX)
            if (!marker.createNewFile()) marker.setLastModified(now)
        }
    }

    /** Finns det plats kvar på disken? Kollas högst var trettionde sekund. */
    private fun hasRoom(): Boolean {
        val now = System.currentTimeMillis()
        if (now - spaceCheckedAt > 30_000L) {
            spaceCheckedAt = now
            spaceLeft = runCatching { folder.usableSpace > MIN_FREE_BYTES }.getOrDefault(true)
        }
        return spaceLeft
    }

    /** Skriver till en tillfällig fil först, så att en avbruten nedladdning aldrig blir en trasig bild. */
    private fun store(name: String, bytes: ByteArray) {
        if (!hasRoom()) return
        val target = File(folder, name)
        val temp = File(folder, "$name.tmp${tempCounter.incrementAndGet()}")
        try {
            temp.writeBytes(bytes)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            onDisk += name
            touched += name
            if (missing.remove(name) != null) File(folder, name + MISSING_SUFFIX).delete()
        } catch (e: Exception) {
            // Full disk eller liknande: bilden visas ändå, den sparas bara inte.
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun scanFolder() {
        runCatching {
            val names = folder.list() ?: emptyArray()
            for (name in names) {
                when {
                    name.endsWith(MISSING_SUFFIX) ->
                        missing[name.removeSuffix(MISSING_SUFFIX)] = File(folder, name).lastModified()
                    '.' !in name -> onDisk += name
                }
            }
        }
        scanDone = true
        scanned.complete(Unit)
    }

    /**
     * Rensar bara när mappen blivit mycket stor, och då bilderna som inte
     * visats på längst tid. Halvfärdiga filer och gamla "saknas" tas bort.
     */
    private fun prune() {
        runCatching {
            val now = System.currentTimeMillis()
            val files = folder.listFiles() ?: return@runCatching
            val images = ArrayList<File>(files.size)
            var total = 0L
            for (file in files) {
                val name = file.name
                when {
                    ".tmp" in name -> if (now - file.lastModified() > DAY_MS) file.delete()
                    name.endsWith(MISSING_SUFFIX) -> if (now - file.lastModified() > MISSING_KEEP_MS) {
                        if (file.delete()) missing.remove(name.removeSuffix(MISSING_SUFFIX))
                    }
                    else -> {
                        images += file
                        total += file.length()
                    }
                }
            }
            if (total <= MAX_DISK) return@runCatching
            images.sortBy { it.lastModified() }
            for (file in images) {
                if (total <= PRUNE_TARGET) break
                val size = file.length()
                if (file.delete()) {
                    total -= size
                    onDisk.remove(file.name)
                }
            }
        }
    }

    // --- Avkodning -----------------------------------------------------------------

    /** Avkodar. Går det inte tas filen bort och adressen vilar en vecka. */
    private fun decodeChecked(bytes: ByteArray, url: String, maxSide: Int): ImageBitmap? = try {
        decode(bytes, maxSide)
    } catch (e: Exception) {
        val name = hash(url)
        if (File(folder, name).delete()) onDisk.remove(name)
        markMissing(name)
        null
    }

    /** En bild från disken, nedskalad som omslagen. */
    internal fun decodeFile(bytes: ByteArray, maxSide: Int): ImageBitmap = decode(bytes, maxSide)

    /** Kastar om bilden inte går att läsa. */
    internal fun validate(bytes: ByteArray) {
        SkiaImage.makeFromEncoded(bytes).close()
    }

    private fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap {
        val full = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        val longest = maxOf(full.width, full.height)
        if (longest <= maxSide) return full
        val scale = maxSide.toFloat() / longest
        val width = (full.width * scale).roundToInt().coerceAtLeast(1)
        val height = (full.height * scale).roundToInt().coerceAtLeast(1)
        val target = ImageBitmap(width, height)
        val paint = Paint().apply { filterQuality = FilterQuality.High }
        Canvas(target).drawImageRect(
            image = full,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(full.width, full.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, height),
            paint = paint,
        )
        // Oföränderlig: annars kopierar Skia pixlarna varje gång bilden ritas.
        target.asSkiaBitmap().setImmutable()
        return target
    }

    private const val FOREGROUND_WORKERS = 6
    private const val BACKGROUND_WORKERS = 2
    private const val BACKGROUND_PACE_MS = 150L
    private const val MAX_MEMORY = 192L * 1024 * 1024
    private const val MAX_DOWNLOAD = 12 * 1024 * 1024
    /** Mappen rensas först över 4 GB, ned till 3 GB. */
    private const val MAX_DISK = 4L * 1024 * 1024 * 1024
    private const val PRUNE_TARGET = 3L * 1024 * 1024 * 1024
    /** Under så här mycket ledigt sparas inga nya bilder. */
    private const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024
    private const val PRUNE_DELAY_MS = 60_000L
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MISSING_RETRY_MS = 7 * DAY_MS
    private const val MISSING_KEEP_MS = 30 * DAY_MS
    private const val MISSING_SUFFIX = ".saknas"
    private const val HOST_FAILURE_LIMIT = 4
    private const val HOST_REST_MS = 45_000L
    /** Väntan efter ett tillfälligt fel: 20 sekunder, en minut, tre, sedan tio. */
    private val RETRY_DELAYS = longArrayOf(20_000L, 60_000L, 180_000L, 600_000L)
    private const val BROWSER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
}

/**
 * En bild från nätet. [placeholder] visas tills bilden finns — och för
 * alltid om den inte gick att hämta; en trasig logga ska se avsiktlig ut.
 * Bilden tonas in när den kommer från disken eller nätet.
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    maxSide: Int = 480,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    placeholder: @Composable () -> Unit = {},
) {
    val key = url?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
    val initial = remember(key, maxSide) { key?.let { Images.cached(it, maxSide) } }
    var image by remember(key, maxSide) { mutableStateOf(initial) }
    if (key != null && image == null) {
        LaunchedEffect(key, maxSide) {
            image = Images.load(key, maxSide)
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (image != null) 1f else 0f,
        animationSpec = tween(if (initial != null) 0 else 220),
        label = "bild",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        val current = image
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                alignment = alignment,
                alpha = alpha,
                filterQuality = FilterQuality.Medium,
            )
        } else {
            placeholder()
        }
    }
}

package se.norrsken.tv.desktop.ui

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import se.norrsken.tv.data.remote.PlaylistDownloader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import org.jetbrains.skia.Image as SkiaImage

/**
 * Laddar logotyper och omslag.
 *
 * Tre saker gör skillnad med IPTV-bibliotek: samma logga används ofta av
 * dussintals kanaler (en hämtning räcker), leverantörernas bildservrar är
 * långsamma (högst sex samtidigt, så en lista inte kör slut på anslutningar),
 * och omslag kommer ibland i 2000 pixlar (skalas ned innan de hamnar i minnet).
 */
object Images {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(6)
    private val lock = Any()
    private val cache = LinkedHashMap<String, ImageBitmap>(256, 0.75f, true)
    private val inFlight = HashMap<String, Deferred<ImageBitmap?>>()
    private val failed = HashSet<String>()
    private var cachedBytes = 0L

    private const val MAX_BYTES = 192L * 1024 * 1024
    private const val MAX_DOWNLOAD = 8 * 1024 * 1024

    fun cached(url: String, maxSide: Int): ImageBitmap? = synchronized(lock) { cache[key(url, maxSide)] }

    suspend fun load(url: String, maxSide: Int): ImageBitmap? {
        val key = key(url, maxSide)
        val job = synchronized(lock) {
            cache[key]?.let { return it }
            if (key in failed) return null
            inFlight.getOrPut(key) {
                scope.async {
                    val image = permits.withPermit {
                        runCatching { decode(download(url), maxSide) }.getOrNull()
                    }
                    synchronized(lock) {
                        inFlight.remove(key)
                        if (image != null) store(key, image) else failed.add(key)
                    }
                    image
                }
            }
        }
        return job.await()
    }

    private fun key(url: String, maxSide: Int) = "$maxSide|$url"

    private fun store(key: String, image: ImageBitmap) {
        cache.put(key, image)?.let { cachedBytes -= it.byteCount() }
        cachedBytes += image.byteCount()
        val iterator = cache.entries.iterator()
        while (cachedBytes > MAX_BYTES && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key == key) continue
            cachedBytes -= entry.value.byteCount()
            iterator.remove()
        }
    }

    private fun ImageBitmap.byteCount(): Long = width.toLong() * height * 4

    /** Egen enkel hämtning med kortare tidsgränser än spellistorna. */
    private fun download(url: String): ByteArray {
        var current = url
        repeat(5) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("User-Agent", PlaylistDownloader.DEFAULT_USER_AGENT)
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                // Omdirigering mellan http och https följer Java inte av sig själv.
                current = URL(URL(current), location ?: throw IOException("Omdirigering utan adress")).toString()
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw IOException("HTTP $code")
            }
            return connection.inputStream.use { it.readNBytes(MAX_DOWNLOAD) }
        }
        throw IOException("För många omdirigeringar")
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
}

/**
 * En bild från nätet. [placeholder] visas tills bilden finns — och för
 * alltid om den inte gick att hämta; en trasig logga ska se avsiktlig ut.
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
    var image by remember(key, maxSide) { mutableStateOf(key?.let { Images.cached(it, maxSide) }) }
    if (key != null && image == null) {
        LaunchedEffect(key, maxSide) {
            image = Images.load(key, maxSide)
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val current = image
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                alignment = alignment,
                filterQuality = FilterQuality.Medium,
            )
        } else {
            placeholder()
        }
    }
}

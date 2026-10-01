package se.norrsken.tv.desktop.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

/**
 * Shield-versionen av bildladdningen: samma upplägg som på Windows — högst
 * sex hämtningar samtidigt, en hämtning per adress, nedskalning innan bilden
 * hamnar i minnet — men med Androids bildavkodare.
 */
object Images {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(6)
    private val lock = Any()
    private val cache = LinkedHashMap<String, ImageBitmap>(256, 0.75f, true)
    private val inFlight = HashMap<String, Deferred<ImageBitmap?>>()
    private val failed = HashSet<String>()
    private var cachedBytes = 0L

    // Shield har gott om minne, men mindre än en dator.
    private const val MAX_BYTES = 128L * 1024 * 1024
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
                current = URL(URL(current), location ?: throw IOException("Omdirigering utan adress")).toString()
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw IOException("HTTP $code")
            }
            return connection.inputStream.use { it.readNBytesCompat(MAX_DOWNLOAD) }
        }
        throw IOException("För många omdirigeringar")
    }

    /** En bild från disken, nedskalad som omslagen. */
    internal fun decodeFile(bytes: ByteArray, maxSide: Int): ImageBitmap = decode(bytes, maxSide)

    /** Kastar om bilden inte går att läsa. */
    internal fun validate(bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Bilden gick inte att läsa")
    }

    private fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Bilden gick inte att läsa")
        // Avkoda direkt i ungefär rätt storlek: ett omslag på 2000 pixlar tar annars 16 MB.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Bilden gick inte att läsa")
        val longest = maxOf(decoded.width, decoded.height)
        val bitmap: Bitmap = if (longest > maxSide) {
            val scale = maxSide.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }
        return bitmap.asImageBitmap()
    }

    /** InputStream.readNBytes finns först i Android 13; Shield kör 11. */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
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

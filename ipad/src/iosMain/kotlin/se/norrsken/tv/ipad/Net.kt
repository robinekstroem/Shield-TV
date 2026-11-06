package se.norrsken.tv.ipad

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.dataTaskWithURL
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Samma som Windows och Shield: många leverantörer släpper bara in kända spelare. */
internal const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

internal class HttpException(message: String) : Exception(message)

/** iPadens eget nätverk, med spelarens namn i varje anrop. */
private val session: NSURLSession by lazy {
    val configuration = NSURLSessionConfiguration.defaultSessionConfiguration
    configuration.HTTPAdditionalHeaders = mapOf<Any?, Any?>("User-Agent" to USER_AGENT)
    configuration.timeoutIntervalForRequest = 30.0
    NSURLSession.sessionWithConfiguration(configuration)
}

internal suspend fun fetchBytes(url: String): ByteArray = suspendCancellableCoroutine { continuation ->
    val address = NSURL.URLWithString(url)
    if (address == null) {
        continuation.resumeWithException(HttpException("Adressen går inte att läsa"))
        return@suspendCancellableCoroutine
    }
    val task = session.dataTaskWithURL(address) { data, response, error ->
        val status = (response as? NSHTTPURLResponse)?.statusCode ?: 200L
        when {
            error != null -> continuation.resumeWithException(HttpException(error.localizedDescription))
            status !in 200L..299L -> continuation.resumeWithException(HttpException("Servern svarade $status"))
            else -> continuation.resume(data?.toByteArray() ?: ByteArray(0))
        }
    }
    continuation.invokeOnCancellation { task.cancel() }
    task.resume()
}

internal suspend fun fetchText(url: String): String = fetchBytes(url).decodeToString()

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val result = ByteArray(size)
    if (size > 0) {
        result.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
    return result
}

/** Kodar ett värde för en adress: allt utom bokstäver, siffror och -._~ blir %XX. */
internal fun encodeUrl(text: String): String = buildString {
    for (byte in text.encodeToByteArray()) {
        val c = (byte.toInt() and 0xFF)
        val ch = c.toChar()
        if (ch.isLetterOrDigit() && c < 128 || ch in "-._~") append(ch) else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
    }
}

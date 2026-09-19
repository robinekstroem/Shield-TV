package se.norrsken.tv.data.remote

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fel som går att visa för en användare, till skillnad från en stacktrace.
 *
 * [status] är HTTP-koden när felet kom från servern. Finns för att anropare
 * ska kunna skilja "fel nyckel" från "nätet hackade" utan att matcha på
 * felmeddelandets text — den är skriven för människor och kan ändras.
 */
class PlaylistException(
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

@Singleton
class PlaylistDownloader @Inject constructor() {

    /**
     * Hämtar spellistan och ger den vidare som en ström.
     *
     * Varför inte bara URL().readText(): tre saker går sönder i praktiken.
     *
     * 1. Många IPTV-servrar svarar 403 om User-Agent inte ser ut som en
     *    mediaspelare. Därför skickas en explicit.
     * 2. HttpURLConnection följer inte omdirigeringar mellan http och https.
     *    Väldigt vanligt hos de här leverantörerna — därför loopen nedan.
     * 3. Filen kan vara gzippad utan att servern sätter Content-Encoding.
     *    Därför kollas magic bytes i stället för headern.
     */
    fun <T> open(
        url: String,
        userAgent: String = DEFAULT_USER_AGENT,
        block: (BufferedReader) -> T,
    ): T {
        var current = url
        var redirects = 0

        while (true) {
            val connection = connect(current, userAgent)
            val code = connection.responseCode

            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null || ++redirects > MAX_REDIRECTS) {
                    throw PlaylistException("Servern skickade vidare utan att ange vart.")
                }
                current = URL(URL(current), location).toString()
                continue
            }

            if (code !in 200..299) {
                connection.disconnect()
                throw PlaylistException(explain(code), status = code)
            }

            return connection.inputStream.buffered().use { raw ->
                raw.mark(2)
                val magic = ByteArray(2)
                val read = raw.read(magic)
                raw.reset()

                val stream = if (read == 2 && magic[0] == 0x1f.toByte() &&
                    magic[1] == 0x8b.toByte()
                ) {
                    GZIPInputStream(raw)
                } else {
                    raw
                }

                block(BufferedReader(InputStreamReader(stream, Charsets.UTF_8)))
            }
        }
    }

    /** Hela svaret som text. Används för Xtreams JSON-anrop. */
    fun readText(url: String, userAgent: String = DEFAULT_USER_AGENT): String =
        open(url, userAgent) { it.readText() }

    private fun connect(url: String, userAgent: String): HttpURLConnection {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw PlaylistException("Adressen går inte att öppna. Kontrollera länken.", cause = e)
        }
        return connection.apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
        }
    }

    private fun explain(code: Int): String = when (code) {
        401, 403 -> "Servern nekar åtkomst. Kontrollera användarnamn, lösenord och att abonnemanget är aktivt."
        404 -> "Spellistan finns inte på den adressen."
        429 -> "Servern har tillfälligt stoppat anrop. Vänta en stund och försök igen."
        in 500..599 -> "Leverantörens server svarar med ett fel ($code). Problemet ligger inte hos appen."
        else -> "Servern svarade med kod $code."
    }

    companion object {
        /**
         * Vissa leverantörer filtrerar på User-Agent och släpper bara igenom
         * kända spelare. Går att byta per spellista om en server är kinkig.
         */
        const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}

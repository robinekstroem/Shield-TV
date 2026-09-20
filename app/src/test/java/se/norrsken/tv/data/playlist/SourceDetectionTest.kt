package se.norrsken.tv.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceDetectionTest {

    @Test
    fun `plockar ut uppgifter ur en get-php-lank`() {
        val result = extractXtreamCredentials(
            "http://server.se:8080/get.php?username=robin&password=hemligt&type=m3u_plus"
        )
        assertEquals("http://server.se:8080", result?.baseUrl)
        assertEquals("robin", result?.username)
        assertEquals("hemligt", result?.password)
    }

    @Test
    fun `klarar procentkodade losenord`() {
        val result = extractXtreamCredentials(
            "http://s/get.php?username=a&password=p%40ss%20word"
        )
        assertEquals("p@ss word", result?.password)
    }

    @Test
    fun `vanlig m3u-fil ar inte ett xtream-konto`() {
        assertNull(extractXtreamCredentials("http://server.se/lista.m3u"))
    }

    @Test
    fun `uppgifter som saknas ger null`() {
        assertNull(extractXtreamCredentials("http://server.se/get.php?type=m3u_plus"))
    }

    @Test
    fun `normaliserar serveradress`() {
        assertEquals("http://server.se:8080", normalizeBaseUrl("server.se:8080/"))
        assertEquals("https://server.se", normalizeBaseUrl("https://server.se"))
    }
}

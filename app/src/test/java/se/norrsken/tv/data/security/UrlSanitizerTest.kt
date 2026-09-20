package se.norrsken.tv.data.security

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlSanitizerTest {

    @Test
    fun `maskerar uppgifter i query`() {
        assertEquals(
            "http://server:8080/get.php?username=***&password=***&type=m3u_plus",
            sanitizeUrl("http://server:8080/get.php?username=robin&password=hemligt&type=m3u_plus"),
        )
    }

    @Test
    fun `maskerar uppgifter i sokvagen`() {
        // Den har varianten glommas latt: uppgifterna ser ut som mappnamn.
        assertEquals(
            "http://server:8080/live/***/***/12345.ts",
            sanitizeUrl("http://server:8080/live/robin/hemligt/12345.ts"),
        )
    }

    @Test
    fun `maskerar film- och seriestrommar`() {
        assertEquals(
            "http://s/movie/***/***/99.mkv",
            sanitizeUrl("http://s/movie/robin/pw/99.mkv"),
        )
        assertEquals(
            "http://s/series/***/***/42.mp4",
            sanitizeUrl("http://s/series/robin/pw/42.mp4"),
        )
    }

    @Test
    fun `lamnar ofarliga adresser orörda`() {
        val url = "https://api.themoviedb.org/3/search/movie?query=Dune&year=2024"
        assertEquals(url, sanitizeUrl(url))
    }

    @Test
    fun `klarar tom och trasig indata`() {
        assertEquals("", sanitizeUrl(null))
        assertEquals("", sanitizeUrl(""))
        assertEquals("inte-en-url", sanitizeUrl("inte-en-url"))
    }
}

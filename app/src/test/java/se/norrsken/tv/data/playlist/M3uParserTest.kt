package se.norrsken.tv.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    private fun parse(text: String) = parseM3u(text.trimIndent().reader().buffered())

    @Test
    fun `laser attribut och url`() {
        val result = parse(
            """
            #EXTM3U url-tvg="http://server/epg.xml.gz"
            #EXTINF:-1 tvg-id="svt1.se" tvg-logo="http://logo/svt1.png" group-title="Sverige",SVT1 HD
            http://server/live/u/p/1.m3u8
            """
        )

        assertEquals("http://server/epg.xml.gz", result.epgUrl)
        assertEquals(1, result.entries.size)
        val entry = result.entries.first()
        assertEquals("SVT1 HD", entry.name)
        assertEquals("svt1.se", entry.tvgId)
        assertEquals("Sverige", entry.group)
        assertEquals("http://server/live/u/p/1.m3u8", entry.url)
    }

    @Test
    fun `hoppar over rader mellan EXTINF och url`() {
        // Leverantorer lagger in EXTVLCOPT och EXTGRP mellan raderna.
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1,TV4
            #EXTGRP:Sverige
            #EXTVLCOPT:http-user-agent=VLC/3.0
            http://server/tv4.ts
            """
        )
        assertEquals(1, result.entries.size)
        assertEquals("Sverige", result.entries.first().group)
        assertEquals("http://server/tv4.ts", result.entries.first().url)
    }

    @Test
    fun `komma i attributvarde forstor inte namnet`() {
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Sport, Live",Viaplay Sport
            http://server/vp.ts
            """
        )
        assertEquals("Viaplay Sport", result.entries.first().name)
        assertEquals("Sport, Live", result.entries.first().group)
    }

    @Test
    fun `EXTINF utan komma ger inte hela raden som namn`() {
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="Kanal 9"
            http://server/k9.ts
            """
        )
        assertEquals("Kanal 9", result.entries.first().name)
    }

    @Test
    fun `saknad epg-adress ger null`() {
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1,A
            http://a
            """
        )
        assertNull(result.epgUrl)
    }

    @Test
    fun `normalisering tar bort kvalitetsmarkorer`() {
        assertEquals("svt1", normalizeChannelName("SVT1 HD"))
        assertEquals("svt 1", normalizeChannelName("SVT 1 FHD"))
        assertEquals("tv4", normalizeChannelName("TV4  |  4K"))
    }
}

package se.norrsken.tv.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.norrsken.tv.data.db.entity.ChannelEntity
import se.norrsken.tv.data.db.entity.MappingSource

/**
 * Regeln som testas: hellre ingen EPG än fel EPG. Flera av testerna är
 * därför fall som INTE ska kopplas.
 */
class EpgMatcherTest {

    private fun channel(id: Long, name: String, tvgId: String? = null) = ChannelEntity(
        id = id,
        playlistId = 1,
        categoryId = null,
        name = name,
        normalizedName = name.lowercase(),
        streamUrl = "http://x/$id",
        tvgId = tvgId,
    )

    @Test
    fun `tvg-id som tablan deklarerar vinner`() {
        val result = matchChannels(
            listOf(channel(1, "Kanal Ett", tvgId = "svt1.se")),
            listOf(XmltvChannel("svt1.se", listOf("SVT1"))),
        )
        assertEquals("svt1.se", result.single().epgChannelId)
        assertEquals(MappingSource.TVG_ID, result.single().source)
    }

    @Test
    fun `exakt namn kopplas`() {
        val result = matchChannels(
            listOf(channel(1, "TV4")),
            listOf(XmltvChannel("tv4.se", listOf("TV4"))),
        )
        assertEquals("tv4.se", result.single().epgChannelId)
        assertEquals(MappingSource.EXACT_NAME, result.single().source)
    }

    @Test
    fun `normaliserat namn kopplas`() {
        val result = matchChannels(
            listOf(channel(1, "SVT1 HD")),
            listOf(XmltvChannel("svt1.se", listOf("SVT1"))),
        )
        assertEquals("svt1.se", result.single().epgChannelId)
        assertEquals(MappingSource.NORMALIZED_NAME, result.single().source)
    }

    @Test
    fun `tvetydigt namn kopplas inte`() {
        // Två regioner med samma namn: att gissa vore att riskera fel tablå.
        val result = matchChannels(
            listOf(channel(1, "SVT1")),
            listOf(
                XmltvChannel("svt1.sthlm", listOf("SVT1")),
                XmltvChannel("svt1.gbg", listOf("SVT1")),
            ),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `liknande namn kopplas inte`() {
        // Ingen fuzzy matchning: "SVT1" är inte "SVT2".
        val result = matchChannels(
            listOf(channel(1, "SVT1")),
            listOf(XmltvChannel("svt2.se", listOf("SVT2"))),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `odeklarerat tvg-id anvands som sista utvag`() {
        // Leverantörens uttryckliga koppling. Kan ge inga program, aldrig fel.
        val result = matchChannels(
            listOf(channel(1, "Okänd", tvgId = "okand.se")),
            emptyList(),
        )
        assertEquals("okand.se", result.single().epgChannelId)
        assertEquals(70, result.single().confidence)
    }

    @Test
    fun `kanal utan tvg-id och utan traff kopplas inte`() {
        val result = matchChannels(listOf(channel(1, "Något Helt Annat")), emptyList())
        assertTrue(result.isEmpty())
    }
}

package se.norrsken.tv.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regeln som testas: hellre ingen metadata an fel metadata.
 * Testerna nedan ar darfor mest fall som INTE ska matcha.
 */
class TitleMatcherTest {

    private fun candidate(
        id: String,
        title: String,
        year: Int?,
        popularity: Double = 1.0,
    ) = MetadataCandidate(
        providerId = id,
        title = title,
        originalTitle = null,
        year = year,
        overview = null,
        posterUrl = null,
        backdropUrl = null,
        rating = null,
        popularity = popularity,
    )

    @Test
    fun `titel och ar ger full traff`() {
        val verdict = pickBestMatch("Dune", 2021, listOf(candidate("1", "Dune", 2021)))
        assertTrue(verdict.accepted())
        assertEquals("1", verdict.candidate?.providerId)
    }

    @Test
    fun `ett ars skillnad accepteras`() {
        // Festival i december, bio i januari ger tva olika artal.
        val verdict = pickBestMatch("Heat", 1995, listOf(candidate("1", "Heat", 1996)))
        assertTrue(verdict.accepted())
    }

    @Test
    fun `fel ar matchar inte`() {
        val verdict = pickBestMatch("Heat", 1995, listOf(candidate("1", "Heat", 1986)))
        assertFalse(verdict.accepted())
    }

    @Test
    fun `delvis titel matchar inte`() {
        // Dune och Dune Part Two ar olika filmer.
        val verdict = pickBestMatch("Dune", 2021, listOf(candidate("1", "Dune: Part Two", 2024)))
        assertFalse(verdict.accepted())
    }

    @Test
    fun `flera lika troliga utan ar matchar inte`() {
        val verdict = pickBestMatch(
            "The Office",
            null,
            listOf(candidate("1", "The Office", 2005), candidate("2", "The Office", 2001)),
        )
        assertFalse(verdict.accepted())
    }

    @Test
    fun `enda traffen utan ar accepteras`() {
        val verdict = pickBestMatch("Shogun", null, listOf(candidate("1", "Shogun", 2024)))
        assertTrue(verdict.accepted())
    }

    @Test
    fun `inga kandidater ger inget`() {
        assertFalse(pickBestMatch("Vad som helst", 2020, emptyList()).accepted())
    }

    @Test
    fun `normalisering ignorerar artikel och skiljetecken`() {
        assertEquals(normalizeTitle("The Last of Us"), normalizeTitle("Last of Us"))
        assertEquals(normalizeTitle("Marvel's Daredevil"), normalizeTitle("Marvels Daredevil"))
    }
}

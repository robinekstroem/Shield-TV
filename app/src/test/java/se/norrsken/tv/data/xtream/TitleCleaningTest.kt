package se.norrsken.tv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Test
import se.norrsken.tv.data.db.entity.MatchState

class TitleCleaningTest {

    @Test
    fun `hakparentestaggar tas bort helt`() {
        // Fallet som syntes i spelaren: skalen blev kvar.
        val (title, year) = splitTitleAndYear("Moana (2026) [HD-Sub] [HD-AUDIO] [4K]")
        assertEquals("Moana", title)
        assertEquals(2026, year)
    }

    @Test
    fun `ar i hakparentes laes fortfarande`() {
        val (title, year) = splitTitleAndYear("Dune Part Two [2024] [4K]")
        assertEquals("Dune Part Two", title)
        assertEquals(2024, year)
    }

    @Test
    fun `vanlig titel lamnas ifred`() {
        val (title, year) = splitTitleAndYear("Gladiator (2000)")
        assertEquals("Gladiator", title)
        assertEquals(2000, year)
    }

    @Test
    fun `ny titel efter rensning gor om matchningen`() {
        assertEquals(
            MatchState.UNMATCHED,
            keptMatchState(MatchState.AMBIGUOUS, "Moana [ -Sub]", "Moana"),
        )
    }

    @Test
    fun `manuellt val overlever alltid`() {
        assertEquals(
            MatchState.MANUAL,
            keptMatchState(MatchState.MANUAL, "Gammal", "Ny"),
        )
    }

    @Test
    fun `oforandrad titel behaller sin status`() {
        assertEquals(
            MatchState.MATCHED,
            keptMatchState(MatchState.MATCHED, "Heat", "Heat"),
        )
    }
}

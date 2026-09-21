package se.norrsken.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPatternTest {

    @Test
    fun `vanlig text omges av jokertecken`() {
        assertEquals("%gladiator%", likePattern("gladiator"))
    }

    @Test
    fun `svenska bokstaver blir ett-teckens jokertecken`() {
        // Sa hittar "äventyr" aven "Äventyr" — SQLite:s LIKE ar bara
        // skiftlagesokanslig for A-Z.
        assertEquals("%_ventyr%", likePattern("äventyr"))
        assertEquals("%sk_rg_rd%", likePattern("skärgård"))
    }

    @Test
    fun `procent och understreck escapas`() {
        // Annars vore "100%" ett jokertecken i stallet for en procentsats.
        assertEquals("%100\\%%", likePattern("100%"))
        assertEquals("%a\\_b%", likePattern("a_b"))
    }

    @Test
    fun `bakstreck escapas`() {
        assertEquals("%a\\\\b%", likePattern("a\\b"))
    }

    @Test
    fun `mellanslag runt texten tas bort`() {
        assertEquals("%dune%", likePattern("  dune  "))
    }
}

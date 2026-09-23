package se.norrsken.tv.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import se.norrsken.tv.core.ui.component.metaLine

/** Faktaraden får aldrig innehålla tomma hål — "2026 • • 4K". */
class MetaLineTest {

    @Test
    fun `bygger rad av det som finns`() {
        assertEquals("2026  •  ★ 7.4  •  Action", metaLine("2026", "★ 7.4", "Action"))
    }

    @Test
    fun `hoppar over null och tomt`() {
        assertEquals("2026  •  Action", metaLine("2026", null, "", "   ", "Action"))
    }

    @Test
    fun `allt tomt ger tom strang`() {
        assertEquals("", metaLine(null, "", "  "))
    }

    @Test
    fun `ett enda falt ger ingen separator`() {
        assertEquals("2026", metaLine(null, "2026"))
    }
}

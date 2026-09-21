package se.norrsken.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.norrsken.tv.data.db.entity.CategoryEntity
import se.norrsken.tv.data.db.entity.ContentKind
import se.norrsken.tv.data.settings.AppPreferences

class CategoryVisibilityTest {

    private fun category(name: String, id: Long = 1, playlist: Long = 7) = CategoryEntity(
        id = id,
        playlistId = playlist,
        kind = ContentKind.MOVIE,
        externalId = null,
        name = name,
    )

    @Test
    fun `ordfilter traffar oavsett skiftlage`() {
        val words = listOf("arab")
        assertEquals("arab", matchingKeyword("Arabic Movies", words))
        assertEquals("arab", matchingKeyword("ARABIC", words))
        assertEquals("arab", matchingKeyword("|AR| Arabiska filmer", words))
    }

    @Test
    fun `ordfilter traffar inte det som inte innehaller ordet`() {
        assertNull(matchingKeyword("Svenska filmer", listOf("arab")))
    }

    @Test
    fun `tomma ord ignoreras`() {
        // Ett tomt ord hade annars traffat allt och dolt hela biblioteket.
        assertNull(matchingKeyword("Svenska filmer", listOf("", "  ")))
    }

    @Test
    fun `enskilt dold kategori ar dold`() {
        val c = category("Action")
        val prefs = AppPreferences(hiddenCategories = setOf(c.visibilityKey()))
        assertTrue(isCategoryHidden(c, prefs))
    }

    @Test
    fun `nyckeln overlever att id andras vid synk`() {
        // Samma kategori far nytt id efter en uppdatering av spellistan.
        val before = category("Action", id = 1)
        val after = category("Action", id = 999)
        val prefs = AppPreferences(hiddenCategories = setOf(before.visibilityKey()))
        assertTrue(isCategoryHidden(after, prefs))
    }

    @Test
    fun `samma namn i annan kalla paverkas inte`() {
        val hidden = category("Action", playlist = 1)
        val other = category("Action", playlist = 2)
        val prefs = AppPreferences(hiddenCategories = setOf(hidden.visibilityKey()))
        assertFalse(isCategoryHidden(other, prefs))
    }

    @Test
    fun `synlig kategori utan filter syns`() {
        assertFalse(isCategoryHidden(category("Komedi"), AppPreferences()))
    }
}

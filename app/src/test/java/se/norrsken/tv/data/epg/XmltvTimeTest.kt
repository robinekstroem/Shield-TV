package se.norrsken.tv.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Förväntade värden räknade med en oberoende kalender, inte med koden själv. */
class XmltvTimeTest {

    @Test
    fun `tid med tidszon`() {
        assertEquals(1_790_092_800_000L, parseXmltvTime("20260922180000 +0200"))
    }

    @Test
    fun `tid utan tidszon tolkas som UTC`() {
        assertEquals(1_790_100_000_000L, parseXmltvTime("20260922180000"))
    }

    @Test
    fun `tid utan sekunder`() {
        assertEquals(1_790_100_000_000L, parseXmltvTime("202609221800 +0000"))
    }

    @Test
    fun `tidszon utan mellanslag och med halvtimme`() {
        assertEquals(1_790_119_800_000L, parseXmltvTime("20260922180000-0530"))
    }

    @Test
    fun `skottdag`() {
        assertEquals(1_709_247_599_000L, parseXmltvTime("20240229235959 +0100"))
    }

    @Test
    fun `epokens start`() {
        assertEquals(0L, parseXmltvTime("19700101000000 +0000"))
    }

    @Test
    fun `trasigt ger null i stallet for krasch`() {
        assertNull(parseXmltvTime(null))
        assertNull(parseXmltvTime("inte en tid"))
        assertNull(parseXmltvTime("2026092"))
        assertNull(parseXmltvTime("20261322180000"))
        assertNull(parseXmltvTime("20260922180000 +02"))
    }
}

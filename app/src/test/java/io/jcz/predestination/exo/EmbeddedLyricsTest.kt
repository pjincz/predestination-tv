package io.jcz.predestination.exo

import org.junit.Assert.*
import org.junit.Test

class EmbeddedLyricsTest {
    @Test fun seeksBothDirectionsAndKeepsBlankInstrumentalLines() {
        val lyrics = EmbeddedLyrics.parse("[00:02.50]First\n[00:05]Second\n[00:08]\n[00:10]Last")
        assertEquals("" to "First", lyrics.at(0))
        assertEquals("First" to "Second", lyrics.at(2500))
        assertEquals("Second" to "", lyrics.at(7000))
        assertEquals("" to "Last", lyrics.at(8000))
        assertEquals("Last" to "", lyrics.at(20000))
        assertEquals("First" to "Second", lyrics.at(3000))
    }

    @Test fun handlesMultipleStampsOffsetsAndTranslations() {
        val lyrics = EmbeddedLyrics.parse("[ar:Example]\n[00:02.1][00:06.125]Repeat\n[00:02.100]Translation\n[offset:100]")
        assertEquals(listOf(2000L, 6025L), lyrics.lines.map { it.timeMs })
        assertEquals("Repeat\nTranslation", lyrics.lines.first().text)
        assertEquals(2100L, EmbeddedLyrics.parse("[offset:-100]\n[00:02]Text").lines.first().timeMs)
    }

    @Test fun centersCurrentLineWithEarlierAndLaterLyrics() {
        val lyrics = EmbeddedLyrics.parse("[00:01]One\n[00:02]Two\n[00:03]Three\n[00:04]Four\n[00:05]Five")
        assertEquals(listOf("One", "Two", "Three", "Four", "Five"), lyrics.window(3000))
        assertEquals(listOf("", "", "One", "Two", "Three"), lyrics.window(1000))
        assertEquals(listOf("Three", "Four", "Five", "", ""), lyrics.window(9000))
        assertEquals(listOf("", "", "", "One", "Two"), lyrics.window(0))
    }

    @Test fun ignoresPlainLyricsAndMalformedOrOversizedInput() {
        assertTrue(EmbeddedLyrics.parse("No timestamp\n[00:99]Invalid\n[ar:Artist]").lines.isEmpty())
        assertTrue(EmbeddedLyrics.parse("x".repeat(512 * 1024 + 1)).lines.isEmpty())
        assertEquals(1000L, EmbeddedLyrics.parse("\uFEFF[00:01]Text").lines.single().timeMs)
    }
}

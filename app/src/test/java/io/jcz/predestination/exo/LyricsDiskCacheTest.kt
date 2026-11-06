package io.jcz.predestination.exo

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LyricsDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun survivesRecreationAndMoreThan32Tracks() {
        val directory = temporary.newFolder()
        val cache = LyricsDiskCache(directory)
        val lyrics = EmbeddedLyrics(listOf(LyricLine(1000, "第一行\nTranslation")))
        for (index in 0..40) cache.write(LyricQuery("Song $index", "Singer", "Album", 200), lyrics)
        val reopened = LyricsDiskCache(directory)
        for (index in 0..40) assertEquals(lyrics.lines,
            reopened.read(LyricQuery("Song $index", "Singer", "Album", 200))?.lines)
        assertNull(reopened.read(LyricQuery("Song 0", "Other singer", "Album", 200)))
    }

    @Test fun ignoresEmptyResultsAndRecoversFromCorruption() {
        val directory = temporary.newFolder()
        val cache = LyricsDiskCache(directory)
        val query = LyricQuery("Song", "Singer", "Album", 200)
        cache.write(query, EmbeddedLyrics(emptyList()))
        assertEquals(0, directory.listFiles()!!.size)
        val lyrics = EmbeddedLyrics(listOf(LyricLine(1000, "Line")))
        cache.write(query, lyrics)
        directory.listFiles()!!.single().writeText("broken")
        assertNull(cache.read(query))
        cache.write(query, lyrics)
        assertEquals(lyrics.lines, cache.read(query)?.lines)
    }
}

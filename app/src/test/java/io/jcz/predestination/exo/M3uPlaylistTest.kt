package io.jcz.predestination.exo

import org.junit.Assert.*
import org.junit.Test

class M3uPlaylistTest {
    @Test fun parsesTitlesRelativeUrlsAndPreservesDuplicatesAndTokens() {
        val result = M3uPlaylist.parse(
            "\uFEFF#EXTM3U\r\n#EXTINF:-1,歌曲一\r\nplay?token=abc&fpath=a.mp3\r\n" +
                "# comment\nhttps://example.com/b.mp3\nhttps://example.com/b.mp3\n",
            "https://example.com/music/playlist.m3u?token=other"
        )
        assertFalse(result.hls)
        assertEquals(3, result.entries.size)
        assertEquals("歌曲一", result.entries[0].title)
        assertEquals("https://example.com/music/play?token=abc&fpath=a.mp3", result.entries[0].url)
        assertNull(result.entries[1].title)
        assertEquals(result.entries[1], result.entries[2])
    }

    @Test fun hlsIsNotExpandedIntoSongs() {
        val url = "https://example.com/live.m3u?token=abc"
        val result = M3uPlaylist.parse("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\na.ts", url)
        assertTrue(result.hls)
        assertEquals(listOf(M3uPlaylist.Entry(url)), result.entries)
    }

    @Test fun recognizesPlaylistPathWithQuery() {
        assertTrue(M3uPlaylist.matches("https://example.com/list.M3U?token=abc"))
        assertTrue(M3uPlaylist.matches("https://example.com/list.m3u8"))
        assertFalse(M3uPlaylist.matches("https://example.com/a.mp3?name=list.m3u"))
    }

    @Test fun rejectsEmptyInvalidAndNestedLists() {
        for (text in listOf("#EXTM3U\n", "<!DOCTYPE html>", "file:///etc/test.mp3", "nested.m3u")) {
            assertThrows(Exception::class.java) { M3uPlaylist.parse(text, "https://example.com/list.m3u") }
        }
    }
}

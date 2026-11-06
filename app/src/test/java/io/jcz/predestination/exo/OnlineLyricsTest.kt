package io.jcz.predestination.exo

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OnlineLyricsTest {
    private val query = LyricQuery("Song", "Artist", "", 200)

    @Test fun acceptsTimedLyricsWithinDurationTolerance() {
        val result = OnlineLyrics.decode(query,
            """{"duration":201.5,"syncedLyrics":"[00:01]First\n[00:02]Next"}""")
        assertEquals("First" to "Next", result.at(1000))
    }

    @Test fun rejectsWrongVersionAndUntimedLyrics() {
        for (json in listOf(
            """{"duration":210,"syncedLyrics":"[00:01]Wrong"}""",
            """{"duration":200,"syncedLyrics":null,"plainLyrics":"Words"}""",
            """{"duration":200,"instrumental":true,"syncedLyrics":"[00:01]Wrong"}""",
            """{"syncedLyrics":"[00:01]Wrong"}"""
        )) assertTrue(OnlineLyrics.decode(query, json).lines.isEmpty())
    }

    @Test fun readsNamespacedCastArtistAndRejectsEntities() {
        val ns = "urn:schemas-upnp-org:metadata-1-0/upnp/"
        assertEquals("Singer", CastTitle.field("<item xmlns:u='$ns'><u:artist>Singer</u:artist></item>", ns, "artist"))
        assertNull(CastTitle.field("<!DOCTYPE item><item/>", ns, "artist"))
    }
}

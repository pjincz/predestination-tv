package io.jcz.predestination.exo

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CastSubtitlesTest {
    @Test fun parsesMultipleTracksAndDeduplicatesCompatibilityFields() {
        val xml = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:sec="http://www.sec.co.kr/"><item>
            <res protocolInfo="http-get:*:video/mp4:*">https://example.org/video</res>
            <res protocolInfo="http-get:*:text/srt:*">https://example.org/a?x=1&amp;y=2</res>
            <res protocolInfo="http-get:*:text/vtt:*">https://example.org/b</res>
            <sec:CaptionInfoEx sec:type="srt">https://example.org/a?x=1&amp;y=2</sec:CaptionInfoEx>
            </item></DIDL-Lite>"""
        val tracks = CastSubtitles.parse(xml)
        assertEquals(2, tracks.size)
        assertEquals("https://example.org/a?x=1&y=2", tracks[0].uri.toString())
        assertEquals("application/x-subrip", tracks[0].mimeType)
        assertEquals(androidx.media3.common.C.SELECTION_FLAG_DEFAULT, tracks[0].selectionFlags)
        assertEquals(0, tracks[1].selectionFlags)
        assertTrue(CastSubtitles.parse("").isEmpty())
        assertTrue(CastSubtitles.parse("<!DOCTYPE x><x/>").isEmpty())
    }
}

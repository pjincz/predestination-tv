package io.jcz.predestination.exo

import org.junit.Assert.*
import org.junit.Test

class CastArtworkTest {
    private fun metadata(url: String) = "<DIDL-Lite xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
        "<item><upnp:albumArtURI>${xml(url)}</upnp:albumArtURI></item></DIDL-Lite>"

    @Test fun readsEscapedCoverUrlIndependentlyForEachTrack() {
        val first = "http://p2.music.126.net/first.jpg?a=1&b=2"
        val second = "https://p1.music.126.net/second.jpg"
        assertEquals(first, CastArtwork.artworkUrl(metadata(first)))
        assertEquals(second, CastArtwork.artworkUrl(metadata(second)))
        assertNull(CastArtwork.artworkUrl("<DIDL-Lite><item/></DIDL-Lite>"))
    }

    @Test fun rejectsUnsafeAndMalformedMetadata() {
        assertNull(CastArtwork.artworkUrl(metadata("file:///private/cover.jpg")))
        assertNull(CastArtwork.artworkUrl(metadata("http://user:password@host/cover.jpg")))
        assertNull(CastArtwork.artworkUrl("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///private'>]><x/>"))
        assertNull(CastArtwork.artworkUrl(""))
    }
}

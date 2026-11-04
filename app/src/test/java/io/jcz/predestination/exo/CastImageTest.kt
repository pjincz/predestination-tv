package io.jcz.predestination.exo

import org.junit.Assert.*
import org.junit.Test

class CastImageTest {
    private fun metadata(content: String) =
        """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item>$content</item></DIDL-Lite>"""

    @Test fun recognizesExtensionsWithoutQueryAndCaseSensitivity() {
        assertTrue(CastImage.matches("http://tv/photo.JPG?token=123", ""))
        assertTrue(CastImage.matches("https://tv/photo.png", ""))
        assertFalse(CastImage.matches("http://tv/video.mp4?name=photo.jpg", ""))
    }

    @Test fun recognizesExtensionlessImagesFromMetadata() {
        val uri = "http://tv/resource/42"
        assertTrue(CastImage.matches(uri, metadata("<upnp:class>object.item.imageItem.photo</upnp:class>")))
        assertTrue(CastImage.matches(uri, metadata("""<res protocolInfo="http-get:*:image/jpeg:*">$uri</res>""")))
        assertFalse(CastImage.matches(uri, metadata("""<res protocolInfo="http-get:*:image/jpeg:*">http://tv/other</res>""")))
    }

    @Test fun explicitAudioVideoMetadataOverridesFilename() {
        assertFalse(CastImage.matches("http://tv/photo.jpg",
            metadata("<upnp:class>object.item.videoItem</upnp:class>")))
        assertFalse(CastImage.matches("http://tv/photo.png",
            metadata("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")))
    }

    @Test fun advertisesOnlyImplementedImageFormats() {
        assertTrue(SINK.split(',').contains("http-get:*:image/jpeg:*"))
        assertTrue(SINK.split(',').contains("http-get:*:image/png:*"))
        assertFalse(SINK.contains("image/*"))
    }
}

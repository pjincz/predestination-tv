package io.jcz.predestination.exo

import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LyricsMetadataTest {
    @Test fun readsFlacLyricsAndClearsWhenNextTrackHasNone() {
        val format = Format.Builder().setMetadata(Metadata(
            VorbisComment("TITLE", "Example"), VorbisComment("LYRICS", "[00:01]First\n[00:02]Second")
        )).build()
        assertEquals("First" to "Second", EmbeddedLyrics.from(listOf(format)).at(1000))
        assertTrue(EmbeddedLyrics.from(listOf(Format.Builder().build())).lines.isEmpty())
    }
}

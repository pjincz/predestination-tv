package io.jcz.predestination.exo

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExternalMediaTest {
    @Test fun acceptsGrantedContentUrisWithoutConvertingToFilePaths() {
        val uri = Uri.parse("content://files.provider/document/video%3A123")
        val intent = Intent(Intent.ACTION_VIEW, uri).setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(ExternalMedia.isOpen(intent))
        assertEquals(listOf(uri), ExternalMedia.uris(intent))
    }

    @Test fun supportsSingleAndMultipleSharedStreamsInOrder() {
        val first = Uri.parse("content://files.provider/a")
        val second = Uri.parse("content://files.provider/b")
        assertEquals(listOf(first), ExternalMedia.uris(
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first)))
        assertEquals(listOf(first, second), ExternalMedia.uris(
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
                Intent.EXTRA_STREAM, arrayListOf(first, second))))
    }

    @Test fun rejectsUnsupportedSchemesAndIgnoresLauncherIntent() {
        assertTrue(ExternalMedia.uris(Intent(Intent.ACTION_VIEW, Uri.parse("javascript:alert(1)"))).isEmpty())
        assertFalse(ExternalMedia.isOpen(Intent(Intent.ACTION_MAIN)))
        assertTrue(ExternalMedia.uris(Intent(Intent.ACTION_SEND)).isEmpty())
    }
}

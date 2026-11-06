package io.jcz.predestination.exo

import android.graphics.Bitmap
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmbeddedArtworkTest {
    private fun picture(width: Int = 32, height: Int = 32): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }

    private fun awaitImage(ready: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!ready() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Embedded artwork was not displayed", ready())
    }

    @Test fun failedRemoteFallsBackToEmbeddedAndNextTrackClearsIt() {
        var shown: Bitmap? = null
        var updates = 0
        val artwork = CastArtwork { shown = it; updates++ }
        try {
            val bytes = picture()
            artwork.loadMedia("first", bytes, "http://127.0.0.1:1/unused.png")
            awaitImage { shown != null }
            assertEquals(32, shown!!.width)
            val previousUpdates = updates
            artwork.loadMedia("first", bytes.copyOf(), "http://127.0.0.1:1/unused.png")
            assertEquals(previousUpdates, updates)
            artwork.loadMedia("second", null, null)
            assertNull(shown)
        } finally { artwork.close() }
    }

    @Test fun staleDecodeCannotReplaceNextTracksCover() {
        var shown: Bitmap? = null
        val artwork = CastArtwork { shown = it }
        try {
            artwork.loadMedia("first", picture(2048, 1024), null)
            artwork.loadMedia("second", picture(64, 32), null)
            awaitImage { shown != null }
            assertEquals(64, shown!!.width)
            assertEquals(32, shown!!.height)
        } finally { artwork.close() }
    }

    @Test fun clientCoverTakesPriorityOverEmbedded() {
        var shown: Bitmap? = null
        val artwork = CastArtwork { shown = it }
        val remote = picture(128, 64)
        val server = HttpServer(java.net.InetAddress.getByName("127.0.0.1")) {
            HttpResponse(binaryBody = remote)
        }
        try {
            artwork.loadMedia("track", picture(32, 32), "http://127.0.0.1:${server.port}/cover.png")
            awaitImage { shown != null }
            assertEquals(128, shown!!.width)
            assertEquals(64, shown!!.height)
        } finally {
            artwork.close()
            server.close()
        }
    }

    @Test fun boundsLargeImagesAndRejectsInvalidData() {
        val bitmap = CastArtwork.decode(picture(2048, 1024), 1024)
        assertNotNull(bitmap)
        assertTrue(bitmap!!.width <= 1024)
        assertTrue(bitmap.height <= 1024)
        bitmap.recycle()
        assertNull(CastArtwork.decode(byteArrayOf(1, 2, 3), 1024))
    }
}

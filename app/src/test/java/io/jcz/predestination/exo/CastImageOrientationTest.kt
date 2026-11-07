package io.jcz.predestination.exo

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CastImageOrientationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun orientsJpegCornersAfterSampling() {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)
        val original = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        for (y in 0 until 40) for (x in 0 until 80)
            original.setPixel(x, y, colors[(if (x < 40) 0 else 1) + (if (y < 20) 0 else 2)])
        val layouts = listOf("0123", "1032", "3210", "2301", "0213", "2031", "3120", "1302")
        layouts.forEachIndexed { index, expected ->
            val file = File(temp.root, "$index.jpg")
            file.outputStream().use { original.compress(Bitmap.CompressFormat.JPEG, 96, it) }
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, (index + 1).toString())
                saveAttributes()
            }
            val result = requireNotNull(CastArtwork.decode(file.readBytes(), 40))
            assertEquals(if (index < 4) 40 else 20, result.width)
            assertEquals(if (index < 4) 20 else 40, result.height)
            for (corner in 0..3) {
                val pixel = result.getPixel(result.width * (1 + 2 * (corner % 2)) / 4,
                    result.height * (1 + 2 * (corner / 2)) / 4)
                val nearest = colors.indices.minBy { i ->
                    kotlin.math.abs(Color.red(pixel) - Color.red(colors[i])) +
                        kotlin.math.abs(Color.green(pixel) - Color.green(colors[i])) +
                        kotlin.math.abs(Color.blue(pixel) - Color.blue(colors[i]))
                }
                assertEquals("orientation ${index + 1}, corner $corner", expected[corner].digitToInt(), nearest)
            }
        }
    }
}

package io.jcz.predestination.exo

import android.app.Activity
import android.app.AlertDialog
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AudioControlsTest {
    private fun sliders(view: View): List<SeekBar> = when (view) {
        is SeekBar -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { sliders(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun remoteChangesThreeDbAndResetKeepsPanelOpen() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val audio = AudioGain {}.apply {
            configure(AudioFormat(48000, 8, C.ENCODING_PCM_16BIT))
            flush()
        }
        val dialog = audioControls(activity.get(), audio, audio::setGain)
        dialog.show()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val bars = sliders(dialog.window!!.decorView)
        assertEquals(6, bars.size)
        assertEquals(6, bars[0].max)
        assertEquals(14, bars[1].max)
        bars[0].dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(3, audio.decibels)
        bars[0].dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(bars[1].hasFocus())
        bars[1].dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(-3, audio.channelLevel(2))
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        assertTrue(dialog.isShowing)
        assertEquals(0, audio.decibels)
        assertEquals(0, audio.channelLevel(2))
        dialog.dismiss()
        activity.pause().stop().destroy()
    }
}

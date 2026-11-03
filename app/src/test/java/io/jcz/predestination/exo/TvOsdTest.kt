package io.jcz.predestination.exo

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TvOsdTest {
    @Test fun remoteNavigatesButtonsAndTimelineWithoutTriggeringHiddenControls() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val osd = TvOsd(activity)
        var clicked = 0
        var seek = 0
        osd.addButton("播放 / 暂停") { clicked++ }
        osd.addButton("音频设置") { clicked += 10 }
        osd.seek = { seek += it }
        activity.setContentView(osd)
        fun key(code: Int) = osd.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, code))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(View.VISIBLE, osd.visibility)
        assertEquals(0, clicked)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(10, clicked)
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(osd.timeline.hasFocus())
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(-10, seek)
        osd.updateProgress(30.0, 120.0, true)
        assertEquals(250, osd.timeline.progress)
        osd.hide()
        assertEquals(View.GONE, osd.visibility)
        controller.pause().stop().destroy()
    }
}

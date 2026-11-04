package io.jcz.predestination.exo

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WakeKeyFilterTest {
    @Test fun consumesWakePressRepeatsAndReleaseButNotNextPress() {
        for (key in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            val filter = WakeKeyFilter()
            assertTrue(filter.consume(KeyEvent(10, 10, KeyEvent.ACTION_DOWN, key, 0), true))
            assertTrue(filter.consume(KeyEvent(10, 20, KeyEvent.ACTION_DOWN, key, 1), false))
            assertTrue(filter.consume(KeyEvent(10, 30, KeyEvent.ACTION_UP, key, 0), false))
            assertFalse(filter.consume(KeyEvent(40, 40, KeyEvent.ACTION_DOWN, key, 0), false))
        }
    }

    @Test fun missingDownOrReleaseDoesNotLeakWakeOrSwallowNextGesture() {
        val filter = WakeKeyFilter()
        assertTrue(filter.consume(KeyEvent(10, 20, KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_BACK, 0), true))
        assertTrue(filter.consume(KeyEvent(30, 30, KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_BACK, 0), true))
        assertFalse(filter.consume(KeyEvent(40, 40, KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_BACK, 0), false))
    }
}

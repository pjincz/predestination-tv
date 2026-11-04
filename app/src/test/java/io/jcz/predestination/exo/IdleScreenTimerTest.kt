package io.jcz.predestination.exo

import org.junit.Assert.*
import org.junit.Test

class IdleScreenTimerTest {
    @Test fun fullCountdownIsShownAndResetHidesCountdown() {
        val timer = IdleScreenTimer()
        timer.update(1000, true)
        assertEquals(30, timer.countdownSeconds(1000))
        assertEquals(20, timer.countdownSeconds(11000))
        assertEquals(11, timer.countdownSeconds(20999))
        assertEquals(10, timer.countdownSeconds(21000))
        assertEquals(10, timer.countdownSeconds(21999))
        assertEquals(9, timer.countdownSeconds(22000))
        assertEquals(1, timer.countdownSeconds(30999))
        assertTrue(timer.update(31000, true))
        assertNull(timer.countdownSeconds(31000))
        timer.reset()
        assertNull(timer.countdownSeconds(32000))
        timer.update(32000, true)
        timer.update(53000, false)
        assertNull(timer.countdownSeconds(53000))
    }
    @Test fun waitsThirtySecondsAndFiresOnlyOnce() {
        val timer = IdleScreenTimer()
        assertFalse(timer.update(1000, true))
        assertFalse(timer.update(30999, true))
        assertTrue(timer.update(31000, true))
        assertFalse(timer.update(61000, true))
    }

    @Test fun playbackPauseBufferingOrLeavingWindowBreaksIdleInterval() {
        val timer = IdleScreenTimer()
        timer.update(0, true)
        assertFalse(timer.update(29000, false))
        assertFalse(timer.update(60000, true))
        assertFalse(timer.update(89999, true))
        assertTrue(timer.update(90000, true))
    }

    @Test fun remoteInteractionRestartsCountdown() {
        val timer = IdleScreenTimer()
        timer.update(0, true)
        timer.reset()
        assertFalse(timer.update(25000, true))
        assertFalse(timer.update(30000, true))
        assertTrue(timer.update(55000, true))
    }
}

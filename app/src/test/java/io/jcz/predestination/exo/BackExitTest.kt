package io.jcz.predestination.exo

import org.junit.Assert.*
import org.junit.Test

class BackExitTest {
    @Test fun exitsOnlyAtTopLevelWithin500Milliseconds() {
        val back = BackExit()
        assertFalse(back.onBack(true, 1000))
        assertFalse(back.onBack(true, 1501))
        assertTrue(back.onBack(true, 2001))
        back.reset()
        assertFalse(back.onBack(true, 2100))
    }

    @Test fun closingOverlaysCountsButNeverExits() {
        val back = BackExit()
        assertFalse(back.onBack(false, 1000))
        assertFalse(back.onBack(false, 1100))
        assertTrue(back.onBack(true, 1500))
        assertFalse(back.onBack(false, 1600))
        assertFalse(back.onBack(true, 2101))
    }
}

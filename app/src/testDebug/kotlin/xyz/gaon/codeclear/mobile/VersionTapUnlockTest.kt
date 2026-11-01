package xyz.gaon.codeclear.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTapUnlockTest {
    @Test
    fun sevenConsecutiveTapsUnlockAndStartANewSequence() {
        val taps = VersionTapUnlock()
        repeat(6) { assertFalse(taps.tap(it * 100L)) }
        assertTrue(taps.tap(600))
        assertFalse(taps.tap(700))
    }

    @Test
    fun longPausesAndClockReversalResetTheSequence() {
        val taps = VersionTapUnlock()
        repeat(6) { assertFalse(taps.tap(it * 100L)) }
        assertFalse(taps.tap(5001))
        repeat(5) { assertFalse(taps.tap(5100L + it)) }
        assertFalse(taps.tap(1))
    }
}

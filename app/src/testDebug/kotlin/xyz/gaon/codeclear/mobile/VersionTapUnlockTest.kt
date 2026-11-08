package xyz.gaon.codeclear.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.translation.TranslationTarget

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

    @Test
    fun slowVisitsNeverAccumulateIntoAReveal() {
        val taps = VersionTapUnlock()
        repeat(20) { assertFalse(taps.tap(it * 900L)) }
    }

    @Test
    fun labsDiscoveryNeverEnablesFeaturesOrAppearsOnFreshMenus() {
        val initial = MobileOptions(target = TranslationTarget.KOREAN)
        assertFalse(SettingsPage.LABS in settingsMenuPages(initial))
        val revealed = initial.copy(labsUnlocked = true)
        assertTrue(SettingsPage.LABS in settingsMenuPages(revealed))
        assertFalse(revealed.labsEnabled)
        assertFalse(revealed.serverAllowed)
        assertFalse(revealed.nearbyEnabled)
        assertFalse(SettingsPage.LABS in settingsMenuPages(initial.copy(labsEnabled = true)))
    }
}

package xyz.gaon.codeclear.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.probe.LocalTarget

class DetailLanguageChoicesTest {
    @Test
    fun originalLanguageIsExcludedIncludingBothChineseScriptTargets() {
        assertFalse(canTranslateDetailTo(LocalTarget.KOREAN, "ko-KR"))
        assertFalse(canTranslateDetailTo(LocalTarget.SIMPLIFIED_CHINESE, "zh-Hant"))
        assertFalse(canTranslateDetailTo(LocalTarget.TRADITIONAL_CHINESE, "zh-Hans"))
        assertTrue(canTranslateDetailTo(LocalTarget.FRENCH, "ko"))
    }

    @Test
    fun unidentifiedSourcesCannotOfferAnUnverifiedTargetChoice() {
        for (source in listOf(null, "", "und", "invalid-language")) {
            assertFalse(canTranslateDetailTo(LocalTarget.ENGLISH, source))
        }
    }
}

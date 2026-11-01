package xyz.gaon.codeclear.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.translation.TranslationTarget

class DetailLanguageChoicesTest {
    @Test
    fun originalLanguageIsExcludedIncludingBothChineseScriptTargets() {
        assertFalse(canTranslateDetailTo(TranslationTarget.KOREAN, "ko-KR"))
        assertFalse(canTranslateDetailTo(TranslationTarget.SIMPLIFIED_CHINESE, "zh-Hant"))
        assertFalse(canTranslateDetailTo(TranslationTarget.TRADITIONAL_CHINESE, "zh-Hans"))
        assertTrue(canTranslateDetailTo(TranslationTarget.FRENCH, "ko"))
    }

    @Test
    fun unidentifiedSourcesCannotOfferAnUnverifiedTargetChoice() {
        for (source in listOf(null, "", "und", "invalid-language")) {
            assertFalse(canTranslateDetailTo(TranslationTarget.ENGLISH, source))
        }
    }
}

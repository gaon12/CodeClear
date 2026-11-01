package xyz.gaon.codeclear.mobile

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.gaon.codeclear.translation.TranslationTarget

class UiLanguagesTest {
    @Test
    fun regionalPreferencesRemainSupportedWithoutMixingTargetLanguage() {
        assertEquals("en", UiLanguages.normalize("en-US"))
        assertEquals("ko", UiLanguages.normalize("ko-KR,en-US"))
    }

    @Test
    fun chineseScriptChoicesRespectExplicitScriptBeforeRegionDefaults() {
        assertEquals("zh-Hant", UiLanguages.normalize("zh-TW"))
        assertEquals("zh-Hant", UiLanguages.normalize("zh-HK"))
        assertEquals("zh-Hant", UiLanguages.normalize("zh-Hant-CN"))
        assertEquals("zh-Hans", UiLanguages.normalize("zh-Hans-HK"))
        assertEquals("zh-Hans", UiLanguages.normalize("zh-CN"))
        assertEquals("zh-Hans", UiLanguages.normalize("zh"))
    }

    @Test
    fun initialTranslationTargetAlsoPreservesAnExplicitChineseScript() {
        assertEquals(TranslationTarget.SIMPLIFIED_CHINESE, defaultTarget("zh-Hans-HK"))
        assertEquals(TranslationTarget.TRADITIONAL_CHINESE, defaultTarget("zh-Hant-CN"))
    }

    @Test
    fun initialTranslationTargetUsesThePrimarySupportedLanguageOrEnglishFallback() {
        assertEquals(TranslationTarget.KOREAN, defaultTarget("ko-KR,en-US"))
        assertEquals(TranslationTarget.JAPANESE, defaultTarget("ja-JP"))
        assertEquals(TranslationTarget.FRENCH, defaultTarget("fr-CA"))
        assertEquals(TranslationTarget.GERMAN, defaultTarget("de-DE"))
        assertEquals(TranslationTarget.ENGLISH, defaultTarget("es-ES"))
        assertEquals(TranslationTarget.ENGLISH, defaultTarget(""))
    }

    @Test
    fun emptyUnsupportedAndOversizedPreferencesUseTheSystemDefault() {
        assertEquals("", UiLanguages.normalize(""))
        assertEquals("", UiLanguages.normalize("es-ES"))
        assertEquals("", UiLanguages.normalize("x".repeat(513)))
    }
}

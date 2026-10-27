package xyz.gaon.codeclear.mobile

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.gaon.codeclear.probe.LocalTarget

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
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("zh-Hans-HK"))
            assertEquals(LocalTarget.SIMPLIFIED_CHINESE, defaultTarget())
            Locale.setDefault(Locale.forLanguageTag("zh-Hant-CN"))
            assertEquals(LocalTarget.TRADITIONAL_CHINESE, defaultTarget())
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun emptyUnsupportedAndOversizedPreferencesUseTheSystemDefault() {
        assertEquals("", UiLanguages.normalize(""))
        assertEquals("", UiLanguages.normalize("es-ES"))
        assertEquals("", UiLanguages.normalize("x".repeat(513)))
    }
}

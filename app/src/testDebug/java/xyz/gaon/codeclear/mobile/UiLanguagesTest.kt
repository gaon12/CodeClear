package xyz.gaon.codeclear.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class UiLanguagesTest {
    @Test
    fun regionalPreferencesRemainSupportedWithoutMixingTargetLanguage() {
        assertEquals("en", UiLanguages.normalize("en-US"))
        assertEquals("ko", UiLanguages.normalize("ko-KR,en-US"))
    }

    @Test
    fun emptyUnsupportedAndOversizedPreferencesUseTheSystemDefault() {
        assertEquals("", UiLanguages.normalize(""))
        assertEquals("", UiLanguages.normalize("es-ES"))
        assertEquals("", UiLanguages.normalize("x".repeat(513)))
    }
}

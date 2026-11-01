package xyz.gaon.codeclear.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTranslationSupportTest {
    @Test
    fun preparedDuplicatesCountOnceAndWinOverUnpreparedCopies() {
        val support = summarizeSystemTargets(
            listOf(
                SystemLanguagePair("EN", "ko", false),
                SystemLanguagePair("en", "KO", true),
                SystemLanguagePair("ja", "ko", false)
            )
        )
        assertEquals(SystemTargetSupport(1, 1), support.getValue(TranslationTarget.KOREAN))
        assertTrue(support.getValue(TranslationTarget.KOREAN).available)
        assertFalse(support.getValue(TranslationTarget.ENGLISH).available)
    }

    @Test
    fun genericChineseSupportsBothScriptsWhileSpecificScriptsStaySeparate() {
        val support = summarizeSystemTargets(
            listOf(
                SystemLanguagePair("en", "zh-Hans", true),
                SystemLanguagePair("en", "zh-Hant", false),
                SystemLanguagePair("ja", "zh", true)
            )
        )
        assertEquals(
            SystemTargetSupport(2, 0),
            support.getValue(TranslationTarget.SIMPLIFIED_CHINESE)
        )
        assertEquals(
            SystemTargetSupport(1, 1),
            support.getValue(TranslationTarget.TRADITIONAL_CHINESE)
        )
    }

    @Test
    fun malformedOrUnknownTagsDoNotAdvertiseUsablePairs() {
        val support = summarizeSystemTargets(
            listOf(
                SystemLanguagePair("und", "en", true),
                SystemLanguagePair("en_XX", "ko", true),
                SystemLanguagePair("en", "ko\n", true),
                SystemLanguagePair("en", "zh-Latn", true)
            )
        )
        assertTrue(support.values.none { it.available })
    }

    @Test
    fun summariesRemainBoundedForOversizedProviderLists() {
        val support = summarizeSystemTargets(
            (1..5000).map { SystemLanguagePair("en-x-v$it", "ko", true) }
        )
        assertEquals(4096, support.getValue(TranslationTarget.KOREAN).readyPairs)
        assertEquals(TranslationTarget.entries.toSet(), support.keys)
    }
}

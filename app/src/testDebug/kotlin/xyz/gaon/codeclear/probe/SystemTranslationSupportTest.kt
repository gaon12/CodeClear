package xyz.gaon.codeclear.probe

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
        assertEquals(SystemTargetSupport(1, 1), support.getValue(LocalTarget.KOREAN))
        assertTrue(support.getValue(LocalTarget.KOREAN).available)
        assertFalse(support.getValue(LocalTarget.ENGLISH).available)
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
        assertEquals(SystemTargetSupport(2, 0), support.getValue(LocalTarget.SIMPLIFIED_CHINESE))
        assertEquals(SystemTargetSupport(1, 1), support.getValue(LocalTarget.TRADITIONAL_CHINESE))
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
        assertEquals(4096, support.getValue(LocalTarget.KOREAN).readyPairs)
        assertEquals(LocalTarget.entries.toSet(), support.keys)
    }
}

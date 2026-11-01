package xyz.gaon.codeclear.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTranslationTest {
    private class Engine : TranslationEngine {
        data class Call(
            val input: TranslationInput,
            val download: Boolean,
            val done: (TranslationResult) -> Unit
        )
        val calls = mutableListOf<Call>()
        override fun run(
            input: TranslationInput,
            allowModelDownload: Boolean,
            complete: (TranslationResult) -> Unit
        ) {
            calls += Call(input, allowModelDownload, complete)
        }
    }

    @Test
    fun replacedInputKeepsOnlyTheLatestPendingWorkAndRejectsStaleResults() {
        val engine = Engine()
        val coordinator = TranslationCoordinator(engine) {}
        coordinator.select(input(1))
        coordinator.select(input(2))
        coordinator.select(input(3))
        assertEquals(1, engine.calls.size)
        engine.calls.first().done(ready("old"))
        assertEquals(2, engine.calls.size)
        assertEquals(3L, engine.calls.last().input.recordId)
        assertEquals(TranslationState.Working, coordinator.state)
        engine.calls.last().done(ready("new"))
        assertEquals(TranslationState.Finished(ready("new")), coordinator.state)
    }

    @Test
    fun deletingOrWithdrawingConsentPreventsLateResultsFromReturning() {
        val engine = Engine()
        val coordinator = TranslationCoordinator(engine) {}
        coordinator.select(input(1))
        coordinator.select(null)
        engine.calls.first().done(ready("late"))
        assertEquals(TranslationState.Idle, coordinator.state)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun duplicateSelectionDoesNotTranslateAgainAndRetryIsExplicit() {
        val engine = Engine()
        val coordinator = TranslationCoordinator(engine) {}
        coordinator.select(input(1))
        coordinator.select(input(1))
        coordinator.retry()
        assertEquals(1, engine.calls.size)
        engine.calls.first().done(TranslationResult.Failed)
        coordinator.select(input(1))
        assertEquals(1, engine.calls.size)
        coordinator.retry()
        assertEquals(2, engine.calls.size)
        assertFalse(engine.calls.last().download)
    }

    @Test
    fun changingTargetDoesNotCarryDownloadApprovalToAnotherRequest() {
        val engine = Engine()
        val coordinator = TranslationCoordinator(engine) {}
        coordinator.select(input(1))
        engine.calls.first().done(TranslationResult.NeedsModels)
        coordinator.retry(allowModelDownload = true)
        assertTrue(engine.calls.last().download)
        coordinator.select(input(1).copy(target = TranslationTarget.JAPANESE))
        engine.calls[1].done(ready("stale"))
        assertFalse(engine.calls.last().download)
        assertEquals(TranslationTarget.JAPANESE, engine.calls.last().input.target)
    }

    @Test
    fun downloadCannotBeStartedFromAnUnrelatedFailureState() {
        val engine = Engine()
        val coordinator = TranslationCoordinator(engine) {}
        coordinator.select(input(1))
        engine.calls.first().done(TranslationResult.UnknownLanguage)
        coordinator.retry(allowModelDownload = true)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun chineseConversionPreservesEmergencyNumbersPunctuationAndOtherScripts() {
        val converter = ChineseScriptConverter()
        val simplified = "[演练] 请勿进入危险区域。14:05，112，3 km。서울 东京 ひらがな 🚨 https://example.org/a?x=1"
        val traditional = "[演練] 請勿進入危險區域。14:05，112，3 km。서울 東京 ひらがな 🚨 https://example.org/a?x=1"
        assertEquals(
            traditional,
            converter.convert(simplified, TranslationTarget.TRADITIONAL_CHINESE)
        )
        assertEquals(
            simplified,
            converter.convert(traditional, TranslationTarget.SIMPLIFIED_CHINESE)
        )
        assertEquals(simplified, converter.convert(simplified, TranslationTarget.ENGLISH))
    }

    private fun input(id: Long) =
        TranslationInput(id, "[DRILL] Do not enter Zone $id.", TranslationTarget.KOREAN)
    private fun ready(text: String) = TranslationResult.Ready(text, "en", false)
}

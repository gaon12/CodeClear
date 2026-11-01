package xyz.gaon.codeclear.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTranslationTest {
    private class Engine : LocalEngine {
        data class Call(
            val input: LocalInput,
            val download: Boolean,
            val done: (LocalResult) -> Unit
        )
        val calls = mutableListOf<Call>()
        override fun run(
            input: LocalInput,
            allowModelDownload: Boolean,
            complete: (LocalResult) -> Unit
        ) {
            calls += Call(input, allowModelDownload, complete)
        }
    }

    @Test
    fun replacedInputKeepsOnlyTheLatestPendingWorkAndRejectsStaleResults() {
        val engine = Engine()
        val coordinator = LocalTranslationCoordinator(engine) {}
        coordinator.select(input(1))
        coordinator.select(input(2))
        coordinator.select(input(3))
        assertEquals(1, engine.calls.size)
        engine.calls.first().done(ready("old"))
        assertEquals(2, engine.calls.size)
        assertEquals(3L, engine.calls.last().input.recordId)
        assertEquals(LocalState.Working, coordinator.state)
        engine.calls.last().done(ready("new"))
        assertEquals(LocalState.Finished(ready("new")), coordinator.state)
    }

    @Test
    fun deletingOrWithdrawingConsentPreventsLateResultsFromReturning() {
        val engine = Engine()
        val coordinator = LocalTranslationCoordinator(engine) {}
        coordinator.select(input(1))
        coordinator.select(null)
        engine.calls.first().done(ready("late"))
        assertEquals(LocalState.Idle, coordinator.state)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun duplicateSelectionDoesNotTranslateAgainAndRetryIsExplicit() {
        val engine = Engine()
        val coordinator = LocalTranslationCoordinator(engine) {}
        coordinator.select(input(1))
        coordinator.select(input(1))
        coordinator.retry()
        assertEquals(1, engine.calls.size)
        engine.calls.first().done(LocalResult.Failed)
        coordinator.select(input(1))
        assertEquals(1, engine.calls.size)
        coordinator.retry()
        assertEquals(2, engine.calls.size)
        assertFalse(engine.calls.last().download)
    }

    @Test
    fun changingTargetDoesNotCarryDownloadApprovalToAnotherRequest() {
        val engine = Engine()
        val coordinator = LocalTranslationCoordinator(engine) {}
        coordinator.select(input(1))
        engine.calls.first().done(LocalResult.NeedsModels)
        coordinator.retry(allowModelDownload = true)
        assertTrue(engine.calls.last().download)
        coordinator.select(input(1).copy(target = LocalTarget.JAPANESE))
        engine.calls[1].done(ready("stale"))
        assertFalse(engine.calls.last().download)
        assertEquals(LocalTarget.JAPANESE, engine.calls.last().input.target)
    }

    @Test
    fun downloadCannotBeStartedFromAnUnrelatedFailureState() {
        val engine = Engine()
        val coordinator = LocalTranslationCoordinator(engine) {}
        coordinator.select(input(1))
        engine.calls.first().done(LocalResult.UnknownLanguage)
        coordinator.retry(allowModelDownload = true)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun chineseConversionPreservesEmergencyNumbersPunctuationAndOtherScripts() {
        val converter = ChineseScriptConverter()
        val simplified = "[演练] 请勿进入危险区域。14:05，112，3 km。서울 东京 ひらがな 🚨 https://example.org/a?x=1"
        val traditional = "[演練] 請勿進入危險區域。14:05，112，3 km。서울 東京 ひらがな 🚨 https://example.org/a?x=1"
        assertEquals(traditional, converter.convert(simplified, LocalTarget.TRADITIONAL_CHINESE))
        assertEquals(simplified, converter.convert(traditional, LocalTarget.SIMPLIFIED_CHINESE))
        assertEquals(simplified, converter.convert(simplified, LocalTarget.ENGLISH))
    }

    private fun input(id: Long) =
        LocalInput(id, "[DRILL] Do not enter Zone $id.", LocalTarget.KOREAN)
    private fun ready(text: String) = LocalResult.Ready(text, "en", false)
}

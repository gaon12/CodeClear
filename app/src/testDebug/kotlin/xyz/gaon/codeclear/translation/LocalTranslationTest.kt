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

    private fun input(id: Long) =
        TranslationInput(id, "[DRILL] Do not enter Zone $id.", TranslationTarget.KOREAN)
    private fun ready(text: String) = TranslationResult.Ready(text, "en", false)
}

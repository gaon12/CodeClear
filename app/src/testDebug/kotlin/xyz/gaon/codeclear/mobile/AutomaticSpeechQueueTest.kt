package xyz.gaon.codeclear.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.gaon.codeclear.translation.TranslationEngineKind

class AutomaticSpeechQueueTest {
    private val wall = 1_800_000_000_000L
    private val engine = TranslationEngineKind.ML_KIT
    private val selection = AutomaticSpeechSelection("en", engine)
    private fun capture() = CapturedAlert(
        "test.system.receiver",
        CaptureSource.NOTIFICATION,
        "[DRILL]",
        "[DRILL] Gate 3.",
        wall,
        postedAtMillis = wall
    )
    private fun ready(queue: AutomaticSpeechQueue, id: Long, elapsed: Long = 0) {
        queue.captured(id, capture(), wall, elapsed)
        queue.translated(id, "en", engine, "[DRILL] Translation $id.", elapsed)
    }

    @Test fun offHistoricalPeerAndUnknownFreshnessCannotScheduleSpeech() {
        val queue = AutomaticSpeechQueue()
        ready(queue, 1)
        assertNull(queue.next(true, 0))
        queue.configure(selection)
        for ((index, alert) in listOf(
            capture().copy(source = CaptureSource.PEER),
            capture().copy(source = CaptureSource.ORIGINAL_NOTIFICATION),
            capture().copy(postedAtMillis = wall - 15001),
            capture().copy(postedAtMillis = null),
            capture().copy(receivedAtMillis = wall - 15001),
            capture().copy(capturedAtMillis = wall - 15001)
        ).withIndex()) {
            queue.captured(index.toLong(), alert, wall, 0)
            queue.translated(index.toLong(), "en", engine, "[DRILL]", 0)
        }
        assertFalse(queue.hasPending)
    }

    @Test fun burstUsesABoundedSerialQueueAndNeverRepeatsACompletedTranslation() {
        val queue = AutomaticSpeechQueue().also { it.configure(selection) }
        repeat(12) { ready(queue, it.toLong()) }
        assertNull(queue.next(false, 0))
        repeat(8) { assertEquals(it.toLong(), queue.next(true, 0)!!.id) }
        assertNull(queue.next(true, 0))
        ready(queue, 0)
        assertNull(queue.next(true, 0))
    }

    @Test fun withdrawalReturnEngineAndLanguageChangesDiscardOldWork() {
        val queue = AutomaticSpeechQueue().also { it.configure(selection) }
        ready(queue, 1)
        queue.configure(null)
        queue.configure(selection)
        queue.translated(1, "en", engine, "[DRILL] Old result", 0)
        assertNull(queue.next(true, 0))
        ready(queue, 2)
        queue.configure(AutomaticSpeechSelection("ko", engine))
        queue.translated(2, "en", engine, "[DRILL] Old language", 0)
        assertNull(queue.next(true, 0))
        queue.configure(selection)
        ready(queue, 3)
        queue.configure(AutomaticSpeechSelection("en", TranslationEngineKind.MY_MEMORY))
        assertNull(queue.next(true, 0))
    }

    @Test fun deletionStopExpirationAndWrongResultCannotRestoreQueuedSpeech() {
        val queue = AutomaticSpeechQueue().also { it.configure(selection) }
        ready(queue, 1)
        ready(queue, 2)
        queue.deleted(1)
        assertEquals(2L, queue.next(true, 0)!!.id)
        queue.captured(3, capture(), wall, 0)
        queue.translated(3, "ko", engine, "[DRILL] Wrong language", 0)
        queue.translated(3, "en", TranslationEngineKind.MY_MEMORY, "[DRILL] Wrong engine", 0)
        assertNull(queue.next(true, 0))
        queue.translated(3, "en", engine, "[DRILL] Late", 120000)
        assertNull(queue.next(true, 120000))
        ready(queue, 4, 120001)
        queue.clear()
        queue.translated(4, "en", engine, "[DRILL] Stopped", 120001)
        assertNull(queue.next(true, 120001))
        ready(queue, 5, 120002)
        queue.deleted(null)
        assertFalse(queue.hasPending)
    }
}

package xyz.gaon.codeclear.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.gaon.codeclear.capture.SourceWindowSnapshot
import xyz.gaon.codeclear.translation.TranslationEngineKind

class DeferredAlertQueueTest {
    private val wall = 1_700_000_000_000L
    private val engine = TranslationEngineKind.ML_KIT
    private val gone = SourceWindowSnapshot(setOf(99), emptySet())

    private fun capture(source: CaptureSource, body: String = "[DRILL] Gate 3. 112.") =
        CapturedAlert(
            "test.system.receiver",
            source,
            "[DRILL]",
            body,
            wall,
            postedAtMillis = wall
        )

    private fun offer(queue: DeferredAlertQueue, id: Long, created: Boolean = true) {
        queue.observed(
            StoredObservation(id, created),
            capture(CaptureSource.NOTIFICATION),
            null,
            null,
            "en",
            engine,
            wall,
            0
        )
        queue.observed(
            StoredObservation(id, false),
            capture(CaptureSource.DIALOG),
            10,
            0,
            "en",
            engine,
            wall,
            0
        )
    }

    @Test
    fun visibleUnknownAndUnsettledWindowsCannotPresent() {
        val queue = DeferredAlertQueue()
        offer(queue, 1)
        assertNull(queue.select(0, SourceWindowSnapshot(setOf(10, 99), setOf(10))))
        assertNull(queue.select(1000, null))
        assertNull(queue.select(2000, gone))
        assertNull(queue.select(2749, gone))
        assertEquals(1L, queue.select(2750, gone)?.id)
        queue.sourceAppeared()
        assertNull(queue.select(3000, SourceWindowSnapshot(setOf(11), setOf(11))))
        assertNull(queue.select(4000, gone))
        assertEquals(1L, queue.select(4750, gone)?.id)
    }

    @Test
    fun existingHistoryAndUnreviewedDisplaysNeverBecomePopupCandidates() {
        val queue = DeferredAlertQueue(settleMillis = 0)
        offer(queue, 1, created = false)
        assertFalse(queue.hasPending)
        queue.observed(
            StoredObservation(2, true),
            capture(CaptureSource.DIALOG),
            10,
            1,
            "en",
            engine,
            wall,
            0
        )
        assertNull(queue.select(1000, gone))
        queue.observed(
            StoredObservation(2, false),
            capture(CaptureSource.NOTIFICATION),
            null,
            null,
            "en",
            engine,
            wall,
            1000
        )
        assertNull(queue.select(1000, gone))
        queue.observed(
            StoredObservation(2, false),
            capture(CaptureSource.DIALOG),
            10,
            0,
            "en",
            engine,
            wall,
            1000
        )
        assertEquals(2L, queue.select(1000, gone)?.id)
    }

    @Test
    fun notificationAndWindowEvidenceMayArriveInEitherOrderWithoutRepeatingDismissedItems() {
        val queue = DeferredAlertQueue(settleMillis = 0)
        queue.observed(
            StoredObservation(1, true),
            capture(CaptureSource.DIALOG),
            10,
            0,
            "en",
            engine,
            wall,
            0
        )
        assertNull(queue.select(0, gone))
        queue.observed(
            StoredObservation(1, false),
            capture(CaptureSource.NOTIFICATION),
            null,
            null,
            "en",
            engine,
            wall,
            1
        )
        assertEquals(1L, queue.select(1, gone)?.id)
        queue.dismiss(1)
        offer(queue, 1)
        assertFalse(queue.hasPending)
    }

    @Test
    fun expiredStaleOrFutureObservationsAndManualRecoveryDoNotPop() {
        val queue = DeferredAlertQueue(settleMillis = 0)
        queue.observed(
            StoredObservation(1, true),
            capture(CaptureSource.NOTIFICATION).copy(capturedAtMillis = wall - 120_001),
            null,
            null,
            "en",
            engine,
            wall,
            0
        )
        queue.observed(
            StoredObservation(2, true),
            capture(CaptureSource.ORIGINAL_NOTIFICATION),
            10,
            0,
            "en",
            engine,
            wall,
            0
        )
        queue.observed(
            StoredObservation(4, true),
            capture(CaptureSource.NOTIFICATION).copy(capturedAtMillis = wall + 5001),
            null,
            null,
            "en",
            engine,
            wall,
            0
        )
        queue.observed(
            StoredObservation(5, true),
            capture(CaptureSource.NOTIFICATION).copy(receivedAtMillis = wall - 120_001),
            null,
            null,
            "en",
            engine,
            wall,
            0
        )
        assertFalse(queue.hasPending)
        offer(queue, 3)
        assertNull(queue.select(120_000, gone))
        assertFalse(queue.hasPending)
    }

    @Test
    fun capacityIsBoundedAndChangesCannotRestoreClearedText() {
        val queue = DeferredAlertQueue(capacity = 2, settleMillis = 0)
        offer(queue, 1)
        assertEquals(1L, queue.select(0, gone)?.id)
        offer(queue, 2)
        offer(queue, 3)
        queue.dismiss(1)
        assertEquals(3L, queue.select(1, gone)?.id)
        queue.translated(3, "ko", engine, "[DRILL] Wrong target")
        assertNull(queue.select(1, gone)?.translation)
        queue.translated(3, "en", engine, "[DRILL] Ready 112")
        assertEquals("[DRILL] Ready 112", queue.select(1, gone)?.translation)
        queue.clear()
        queue.translated(3, "en", engine, "[DRILL] Late")
        assertFalse(queue.hasPending)
        assertNull(queue.select(2, gone))
    }

    @Test
    fun conflictingBodiesInvalidatePresentation() {
        val queue = DeferredAlertQueue(settleMillis = 0)
        offer(queue, 1)
        queue.observed(
            StoredObservation(1, false),
            capture(CaptureSource.DIALOG, "[DRILL] Other"),
            10,
            0,
            "en",
            engine,
            wall,
            1
        )
        assertFalse(queue.hasPending)
    }
}

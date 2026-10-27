package xyz.gaon.codeclear.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeWindowReadsTest {
    private val source = "com.google.android.cellbroadcastreceiver"

    @Test
    fun historyListDoesNotQualifyAsAnAlertDialog() {
        val tracker = ProbeWindowTracker()
        assertNull(
            tracker.windowChanged(
                source,
                "com.android.cellbroadcastreceiver.CellBroadcastListActivity",
                1
            )
        )
        assertNull(tracker.contentChanged(source, 1))
    }

    @Test
    fun contentEventsCannotBootstrapAnUnverifiedWindow() {
        assertNull(ProbeWindowTracker().contentChanged(source, 1))
    }

    @Test
    fun validAlertWindowRejectsOtherPackagesAndWindowIds() {
        val tracker = ProbeWindowTracker()
        val window = tracker.windowChanged(source, ProbeWindowTracker.ALERT_CLASS, 1)
        assertEquals(source, window?.packageName)
        assertNull(tracker.contentChanged(source, 2))
        assertNull(tracker.contentChanged("example.chat", 1))
    }

    @Test
    fun newWindowAndContentRevisionsInvalidateOldReads() {
        val tracker = ProbeWindowTracker()
        val original = tracker.windowChanged(source, ProbeWindowTracker.ALERT_CLASS, 1)
        val updated = tracker.contentChanged(source, 1)
        assertNotEquals(original, updated)
        val reopened = tracker.windowChanged(source, ProbeWindowTracker.ALERT_CLASS, 1)
        assertNotEquals(updated, reopened)
    }

    @Test
    fun returningToHistoryInvalidatesTheDialog() {
        val tracker = ProbeWindowTracker()
        tracker.windowChanged(source, ProbeWindowTracker.ALERT_CLASS, 1)
        tracker.windowChanged(source, "android.widget.ListView", 2)
        assertNull(tracker.current)
    }

    @Test
    fun latestWindowIsRetainedWhileAnOlderReadIsBusy() {
        val queue = LatestProbeReadQueue()
        val first = request(1)
        queue.offer(first)
        assertEquals(first, queue.takeIfIdle())
        queue.offer(request(2))
        val latest = request(3)
        queue.offer(latest)
        assertTrue(queue.busy)
        assertNull(queue.takeIfIdle())
        queue.complete()
        assertEquals(latest, queue.takeIfIdle())
        assertFalse(queue.hasPending)
    }

    @Test
    fun invalidatingPendingReadsDoesNotOpenAnotherWorkerSlot() {
        val queue = LatestProbeReadQueue()
        queue.offer(request(1))
        queue.takeIfIdle()
        queue.offer(request(2))
        queue.discardPending()
        assertTrue(queue.busy)
        queue.complete()
        assertNull(queue.takeIfIdle())
    }

    private fun request(revision: Long) = ProbeReadRequest(
        ProbeAlertWindow(source, 1, revision),
        consentGeneration = 1
    )
}

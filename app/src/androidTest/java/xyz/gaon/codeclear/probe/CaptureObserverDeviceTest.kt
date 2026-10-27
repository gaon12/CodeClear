package xyz.gaon.codeclear.probe

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureObserverDeviceTest {
    @Test
    fun observersReceiveGenerationBoundUpdatesAndStopAfterClosing() = onMain {
        ProbeSession.configure(ProbeRoute.NOTIFICATION, false)
        ProbeSession.configure(ProbeRoute.ACCESSIBILITY, false)
        val events = mutableListOf<String>()
        val subscription = ProbeSession.observe(object : CaptureObserver {
            override fun added(generation: Long, record: ProbeRecord) {
                events += "added:$generation"
            }

            override fun updated(generation: Long, record: ProbeRecord) {
                events += "updated:$generation"
            }

            override fun cleared(generation: Long) {
                events += "cleared:$generation"
            }
        })
        try {
            val initial = ProbeSession.currentGeneration()
            val id = ProbeSession.publish(initial, fixture())!!
            ProbeSession.annotateHeading(initial, id, ProbeHeadingKind.TEST)
            ProbeSession.clear()
            val next = ProbeSession.currentGeneration()
            assertNull(ProbeSession.publish(initial, fixture()))
            assertNotNull(ProbeSession.publish(next, fixture()))
            subscription.close()
            ProbeSession.publish(next, fixture())
            assertEquals(
                listOf("added:$initial", "updated:$initial", "cleared:$next", "added:$next"),
                events
            )
        } finally {
            subscription.close()
            ProbeSession.clear()
        }
    }

    @Test
    fun clearingInsideAnObserverCannotMakePublishReadAnEmptyBuffer() = onMain {
        ProbeSession.configure(ProbeRoute.NOTIFICATION, false)
        ProbeSession.configure(ProbeRoute.ACCESSIBILITY, false)
        val subscription = ProbeSession.observe(object : CaptureObserver {
            override fun added(generation: Long, record: ProbeRecord) {
                ProbeSession.clear()
            }
        })
        try {
            assertNotNull(ProbeSession.publish(ProbeSession.currentGeneration(), fixture()))
            assertEquals(0, ProbeSession.state.records.size)
        } finally {
            subscription.close()
            ProbeSession.clear()
        }
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun fixture() = ProbeObservation(
        packageName = "com.android.shell",
        route = ProbeRoute.NOTIFICATION,
        origin = ProbeOrigin.SHELL_FIXTURE,
        title = "[DRILL] Observer fixture",
        body = "[DRILL] Do not enter Gate 2. 112.",
        capturedAtMillis = 1700000000000L,
        bodyEvidence = ProbeBodyEvidence.SHELL_FIXTURE_FIELD
    )
}

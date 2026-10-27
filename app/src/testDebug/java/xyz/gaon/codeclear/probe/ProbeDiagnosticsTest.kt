package xyz.gaon.codeclear.probe

import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeDiagnosticsTest {
    @Test
    fun diagnosticsExposeCountsAndFlagsWithoutRawTitlesOrBodies() {
        val state = ProbeSessionState(
            accessibilityConnected = true,
            records = listOf(record(1))
        )
        val output = render(state)
        assertTrue(output.contains("accessibilityConnected=true"))
        assertTrue(output.contains("recordCount=1"))
        assertTrue(output.contains("bodyLength=${state.records.single().observation.body.length}"))
        assertTrue(output.contains("platformBoundaryPossible=true"))
        assertFalse(output.contains("private-title-fixture"))
        assertFalse(output.contains("private-body-fixture"))
    }

    @Test
    fun diagnosticsKeepOutputBoundedWhenTheObservationBufferIsFull() {
        val output = render(ProbeSessionState(records = (1L..40L).map(::record)))
        assertTrue(output.contains("recordCount=40"))
        assertEquals(5, output.lineSequence().count { it.startsWith("id=") })
        assertTrue(output.contains("id=40 "))
        assertFalse(output.contains("id=1 "))
    }

    private fun render(state: ProbeSessionState): String {
        val output = StringWriter()
        ProbeDiagnostics.write(state, PrintWriter(output))
        return output.toString()
    }

    private fun record(id: Long) = ProbeRecord(
        id,
        ProbeObservation(
            packageName = "com.google.android.cellbroadcastreceiver",
            route = ProbeRoute.ACCESSIBILITY,
            origin = ProbeOrigin.SYSTEM_CANDIDATE,
            title = "private-title-fixture",
            body = "private-body-fixture",
            capturedAtMillis = 1,
            platformBoundaryPossible = true
        )
    )
}

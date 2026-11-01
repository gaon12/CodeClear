package xyz.gaon.codeclear.capture

import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeDiagnosticsTest {
    @Test
    fun equalityDiagnosticComparesActualNonemptyBodiesWithoutPrintingThem() {
        val first = record(1).let {
            it.copy(observation = it.observation.copy(route = ProbeRoute.NOTIFICATION))
        }
        val second = record(2)
        val matching = render(ProbeSessionState(records = listOf(first, second)))
        assertTrue(matching.contains("nonemptyBodiesEqual=true"))
        assertFalse(matching.contains("private-body-fixture"))
        val different = second.copy(
            observation = second.observation.copy(body = "private-body-fixturX")
        )
        assertEquals(first.observation.body.length, different.observation.body.length)
        assertTrue(
            render(ProbeSessionState(records = listOf(first, different)))
                .contains("nonemptyBodiesEqual=false")
        )
    }

    @Test
    fun diagnosticsExposeCountsAndFlagsWithoutRawTitlesOrBodies() {
        val state = ProbeSessionState(
            accessibilityConnected = true,
            records = listOf(record(1))
        )
        val output = render(
            state,
            ProbeSourceActionState(
                returnedMessages = 2,
                recognizedTypes = true,
                importedMessages = 2,
                matchingObservedBodies = 1
            )
        )
        assertTrue(output.contains("accessibilityConnected=true"))
        assertTrue(output.contains("recordCount=1"))
        assertTrue(output.contains("bodyLength=${state.records.single().observation.body.length}"))
        assertTrue(output.contains("platformBoundaryPossible=true"))
        assertTrue(output.contains("sourceImportedMessages=2"))
        assertTrue(output.contains("sourceBodiesMatchingObserved=1"))
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

    private fun render(
        state: ProbeSessionState,
        action: ProbeSourceActionState = ProbeSourceActionState()
    ): String {
        val output = StringWriter()
        ProbeDiagnostics.write(state, PrintWriter(output), action)
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

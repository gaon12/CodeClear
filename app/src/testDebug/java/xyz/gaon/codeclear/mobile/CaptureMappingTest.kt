package xyz.gaon.codeclear.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.gaon.codeclear.probe.ProbeBodyEvidence
import xyz.gaon.codeclear.probe.ProbeBroadcastFields
import xyz.gaon.codeclear.probe.ProbeHeadingKind
import xyz.gaon.codeclear.probe.ProbeObservation
import xyz.gaon.codeclear.probe.ProbeOrigin
import xyz.gaon.codeclear.probe.ProbeRecord
import xyz.gaon.codeclear.probe.ProbeRoute

class CaptureMappingTest {
    private val source = "com.google.android.cellbroadcastreceiver"
    private val observation = ProbeObservation(
        source,
        ProbeRoute.NOTIFICATION,
        ProbeOrigin.SYSTEM_CANDIDATE,
        "Observed heading",
        "  Do not enter. 112.\n",
        1700000000000,
        bodyEvidence = ProbeBodyEvidence.EXPANDED_NOTIFICATION_FIELD
    )

    @Test
    fun shellAndUnverifiedPackagesCannotEnterProductHistory() {
        assertNull(map(observation.copy(origin = ProbeOrigin.SHELL_FIXTURE)))
        assertNull(map(observation.copy(packageName = "untrusted.example")))
    }

    @Test
    fun absentRadioAndWithdrawnRouteConsentRejectCapture() {
        assertNull(ProbeRecord(1, observation).toHistoryCapture(setOf(source), false, true, true))
        assertNull(ProbeRecord(1, observation).toHistoryCapture(setOf(source), true, false, true))
        val dialog = observation.copy(route = ProbeRoute.ACCESSIBILITY)
        assertNull(ProbeRecord(1, dialog).toHistoryCapture(setOf(source), true, true, false))
    }

    @Test
    fun summariesAndIncompleteOrUnknownBodiesAreNotTranslatedAsIndividualAlerts() {
        assertNull(map(observation.copy(groupSummary = true)))
        assertNull(map(observation.copy(limited = true)))
        assertNull(map(observation.copy(bodyEvidence = ProbeBodyEvidence.UNVERIFIED)))
        assertNull(map(observation.copy(platformBoundaryPossible = true)))
        assertNull(map(observation.copy(bodyEvidence = ProbeBodyEvidence.SHELL_FIXTURE_FIELD)))
        assertNull(map(observation.copy(body = " ")))
    }

    @Test
    fun originalWhitespaceAndUnknownTimesRemainUnchanged() {
        val mapped = map(observation)!!
        assertEquals(observation.body, mapped.original)
        assertNull(mapped.postedAtMillis)
        assertNull(mapped.receivedAtMillis)
        assertNull(mapped.sourceIdentity)
    }

    @Test
    fun numericSourceFieldsDoNotOverrideContradictingTitleEstimates() {
        val mapped = map(
            observation.copy(
                bodyEvidence = ProbeBodyEvidence.SOURCE_NOTIFICATION_PAYLOAD,
                broadcastFields = ProbeBroadcastFields(1, 42, 4372, 3, 1, null, 1700000000100),
                headingHint = ProbeHeadingKind.PUBLIC_SAFETY
            )
        )!!
        assertEquals("PUBLIC_SAFETY", mapped.headingHint)
        assertEquals(1, mapped.broadcast?.cmasClass)
        assertEquals(4372, mapped.broadcast?.category)
        assertEquals(1700000000100, mapped.receivedAtMillis)
        assertEquals(CaptureSource.ORIGINAL_NOTIFICATION, mapped.source)
        assertEquals("broadcast:1:4372:42:1700000000100", mapped.sourceIdentity)
    }

    private fun map(value: ProbeObservation) = ProbeRecord(1, value)
        .toHistoryCapture(setOf(source), true, true, true)
}

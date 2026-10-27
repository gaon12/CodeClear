package xyz.gaon.codeclear.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeRulesTest {
    @Test
    fun ordinaryNotificationsCannotEnterThroughAnAlertTitleOrTag() {
        assertNull(ProbeSourcePolicy.classify("example.chat", emptySet(), true, "test_alert"))
    }

    @Test
    fun candidatePackageMustAlsoBeAVerifiedSystemApp() {
        val candidate = "com.android.cellbroadcastreceiver"
        assertNull(ProbeSourcePolicy.classify(candidate, emptySet(), false))
        assertEquals(
            ProbeOrigin.SYSTEM_CANDIDATE,
            ProbeSourcePolicy.classify(candidate, setOf(candidate), false)
        )
    }

    @Test
    fun arbitrarySystemAppsCannotEnterTheCaptureScope() {
        assertNull(
            ProbeSourcePolicy.classify(
                "com.android.messaging",
                setOf("com.android.messaging"),
                false
            )
        )
    }

    @Test
    fun shellFixtureNeedsExplicitOptInAndTheExactTag() {
        assertNull(ProbeSourcePolicy.classify("com.android.shell", emptySet(), false, "test_alert"))
        assertNull(ProbeSourcePolicy.classify("com.android.shell", emptySet(), true, "other"))
        assertEquals(
            ProbeOrigin.SHELL_FIXTURE,
            ProbeSourcePolicy.classify("com.android.shell", emptySet(), true, "test_alert")
        )
    }

    @Test
    fun missingAndEmptyTextRemainEmptyRatherThanBeingInvented() {
        assertEquals(BoundedText("", false), BoundedText.read(null))
        assertEquals(BoundedText("", false), BoundedText.read(""))
    }

    @Test
    fun extractionKeepsWhitespaceAndNeverNormalizesTheOriginal() {
        val original = "  [DRILL]\nDo not leave.  "
        assertEquals(BoundedText(original, false), BoundedText.read(original))
    }

    @Test
    fun boundedTextMarksTruncationAndPreservesSurrogatePairs() {
        assertEquals(BoundedText("ab", true), BoundedText.read("ab\uD83D\uDE80c", 3))
        assertEquals(BoundedText("ab\uD83D\uDE80", true), BoundedText.read("ab\uD83D\uDE80c", 4))
        assertEquals(BoundedText("abc", false), BoundedText.read("abc", 3))
    }

    @Test
    fun clearDiscardsAnObservationAlreadyInFlight() {
        val buffer = ProbeBuffer()
        val capturedGeneration = buffer.generation
        buffer.clear()
        assertFalse(buffer.append(capturedGeneration, observation("stale")))
        assertTrue(buffer.snapshot().isEmpty())
        assertTrue(buffer.append(buffer.generation, observation("new")))
    }

    @Test
    fun diagnosticBufferIsBoundedAndKeepsIndividualObservationsSeparate() {
        val buffer = ProbeBuffer(capacity = 2)
        repeat(3) { buffer.append(buffer.generation, observation("message $it")) }
        assertEquals(
            listOf("message 1", "message 2"),
            buffer.snapshot().map {
                it.observation.body
            }
        )
        assertEquals(2, buffer.snapshot().map { it.id }.distinct().size)
    }

    @Test
    fun takingASnapshotDoesNotExposeTheMutableBuffer() {
        val buffer = ProbeBuffer()
        buffer.append(buffer.generation, observation("saved"))
        val snapshot = buffer.snapshot()
        buffer.clear()
        assertEquals("saved", snapshot.single().observation.body)
        assertTrue(buffer.snapshot().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedBodiesCannotBypassTheBufferBound() {
        val buffer = ProbeBuffer()
        buffer.append(buffer.generation, observation("a".repeat(8193)))
    }

    private fun observation(body: String) = ProbeObservation(
        packageName = "com.android.cellbroadcastreceiver",
        route = ProbeRoute.NOTIFICATION,
        origin = ProbeOrigin.SYSTEM_CANDIDATE,
        title = "",
        body = body,
        capturedAtMillis = 1
    )
}

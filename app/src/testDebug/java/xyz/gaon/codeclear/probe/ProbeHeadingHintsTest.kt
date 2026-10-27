package xyz.gaon.codeclear.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeHeadingHintsTest {
    @Test
    fun operatorDefinedHeadingsAreNotAutomaticallyLabeledAsTests() {
        val index = ProbeHeadingIndex(
            listOf(
                ProbeHeadingKind.OPERATOR_DEFINED to "Operator-defined alert",
                ProbeHeadingKind.TEST to "Monthly test"
            )
        )
        assertEquals(ProbeHeadingKind.OPERATOR_DEFINED, index.infer("Operator-defined alert"))
        assertEquals(ProbeHeadingKind.TEST, index.infer("Monthly test"))
    }

    @Test
    fun localizedSourceResourcesMapToTheSameHintWithoutFixedDisplayLabels() {
        val index = ProbeHeadingIndex(
            listOf(
                ProbeHeadingKind.NATIONAL to "National alert",
                ProbeHeadingKind.NATIONAL to "국가 경보",
                ProbeHeadingKind.NATIONAL to "Alerte nationale"
            )
        )
        assertEquals(ProbeHeadingKind.NATIONAL, index.infer("National alert"))
        assertEquals(ProbeHeadingKind.NATIONAL, index.infer("국가 경보"))
        assertEquals(ProbeHeadingKind.NATIONAL, index.infer("Alerte nationale\n27 Oct 13:05"))
    }

    @Test
    fun sharedUnknownAndAlteredHeadingsStayUnknown() {
        val index = ProbeHeadingIndex(
            listOf(
                ProbeHeadingKind.NATIONAL to "Shared heading",
                ProbeHeadingKind.EXTREME to "Shared heading",
                ProbeHeadingKind.AMBER to "Missing person"
            )
        )
        assertEquals(ProbeHeadingKind.UNKNOWN, index.infer("Shared heading"))
        assertEquals(ProbeHeadingKind.UNKNOWN, index.infer("Missing person extra words"))
        assertEquals(ProbeHeadingKind.UNKNOWN, index.infer(""))
        assertEquals(ProbeHeadingKind.UNKNOWN, index.infer("x".repeat(257)))
    }

    @Test
    fun queueCountsUseOnlyTheReviewedStructuralSuffix() {
        listOf("OK", "확인", "確認", "D’accord").forEach { label ->
            assertEquals(3, ProbeDialogQueue.reportedMultipleCount("$label  (1/3)"))
        }
        listOf("OK", "OK (3)", "OK  (1/1)", "OK  (2/3)", "OK  (1/10000)").forEach {
            assertEquals(null, ProbeDialogQueue.reportedMultipleCount(it))
        }
    }

    @Test
    fun aLateTitleHintCannotRestoreAClearedOrEvictedOriginal() {
        val buffer = ProbeBuffer(capacity = 1)
        buffer.append(0, observation())
        val original = buffer.snapshot().single()
        assertTrue(buffer.annotateHeading(0, original.id, ProbeHeadingKind.AMBER))
        assertEquals(ProbeHeadingKind.AMBER, buffer.snapshot().single().observation.headingHint)
        buffer.append(0, observation())
        assertFalse(buffer.annotateHeading(0, original.id, ProbeHeadingKind.NATIONAL))
        val newer = buffer.snapshot().single()
        buffer.clear()
        assertFalse(buffer.annotateHeading(0, newer.id, ProbeHeadingKind.NATIONAL))
        assertTrue(buffer.snapshot().isEmpty())
    }

    private fun observation() = ProbeObservation(
        "com.google.android.cellbroadcastreceiver",
        ProbeRoute.ACCESSIBILITY,
        ProbeOrigin.SYSTEM_CANDIDATE,
        "",
        "[DRILL] Fixture",
        1
    )
}

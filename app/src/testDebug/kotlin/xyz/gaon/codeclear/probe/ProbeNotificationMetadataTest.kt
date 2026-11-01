package xyz.gaon.codeclear.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.gaon.codeclear.translation.TranslationTarget

class ProbeNotificationMetadataTest {
    @Test
    fun settingsUpdatesAndUnreviewedChannelsDoNotBecomeIndividualAlerts() {
        val origin = ProbeOrigin.SYSTEM_CANDIDATE
        assertEquals(
            ProbeBodyEvidence.UNVERIFIED,
            ProbeNotificationMetadata.bodyEvidence(origin, true, false)
        )
        assertEquals(
            ProbeBodyEvidence.EXPANDED_NOTIFICATION_FIELD,
            ProbeNotificationMetadata.bodyEvidence(
                origin,
                true,
                false,
                ProbeNotificationChannel.EMERGENCY_PRESENTATION
            )
        )
    }

    @Test
    fun unflaggedReceiverSummariesRemainUnverifiedWithoutAnExpandedBody() {
        val evidence = ProbeNotificationMetadata.bodyEvidence(
            ProbeOrigin.SYSTEM_CANDIDATE,
            false,
            false,
            ProbeNotificationChannel.EMERGENCY_PRESENTATION
        )
        assertEquals(ProbeBodyEvidence.UNVERIFIED, evidence)
        listOf("3 unread alerts", "미확인 경보 3건", "3件の未読アラート", "3 alertes non lues").forEach { title ->
            assertNull(record(title, evidence).localInput(TranslationTarget.ENGLISH))
        }
    }

    @Test
    fun explicitFixturesDoNotEstablishASystemLevelOrPresentationChannel() {
        assertEquals(
            ProbeBodyEvidence.SHELL_FIXTURE_FIELD,
            ProbeNotificationMetadata.bodyEvidence(ProbeOrigin.SHELL_FIXTURE, false, false)
        )
        assertEquals(
            ProbeNotificationChannel.UNKNOWN,
            ProbeNotificationMetadata.channel(ProbeOrigin.SHELL_FIXTURE, "broadcastMessages")
        )
    }

    @Test
    fun groupSummariesNeverBecomeIndividualEvenWithAnExpandedField() {
        assertEquals(
            ProbeBodyEvidence.UNVERIFIED,
            ProbeNotificationMetadata.bodyEvidence(
                ProbeOrigin.SYSTEM_CANDIDATE,
                true,
                true,
                ProbeNotificationChannel.EMERGENCY_PRESENTATION
            )
        )
    }

    @Test
    fun knownChannelIdsAreIndependentOfDisplayedLanguageAndUnknownIdsStayUnknown() {
        val origin = ProbeOrigin.SYSTEM_CANDIDATE
        assertEquals(
            ProbeNotificationChannel.EMERGENCY_PRESENTATION,
            ProbeNotificationMetadata.channel(origin, "broadcastMessages")
        )
        assertEquals(
            ProbeNotificationChannel.NON_EMERGENCY_PRESENTATION,
            ProbeNotificationMetadata.channel(origin, "broadcastMessagesNonEmergency")
        )
        assertEquals(
            ProbeNotificationChannel.UNKNOWN,
            ProbeNotificationMetadata.channel(origin, "긴급재난문자")
        )
        assertEquals(
            ProbeNotificationChannel.UNKNOWN,
            ProbeNotificationMetadata.channel(origin, null)
        )
    }

    @Test
    fun readableIndividualFieldsTranslateWhileEmptyLimitedAndGroupedBodiesDoNot() {
        val record = record("[DRILL] Stay indoors.", ProbeBodyEvidence.VISIBLE_DIALOG_FIELD)
        assertNotNull(record.localInput(TranslationTarget.ENGLISH))
        listOf(
            record.observation.copy(body = ""),
            record.observation.copy(limited = true),
            record.observation.copy(groupSummary = true)
        ).forEach {
            assertNull(record.copy(observation = it).localInput(TranslationTarget.ENGLISH))
        }
    }

    private fun record(body: String, evidence: ProbeBodyEvidence) = ProbeRecord(
        1,
        ProbeObservation(
            "com.google.android.cellbroadcastreceiver",
            ProbeRoute.NOTIFICATION,
            ProbeOrigin.SYSTEM_CANDIDATE,
            "",
            body,
            1,
            bodyEvidence = evidence
        )
    )
}

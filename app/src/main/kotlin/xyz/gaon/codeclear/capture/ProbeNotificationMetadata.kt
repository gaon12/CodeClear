package xyz.gaon.codeclear.capture

object ProbeNotificationMetadata {
    fun bodyEvidence(
        origin: ProbeOrigin,
        hasExpandedBody: Boolean,
        groupSummary: Boolean,
        channel: ProbeNotificationChannel = ProbeNotificationChannel.UNKNOWN
    ): ProbeBodyEvidence = when {
        groupSummary -> ProbeBodyEvidence.UNVERIFIED

        origin == ProbeOrigin.SHELL_FIXTURE -> ProbeBodyEvidence.SHELL_FIXTURE_FIELD

        channel == ProbeNotificationChannel.UNKNOWN -> ProbeBodyEvidence.UNVERIFIED

        hasExpandedBody -> ProbeBodyEvidence.EXPANDED_NOTIFICATION_FIELD

        // Reviewed AOSP receivers can post an unread-count summary without GROUP_SUMMARY.
        else -> ProbeBodyEvidence.UNVERIFIED
    }

    /** Recognize only a presentation-channel ID. This is never an official alert level. */
    fun channel(origin: ProbeOrigin, channelId: String?): ProbeNotificationChannel {
        if (origin != ProbeOrigin.SYSTEM_CANDIDATE) return ProbeNotificationChannel.UNKNOWN
        return when (channelId) {
            "broadcastMessages" -> ProbeNotificationChannel.EMERGENCY_PRESENTATION
            "broadcastMessagesHighPriority" -> ProbeNotificationChannel.HIGH_PRIORITY_PRESENTATION
            "broadcastMessagesNonEmergency" -> ProbeNotificationChannel.NON_EMERGENCY_PRESENTATION
            "broadcastMessagesInVoiceCall" -> ProbeNotificationChannel.VOICE_CALL_PRESENTATION
            else -> ProbeNotificationChannel.UNKNOWN
        }
    }
}

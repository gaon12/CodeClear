package xyz.gaon.codeclear.capture

import java.io.PrintWriter

/** Metadata-only development inspection; never write raw text, content hashes, or full state. */
object ProbeDiagnostics {
    fun write(
        state: ProbeSessionState,
        writer: PrintWriter,
        sourceAction: ProbeSourceActionState = ProbeSourceActionState()
    ) {
        writer.println("captureProbeOnly=true")
        writer.println("notificationConsent=${state.notificationConsent}")
        writer.println("accessibilityConsent=${state.accessibilityConsent}")
        writer.println("notificationConnected=${state.notificationConnected}")
        writer.println("accessibilityConnected=${state.accessibilityConnected}")
        writer.println("recordCount=${state.records.size}")
        writer.println(
            "sourceActionAvailable=${sourceAction.available} " +
                "sourceActionBusy=${sourceAction.busy} " +
                "sourceReturnedMessages=${sourceAction.returnedMessages ?: -1} " +
                "sourceKnownMessageTypes=${sourceAction.recognizedTypes} " +
                "sourceImportedMessages=${sourceAction.importedMessages} " +
                "sourceBodiesMatchingObserved=${sourceAction.matchingObservedBodies} " +
                "sourceActionFailure=${sourceAction.failure ?: "-"}"
        )
        writer.println("lastAccessibilityWindowClass=${state.lastAccessibilityWindowClass ?: "-"}")
        state.records.takeLast(5).forEach { record ->
            val observation = record.observation
            writer.println(
                "id=${record.id} route=${observation.route} origin=${observation.origin} " +
                    "package=${observation.packageName} " +
                    "capturedAtMillis=${observation.capturedAtMillis} " +
                    "postedAtMillis=${observation.postedAtMillis ?: -1} " +
                    "receivedAtMillis=${observation.broadcastFields?.receivedAtMillis ?: -1} " +
                    "bodyLength=${observation.body.length} " +
                    "titleLength=${observation.title.length} " +
                    "limited=${observation.limited} groupSummary=${observation.groupSummary} " +
                    "platformBoundaryPossible=${observation.platformBoundaryPossible} " +
                    "windowClass=${observation.windowClass ?: "-"} " +
                    "readDurationMillis=${observation.readDurationMillis ?: -1} " +
                    "bodyEvidence=${observation.bodyEvidence} " +
                    "notificationChannel=${observation.notificationChannel} " +
                    "headingHint=${observation.headingHint} " +
                    "reportedDialogQueueSize=${observation.reportedDialogQueueSize ?: -1} " +
                    "broadcastFormat=${observation.broadcastFields?.format ?: -1} " +
                    "broadcastCategory=${observation.broadcastFields?.category ?: -1} " +
                    "broadcastPriority=${observation.broadcastFields?.priority ?: -1} " +
                    "cmasClass=${observation.broadcastFields?.cmasClass ?: -1} " +
                    "etwsType=${observation.broadcastFields?.etwsType ?: -1}"
            )
        }
        val notification = state.records.lastOrNull {
            it.observation.route == ProbeRoute.NOTIFICATION &&
                it.observation.origin == ProbeOrigin.SYSTEM_CANDIDATE
        }
        val accessibility = state.records.lastOrNull {
            it.observation.route == ProbeRoute.ACCESSIBILITY &&
                it.observation.origin == ProbeOrigin.SYSTEM_CANDIDATE
        }
        if (notification != null && accessibility != null &&
            notification.observation.packageName == accessibility.observation.packageName
        ) {
            writer.println(
                "latestSystemCandidatePair notificationId=${notification.id} " +
                    "accessibilityId=${accessibility.id} " +
                    "nonemptyBodiesEqual=${notification.observation.body.isNotEmpty() &&
                        notification.observation.body == accessibility.observation.body}"
            )
        }
        writer.flush()
    }
}

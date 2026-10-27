package xyz.gaon.codeclear.probe

import java.io.PrintWriter

/** Metadata-only development inspection; never write raw text, content hashes, or full state. */
object ProbeDiagnostics {
    fun write(state: ProbeSessionState, writer: PrintWriter) {
        writer.println("captureProbeOnly=true")
        writer.println("notificationConsent=${state.notificationConsent}")
        writer.println("accessibilityConsent=${state.accessibilityConsent}")
        writer.println("notificationConnected=${state.notificationConnected}")
        writer.println("accessibilityConnected=${state.accessibilityConnected}")
        writer.println("recordCount=${state.records.size}")
        state.records.takeLast(5).forEach { record ->
            val observation = record.observation
            writer.println(
                "id=${record.id} route=${observation.route} origin=${observation.origin} " +
                    "package=${observation.packageName} " +
                    "bodyLength=${observation.body.length} " +
                    "titleLength=${observation.title.length} " +
                    "limited=${observation.limited} groupSummary=${observation.groupSummary} " +
                    "platformBoundaryPossible=${observation.platformBoundaryPossible}"
            )
        }
        writer.flush()
    }
}

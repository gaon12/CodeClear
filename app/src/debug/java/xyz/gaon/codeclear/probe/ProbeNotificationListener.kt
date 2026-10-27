package xyz.gaon.codeclear.probe

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.FileDescriptor
import java.io.PrintWriter

class ProbeNotificationListener : NotificationListenerService() {
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        ProbeDiagnostics.write(ProbeSession.state, writer)
    }

    override fun onListenerConnected() {
        ProbeSession.setConnected(ProbeRoute.NOTIFICATION, true)
    }

    override fun onListenerDisconnected() {
        ProbeSession.setConnected(ProbeRoute.NOTIFICATION, false)
    }

    override fun onDestroy() {
        ProbeSession.setConnected(ProbeRoute.NOTIFICATION, false)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isUnlocked()) return
        // Reject unrelated packages before reading any of their extras.
        if (sbn.packageName !in ProbeSourcePolicy.candidatePackages &&
            !(sbn.packageName == "com.android.shell" && ProbeSession.state.shellFixture)
        ) {
            return
        }
        val environment = ProbeEnvironment.read(this)
        val ticket = ProbeSession.ticket(ProbeRoute.NOTIFICATION, environment) ?: return
        val origin = ProbeSourcePolicy.classify(
            sbn.packageName,
            environment.systemPackages,
            ProbeSession.state.shellFixture,
            sbn.tag
        ) ?: return
        try {
            val notification = sbn.notification
            val extras = notification.extras
            val title = BoundedText.read(extras.getCharSequence(Notification.EXTRA_TITLE), 256)
            val expanded = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            val groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
            val channel = ProbeNotificationMetadata.channel(origin, notification.channelId)
            val body = BoundedText.read(
                expanded ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )
            // Do not combine grouped lines into an invented individual alert body.
            val observation = ProbeObservation(
                packageName = sbn.packageName,
                route = ProbeRoute.NOTIFICATION,
                origin = origin,
                title = title.value,
                body = body.value,
                capturedAtMillis = System.currentTimeMillis(),
                postedAtMillis = sbn.postTime,
                limited = title.limited || body.limited,
                groupSummary = groupSummary,
                platformBoundaryPossible =
                    ProbeSourcePolicy.mayMatchNotificationTextBoundary(body.value.length),
                bodyEvidence = ProbeNotificationMetadata.bodyEvidence(
                    origin,
                    expanded != null,
                    groupSummary,
                    channel
                ),
                notificationChannel = channel
            )
            ProbeSession.publish(ticket, observation)?.let { id ->
                ProbeSourceTitleResources.enrich(this, ticket, id, observation)
            }
        } catch (_: RuntimeException) {
            // Malformed Parcelable extras must not crash the listener or leak content to logs.
        }
    }
}

package xyz.gaon.codeclear.probe

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class ProbeNotificationListener : NotificationListenerService() {
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
            val body = BoundedText.read(
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )
            // Do not combine grouped lines into an invented individual alert body.
            ProbeSession.publish(
                ticket,
                ProbeObservation(
                    packageName = sbn.packageName,
                    route = ProbeRoute.NOTIFICATION,
                    origin = origin,
                    title = title.value,
                    body = body.value,
                    capturedAtMillis = System.currentTimeMillis(),
                    postedAtMillis = sbn.postTime,
                    limited = title.limited || body.limited,
                    groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
                )
            )
        } catch (_: RuntimeException) {
            // Malformed Parcelable extras must not crash the listener or leak content to logs.
        }
    }
}

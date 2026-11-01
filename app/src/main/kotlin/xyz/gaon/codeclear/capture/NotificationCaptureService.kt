package xyz.gaon.codeclear.capture

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.FileDescriptor
import java.io.PrintWriter
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

open class NotificationCaptureService : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val snapshots = Executors.newSingleThreadExecutor()
    private val snapshotGate = NotificationSnapshotGate()
    private var connected = false

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        ProbeDiagnostics.write(ProbeSession.state, writer, ProbeSourceActions.state)
    }

    override fun onListenerConnected() {
        snapshotGate.invalidate()
        connected = true
        current = WeakReference(this)
        ProbeSession.setConnected(ProbeRoute.NOTIFICATION, true)
        inspectCurrent()
    }

    override fun onListenerDisconnected() {
        snapshotGate.invalidate()
        connected = false
        ProbeSourceActions.clear()
        ProbeSession.setConnected(ProbeRoute.NOTIFICATION, false)
    }

    override fun onDestroy() {
        snapshotGate.invalidate()
        connected = false
        if (current.get() === this) current.clear()
        snapshots.shutdownNow()
        ProbeSourceActions.clear()
        ProbeSession.setConnected(ProbeRoute.NOTIFICATION, false)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Reject unrelated packages before reading any of their extras.
        if (!isCandidate(sbn)) return
        snapshotGate.observed(sbn.key)
        capture(sbn)
    }

    private fun isCandidate(sbn: StatusBarNotification): Boolean =
        sbn.user == Process.myUserHandle() &&
            (
                sbn.packageName in ProbeSourcePolicy.candidatePackages ||
                    (sbn.packageName == "com.android.shell" && ProbeSession.state.shellFixture)
                )

    private fun capture(sbn: StatusBarNotification) {
        if (!isUnlocked() || !isCandidate(sbn)) return
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
            if (origin == ProbeOrigin.SYSTEM_CANDIDATE &&
                channel != ProbeNotificationChannel.UNKNOWN
            ) {
                ProbeSourceActions.offer(
                    ticket,
                    sbn.key,
                    sbn.packageName,
                    notification.contentIntent
                )
            }
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
                notificationChannel = channel,
                sourceObservationKey = sbn.key.takeIf { it.length <= 384 }?.let {
                    "notification:$it:${sbn.postTime}"
                }
            )
            ProbeSession.publish(ticket, observation)?.let { id ->
                ProbeSourceTitleResources.enrich(this, ticket, id, observation)
            }
        } catch (_: RuntimeException) {
            // Malformed Parcelable extras must not crash the listener or leak content to logs.
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isCandidate(sbn)) snapshotGate.observed(sbn.key)
        ProbeSourceActions.removed(sbn.key)
    }

    private fun inspectCurrent() {
        if (!connected || !isUnlocked()) return
        val ticket =
            ProbeSession.ticket(ProbeRoute.NOTIFICATION, ProbeEnvironment.read(this)) ?: return
        val allowShell = ProbeSession.state.shellFixture
        if (!snapshotGate.begin()) return
        try {
            snapshots.execute {
                val found = try {
                    activeNotifications.orEmpty().asSequence().filter {
                        it.user == Process.myUserHandle() &&
                            (
                                it.packageName in ProbeSourcePolicy.candidatePackages ||
                                    (
                                        allowShell && it.packageName == "com.android.shell" &&
                                            it.tag == "test_alert"
                                        )
                                )
                    }.take(100).toList()
                } catch (_: RuntimeException) {
                    emptyList()
                }
                main.post {
                    val generationMatches = ProbeSession.currentGeneration() == ticket
                    try {
                        if (connected && generationMatches) {
                            found.filter { snapshotGate.accepts(it.key) }.forEach(::capture)
                        }
                    } finally {
                        val refresh = snapshotGate.finish()
                        if (connected && (refresh || !generationMatches)) inspectCurrent()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            snapshotGate.finish()
        }
    }

    companion object {
        private var current = WeakReference<NotificationCaptureService>(null)

        fun inspectConsentedSources() {
            current.get()?.inspectCurrent()
        }
    }
}

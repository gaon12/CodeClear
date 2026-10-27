package xyz.gaon.codeclear.probe

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.FileDescriptor
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

class ProbeAccessibilityService : AccessibilityService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val windows = ProbeWindowTracker()
    private val reads = LatestProbeReadQueue()
    private val pendingRead = Runnable { readPendingWindow() }
    private var nextReadAt = 0L
    private var destroyed = false
    private val observationSession = UUID.randomUUID().toString()
    private var visibleSession = 0L
    private var bodyRevision = 0L

    @Volatile private var lastReadOutcome = "NONE"

    @Volatile private var expectedWindowId = -1

    @Volatile private var rootWindowId = -1
    private var readAttempts = 0
    private data class BodyStamp(val digest: List<Byte>, val limited: Boolean, val queueSize: Int?)
    private var lastBody: BodyStamp? = null

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        ProbeDiagnostics.write(ProbeSession.state, writer, ProbeSourceActions.state)
        // Report only read state and platform IDs, never window text or content fingerprints.
        writer.println(
            "dialogReadAttempts=$readAttempts dialogReadOutcome=$lastReadOutcome " +
                "expectedWindowId=$expectedWindowId rootWindowId=$rootWindowId"
        )
    }

    override fun onServiceConnected() {
        ProbeSession.setConnected(ProbeRoute.ACCESSIBILITY, true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (destroyed || !isUnlocked() || !ProbeSession.state.accessibilityConsent) return
        val eventPackage = event.packageName ?: return
        if (eventPackage.length > 256) return
        val packageName = eventPackage.toString()
        if (packageName !in ProbeSourcePolicy.candidatePackages) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                visibleSession++
                lastBody = null
                val environment = ProbeEnvironment.read(this)
                val ticket = ProbeSession.ticket(ProbeRoute.ACCESSIBILITY, environment) ?: return
                if (packageName !in environment.systemPackages) return
                val windowClass = BoundedText.read(event.className, 256).value
                ProbeSession.noteAccessibilityWindow(windowClass)
                val window = windows.windowChanged(packageName, windowClass, event.windowId)
                if (window == null) {
                    reads.discardPending()
                    main.removeCallbacks(pendingRead)
                    return
                }
                reads.offer(ProbeReadRequest(window, ticket))
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val window = windows.contentChanged(packageName, event.windowId) ?: return
                reads.offer(ProbeReadRequest(window, ProbeSession.currentGeneration()))
            }

            else -> return
        }
        schedulePendingRead()
    }

    private fun schedulePendingRead() {
        if (destroyed || reads.busy || !reads.hasPending) return
        main.removeCallbacks(pendingRead)
        main.postDelayed(pendingRead, (nextReadAt - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    private fun readPendingWindow() {
        if (destroyed || !isUnlocked()) return
        val request = reads.takeIfIdle() ?: return
        val environment = ProbeEnvironment.read(this)
        val ticket = ProbeSession.ticket(ProbeRoute.ACCESSIBILITY, environment)
        if (ticket != request.consentGeneration || windows.current != request.window ||
            request.window.packageName !in environment.systemPackages
        ) {
            reads.complete()
            return
        }
        nextReadAt = SystemClock.uptimeMillis() + 500
        readAttempts++
        expectedWindowId = request.window.windowId
        rootWindowId = -1
        lastReadOutcome = "PENDING"
        worker.execute {
            var observation: ProbeObservation? = null
            try {
                observation = readAlertBody(request.window)
            } catch (_: RuntimeException) {
                lastReadOutcome = "UNAVAILABLE"
                // A stale or protected window is unavailable; never reconstruct its text.
            } finally {
                val result = observation
                main.post {
                    reads.complete()
                    if (destroyed) return@post
                    if (isUnlocked() && windows.current == request.window) {
                        val current = ProbeEnvironment.read(this)
                        if (ProbeSession.ticket(ProbeRoute.ACCESSIBILITY, current) ==
                            request.consentGeneration
                        ) {
                            if (result == null || result.body.isBlank()) {
                                // WINDOW_STATE_CHANGED can precede attachment of the active tree.
                                // Retry only this verified window, under the existing 2 Hz limit.
                                reads.retryUnavailable(request)
                            } else {
                                val stamp = bodyStamp(result)
                                if (lastBody != stamp) bodyRevision++
                                lastBody = stamp
                                val identified = result.copy(
                                    sourceObservationKey =
                                        "dialog:$observationSession:$visibleSession:$bodyRevision"
                                )
                                ProbeSession.publish(
                                    request.consentGeneration,
                                    identified
                                )?.let { id ->
                                    ProbeSourceTitleResources.enrich(
                                        this,
                                        request.consentGeneration,
                                        id,
                                        result
                                    )
                                }
                            }
                        }
                    }
                    schedulePendingRead()
                }
            }
        }
    }

    private fun bodyStamp(observation: ProbeObservation): BodyStamp {
        // Preserve a stable observation key without keeping another raw body after history deletion.
        val title = observation.title.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(4).putInt(title.size).array())
        digest.update(title)
        digest.update(observation.body.toByteArray(Charsets.UTF_8))
        return BodyStamp(
            digest.digest().toList(),
            observation.limited,
            observation.reportedDialogQueueSize
        )
    }

    private fun readAlertBody(window: ProbeAlertWindow): ProbeObservation? {
        val startedAt = SystemClock.elapsedRealtime()
        val root = rootForAlert(window) ?: run {
            lastReadOutcome = "NO_EVENT_ROOT"
            return null
        }
        try {
            rootWindowId = root.windowId
            if (root.packageName?.toString() != window.packageName ||
                root.windowId != window.windowId
            ) {
                lastReadOutcome = "DIFFERENT_ROOT"
                return null
            }
            // Query the declared dialog body directly instead of walking background list rows.
            val fields = root.findAccessibilityNodeInfosByViewId("${window.packageName}:id/message")
            try {
                val field = fields.singleOrNull()
                val body = if (field != null && field.isVisibleToUser &&
                    field.packageName?.toString() == window.packageName &&
                    field.windowId == window.windowId
                ) {
                    BoundedText.read(field.text)
                } else {
                    BoundedText("", fields.size > 1)
                }
                val title = readAuxiliaryField(root, window, "alertTitle")
                val dismiss = readAuxiliaryField(root, window, "dismissButton")
                lastReadOutcome = if (body.value.isBlank()) "EMPTY_FIELD" else "BODY_AVAILABLE"
                return ProbeObservation(
                    packageName = window.packageName,
                    route = ProbeRoute.ACCESSIBILITY,
                    origin = ProbeOrigin.SYSTEM_CANDIDATE,
                    title = title,
                    body = body.value,
                    capturedAtMillis = System.currentTimeMillis(),
                    limited = body.limited,
                    windowClass = ProbeWindowTracker.ALERT_CLASS,
                    readDurationMillis = SystemClock.elapsedRealtime() - startedAt,
                    bodyEvidence = ProbeBodyEvidence.VISIBLE_DIALOG_FIELD,
                    reportedDialogQueueSize = ProbeDialogQueue.reportedMultipleCount(dismiss)
                )
            } finally {
                fields.forEach(::releaseNode)
            }
        } finally {
            releaseNode(root)
        }
    }

    @Suppress("DEPRECATION")
    private fun rootForAlert(window: ProbeAlertWindow): AccessibilityNodeInfo? {
        // An emergency dialog can be visible while another window retains input focus.
        // Inspect only window IDs; retrieve content solely for the verified event's window.
        val displays = windowsOnAllDisplays
        try {
            var inspected = 0
            for (display in 0 until minOf(displays.size(), 8)) {
                for (candidate in displays.valueAt(display)) {
                    if (++inspected > 64) return rootInActiveWindow
                    if (candidate.id == window.windowId) return candidate.root
                }
            }
            // Some receivers expose only the active window; the caller still checks its exact ID.
            return rootInActiveWindow
        } finally {
            for (display in 0 until displays.size()) {
                displays.valueAt(display).forEach { it.recycle() }
            }
        }
    }

    private fun readAuxiliaryField(
        root: AccessibilityNodeInfo,
        window: ProbeAlertWindow,
        resourceName: String
    ): String = try {
        val fields = root.findAccessibilityNodeInfosByViewId(
            "${window.packageName}:id/$resourceName"
        )
        try {
            val field = fields.singleOrNull()
            if (field != null && field.isVisibleToUser &&
                field.packageName?.toString() == window.packageName &&
                field.windowId == window.windowId
            ) {
                val text = BoundedText.read(field.text, 256)
                if (text.limited) "" else text.value
            } else {
                ""
            }
        } finally {
            fields.forEach(::releaseNode)
        }
    } catch (_: RuntimeException) {
        // Optional metadata must not discard an already readable original body.
        ""
    }

    override fun onInterrupt() {
        reads.discardPending()
        windows.clear()
        ProbeSession.clear()
    }

    override fun onDestroy() {
        destroyed = true
        reads.discardPending()
        windows.clear()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        ProbeSession.setConnected(ProbeRoute.ACCESSIBILITY, false)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun releaseNode(node: AccessibilityNodeInfo) {
        // Recycling is required on the Android 11/12 part of the supported range.
        node.recycle()
    }
}

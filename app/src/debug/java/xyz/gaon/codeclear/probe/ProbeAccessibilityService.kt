package xyz.gaon.codeclear.probe

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.Executors

class ProbeAccessibilityService : AccessibilityService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val windows = ProbeWindowTracker()
    private val reads = LatestProbeReadQueue()
    private val pendingRead = Runnable { readPendingWindow() }
    private var nextReadAt = 0L
    private var destroyed = false

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        ProbeDiagnostics.write(ProbeSession.state, writer)
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
        worker.execute {
            var observation: ProbeObservation? = null
            try {
                observation = readAlertBody(request.window)
            } catch (_: RuntimeException) {
                // A stale or protected window is unavailable; never reconstruct its text.
            } finally {
                val result = observation
                main.post {
                    reads.complete()
                    if (destroyed) return@post
                    if (result != null && isUnlocked() && windows.current == request.window) {
                        val current = ProbeEnvironment.read(this)
                        if (ProbeSession.ticket(ProbeRoute.ACCESSIBILITY, current) ==
                            request.consentGeneration
                        ) {
                            ProbeSession.publish(request.consentGeneration, result)
                        }
                    }
                    schedulePendingRead()
                }
            }
        }
    }

    private fun readAlertBody(window: ProbeAlertWindow): ProbeObservation? {
        val startedAt = SystemClock.elapsedRealtime()
        val root = rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != window.packageName ||
                root.windowId != window.windowId
            ) {
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
                return ProbeObservation(
                    packageName = window.packageName,
                    route = ProbeRoute.ACCESSIBILITY,
                    origin = ProbeOrigin.SYSTEM_CANDIDATE,
                    title = "",
                    body = body.value,
                    capturedAtMillis = System.currentTimeMillis(),
                    limited = body.limited,
                    windowClass = ProbeWindowTracker.ALERT_CLASS,
                    readDurationMillis = SystemClock.elapsedRealtime() - startedAt,
                    bodyEvidence = ProbeBodyEvidence.VISIBLE_DIALOG_FIELD
                )
            } finally {
                fields.forEach(::releaseNode)
            }
        } finally {
            releaseNode(root)
        }
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

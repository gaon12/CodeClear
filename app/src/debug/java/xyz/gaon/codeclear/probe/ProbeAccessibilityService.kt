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
import java.util.concurrent.atomic.AtomicBoolean

class ProbeAccessibilityService : AccessibilityService() {
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        ProbeDiagnostics.write(ProbeSession.state, writer)
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val reading = AtomicBoolean(false)
    private var lastReadAt = -500L
    private var destroyed = false

    override fun onServiceConnected() {
        ProbeSession.setConnected(ProbeRoute.ACCESSIBILITY, true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (destroyed || !isUnlocked()) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        val eventPackage = event.packageName ?: return
        if (eventPackage.length > 256) return
        val packageName = eventPackage.toString()
        if (packageName !in ProbeSourcePolicy.candidatePackages) return
        val now = SystemClock.uptimeMillis()
        if (now - lastReadAt < 500 || reading.get()) return
        val environment = ProbeEnvironment.read(this)
        val ticket = ProbeSession.ticket(ProbeRoute.ACCESSIBILITY, environment) ?: return
        if (packageName !in environment.systemPackages) return
        if (!reading.compareAndSet(false, true)) return
        lastReadAt = now
        val windowId = event.windowId
        worker.execute {
            try {
                val root = rootInActiveWindow ?: return@execute
                if (root.packageName?.toString() != packageName || root.windowId != windowId) {
                    releaseNode(root)
                    return@execute
                }
                val body = readBody(root, "$packageName:id/message")
                val observation = ProbeObservation(
                    packageName = packageName,
                    route = ProbeRoute.ACCESSIBILITY,
                    origin = ProbeOrigin.SYSTEM_CANDIDATE,
                    title = "",
                    body = body.value,
                    capturedAtMillis = System.currentTimeMillis(),
                    limited = body.limited
                )
                main.post {
                    if (!destroyed && isUnlocked()) {
                        val currentEnvironment = ProbeEnvironment.read(this)
                        if (ProbeSession.ticket(ProbeRoute.ACCESSIBILITY, currentEnvironment) ==
                            ticket
                        ) {
                            ProbeSession.publish(ticket, observation)
                        }
                    }
                }
            } catch (_: RuntimeException) {
                // Stale/protected windows are unavailable, never a reason to bypass protection.
            } finally {
                reading.set(false)
            }
        }
    }

    private fun readBody(root: AccessibilityNodeInfo, messageId: String): BoundedText {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var acquired = 1
        var body: BoundedText? = null
        var limited = false
        try {
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                try {
                    if (node.viewIdResourceName == messageId) {
                        val field = BoundedText.read(node.text)
                        if (body == null) body = field else limited = true
                    }
                    val childLimit = minOf(node.childCount, 64 - acquired)
                    if (childLimit < node.childCount) limited = true
                    repeat(childLimit) { index ->
                        node.getChild(index)?.let {
                            pending.addLast(it)
                            acquired++
                        }
                    }
                } finally {
                    releaseNode(node)
                }
            }
        } finally {
            pending.forEach(::releaseNode)
        }
        // An empty/hidden body remains empty. Other screen labels are never used as a substitute.
        return BoundedText(body?.value.orEmpty(), limited || body?.limited == true)
    }

    override fun onInterrupt() {
        ProbeSession.clear()
    }

    override fun onDestroy() {
        destroyed = true
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

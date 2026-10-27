package xyz.gaon.codeclear.probe

data class ProbeAlertWindow(val packageName: String, val windowId: Int, val revision: Long)

class ProbeWindowTracker {
    private var revision = 0L
    var current: ProbeAlertWindow? = null
        private set

    fun windowChanged(packageName: String, className: String, windowId: Int): ProbeAlertWindow? {
        revision++
        current =
            if (packageName in ProbeSourcePolicy.candidatePackages && className == ALERT_CLASS) {
                ProbeAlertWindow(packageName, windowId, revision)
            } else {
                null
            }
        return current
    }

    fun contentChanged(packageName: String, windowId: Int): ProbeAlertWindow? {
        val window = current ?: return null
        if (window.packageName != packageName || window.windowId != windowId) return null
        current = window.copy(revision = ++revision)
        return current
    }

    fun clear() {
        revision++
        current = null
    }

    companion object {
        const val ALERT_CLASS = "com.android.cellbroadcastreceiver.CellBroadcastAlertDialog"
    }
}

data class ProbeReadRequest(
    val window: ProbeAlertWindow,
    val consentGeneration: Long,
    val attempt: Int = 0
)

/** One active read and one replacement request; a busy worker never drops the latest window. */
class LatestProbeReadQueue {
    private var pending: ProbeReadRequest? = null
    var busy = false
        private set
    val hasPending: Boolean get() = pending != null

    fun offer(request: ProbeReadRequest) {
        pending = request
    }

    /** A not-yet-attached tree gets three retries; newer window events always take precedence. */
    fun retryUnavailable(request: ProbeReadRequest) {
        if (pending == null &&
            request.attempt < 3
        ) {
            pending = request.copy(attempt = request.attempt + 1)
        }
    }

    fun takeIfIdle(): ProbeReadRequest? {
        if (busy) return null
        val request = pending ?: return null
        pending = null
        busy = true
        return request
    }

    fun complete() {
        busy = false
    }

    fun discardPending() {
        pending = null
    }
}

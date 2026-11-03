package xyz.gaon.codeclear.nearby

/** Keep the registration callback alive until Android can remove the newly added service. */
internal class DeferredGattClose(private val release: () -> Unit) {
    private var registrationPending = false
    private var closing = false
    var released = false
        private set

    fun registrationStarted() {
        check(!registrationPending && !closing && !released)
        registrationPending = true
    }

    fun registrationFinished() {
        registrationPending = false
        if (closing) releaseOnce()
    }

    fun close() {
        closing = true
        if (!registrationPending) releaseOnce()
    }

    /** A missing platform callback must not keep a worker and server alive indefinitely. */
    fun expire() {
        if (closing) releaseOnce()
    }

    private fun releaseOnce() {
        if (released) return
        released = true
        release()
    }
}

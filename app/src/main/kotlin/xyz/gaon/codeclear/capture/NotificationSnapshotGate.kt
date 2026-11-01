package xyz.gaon.codeclear.capture

/** Main-thread-owned invalidation for one active notification snapshot. */
internal class NotificationSnapshotGate(private val maximumChangedKeys: Int = 128) {
    init {
        require(maximumChangedKeys in 1..128)
    }

    private var active = false
    private var invalidated = false
    private val changedKeys = mutableSetOf<String>()

    fun begin(): Boolean {
        if (active) return false
        active = true
        return true
    }

    fun observed(key: String) {
        if (!active || invalidated) return
        if (key.length > 384 || (key !in changedKeys && changedKeys.size == maximumChangedKeys)) {
            invalidate()
        } else {
            changedKeys += key
        }
    }

    fun accepts(key: String): Boolean = active && !invalidated && key !in changedKeys

    fun invalidate() {
        if (!active) return
        invalidated = true
        changedKeys.clear()
    }

    /** Returns whether a fresh snapshot is needed after the current worker finishes. */
    fun finish(): Boolean {
        check(active)
        val refresh = invalidated
        active = false
        invalidated = false
        changedKeys.clear()
        return refresh
    }
}

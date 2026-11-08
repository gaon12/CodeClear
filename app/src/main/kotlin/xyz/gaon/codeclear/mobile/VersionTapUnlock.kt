package xyz.gaon.codeclear.mobile

/** Reveal only after a short deliberate sequence; reopening About starts a fresh sequence. */
internal class VersionTapUnlock {
    private var previous = Long.MIN_VALUE
    private var first = Long.MIN_VALUE
    private var count = 0

    fun tap(elapsed: Long): Boolean {
        if (previous == Long.MIN_VALUE || elapsed < previous || elapsed - previous > 1000 ||
            elapsed - first > 3000
        ) {
            count = 0
            first = elapsed
        }
        previous = elapsed
        count++
        if (count < 7) return false
        count = 0
        first = elapsed
        return true
    }
}

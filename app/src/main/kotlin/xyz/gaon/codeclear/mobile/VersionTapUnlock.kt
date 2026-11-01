package xyz.gaon.codeclear.mobile

/** Separate deliberate repeated taps from occasional visits to the version row. */
internal class VersionTapUnlock {
    private var previous = Long.MIN_VALUE
    private var count = 0

    fun tap(elapsed: Long): Boolean {
        if (elapsed < previous || previous == Long.MIN_VALUE || elapsed - previous > 4000) count = 0
        previous = elapsed
        count++
        if (count < 7) return false
        count = 0
        return true
    }
}

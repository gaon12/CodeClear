package xyz.gaon.codeclear.nearby

/** Keep foreground recreation from rapidly registering another process-owned BLE scanner. */
class ScanStartBudget {
    private val starts = ArrayDeque<Long>()

    @Synchronized
    fun reserveDelay(elapsed: Long): Long {
        while (starts.firstOrNull()?.let { it <= elapsed - 31000 } == true) starts.removeFirst()
        if (starts.size >= 4) return (starts.first() + 31000 - elapsed).coerceAtLeast(1000)
        starts.addLast(elapsed)
        return 0
    }
}

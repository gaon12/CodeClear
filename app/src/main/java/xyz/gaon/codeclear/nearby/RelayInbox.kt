package xyz.gaon.codeclear.nearby

/** Bounded recent receipts; message retention is independent of the public propagation lifetime. */
class RelayInbox {
    private val entries = linkedMapOf<String, NearbyOriginal>()
    private val seen = linkedMapOf<String, Long>()
    private val accepted = ArrayDeque<Long>()

    @Synchronized
    fun prune(elapsed: Long) {
        seen.entries.removeAll { it.value <= elapsed }
        while (accepted.firstOrNull()?.let { it <= elapsed - 60000 } == true) accepted.removeFirst()
    }

    @Synchronized
    fun contains(root: String, elapsed: Long): Boolean {
        prune(elapsed)
        return root in entries || root in seen
    }

    @Synchronized
    fun accept(original: NearbyOriginal, now: Long, elapsed: Long): Boolean {
        prune(elapsed)
        if (original.root in seen || accepted.size >= 8 || entries.size >= 32) return false
        val remaining = (original.capturedAt + RelayProtocol.LIFETIME - now)
            .coerceIn(0, RelayProtocol.LIFETIME)
        if (remaining == 0L) return false
        entries[original.root] = original
        seen[original.root] = elapsed + RelayProtocol.LIFETIME
        if (seen.size > 512) seen.remove(seen.keys.first())
        accepted.addLast(elapsed)
        return true
    }

    @Synchronized
    fun originals(elapsed: Long): List<NearbyOriginal> {
        prune(elapsed)
        return entries.values.toList().asReversed()
    }

    @Synchronized
    fun remove(root: String) {
        entries.remove(root)
    }

    @Synchronized
    fun clear() {
        entries.clear()
        seen.clear()
        accepted.clear()
    }
}

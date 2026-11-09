package xyz.gaon.codeclear.nearby

import xyz.gaon.codeclear.sha256Hex

/** Bounded recent receipts; message retention is independent of the public propagation lifetime. */
class RelayInbox {
    private val entries = linkedMapOf<String, NearbyOriginal>()
    private val seen = linkedMapOf<String, Long>()
    private val contentSeen = linkedMapOf<String, Long>()
    private val accepted = ArrayDeque<Long>()

    @Synchronized
    fun prune(elapsed: Long) {
        seen.entries.removeAll { it.value <= elapsed }
        contentSeen.entries.removeAll { it.value <= elapsed }
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
        if (!original.root.matches(Regex("[a-f0-9]{64}")) || original.title.length > 256 ||
            original.body.isBlank() || original.body.length > 8192 || original.capturedAt <= 0 ||
            original.capturedAt > now + 300000 ||
            original.capturedAt <= now - RelayProtocol.LIFETIME
        ) {
            return false
        }
        // Untrusted senders can replace the nonce and claimed time without changing the message.
        val content = sha256Hex(original.body)
        if (content in contentSeen) return false
        val remaining = (original.capturedAt + RelayProtocol.LIFETIME - now)
            .coerceIn(0, RelayProtocol.LIFETIME)
        if (remaining == 0L) return false
        entries[original.root] = original
        seen[original.root] = elapsed + RelayProtocol.LIFETIME
        contentSeen[content] = elapsed + RelayProtocol.LIFETIME
        if (seen.size > 512) seen.remove(seen.keys.first())
        if (contentSeen.size > 512) contentSeen.remove(contentSeen.keys.first())
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
        contentSeen.clear()
        accepted.clear()
    }
}

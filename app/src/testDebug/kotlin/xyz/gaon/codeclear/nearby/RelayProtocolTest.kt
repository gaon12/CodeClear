package xyz.gaon.codeclear.nearby

import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProtocolTest {
    private val now = 1800000000000L
    private fun packet(body: String = "[DRILL] 원문  두 칸\n👩‍🚒 é") =
        RelayProtocol.encode("[DRILL] Test", body, now)

    @Test
    fun preservesExactUnicodeAndDistinguishesRepeatedOccurrences() {
        val first = packet()
        assertEquals("[DRILL] 원문  두 칸\n👩‍🚒 é", RelayProtocol.decode(first, now)!!.body)
        assertEquals(RelayProtocol.root(first), RelayProtocol.decode(first, now)!!.root)
        val occurrence = RelayOccurrence()
        val stable = RelayProtocol.encode("[DRILL] Stable event", "Exact original", now, occurrence)
        assertEquals(
            RelayProtocol.root(stable),
            RelayProtocol.root(
                RelayProtocol.encode("[DRILL] Stable event", "Exact original", now, occurrence)
            )
        )
        assertNotEquals(RelayProtocol.root(first), RelayProtocol.root(packet()))
    }

    @Test
    fun rejectsCorruptionTruncationOversizeAndStaleOrFutureClaims() {
        val bytes = packet()
        assertNull(RelayProtocol.decode(bytes.copyOf(bytes.size - 1), now))
        assertNull(RelayProtocol.decode(bytes.copyOf().apply { this[32] = 0 }, now))
        assertNull(RelayProtocol.decode(ByteArray(32769), now))
        assertNull(RelayProtocol.decode(bytes, now + RelayProtocol.LIFETIME + 1))
        assertNull(RelayProtocol.decode(bytes, now - 300001))
        assertNull(RelayProtocol.decode(resign(bytes.copyOf().apply { this[0] = 2 }), now))
    }

    @Test
    fun rejectsInvalidUtf8AndHostileLengthsEvenWithAConsistentDigest() {
        val bytes = packet("hello")
        assertNull(
            RelayProtocol.decode(
                resign(
                    bytes.copyOf().apply {
                        ByteBuffer.wrap(this).putInt(27, Int.MAX_VALUE)
                    }
                ),
                now
            )
        )
        assertNull(
            RelayProtocol.decode(
                resign(
                    bytes.copyOf().apply {
                        this[size - 33] = 0xFF.toByte()
                    }
                ),
                now
            )
        )
        assertThrows(IllegalArgumentException::class.java) { packet("x".repeat(8193)) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { packet("\uD800") }
        assertNotNull(RelayProtocol.decode(packet("字".repeat(8192)), now))
    }

    @Test
    fun inboxBoundsRatesAndDismissedDuplicatesWithoutExpiringReceivedContent() {
        val inbox = RelayInbox()
        val first = RelayProtocol.decode(packet(), now)!!
        assertTrue(inbox.accept(first, now, 1))
        inbox.remove(first.root)
        assertFalse(inbox.accept(first, now, 2))
        repeat(7) {
            assertTrue(
                inbox.accept(RelayProtocol.decode(packet("[DRILL] Batch $it"), now)!!, now, 3)
            )
        }
        assertFalse(inbox.accept(RelayProtocol.decode(packet(), now)!!, now, 4))
        repeat(3) { minute ->
            repeat(8) { index ->
                assertTrue(
                    inbox.accept(
                        RelayProtocol.decode(packet("[DRILL] Batch $minute/$index"), now)!!,
                        now,
                        (minute + 1) * 60010L
                    )
                )
            }
        }
        assertEquals(31, inbox.originals(190000).size)
        assertTrue(inbox.accept(RelayProtocol.decode(packet("[DRILL] Last"), now)!!, now, 240040))
        assertFalse(inbox.accept(RelayProtocol.decode(packet(), now)!!, now, 240041))
        assertEquals(32, inbox.originals(RelayProtocol.LIFETIME + 240041).size)
        inbox.clear()
        assertFalse(inbox.contains(first.root, RelayProtocol.LIFETIME + 240042))
    }

    @Test
    fun minimumMtuTransferPreservesTheWholeOriginalAndRejectsOverlap() {
        val bytes = packet("[DRILL] " + "字👩‍🚒".repeat(200))
        val entry = RelayEntry(RelayProtocol.root(bytes), bytes.size)
        val inventory = RelayFrames.inventory(RelayFrames.inventory(listOf(entry)))
        assertEquals(listOf(entry), inventory)
        val assembly = RelayAssembly(entry, 0)
        val first = RelayFrames.block(
            RelayFrames.request(RelayFrames.request(0, 0, 248)),
            bytes,
            23
        )
        assembly.append(first)
        assertThrows(IllegalArgumentException::class.java) { assembly.append(first) }
        assertThrows(IllegalArgumentException::class.java) { assembly.finish(now) }
        while (!assembly.complete) {
            val request = RelayFrames.request(RelayFrames.request(0, assembly.offset, 248))
            val block = RelayFrames.block(request, bytes, 23)
            assertTrue(block.size <= 20)
            assembly.append(block)
        }
        assertEquals(RelayProtocol.decode(bytes, now), assembly.finish(now))
        assertThrows(IllegalArgumentException::class.java) {
            RelayFrames.inventory(RelayFrames.inventory(listOf(entry, entry)))
        }
        assertThrows(IllegalArgumentException::class.java) { RelayFrames.request(ByteArray(8)) }
    }

    @Test
    fun concurrentDuplicateReceiptsAreAcceptedOnlyOnce() {
        val inbox = RelayInbox()
        val original = RelayProtocol.decode(packet(), now)!!
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val work = List(24) {
                pool.submit<Boolean> { inbox.accept(original, now, 1) }
            }
            assertEquals(1, work.count { it.get(5, java.util.concurrent.TimeUnit.SECONDS) })
            assertEquals(1, inbox.originals(2).size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun scannerRecreationWaitsForTheSharedStartBudgetInsteadOfSpinning() {
        val budget = ScanStartBudget()
        repeat(4) { assertEquals(0L, budget.reserveDelay(it.toLong())) }
        assertEquals(30996L, budget.reserveDelay(4))
        assertEquals(1000L, budget.reserveDelay(30999))
        assertEquals(0L, budget.reserveDelay(31000))
        assertTrue(budget.reserveDelay(31000) > 0)
        assertEquals(0L, budget.reserveDelay(62000))
    }

    @Test
    fun changedNonceTitleAndTimeCannotReplayTheSameBodyAfterDismissal() {
        val inbox = RelayInbox()
        val first = RelayProtocol.decode(packet(), now)!!
        assertTrue(inbox.accept(first, now, 1))
        inbox.remove(first.root)
        val replay = RelayProtocol.decode(
            RelayProtocol.encode("Forged title", first.body, now + 1),
            now
        )!!
        assertNotEquals(first.root, replay.root)
        assertFalse(inbox.accept(replay, now, 2))
        assertTrue(inbox.originals(2).isEmpty())
        val later = replay.copy(capturedAt = now + RelayProtocol.LIFETIME)
        assertTrue(inbox.accept(later, now + RelayProtocol.LIFETIME, RelayProtocol.LIFETIME + 2))
    }

    @Test
    fun directReceiptBoundaryRejectsMalformedAndImpossibleTimeClaims() {
        val first = RelayProtocol.decode(packet(), now)!!
        for (invalid in listOf(
            first.copy(root = "invalid"),
            first.copy(body = " "),
            first.copy(title = "x".repeat(257)),
            first.copy(body = "x".repeat(8193)),
            first.copy(capturedAt = 0),
            first.copy(capturedAt = Long.MAX_VALUE),
            first.copy(capturedAt = now - RelayProtocol.LIFETIME)
        )) {
            assertFalse(RelayInbox().accept(invalid, now, 1))
        }
    }

    private fun resign(bytes: ByteArray): ByteArray {
        val payload = bytes.copyOf(bytes.size - 32)
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }
}

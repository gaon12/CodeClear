package xyz.gaon.codeclear.translation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import xyz.gaon.codeclear.probe.LocalEngineKind
import xyz.gaon.codeclear.probe.LocalInput
import xyz.gaon.codeclear.probe.LocalResult
import xyz.gaon.codeclear.probe.LocalTarget

class MyMemoryOperationTest {
    private class Storage : RemoteBudgetStorage {
        var value: RemoteBudgetSnapshot? = null
        var writable = true
        override fun read() = value
        override fun write(value: RemoteBudgetSnapshot): Boolean {
            if (writable) this.value = value
            return writable
        }
    }

    @Test fun segmentsPreserveUnicodeParagraphsAndLiteralSeparatorsWithinByteLimits() {
        val body = "  " + "경보 🚨 ".repeat(180) + "\r\n\nSecond paragraph.\u2029Final paragraph.  "
        val plan = MyMemoryPlan.segments(body)
        assertEquals(body, plan.joinToString("") { it.text })
        assertTrue(plan.count { it.translate } > 2)
        for (segment in plan.filter { it.translate }) {
            assertTrue(segment.text.toByteArray(Charsets.UTF_8).size <= 500)
            assertTrue(Charsets.UTF_8.newEncoder().canEncode(segment.text))
            assertFalse(segment.text.any { isRemoteParagraphBoundary(it) })
        }
    }

    @Test fun malformedOversizedAndExcessiveParagraphsAreRejectedWithoutTruncation() {
        for (body in listOf("x".repeat(5001), "\uD800", "a\n".repeat(200))) {
            try {
                MyMemoryPlan.segments(body)
                fail("Expected bounded input rejection")
            } catch (
                _: IllegalArgumentException
            ) { }
        }
    }

    @Test fun budgetReservesConcurrentAttemptsAndNeverResetsOnClockRollback() {
        val storage = Storage()
        var day = 10L
        val budget = RemoteDailyBudget(storage) { day }
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        val accepted = AtomicInteger()
        val executor = Executors.newFixedThreadPool(4)
        repeat(16) {
            executor.execute {
                start.await()
                if (budget.reserve(500)) accepted.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals(10, accepted.get())
        day = 9
        assertFalse(budget.reserve(1))
        day = 11
        assertTrue(budget.reserve(1))
        assertEquals(RemoteBudgetSnapshot(11, 1), storage.value)
        budget.exhaust()
        assertFalse(budget.reserve(1))
    }

    @Test fun noPermissionSameLanguageAndFailedBudgetWritesNeverUpload() {
        val storage = Storage()
        val budget = RemoteDailyBudget(storage) { 1 }
        val calls = AtomicInteger()
        var allowed = false
        val operation =
            MyMemoryOperation(
                MyMemoryTransport { _, _, _, _ ->
                    calls.incrementAndGet()
                    MyMemoryReply.Text("result")
                },
                budget,
                { "ko" },
                { text, _ -> text },
                { allowed },
                { 0 }
            )
        assertEquals(
            LocalResult.Unavailable,
            operation.run(input("Synthetic"), RemoteCancellation())
        )
        allowed = true
        assertEquals(
            LocalResult.Ready("Synthetic", "ko", true),
            operation.run(input("Synthetic", LocalTarget.KOREAN), RemoteCancellation())
        )
        storage.writable = false
        assertEquals(LocalResult.Failed, operation.run(input("Synthetic"), RemoteCancellation()))
        assertEquals(0, calls.get())
        assertNull(storage.value)
    }

    @Test fun withdrawalAfterTheFirstSegmentStopsUploadsAndDiscardsPartialOutput() {
        var allowed = true
        var calls = 0
        val storage = Storage()
        val operation =
            MyMemoryOperation(
                MyMemoryTransport { _, _, _, _ ->
                    calls++
                    allowed = false
                    MyMemoryReply.Text("partial")
                },
                RemoteDailyBudget(storage) { 1 },
                { "ko" },
                { text, _ -> text },
                { allowed },
                { 0 }
            )
        assertEquals(
            LocalResult.Failed,
            operation.run(input("x".repeat(800)), RemoteCancellation())
        )
        assertEquals(1, calls)
        assertEquals(800, storage.value!!.used)
    }

    @Test fun providerQuotaAndOutputBoundsStopWithoutAutomaticRetries() {
        val storage = Storage()
        var reply: MyMemoryReply = MyMemoryReply.Quota
        var calls = 0
        val operation =
            MyMemoryOperation(
                MyMemoryTransport { _, _, _, _ ->
                    calls++
                    reply
                },
                RemoteDailyBudget(storage) { 1 },
                { "ko" },
                { text, _ -> text },
                { true },
                { 0 }
            )
        assertEquals(LocalResult.Quota, operation.run(input("Synthetic"), RemoteCancellation()))
        assertEquals(5000, storage.value!!.used)
        assertEquals(LocalResult.Quota, operation.run(input("Synthetic"), RemoteCancellation()))
        assertEquals(1, calls)
        storage.value = null
        reply = MyMemoryReply.Text("x".repeat(16385))
        assertEquals(LocalResult.Failed, operation.run(input("Synthetic"), RemoteCancellation()))
        assertEquals(2, calls)
    }

    @Test fun cancellationBeforeCallInstallationPreventsStartAndDeadlineStopsLaterSegments() {
        val cancellation = RemoteCancellation()
        var stops = 0
        cancellation.cancel()
        assertFalse(cancellation.install { stops++ })
        assertEquals(1, stops)
        var now = 0L
        var calls = 0
        val operation =
            MyMemoryOperation(
                MyMemoryTransport { _, _, _, _ ->
                    calls++
                    now = 46000
                    MyMemoryReply.Text("partial")
                },
                RemoteDailyBudget(Storage()) { 1 },
                { "ko" },
                { text, _ -> text },
                { true },
                { now }
            )
        assertEquals(
            LocalResult.Failed,
            operation.run(input("x".repeat(800)), RemoteCancellation())
        )
        assertEquals(1, calls)
    }

    private fun input(body: String, target: LocalTarget = LocalTarget.ENGLISH) =
        LocalInput(1, body, target, LocalEngineKind.MY_MEMORY)
}

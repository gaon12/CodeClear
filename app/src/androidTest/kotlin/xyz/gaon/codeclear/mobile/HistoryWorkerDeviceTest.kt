package xyz.gaon.codeclear.mobile

import android.os.Looper
import android.os.OperationCanceledException
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HistoryWorkerDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var worker: HistoryWorker
    private val releases = AtomicInteger()
    private val failures = AtomicInteger()
    private val unblock = CountDownLatch(1)
    private var onOwnerFailure: () -> Unit = {}

    @Before
    fun createIsolatedWorker() {
        onMain {
            worker = HistoryWorker({ releases.incrementAndGet() }, {
                failures.incrementAndGet()
                onOwnerFailure()
            })
        }
    }

    @After
    fun releaseRunningWorkAndClose() {
        unblock.countDown()
        onMain { worker.close() }
        assertTrue(worker.awaitClosed(5000))
        assertEquals(1, releases.get())
    }

    @Test
    fun closingDropsQueuedWorkAndLateSuccessBeforeReleasingResources() {
        val entered = CountDownLatch(1)
        val queuedRan = AtomicBoolean()
        val callbacks = AtomicInteger()
        onMain {
            worker.submit({
                entered.countDown()
                check(unblock.await(5, TimeUnit.SECONDS))
            }) { callbacks.incrementAndGet() }
            worker.submit({ queuedRan.set(true) }) { callbacks.incrementAndGet() }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        onMain { worker.close() }
        assertEquals(0, releases.get())
        unblock.countDown()
        assertTrue(worker.awaitClosed(5000))
        instrumentation.waitForIdleSync()
        assertFalse(queuedRan.get())
        assertEquals(0, callbacks.get())
        assertEquals(0, failures.get())
        onMain {
            worker.submit({ queuedRan.set(true) }, { callbacks.incrementAndGet() }) {
                callbacks.incrementAndGet()
            }
        }
        assertFalse(queuedRan.get())
        assertEquals(0, callbacks.get())
    }

    @Test
    fun aFailureAfterCloseCannotDeliverOwnerOrRequestCallbacks() {
        val entered = CountDownLatch(1)
        val requestFailures = AtomicInteger()
        onMain {
            worker.submit({
                entered.countDown()
                check(unblock.await(5, TimeUnit.SECONDS))
                error("Synthetic history failure")
            }, { requestFailures.incrementAndGet() }) {}
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        onMain { worker.close() }
        unblock.countDown()
        assertTrue(worker.awaitClosed(5000))
        instrumentation.waitForIdleSync()
        assertEquals(0, failures.get())
        assertEquals(0, requestFailures.get())
    }

    @Test
    fun cancellationKeepsTheWorkerUsableAndDeliversSuccessOnMain() {
        val delivered = CountDownLatch(1)
        val ranOffMain = AtomicBoolean()
        val deliveredOnMain = AtomicBoolean()
        val result = AtomicInteger()
        onMain {
            worker.submit({ throw OperationCanceledException() }) { failures.incrementAndGet() }
            worker.submit({
                ranOffMain.set(Looper.myLooper() != Looper.getMainLooper())
                7
            }) {
                result.set(it)
                deliveredOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                delivered.countDown()
            }
        }
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertTrue(ranOffMain.get())
        assertTrue(deliveredOnMain.get())
        assertEquals(7, result.get())
        assertEquals(0, failures.get())
    }

    @Test
    fun failedWorkNotifiesItsOwnerBeforeRequestRecoveryAndDoesNotStallTheWorker() {
        val delivered = CountDownLatch(1)
        val ownerWasNotified = AtomicBoolean()
        val recoveryOnMain = AtomicBoolean()
        onMain {
            worker.submit({ error("Synthetic history failure") }, {
                ownerWasNotified.set(failures.get() == 1)
                recoveryOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                worker.submit({ 1 }) { delivered.countDown() }
            }) {}
        }
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertTrue(ownerWasNotified.get())
        assertTrue(recoveryOnMain.get())
        assertEquals(1, failures.get())
    }

    @Test
    fun ownerClosureDuringFailureSuppressesRequestRecovery() {
        val ownerClosed = CountDownLatch(1)
        val requestFailures = AtomicInteger()
        onMain {
            onOwnerFailure = {
                worker.close()
                ownerClosed.countDown()
            }
            worker.submit({ error("Synthetic history failure") }, {
                requestFailures.incrementAndGet()
            }) {}
        }
        assertTrue(ownerClosed.await(5, TimeUnit.SECONDS))
        assertTrue(worker.awaitClosed(5000))
        instrumentation.waitForIdleSync()
        assertEquals(1, failures.get())
        assertEquals(0, requestFailures.get())
    }

    @Test
    fun saturatedQueueReportsRejectionAndCanCloseWithoutRunningItsBacklog() {
        val entered = CountDownLatch(1)
        val queuedRan = AtomicInteger()
        val rejections = AtomicInteger()
        onMain {
            worker.submit({
                entered.countDown()
                check(unblock.await(5, TimeUnit.SECONDS))
            }) {}
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        onMain {
            repeat(128) {
                worker.submit({ queuedRan.incrementAndGet() }) {}
            }
            worker.submit({ queuedRan.incrementAndGet() }, {
                assertEquals(1, failures.get())
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                rejections.incrementAndGet()
            }) {}
            worker.close()
        }
        unblock.countDown()
        assertTrue(worker.awaitClosed(5000))
        assertEquals(1, failures.get())
        assertEquals(1, rejections.get())
        assertEquals(0, queuedRan.get())
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}

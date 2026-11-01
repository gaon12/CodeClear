package xyz.gaon.codeclear.mobile

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.gaon.codeclear.probe.ProbeBodyEvidence
import xyz.gaon.codeclear.probe.ProbeEnvironment
import xyz.gaon.codeclear.probe.ProbeHeadingKind
import xyz.gaon.codeclear.probe.ProbeObservation
import xyz.gaon.codeclear.probe.ProbeOrigin
import xyz.gaon.codeclear.probe.ProbeRadio
import xyz.gaon.codeclear.probe.ProbeRecord
import xyz.gaon.codeclear.probe.ProbeRoute
import xyz.gaon.codeclear.probe.ProbeSession

class AlertCaptureIngestorDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val source = "test.system.receiver"
    private lateinit var store: AlertHistoryStore
    private lateinit var worker: HistoryWorker
    private lateinit var ingestion: AlertCaptureIngestor
    private lateinit var databaseName: String
    private lateinit var scope: AutoCloseable
    private val failures = AtomicInteger()
    private val captured = AtomicInteger()
    private val unblock = CountDownLatch(1)
    private var state = MobileState(
        loaded = true,
        options = MobileOptions(notificationConsent = true)
    )
    private var environment = ProbeEnvironment(ProbeRadio.PRESENT, setOf(source), true, true)
    private var generation = 0L

    @Before
    fun prepareOnlyAnIsolatedSyntheticHistory() {
        databaseName = "ingestion-test-${UUID.randomUUID()}.db"
        store = AlertHistoryStore(context, databaseName)
        onMain {
            scope = context.mobileRuntime.pauseForDiagnostics()
            generation = ProbeSession.currentGeneration()
            worker = HistoryWorker(store::close, { failures.incrementAndGet() })
            ingestion = AlertCaptureIngestor(
                store,
                worker,
                { state },
                { environment },
                captured = { _, _ -> captured.incrementAndGet() },
                networkCountry = { null },
                changed = {}
            )
        }
    }

    @After
    fun closeBeforeDeletingOnlyTheFixtureStore() {
        unblock.countDown()
        try {
            onMain {
                ingestion.close()
                worker.close()
            }
            assertTrue(worker.awaitClosed(5000))
            context.deleteDatabase(databaseName)
            assertEquals(0, failures.get())
        } finally {
            onMain { scope.close() }
        }
    }

    @Test
    fun repeatedCallbacksDoNotMergeDistinctSourceOccurrences() {
        onMain {
            ingestion.added(generation, record(1, "first"))
            ingestion.added(generation, record(2, "first"))
            ingestion.added(generation, record(3, "second"))
        }
        drain()
        val rows = store.page("", state.options.target.languageTag)
        assertEquals(setOf("first", "second"), rows.map { it.capture.sourceIdentity }.toSet())
        assertEquals(2, rows.size)
        assertEquals(2, captured.get())
    }

    @Test
    fun aLateHeadingReviewOfADuplicateUpdatesTheStoredOccurrence() {
        val duplicate = record(2, "same-source")
        onMain {
            ingestion.added(generation, record(1, "same-source"))
            ingestion.added(generation, duplicate)
            ingestion.updated(
                generation,
                duplicate.copy(
                    observation = duplicate.observation.copy(headingHint = ProbeHeadingKind.TEST)
                )
            )
        }
        drain()
        val rows = store.page("", state.options.target.languageTag)
        assertEquals(1, rows.size)
        assertEquals(ProbeHeadingKind.TEST.name, rows.single().capture.headingHint)
        assertEquals(1, captured.get())
    }

    @Test
    fun clearingInvalidatesQueuedObservationsAndCloseRejectsLaterCallbacks() {
        val entered = CountDownLatch(1)
        onMain {
            worker.submit({
                entered.countDown()
                check(unblock.await(5, TimeUnit.SECONDS))
            }) {}
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        onMain {
            ingestion.added(generation, record(1, "obsolete"))
            ingestion.cleared(generation + 1)
        }
        unblock.countDown()
        drain()
        assertTrue(store.page("", state.options.target.languageTag).isEmpty())
        onMain {
            ingestion.close()
            ingestion.added(generation + 2, record(2, "after-close"))
        }
        drain()
        assertTrue(store.page("", state.options.target.languageTag).isEmpty())
        assertEquals(0, captured.get())
    }

    @Test
    fun eachObservationRechecksRouteAccessConsentAndDiagnosticPause() {
        onMain {
            environment = environment.copy(notificationGranted = false)
            ingestion.added(generation, record(1, "no-access"))
            environment = environment.copy(notificationGranted = true)
            ingestion.added(generation, record(2, "accepted"))
            state = state.copy(options = state.options.copy(notificationConsent = false))
            ingestion.added(generation, record(3, "withdrawn"))
            state = state.copy(options = state.options.copy(notificationConsent = true))
            ingestion.pauseCapture(true)
            ingestion.added(generation, record(4, "diagnostics"))
        }
        drain()
        val rows = store.page("", state.options.target.languageTag)
        assertEquals(listOf("accepted"), rows.map { it.capture.sourceIdentity })
    }

    private fun record(id: Long, identity: String) = ProbeRecord(
        id,
        ProbeObservation(
            source,
            ProbeRoute.NOTIFICATION,
            ProbeOrigin.SYSTEM_CANDIDATE,
            "[DRILL] Synthetic source",
            "[DRILL] Same synthetic original. Call 112.",
            1700000000000 + id,
            bodyEvidence = ProbeBodyEvidence.EXPANDED_NOTIFICATION_FIELD,
            sourceObservationKey = identity
        )
    )

    private fun drain() {
        val done = CountDownLatch(1)
        onMain { worker.submit({ Unit }) { done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}

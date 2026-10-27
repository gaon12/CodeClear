package xyz.gaon.codeclear.mobile

import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AlertHistoryStoreDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var store: AlertHistoryStore

    @Before
    fun createIsolatedPrivateHistory() {
        name = "history-test-${UUID.randomUUID()}.db"
        store = AlertHistoryStore(context, name)
    }

    @After
    fun removeOnlyThisTestsDatabase() {
        store.close()
        context.deleteDatabase(name)
    }

    @Test
    fun originalsAndKnownSourceFieldsSurviveReopen() {
        val original = capture("[DRILL] 서울 台北 東京 🚨. Do not enter. 14:05, 112.").copy(
            broadcast = BroadcastMetadata(1, 49, 4370, 3, 0, null),
            receivedAtMillis = 1700000000100L,
            sourceIdentity = "broadcast:49:1700000000100"
        )
        val id = store.insert(original)
        store.close()
        store = AlertHistoryStore(context, name)
        assertEquals(original, store.find(id, "en")?.capture)
        assertNull(store.find(id, "en")?.capture?.postedAtMillis)
        assertNull(store.find(id, "en")?.translation)
    }

    @Test
    fun searchTreatsSqlAndLikeMetacharactersAsLiteralText() {
        val literal = store.insert(capture("[DRILL] 100%_gate \\ north"))
        store.insert(capture("[DRILL] 100xxgate south"))
        assertEquals(listOf(literal), store.page("100%_", "en").map { it.id })
        assertEquals(listOf(literal), store.page("\\ north", "en").map { it.id })
        assertTrue(store.page("' OR 1=1 --", "en").isEmpty())
        assertEquals(2, store.page("ＧＡＴＥ", "en").size)
        val ticket = store.beginTranslation(literal, "ja")!!
        store.finishTranslation(
            ticket,
            SavedTranslation(TranslationPhase.READY, "[訓練] 川に近づかない。112。", "en")
        )
        assertEquals(listOf(literal), store.page("川に近づかない", "de").map { it.id })
    }

    @Test
    fun exactSourceIdentityDeduplicatesWithoutMergingSeparateOccurrences() {
        val first = capture("[DRILL] Shelter now.").copy(sourceIdentity = "notification:1:1000")
        val id = store.insert(first)
        assertEquals(id, store.insert(first))
        assertNotEquals(id, store.insert(first.copy(sourceIdentity = "notification:1:2000")))
        assertNotEquals(id, store.insert(first.copy(sourceIdentity = null)))
        assertNotEquals(id, store.insert(first.copy(original = "[DRILL] Updated shelter.")))
    }

    @Test
    fun staleAndDuplicateCompletionsCannotReplaceNewerTargetWork() {
        val id = store.insert(capture("[DRILL] Do not leave."))
        val old = store.beginTranslation(id, "en")!!
        assertNull(store.beginTranslation(id, "en"))
        val fresh = store.beginTranslation(id, "en", retry = true)!!
        assertFalse(store.finishTranslation(old, ready("old")))
        assertTrue(store.finishTranslation(fresh, ready("current")))
        assertFalse(store.finishTranslation(fresh, ready("duplicate")))
        val german = store.beginTranslation(id, "de")!!
        assertTrue(store.finishTranslation(german, ready("Nicht hinausgehen.")))
        assertEquals("current", store.find(id, "en")?.translation?.text)
        assertEquals("Nicht hinausgehen.", store.find(id, "de")?.translation?.text)
    }

    @Test
    fun deletionCascadesAndLateResultsCannotRecreateHistoryOrCache() {
        val original = capture("[DRILL] Test original.")
        val id = store.insert(original)
        val working = store.beginTranslation(id, "en")!!
        store.delete(id)
        assertFalse(store.finishTranslation(working, ready("late")))
        assertNull(store.find(id, "en"))
        val next = store.insert(original)
        assertTrue(next > id)
        val ticket = store.beginTranslation(next, "en")!!
        store.finishTranslation(ticket, ready("cached copy"))
        assertNotNull(store.cachedTranslation(original.original, "en"))
        assertNull(store.cachedTranslation(original.original + "changed", "en"))
        store.deleteAll()
        assertNull(store.cachedTranslation(original.original, "en"))
        assertTrue(store.page("", "en").isEmpty())
        assertFalse(store.finishTranslation(ticket, ready("late again")))
    }

    @Test
    fun interruptedAndMissingModelWorkRequireValidNewRevisions() {
        val id = store.insert(capture("[DRILL] Do not cross."))
        assertEquals(id, store.nextQueued("en"))
        val interrupted = store.beginTranslation(id, "en")!!
        store.recoverInterruptedWork()
        assertFalse(store.finishTranslation(interrupted, ready("stale")))
        val current = store.beginTranslation(id, "en")!!
        store.finishTranslation(current, SavedTranslation(TranslationPhase.NEEDS_MODELS))
        assertNull(store.nextQueued("en"))
        store.requeueMissingModels("en")
        assertEquals(id, store.nextQueued("en"))
        val retry = store.beginTranslation(id, "en")!!
        store.finishTranslation(retry, SavedTranslation(TranslationPhase.FAILED))
        assertNull(store.nextQueued("en"))
    }

    @Test
    fun cancelledQueriesAndBoundedPagesDoNotDiscardStoredRows() {
        store.insertBatch((1..40).map { capture("[DRILL] Row $it") })
        val page = store.page("", "en", limit = 10)
        assertEquals(10, page.size)
        val next = store.page("", "en", beforeId = page.last().id, limit = 10)
        assertTrue(page.map { it.id }.intersect(next.map { it.id }.toSet()).isEmpty())
        val cancelled = CancellationSignal().apply { cancel() }
        var rejected = false
        try {
            store.page("not present", "en", cancellation = cancelled)
        } catch (_: OperationCanceledException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(40, store.page("", "en").size)
    }

    @Test
    fun searchAtTenThousandRowsMeetsTheScopedQueryBudget() {
        (1..10000).chunked(40).forEach { batch ->
            store.insertBatch(
                batch.map {
                    capture(
                        "[DRILL] Reference $it. Do not enter Gate 3. 14:05, 112. " +
                            "Safety text. ".repeat(30)
                    )
                }
            )
        }
        val samples = (1..20).map { index ->
            val started = SystemClock.elapsedRealtimeNanos()
            store.page(if (index % 2 == 0) "missing needle" else "Reference 12.", "en")
            (SystemClock.elapsedRealtimeNanos() - started) / 1000000.0
        }.sorted()
        val p95 = samples[18]
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("history_search_rows", "10000")
                putString("history_search_p95_ms", p95.toString())
            }
        )
        assertTrue("Scoped query p95 exceeded 300 ms: $p95", p95 <= 300)
        assertEquals(50, store.page("", "en").size)
    }

    private fun capture(body: String) = CapturedAlert(
        sourcePackage = "test.system.receiver",
        source = CaptureSource.NOTIFICATION,
        title = "[DRILL] Test heading",
        original = body,
        capturedAtMillis = 1700000000000L
    )

    private fun ready(text: String) = SavedTranslation(TranslationPhase.READY, text, "en")
}

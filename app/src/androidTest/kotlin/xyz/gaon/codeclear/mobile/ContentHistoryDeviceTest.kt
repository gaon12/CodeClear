package xyz.gaon.codeclear.mobile

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContentHistoryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var store: AlertHistoryStore
    private val time = 1_700_000_000_000L
    private val body = "[DRILL] Gate 3, call 112."

    @Before fun create() {
        name = "content-history-${UUID.randomUUID()}.db"
        store = AlertHistoryStore(context, name)
    }

    @After fun clean() {
        store.close()
        context.deleteDatabase(name)
    }

    private fun capture(index: Int, original: String = body) = CapturedAlert(
        "test.system.receiver",
        CaptureSource.NOTIFICATION,
        "[DRILL] Heading $index",
        original,
        time + index * 60_000,
        postedAtMillis = time + index * 60_000,
        sourceIdentity = "posting-$index"
    )
    private fun grouped(before: Long = Long.MAX_VALUE, limit: Int = 50, query: String = "") =
        store.page(query, "en", before, limit, groupIdenticalOriginals = true)

    @Test fun identicalHistoryRowsKeepEveryObservationWithoutRepeatingAcrossPages() {
        val first = store.insertObservation(capture(0).copy(receivedAtMillis = time - 1000))!!
        val dialog = store.insertObservation(
            capture(0).copy(
                source = CaptureSource.DIALOG,
                sourceIdentity = "dialog",
                capturedAtMillis = time + 1000,
                postedAtMillis = null
            )
        )!!
        assertEquals(first.id, dialog.id)
        val repeated = store.insertObservation(capture(1))!!
        val other = store.insertObservation(capture(2, "[DRILL] Another original."))!!
        assertTrue(repeated.created)
        assertEquals(3, store.page("", "en").size)
        assertEquals(listOf(other.id, repeated.id), grouped().map { it.id })
        assertEquals(listOf(other.id), grouped(limit = 1).map { it.id })
        assertEquals(listOf(repeated.id), grouped(other.id, limit = 1).map { it.id })
        assertTrue(grouped(repeated.id).isEmpty())
        val detail = store.find(repeated.id, "en", withObservations = true)!!
        assertEquals(3, detail.observationCount)
        assertEquals(
            listOf(repeated.observationId, dialog.observationId, first.observationId),
            detail.observations.map { it.id }
        )
        assertEquals(time - 1000, detail.observations.last().receivedAtMillis)
        assertEquals(time + 1000, detail.observations[1].capturedAtMillis)
    }

    @Test fun receiptPaginationReopenAndCancellationRemainBounded() {
        val ids = (0 until 25).map { store.insert(capture(it))!! }
        store.close()
        store = AlertHistoryStore(context, name)
        assertEquals(1, grouped().size)
        val newest = store.find(ids.last(), "en", withObservations = true)!!
        assertEquals(25, newest.observationCount)
        assertEquals(20, newest.observations.size)
        assertTrue(newest.moreObservations)
        val older = store.find(
            ids.last(),
            "en",
            withObservations = true,
            observationBeforeId = newest.observations.last().id
        )!!
        assertEquals(5, older.observations.size)
        assertFalse(older.moreObservations)
        assertEquals(ids.reversed(), (newest.observations + older.observations).map { it.id })
        val cancelled = CancellationSignal().apply { cancel() }
        val failure = runCatching {
            store.page("", "en", cancellation = cancelled, groupIdenticalOriginals = true)
        }.exceptionOrNull()
        assertTrue(failure is OperationCanceledException)
    }

    @Test fun sourceBoundariesPeerTrustAndExactOriginalProtectDistinctMessages() {
        val first = store.insert(capture(0))!!
        store.insert(capture(1))!!
        store.insert(capture(2).copy(sourcePackage = "other.system.receiver"))!!
        val peer = store.insert(
            capture(3).copy(
                source = CaptureSource.PEER,
                sourceIdentity = "a".repeat(64)
            )
        )!!
        val changed = store.insert(capture(4, body + "\n"))!!
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { database ->
            database.execSQL(
                "UPDATE alerts SET original_hash=(SELECT original_hash FROM alerts WHERE id=?) WHERE id=?",
                arrayOf(first, changed)
            )
        }
        val records = grouped()
        assertEquals(4, records.size)
        assertEquals(1, records.single { it.id == peer }.observationCount)
        assertEquals(1, records.single { it.id == changed }.observationCount)
        assertEquals(
            2,
            records.single {
                it.capture.sourcePackage == "test.system.receiver" &&
                    it.capture.source != CaptureSource.PEER && it.capture.original == body
            }.observationCount
        )
    }

    @Test fun deletingAContentEntryCancelsOnlyMatchingCandidatesAndRejectsLateResults() {
        val firstCapture = capture(0)
        val first = store.insert(firstCapture)!!
        val ticket = store.beginTranslation(first, "en")!!
        val repeated = store.insert(capture(1))!!
        val other = store.insert(capture(2, "[DRILL] Other body."))!!
        val peer = store.insert(
            capture(3).copy(
                source = CaptureSource.PEER,
                sourceIdentity = "b".repeat(64)
            )
        )!!
        assertEquals(
            setOf(first, repeated),
            store.deleteContent(repeated, setOf(first, repeated, other, peer))
        )
        assertNull(store.find(first, "en"))
        assertFalse(
            store.finishTranslation(
                ticket,
                SavedTranslation(TranslationPhase.READY, "[DRILL] Late 112.", "ko")
            )
        )
        assertNull(store.insertObservation(firstCapture))
        assertEquals(setOf(other, peer), grouped().map { it.id }.toSet())
        assertTrue(store.insertObservation(capture(4))!!.created)
        assertEquals(3, grouped().size)
    }

    @Test fun groupedSearchRetainsOlderTitlesAndTranslationsWithoutQueryInjection() {
        val first = store.insert(capture(0).copy(title = "[DRILL] Old %_ heading"))!!
        val ticket = store.beginTranslation(first, "en")!!
        assertTrue(
            store.finishTranslation(
                ticket,
                SavedTranslation(TranslationPhase.READY, "[DRILL] Earlier translated marker", "ko")
            )
        )
        val latest = store.insert(capture(1).copy(title = "[DRILL] New heading"))!!
        assertEquals(listOf(latest), grouped(query = "Old %_ heading").map { it.id })
        assertEquals(listOf(latest), grouped(query = "translated marker").map { it.id })
        assertTrue(grouped(query = "' OR 1=1 --").isEmpty())
    }

    @Test fun groupedTenThousandRowHistoryHasCorrectCountsAndMeasuredQueries() {
        store.page("", "en")
        val originals = List(100) { "[DRILL] Bulletin $it, call 112." }
        val hashes = originals.map { original ->
            MessageDigest.getInstance("SHA-256")
                .digest(original.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { database ->
            database.beginTransaction()
            try {
                database.compileStatement(
                    "INSERT INTO alerts(source_package,source_kind,source_identity,title," +
                        "original,original_hash,search_original,captured_ms,heading_hint) " +
                        "VALUES('test.system.receiver','NOTIFICATION',?,'[DRILL]',?,?,?,?,'UNKNOWN')"
                ).use { statement ->
                    repeat(10_000) { index ->
                        statement.bindString(1, "benchmark-$index")
                        statement.bindString(2, originals[index % 100])
                        statement.bindString(3, hashes[index % 100])
                        statement.bindString(4, HistoryText.searchable(originals[index % 100]))
                        statement.bindLong(5, time + index * 60_000)
                        statement.executeInsert()
                    }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
        val timings = List(20) {
            val start = SystemClock.elapsedRealtimeNanos()
            val records = grouped()
            assertEquals(50, records.size)
            assertEquals(50, records.map { it.capture.original }.distinct().size)
            assertTrue(records.all { it.observationCount == 100 })
            (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        }.sorted()
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("grouped_history_query_p95_ms", timings[18].toString())
            }
        )
    }
}

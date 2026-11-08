package xyz.gaon.codeclear.mobile

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ObservationGroupsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var store: AlertHistoryStore
    private val time = 1_700_000_000_000L

    @Before fun create() {
        name = "groups-${UUID.randomUUID()}.db"
        store = AlertHistoryStore(context, name)
    }

    @After fun clean() {
        store.close()
        context.deleteDatabase(name)
    }

    @Test fun crossRouteGroupingKeepsEveryRawObservationAndDeletesEveryAliasTogether() {
        val observations = listOf(
            capture("n", CaptureSource.NOTIFICATION),
            capture("d", CaptureSource.DIALOG, 2_000),
            capture("o", CaptureSource.ORIGINAL_NOTIFICATION, 4_000)
        )
        val saved = observations.map { store.insertObservation(it)!! }
        assertTrue(saved.first().created)
        assertTrue(saved.drop(1).none { it.created })
        assertEquals(1, saved.map { it.id }.distinct().size)
        assertEquals(3, saved.map { it.observationId }.distinct().size)
        assertEquals(1, store.page("", "en").size)
        assertEquals(3, rawCount())
        store.close()
        store = AlertHistoryStore(context, name)
        assertEquals(saved[0].id, store.find(saved[2].observationId, "en")!!.id)
        store.queueTranslation(saved[1].observationId, "en")
        assertEquals(saved[0].id, store.nextQueued("en"))
        val ticket = store.beginTranslation(saved[2].observationId, "en")!!
        assertEquals(saved[0].id, ticket.alertId)
        assertTrue(
            store.finishTranslation(
                ticket,
                SavedTranslation(
                    TranslationPhase.READY,
                    "[DRILL] Alias translation",
                    "ko"
                )
            )
        )
        store.delete(saved[1].observationId)
        assertEquals(0, rawCount())
        observations.forEach { assertNull(store.insertObservation(it)) }
        assertTrue(store.page("", "en").isEmpty())
    }

    @Test fun sameRouteDifferentPackageAndOutsideWindowRemainSeparate() {
        val first = capture("first", CaptureSource.NOTIFICATION)
        val records = listOf(
            first,
            first.copy(sourceIdentity = "repeat", capturedAtMillis = time + 1_000),
            capture("late", CaptureSource.DIALOG, 6_001),
            capture(
                "other",
                CaptureSource.DIALOG,
                1_000
            ).copy(sourcePackage = "other.system.receiver")
        )
        assertEquals(4, records.map { store.insertObservation(it)!!.id }.distinct().size)
        assertEquals(4, store.page("", "en").size)
    }

    @Test fun identicalNewOccurrencesCanEachHaveTheirOwnRoutePair() {
        val first = store.insertObservation(capture("n1", CaptureSource.NOTIFICATION))!!
        assertEquals(
            first.id,
            store.insertObservation(capture("d1", CaptureSource.DIALOG, 1_000))!!.id
        )
        val second = store.insertObservation(capture("n2", CaptureSource.NOTIFICATION, 2_000))!!
        assertNotEquals(first.id, second.id)
        assertEquals(
            second.id,
            store.insertObservation(capture("d2", CaptureSource.DIALOG, 3_000))!!.id
        )
        assertEquals(2, store.page("", "en").size)
        assertEquals(4, rawCount())
    }

    @Test fun ambiguousPairsAndSaturatedCandidatesNeverGuessAnOccurrence() {
        store.insertObservation(capture("n1", CaptureSource.NOTIFICATION))
        store.insertObservation(capture("n2", CaptureSource.NOTIFICATION, 100))
        assertTrue(
            store.insertObservation(capture("ambiguous", CaptureSource.DIALOG, 200))!!.created
        )
        for (i in 3..8) store.insertObservation(capture("n$i", CaptureSource.NOTIFICATION, 300))
        assertTrue(
            store.insertObservation(
                capture("saturated", CaptureSource.ORIGINAL_NOTIFICATION, 400)
            )!!.created
        )
        assertEquals(10, store.page("", "en").size)
    }

    @Test fun conflictingReceivedTimesOrStructuredBroadcastIdentifiersRemainSeparate() {
        val metadata = BroadcastMetadata(1, 42, 4372, 3, 1, null)
        val first = capture(
            "one",
            CaptureSource.NOTIFICATION
        ).copy(broadcast = metadata, receivedAtMillis = time - 10_000)
        val one = store.insertObservation(first)!!
        val two = store.insertObservation(
            capture(
                "two",
                CaptureSource.DIALOG,
                1_000
            ).copy(broadcast = metadata, receivedAtMillis = time - 9_000)
        )!!
        assertNotEquals(one.id, two.id)
        val three = store.insertObservation(
            capture(
                "three",
                CaptureSource.ORIGINAL_NOTIFICATION,
                2_000
            ).copy(broadcast = metadata.copy(serial = 43), receivedAtMillis = time - 10_000)
        )!!
        assertTrue(three.created)
        assertEquals(3, store.page("", "en").size)
    }

    @Test fun peerMessagesNeverJoinNativeOrOtherPeerObservations() {
        val native = store.insertObservation(capture("native", CaptureSource.NOTIFICATION))!!
        val peer = capture("a".repeat(64), CaptureSource.PEER, 1_000)
        val saved = store.insertObservation(peer)!!
        val another = store.insertObservation(peer.copy(sourceIdentity = "b".repeat(64)))!!
        assertNotEquals(native.id, saved.id)
        assertNotEquals(saved.id, another.id)
        assertEquals(3, store.page("", "en").size)
    }

    @Test fun concurrentRoutesCreateOneDisplayEventAndPreserveBothRawRows() {
        val other = AlertHistoryStore(context, name)
        val pool = Executors.newFixedThreadPool(2)
        try {
            store.page("", "en")
            other.page("", "en")
            val start = CountDownLatch(1)
            val first = pool.submit<StoredObservation> {
                check(start.await(5, TimeUnit.SECONDS))
                store.insertObservation(capture("n", CaptureSource.NOTIFICATION))!!
            }
            val second = pool.submit<StoredObservation> {
                check(start.await(5, TimeUnit.SECONDS))
                other.insertObservation(capture("d", CaptureSource.DIALOG, 1_000))!!
            }
            start.countDown()
            val results = listOf(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
            assertEquals(results[0].id, results[1].id)
            assertEquals(1, results.count { it.created })
            assertEquals(2, rawCount())
        } finally {
            pool.shutdownNow()
            other.close()
        }
    }

    @Test fun versionFiveMigrationGroupsOldRowsAndPreservesTheirTranslations() {
        val first = store.insertObservation(capture("n", CaptureSource.NOTIFICATION))!!
        val second = store.insertObservation(capture("d", CaptureSource.DIALOG, 6_000))!!
        val ticket = store.beginTranslation(second.id, "en")!!
        assertTrue(
            store.finishTranslation(
                ticket,
                SavedTranslation(
                    TranslationPhase.READY,
                    "[DRILL] Saved translation",
                    "ko"
                )
            )
        )
        store.queueTranslation(second.id, "ko")
        val stale = store.beginTranslation(second.id, "de")!!
        store.close()
        rawDatabase().use { db ->
            // Rebuild only this isolated fixture as the previously committed schema.
            val schema = db.rawQuery(
                "SELECT sql FROM sqlite_master WHERE name='alerts'",
                null
            ).use {
                check(it.moveToFirst())
                it.getString(0)
            }
            val columns = db.rawQuery("PRAGMA table_info(alerts)", null).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        if (c.getString(1) !=
                            "display_group_id"
                        ) {
                            add(c.getString(1))
                        }
                    }
                }
                    .joinToString(",")
            }
            db.execSQL(
                schema.replace("CREATE TABLE alerts", "CREATE TABLE previous_alerts")
                    .replace("display_group_id INTEGER,", "")
            )
            db.execSQL("INSERT INTO previous_alerts($columns) SELECT $columns FROM alerts")
            db.execSQL("DROP TABLE alerts")
            db.execSQL("ALTER TABLE previous_alerts RENAME TO alerts")
            db.execSQL("CREATE INDEX alert_original_hash ON alerts(original_hash)")
            db.execSQL(
                "UPDATE alerts SET captured_ms=? WHERE id=?",
                arrayOf(time + 1_000, second.id)
            )
            db.execSQL("ALTER TABLE translations RENAME TO current_translations")
            db.execSQL(
                "CREATE TABLE translations (" +
                    "alert_id INTEGER NOT NULL REFERENCES alerts(id) ON DELETE CASCADE," +
                    "target TEXT NOT NULL, engine TEXT NOT NULL, revision INTEGER NOT NULL," +
                    "phase TEXT NOT NULL, text TEXT, source_language TEXT, search_text TEXT," +
                    "same_language INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(alert_id,target,engine))"
            )
            db.execSQL(
                "INSERT INTO translations SELECT alert_id,target,engine,revision,phase,text," +
                    "source_language,search_text,same_language FROM current_translations"
            )
            db.execSQL("DROP TABLE current_translations")
            db.execSQL(
                "CREATE INDEX translation_queue ON translations(engine,target,phase,alert_id DESC)"
            )
            db.version = 5
        }
        store = AlertHistoryStore(context, name)
        assertEquals(1, store.page("", "en").size)
        assertEquals(first.id, store.find(second.id, "en")!!.id)
        assertEquals("[DRILL] Saved translation", store.find(first.id, "en")!!.translation!!.text)
        assertEquals(2, rawCount())
        assertEquals(first.id, store.nextQueued("ko"))
        assertFalse(
            store.finishTranslation(
                stale,
                SavedTranslation(
                    TranslationPhase.READY,
                    "[DRILL] Stale result",
                    "ko"
                )
            )
        )
    }

    private fun capture(identity: String, source: CaptureSource, offset: Long = 0) = CapturedAlert(
        "test.system.receiver",
        source,
        "[DRILL] Emergency alert",
        "[DRILL] Same original body.",
        time + offset,
        sourceIdentity = identity
    )

    private fun rawDatabase() = SQLiteDatabase.openDatabase(
        context.getDatabasePath(name).path,
        null,
        SQLiteDatabase.OPEN_READWRITE
    )

    private fun rawCount(): Int = rawDatabase().use { db ->
        db.rawQuery("SELECT COUNT(*) FROM alerts", null).use {
            check(it.moveToFirst())
            it.getInt(0)
        }
    }
}

package xyz.gaon.codeclear.mobile

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.gaon.codeclear.reception.CountryEvidence
import xyz.gaon.codeclear.reception.ReceptionCountry
import xyz.gaon.codeclear.translation.TranslationEngineKind

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
    fun aRejectedBatchRollsBackEarlierRowsAndReleasesTheTransaction() {
        val first = capture("[DRILL] First batch item.")
        val invalidPeer = capture("[DRILL] Invalid peer item.").copy(
            source = CaptureSource.PEER,
            sourceIdentity = "invalid"
        )
        assertThrows(IllegalArgumentException::class.java) {
            store.insertBatch(listOf(first, invalidPeer))
        }
        store.close()
        store = AlertHistoryStore(context, name)
        assertTrue(store.page("", "en").isEmpty())
        assertNotNull(store.insert(first))
        assertEquals(1, store.page("", "en").size)
    }

    @Test
    fun peerRetentionPreservesOldMessagesAndStopsAtCapacityWithoutDeletingThem() {
        store.close()
        store = AlertHistoryStore(context, name, maxPeerAlerts = 2)
        fun peer(index: Int) = capture("[DRILL] Retained $index").copy(
            source = CaptureSource.PEER,
            sourceIdentity = index.toString(16).padStart(64, '0')
        )
        val first = store.insert(peer(1))!!
        val second = store.insert(peer(2))!!
        assertNull(store.insert(peer(3)))
        assertEquals(first, store.insert(peer(1)))
        assertEquals(2, store.page("", "en").size)
        store.close()
        store = AlertHistoryStore(context, name, maxPeerAlerts = 2)
        assertEquals("[DRILL] Retained 1", store.find(first, "en")!!.capture.original)
        store.delete(second)
        assertNotNull(store.insert(peer(3)))
        assertNull(store.insert(peer(2)))
        assertEquals(2, store.page("", "en").size)
    }

    @Test
    fun countrySnapshotSurvivesReopeningAndDoesNotChangeWhenReplayed() {
        val country = ReceptionCountry("KR", CountryEvidence.CBS_PLMN)
        val original = capture(
            "[DRILL] Country snapshot"
        ).copy(sourceIdentity = "country:1", context = ReceptionContext(country))
        val id = store.insert(original)!!
        store.close()
        store = AlertHistoryStore(context, name)
        assertEquals(country, store.find(id, "en")!!.capture.context.country)
        store.insert(
            original.copy(
                context = ReceptionContext(ReceptionCountry("US", CountryEvidence.NETWORK_SNAPSHOT))
            )
        )
        assertEquals(country, store.find(id, "en")!!.capture.context.country)
        assertEquals(ReceptionContext(), ReceptionContext.decode("{bad"))
    }

    @Test
    fun migrationFromVersionFivePreservesOriginalsAndTranslations() {
        verifyMigration(5)
    }

    @Test
    fun migrationFromVersionFourPreservesOriginalsAndTranslations() {
        verifyMigration(4)
    }

    @Test
    fun onlyANewObservationCanBePublishedAsANewNearbyEvent() {
        val alert = capture("[DRILL] First observation").copy(sourceIdentity = "native:1")
        val first = store.insertObservation(alert)!!
        assertTrue(first.created)
        val replay = store.insertObservation(
            alert.copy(
                capturedAtMillis =
                    alert.capturedAtMillis + 60000
            )
        )!!
        assertFalse(replay.created)
        assertEquals(first.id, replay.id)
        val separate = store.insertObservation(alert.copy(sourceIdentity = "native:2"))!!
        assertTrue(separate.created)
        assertNotEquals(first.id, separate.id)
        store.delete(first.id)
        assertNull(store.insertObservation(alert))
    }

    @Test
    fun headingUpdatesPreserveTheOriginalAndCannotRestoreADeletedRow() {
        val id = store.insert(capture("[DRILL] Heading metadata"))!!
        store.updateHeading(id, "PUBLIC_SAFETY")
        assertEquals("PUBLIC_SAFETY", store.find(id, "en")?.capture?.headingHint)
        assertEquals("[DRILL] Heading metadata", store.find(id, "en")?.capture?.original)
        store.delete(id)
        store.updateHeading(id, "UNKNOWN")
        assertNull(store.find(id, "en"))
    }

    @Test
    fun concurrentReplayAndDeletionCannotRestoreTheDeletedObservation() {
        val other = AlertHistoryStore(context, name)
        val threads = Executors.newFixedThreadPool(2)
        try {
            repeat(20) { index ->
                val original = capture(
                    "[DRILL] Concurrent $index"
                ).copy(sourceIdentity = "source:$index")
                val id = store.insert(original)!!
                other.find(id, "en")
                val start = CountDownLatch(1)
                val replay = threads.submit<Long?> {
                    start.await()
                    store.insert(original)
                }
                val deletion = threads.submit {
                    start.await()
                    other.delete(id)
                }
                start.countDown()
                replay.get(5, TimeUnit.SECONDS)
                deletion.get(5, TimeUnit.SECONDS)
                assertTrue(store.page("Concurrent $index", "en").isEmpty())
                assertNull(store.insert(original))
            }
        } finally {
            threads.shutdownNow()
            other.close()
        }
    }

    @Test
    fun deletionFingerprintsExpireWithoutRetainingDeletedText() {
        val original = capture("[DRILL] Expiry").copy(sourceIdentity = "source:expiry")
        store.delete(store.insert(original)!!)
        store.close()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use {
            it.execSQL(
                "UPDATE ignored_sources SET deleted_ms=?",
                arrayOf(System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000)
            )
        }
        store = AlertHistoryStore(context, name)
        assertNotNull(store.insert(original))
    }

    @Test
    fun deletedSourceObservationsStayIgnoredAcrossRestartButUpdatesRemainEligible() {
        val capture = capture(
            "[DRILL] Stable source."
        ).copy(sourceIdentity = "notification:42:1000")
        val id = store.insert(capture)!!
        store.delete(id)
        store.close()
        store = AlertHistoryStore(context, name)
        assertNull(store.insert(capture))
        assertNotNull(store.insert(capture.copy(sourceIdentity = "notification:42:2000")))
        assertNotNull(store.insert(capture.copy(original = "[DRILL] Changed instructions.")))
        assertNotNull(store.insert(capture.copy(sourceIdentity = null)))
        store.deleteAll()
        assertNull(store.insert(capture.copy(sourceIdentity = "notification:42:2000")))
        assertNull(store.cachedTranslation(capture.original, "en"))
    }

    @Test
    fun migrationFromVersionOnePreservesOriginalsAndTranslations() {
        verifyMigration(1)
    }

    @Test
    fun migrationFromVersionTwoPreservesOriginalsAndTranslations() {
        verifyMigration(2)
    }

    @Test
    fun migrationFromVersionThreePreservesOriginalsAndTranslations() {
        verifyMigration(3)
    }

    private fun verifyMigration(version: Int) {
        val id = store.insert(capture("[DRILL] Migration original."))!!
        store.finishTranslation(store.beginTranslation(id, "en")!!, ready("Saved translation"))
        store.close()
        // Only this isolated fixture database is changed to reproduce the prior committed schema.
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use {
            val schema = it.rawQuery(
                "SELECT sql FROM sqlite_master WHERE name='alerts'",
                null
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            val columns = it.rawQuery("PRAGMA table_info(alerts)", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val field = cursor.getString(1)
                        if (field != "display_group_id" &&
                            (version >= 5 || field != "reception_context")
                        ) {
                            add(field)
                        }
                    }
                }.joinToString(",")
            }
            it.execSQL(
                schema.replace("CREATE TABLE alerts", "CREATE TABLE legacy_alerts")
                    .replace("display_group_id INTEGER,", "")
                    .let { schema ->
                        if (version <
                            5
                        ) {
                            schema.replace("reception_context TEXT,", "")
                        } else {
                            schema
                        }
                    }
            )
            it.execSQL("INSERT INTO legacy_alerts($columns) SELECT $columns FROM alerts")
            it.execSQL("DROP TABLE alerts")
            it.execSQL("ALTER TABLE legacy_alerts RENAME TO alerts")
            it.execSQL("CREATE INDEX alert_original_hash ON alerts(original_hash)")
            if (version < 4) {
                it.execSQL("DROP INDEX translation_queue")
                it.execSQL("ALTER TABLE translations RENAME TO current_translations")
                it.execSQL(
                    "CREATE TABLE translations (" +
                        "alert_id INTEGER NOT NULL REFERENCES alerts(id) ON DELETE CASCADE," +
                        "target TEXT NOT NULL, revision INTEGER NOT NULL, phase TEXT NOT NULL," +
                        "text TEXT, source_language TEXT, search_text TEXT, PRIMARY KEY(alert_id,target))"
                )
                it.execSQL(
                    "INSERT INTO translations SELECT alert_id,target,revision,phase,text," +
                        "source_language,search_text FROM current_translations"
                )
                it.execSQL("DROP TABLE current_translations")
                if (version == 1) it.execSQL("DROP TABLE ignored_sources")
                if (version == 3) {
                    it.execSQL(
                        "CREATE INDEX translation_queue ON translations(target,phase,alert_id DESC)"
                    )
                }
            }
            it.version = version
        }
        store = AlertHistoryStore(context, name)
        assertEquals("[DRILL] Migration original.", store.find(id, "en")?.capture?.original)
        assertEquals("Saved translation", store.find(id, "en")?.translation?.text)
        assertEquals(TranslationEngineKind.ML_KIT, store.find(id, "en")?.translation?.engine)
        assertNull(store.find(id, "en", TranslationEngineKind.SYSTEM)?.translation)
        val system = store.beginTranslation(id, "en", engine = TranslationEngineKind.SYSTEM)!!
        assertTrue(
            store.finishTranslation(system, ready("System result").copy(engine = system.engine))
        )
        assertEquals("Saved translation", store.find(id, "en")?.translation?.text)
    }

    @Test
    fun enginesKeepIndependentQueuesRevisionsAndExactCaches() {
        val system = TranslationEngineKind.SYSTEM
        val original = capture("[DRILL] Independent engine results")
        val id = store.insert(original)!!
        store.queueTranslation(id, "en", system)
        assertNull(store.nextQueued("en"))
        assertEquals(id, store.nextQueued("en", system))
        val systemTicket = store.beginTranslation(id, "en", engine = system)!!
        val defaultTicket = store.beginTranslation(id, "en")!!
        assertFalse(store.finishTranslation(systemTicket, ready("Wrong producer")))
        assertTrue(store.finishTranslation(defaultTicket, ready("Default result")))
        assertNull(store.cachedTranslation(original.original, "en", system))
        val systemResult = ready("System result").copy(engine = system, sameLanguage = true)
        assertTrue(store.finishTranslation(systemTicket, systemResult))
        assertEquals("Default result", store.cachedTranslation(original.original, "en")?.text)
        assertEquals(systemResult, store.cachedTranslation(original.original, "en", system))
        assertEquals(systemResult.copy(revision = 1), store.find(id, "en", system)?.translation)
        assertEquals(id, store.page("System result", "en").single().id)
        assertEquals(
            "System result",
            store.page("", "en", engine = system).single().translation?.text
        )
        val retry = store.beginTranslation(id, "en", retry = true, engine = system)!!
        store.recoverInterruptedWork()
        assertFalse(store.finishTranslation(retry, systemResult))
        assertEquals(id, store.nextQueued("en", system))
        assertNull(store.nextQueued("en"))
        val late = store.beginTranslation(id, "en", engine = system)!!
        store.delete(id)
        assertFalse(store.finishTranslation(late, systemResult))
        assertNull(store.cachedTranslation(original.original, "en", system))
        assertNull(store.cachedTranslation(original.original, "en"))
    }

    @Test
    fun modelPreparationRequeuesOnlyTheSelectedEngine() {
        val id = store.insert(capture("[DRILL] Missing models"))!!
        TranslationEngineKind.entries.forEach { engine ->
            val ticket = store.beginTranslation(id, "en", engine = engine)!!
            store.finishTranslation(
                ticket,
                SavedTranslation(TranslationPhase.NEEDS_MODELS, engine = engine)
            )
        }
        store.requeueMissingModels("en", TranslationEngineKind.SYSTEM)
        assertEquals(id, store.nextQueued("en", TranslationEngineKind.SYSTEM))
        assertNull(store.nextQueued("en"))
    }

    @Test
    fun originalsAndKnownSourceFieldsSurviveReopen() {
        val original = capture("[DRILL] 서울 台北 東京 🚨. Do not enter. 14:05, 112.").copy(
            broadcast = BroadcastMetadata(1, 49, 4370, 3, 0, null),
            receivedAtMillis = 1700000000100L,
            sourceIdentity = "broadcast:49:1700000000100"
        )
        val id = store.insert(original)!!
        store.close()
        store = AlertHistoryStore(context, name)
        assertEquals(original, store.find(id, "en")?.capture)
        assertNull(store.find(id, "en")?.capture?.postedAtMillis)
        assertNull(store.find(id, "en")?.translation)
    }

    @Test
    fun searchTreatsSqlAndLikeMetacharactersAsLiteralText() {
        val literal = store.insert(capture("[DRILL] 100%_gate \\ north"))!!
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
        val id = store.insert(first)!!
        assertEquals(id, store.insert(first))
        assertNotEquals(id, store.insert(first.copy(sourceIdentity = "notification:1:2000")))
        assertNotEquals(id, store.insert(first.copy(sourceIdentity = null)))
        assertNotEquals(id, store.insert(first.copy(original = "[DRILL] Updated shelter.")))
    }

    @Test
    fun staleAndDuplicateCompletionsCannotReplaceNewerTargetWork() {
        val id = store.insert(capture("[DRILL] Do not leave."))!!
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
        val id = store.insert(original)!!
        val working = store.beginTranslation(id, "en")!!
        store.delete(id)
        assertFalse(store.finishTranslation(working, ready("late")))
        assertNull(store.find(id, "en"))
        val next = store.insert(original)!!
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
        val id = store.insert(capture("[DRILL] Do not cross."))!!
        assertNull(store.nextQueued("en"))
        store.queueTranslation(id, "en")
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

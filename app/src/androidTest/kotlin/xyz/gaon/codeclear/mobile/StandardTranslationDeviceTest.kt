package xyz.gaon.codeclear.mobile

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.gaon.codeclear.translation.StandardTranslationAttribution
import xyz.gaon.codeclear.translation.StandardTranslationCatalog
import xyz.gaon.codeclear.translation.TranslationEngine
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.TranslationInput
import xyz.gaon.codeclear.translation.TranslationResult
import xyz.gaon.codeclear.translation.TranslationTarget

class StandardTranslationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val name = "standard-test-${UUID.randomUUID()}.db"
    private lateinit var store: AlertHistoryStore
    private var worker: HistoryWorker? = null
    private var queue: AlertTranslationQueue? = null
    private val original = "先ほど発表した緊急地震速報を取り消します"
    private val calls = AtomicInteger()
    private val failures = AtomicInteger()
    private val delivered = CountDownLatch(1)
    private val input = AtomicReference<TranslationInput>()

    @Before fun create() {
        store = AlertHistoryStore(context, name)
    }

    @After fun remove() {
        instrumentation.runOnMainSync {
            queue?.close()
            worker?.close()
        }
        if (worker != null) assertTrue(worker!!.awaitClosed(5000)) else store.close()
        context.deleteDatabase(name)
    }
    private fun capture(body: String = original) = CapturedAlert(
        "example.fixture",
        CaptureSource.NOTIFICATION,
        "Synthetic fixture",
        body,
        System.currentTimeMillis()
    )
    private fun saved(): SavedTranslation {
        val result = StandardTranslationCatalog.translate(original, TranslationTarget.ENGLISH)!!
        return SavedTranslation(
            TranslationPhase.READY,
            result.text,
            result.sourceLanguage,
            standard = result.attribution
        )
    }
    private fun start(
        allowed: () -> Boolean = { true },
        engine: TranslationEngine = TranslationEngine { value, download, done ->
            assertFalse(download)
            calls.incrementAndGet()
            input.set(value)
            done(TranslationResult.NeedsModels)
        }
    ) {
        instrumentation.runOnMainSync {
            worker = HistoryWorker({ store.close() }, { failures.incrementAndGet() })
            queue = AlertTranslationQueue(
                store,
                worker!!,
                engine,
                {
                    if (allowed()) {
                        TranslationSelection(
                            TranslationTarget.ENGLISH,
                            TranslationEngineKind.ML_KIT,
                            null,
                            null
                        )
                    } else {
                        null
                    }
                },
                {},
                { _, _, _, _ -> delivered.countDown() },
                {}
            )
            queue!!.kick()
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        assertTrue(condition())
        assertEquals(0, failures.get())
    }

    @Test fun exactStandardCompletesWithoutModelOrProviderCalls() {
        val id = store.insert(capture())!!
        store.queueTranslation(id, "en")
        start()
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        val result = store.find(id, "en")!!.translation!!
        assertEquals("JMA has cancelled the EEW.", result.text)
        assertEquals(saved().standard, result.standard)
        assertEquals(0, calls.get())
        assertNull(store.find(id, "en")!!.capture.broadcast)
    }

    @Test fun nonstandardMessageReachesTheEngineWholeAndNeedsModelsNormally() {
        val body = original + " ただし避難を続けてください。"
        val id = store.insert(capture(body))!!
        store.queueTranslation(id, "en")
        start()
        await { store.find(id, "en")?.translation?.phase == TranslationPhase.NEEDS_MODELS }
        assertEquals(body, input.get().body)
        assertEquals(1, calls.get())
        assertNull(store.find(id, "en")?.translation?.standard)
    }

    @Test fun permissionAndInvalidationPreventPreparedResultsFromBeingDelivered() {
        val id = store.insert(capture())!!
        store.queueTranslation(id, "en")
        val allowed = AtomicBoolean(false)
        start(allowed = allowed::get)
        assertEquals(TranslationPhase.QUEUED, store.find(id, "en")?.translation?.phase)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker!!.submit({
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }) {}
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            allowed.set(true)
            queue!!.kick()
            allowed.set(false)
            queue!!.invalidate()
        }
        release.countDown()
        val done = CountDownLatch(1)
        worker!!.submit({}) { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1L, delivered.count)
        assertEquals(0, calls.get())
    }

    @Test fun provenanceSurvivesReopenAndExactCacheButCannotRestoreDeletedRows() {
        val id = store.insert(capture())!!
        assertTrue(store.finishTranslation(store.beginTranslation(id, "en")!!, saved()))
        store.close()
        store = AlertHistoryStore(context, name)
        assertEquals(saved().standard, store.find(id, "en")?.translation?.standard)
        assertEquals(saved(), store.cachedTranslation(original, "en"))
        assertNull(store.cachedTranslation(original, "en", TranslationEngineKind.MY_MEMORY))
        val ticket = store.beginTranslation(id, "en", retry = true)!!
        store.delete(id)
        assertFalse(store.finishTranslation(ticket, saved()))
        assertNull(store.cachedTranslation(original, "en"))
    }

    @Test fun obsoleteCatalogRowsWaitForExplicitRetryWithoutInvalidatingNeuralResults() {
        val id = store.insert(capture())!!
        assertTrue(store.finishTranslation(store.beginTranslation(id, "en")!!, saved()))
        val other = store.insert(capture("Unstructured fixture"))!!
        val neural = SavedTranslation(TranslationPhase.READY, "Ordinary translation", "ja")
        assertTrue(store.finishTranslation(store.beginTranslation(other, "en")!!, neural))
        store.close()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use {
            it.execSQL(
                "UPDATE translations SET catalog_revision='previous' WHERE alert_id=?",
                arrayOf(id)
            )
        }
        store = AlertHistoryStore(context, name)
        assertEquals(TranslationPhase.STALE, store.find(id, "en")?.translation?.phase)
        assertNull(store.find(id, "en")?.translation?.text)
        assertNull(store.cachedTranslation(original, "en"))
        assertEquals(neural.text, store.find(other, "en")?.translation?.text)
        assertNull(store.nextQueued("en"))
        start()
        val settled = CountDownLatch(1)
        worker!!.submit({}) { settled.countDown() }
        assertTrue(settled.await(5, TimeUnit.SECONDS))
        assertEquals(0, calls.get())
        assertEquals(1L, delivered.count)
        assertNull(store.beginTranslation(id, "en"))
        val ticket = store.beginTranslation(id, "en", retry = true)!!
        assertFalse(
            store.finishTranslation(
                ticket,
                saved().copy(
                    standard = StandardTranslationAttribution(
                        "jp-jma-earthquake-cancelled",
                        "previous"
                    )
                )
            )
        )
    }

    @Test fun versionSixMigrationPreservesOriginalsNeuralResultsAndEnginePartitions() {
        val id = store.insert(capture())!!
        val result = SavedTranslation(TranslationPhase.READY, "Existing neural result", "ja")
        assertTrue(store.finishTranslation(store.beginTranslation(id, "en")!!, result))
        store.close()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use {
            it.execSQL("ALTER TABLE translations RENAME TO old_translations")
            it.execSQL(
                "CREATE TABLE translations (alert_id INTEGER NOT NULL REFERENCES alerts(id) ON DELETE CASCADE, target TEXT NOT NULL, engine TEXT NOT NULL, revision INTEGER NOT NULL, phase TEXT NOT NULL, text TEXT, source_language TEXT, search_text TEXT, same_language INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(alert_id,target,engine))"
            )
            it.execSQL(
                "INSERT INTO translations SELECT alert_id,target,engine,revision,phase,text,source_language,search_text,same_language FROM old_translations"
            )
            it.execSQL("DROP TABLE old_translations")
            it.execSQL(
                "CREATE INDEX translation_queue ON translations(engine,target,phase,alert_id DESC)"
            )
            it.version = 6
        }
        store = AlertHistoryStore(context, name)
        assertEquals(original, store.find(id, "en")?.capture?.original)
        assertEquals(result.text, store.find(id, "en")?.translation?.text)
        assertNull(store.find(id, "en")?.translation?.standard)
        assertNull(store.find(id, "en", TranslationEngineKind.SYSTEM)?.translation)
    }

    @Test fun typedSlotsAndMultiwordNamesUseTheActualAndroidRegexAndDateEngines() {
        val source = "2026-11-08 14:05 서울 일대 예상진도 XII의 강한 진동 감지/안전주의, " +
            "기상청 추가정보 확인 바람 Earthquake[기상청]"
        val translated = StandardTranslationCatalog.translate(source, TranslationTarget.ENGLISH)
        assertEquals("kr-kma-intensity", translated?.attribution?.templateId)
        assertTrue(translated!!.text.contains("intensity XII"))
        assertNull(
            StandardTranslationCatalog.translate(
                source.replace("2026-11-08", "2026-02-30"),
                TranslationTarget.ENGLISH
            )
        )
        assertNull(
            StandardTranslationCatalog.translate(
                source.replace("XII", "IIII"),
                TranslationTarget.ENGLISH
            )
        )
        assertNull(
            StandardTranslationCatalog.translate(
                source + " 단, 대피하지 마세요.",
                TranslationTarget.ENGLISH
            )
        )
        val us = "EARTHQUAKE DETECTED! Drop, Cover, Hold On. Protect Yourself. " +
            "National Weather Service https://example.org/quake?a=1&b=2"
        assertTrue(
            StandardTranslationCatalog.translate(
                us,
                TranslationTarget.KOREAN
            )!!.text.endsWith("National Weather Service https://example.org/quake?a=1&b=2")
        )
        assertNull(
            StandardTranslationCatalog.translate(
                us.replace("National Weather Service", "Unknown Agency"),
                TranslationTarget.KOREAN
            )
        )
    }

    @Test fun androidMatcherHandlesLongNearMatchesWithinABoundedWorkerBudget() {
        StandardTranslationCatalog.translate(original, TranslationTarget.ENGLISH)
        val start = SystemClock.elapsedRealtime()
        repeat(100) {
            assertNull(
                StandardTranslationCatalog.translate(
                    "14:05 북한 미사일 발사, " + "서울, ".repeat(1500),
                    TranslationTarget.ENGLISH
                )
            )
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        println("Standard matcher: 100 long synthetic near-matches in $elapsed ms")
        assertTrue("100 synthetic near-matches exceeded 2 seconds", elapsed < 2000)
    }
}

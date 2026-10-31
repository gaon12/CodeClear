package xyz.gaon.codeclear.mobile

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import xyz.gaon.codeclear.nearby.RelayProtocol
import xyz.gaon.codeclear.probe.LocalEngine
import xyz.gaon.codeclear.probe.LocalEngineKind
import xyz.gaon.codeclear.probe.LocalInput
import xyz.gaon.codeclear.probe.LocalResult
import xyz.gaon.codeclear.probe.LocalTarget

class MobileControllerDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var name: String
    private lateinit var store: AlertHistoryStore
    private var controller: MobileController? = null
    private val latest = AtomicReference(MobileState())
    private val engine = ControlledEngine()
    private val notices = mutableListOf<Long>()
    private val noticeEngines = mutableListOf<LocalEngineKind>()
    private var diagnosticScope: AutoCloseable? = null

    @Before
    fun preparePrivateFixtureStore() {
        name = "controller-test-${UUID.randomUUID()}.db"
        store = AlertHistoryStore(context, name)
        onMain { diagnosticScope = context.mobileRuntime.pauseForDiagnostics() }
    }

    @After
    fun closeBeforeRemovingOnlyTheFixtureDatabase() {
        try {
            val owner = controller
            if (owner != null) {
                onMain { owner.close() }
                assertTrue(owner.awaitClosed(5000))
            } else {
                store.close()
            }
            context.deleteDatabase(name)
        } finally {
            onMain { diagnosticScope?.close() }
        }
    }

    @Test
    fun notificationDetailKeepsItsSavedLanguageWhenTheDefaultChanges() {
        val id = store.insert(capture("[DRILL] Pinned language"))!!
        for (target in listOf("en", "de")) {
            val ticket = store.beginTranslation(id, target)!!
            store.finishTranslation(
                ticket,
                SavedTranslation(TranslationPhase.READY, "Saved $target", "fr")
            )
        }
        start(local = false)
        await { it.latest?.id == id }
        onMain { controller!!.openDetail(id, "en") }
        await { it.detail?.translation?.text == "Saved en" }
        onMain {
            controller!!.changeOptions(controller!!.state.options.copy(target = LocalTarget.GERMAN))
        }
        await {
            it.options.target == LocalTarget.GERMAN && it.latest?.translation?.text == "Saved de" &&
                it.detailTarget == "en" && it.detail?.translation?.text == "Saved en"
        }
        assertEquals(0, onMain { engine.calls })
    }

    @Test
    fun detailTargetQueuesBehindActiveWorkWithoutChangingTheDefault() {
        val id = store.insert(capture("[DRILL] Alternate detail target"))!!
        start()
        await { engine.calls == 1 && it.latest?.id == id }
        onMain { controller!!.openDetail(id) }
        await { it.detail?.id == id }
        onMain { controller!!.changeDetailTarget(id, "ko", "ko") }
        assertEquals(null, latest.get().detailTarget)
        onMain { controller!!.changeDetailTarget(id, "de", "ko") }
        await { it.detailTarget == "de" }
        assertEquals(1, onMain { engine.calls })
        onMain { engine.complete("Default English") }
        await { engine.calls == 2 }
        assertEquals(LocalTarget.GERMAN, onMain { engine.inputs.last().target })
        onMain { engine.complete("Selected German") }
        await { it.detail?.translation?.text == "Selected German" }
        assertEquals(LocalTarget.ENGLISH, latest.get().options.target)
        assertEquals("ko", latest.get().detailSourceLanguage)
        assertEquals("Default English", latest.get().latest?.translation?.text)
    }

    @Test
    fun detailLanguageSelectionDoesNotEnableTranslationOrRetargetAnotherRecord() {
        val first = store.insert(capture("[DRILL] First"))!!
        val second = store.insert(capture("[DRILL] Second"))!!
        start(local = false)
        await { it.latest?.id == second }
        onMain { controller!!.openDetail(second) }
        await { it.detail?.id == second }
        onMain { controller!!.changeDetailTarget(first, "de", "ko") }
        assertEquals(null, latest.get().detailTarget)
        onMain { controller!!.changeDetailTarget(second, "de", "ko") }
        await { it.detailTarget == "de" && !it.loading }
        assertEquals(0, onMain { engine.calls })
        assertEquals(LocalTarget.ENGLISH, latest.get().options.target)
        assertFalse(latest.get().options.localConsent)
    }

    @Test
    fun equalBodiesReuseTranslationWithoutMergingOccurrencesOrStartingTwoSdkJobs() {
        store.insert(capture("[DRILL] Same original"))
        store.insert(capture("[DRILL] Same original"))
        start()
        await { engine.calls == 1 && it.latest != null }
        onMain { engine.complete("Local result") }
        await {
            it.records.size == 2 &&
                it.records.all { row -> row.translation?.phase == TranslationPhase.READY }
        }
        assertEquals(1, onMain { engine.calls })
        assertEquals(2, onMain { notices.distinct().size })
    }

    @Test
    fun deletionDuringTranslationCannotRestoreHistoryOrPostAResult() {
        val id = store.insert(capture("[DRILL] Delete during work"))!!
        start()
        await { engine.calls == 1 && it.latest != null }
        onMain { controller!!.delete(id) }
        await { !it.loading && it.latest == null }
        onMain { engine.complete("Late result") }
        SystemClock.sleep(200)
        assertTrue(latest.get().records.isEmpty())
        assertTrue(onMain { notices.isEmpty() })
    }

    @Test
    fun targetChangeDiscardsOldResultAndThemeChangeKeepsValidWork() {
        store.insert(capture("[DRILL] Target change"))
        start()
        await { engine.calls == 1 }
        onMain {
            val owner = controller!!
            owner.changeOptions(owner.state.options.copy(theme = ThemeChoice.DARK))
            engine.complete("English result")
        }
        await { it.latest?.translation?.text == "English result" }
        assertEquals(1, onMain { engine.calls })
        onMain {
            controller!!.changeOptions(controller!!.state.options.copy(target = LocalTarget.GERMAN))
        }
        await { engine.calls == 2 }
        onMain {
            controller!!.changeOptions(controller!!.state.options.copy(target = LocalTarget.FRENCH))
        }
        onMain { engine.complete("Stale German") }
        await { engine.calls == 3 }
        onMain { engine.complete("French result") }
        await { it.latest?.translation?.text == "French result" }
        assertEquals(LocalTarget.FRENCH, latest.get().options.target)
    }

    @Test
    fun fixedDetailAndLatestRecordRemainSeparateAndSearchDropsOlderRequests() {
        val first = store.insert(capture("[DRILL] 川 river"))!!
        start(local = false)
        await { it.latest?.id == first }
        onMain { controller!!.openDetail(first) }
        await { it.detail?.id == first }
        val second = store.insert(capture("[DRILL] 雪 snow"))!!
        onMain { controller!!.refresh() }
        await { it.latest?.id == second && it.detail?.id == first }
        onMain {
            controller!!.search("川")
            controller!!.search("雪")
        }
        await { !it.loading && it.query == "雪" && it.records.size == 1 }
        assertEquals(second, latest.get().records.single().id)
        assertEquals(first, latest.get().detail?.id)
    }

    @Test
    fun preferenceFailurePausesCollectionAndTranslationWithoutLoggingDetails() {
        start(local = false, failSave = true)
        await { it.loaded }
        onMain {
            controller!!.changeOptions(
                controller!!.state.options.copy(localConsent = true, nearbyEnabled = true)
            )
        }
        await { it.storageError }
        assertFalse(latest.get().options.localConsent)
        assertFalse(latest.get().options.nearbyEnabled)
        assertFalse(latest.get().options.notificationConsent)
        assertEquals(0, onMain { engine.calls })
    }

    @Test
    fun engineChangesDiscardStaleWorkAndPreservePinnedProducerResults() {
        val original = "[DRILL] Engine selection"
        val id = store.insert(capture(original))!!
        start()
        await { engine.calls == 1 }
        onMain {
            controller!!.changeOptions(
                controller!!.state.options.copy(engine = LocalEngineKind.SYSTEM)
            )
            engine.complete("Stale default result")
        }
        await { engine.calls == 2 }
        assertEquals(LocalEngineKind.SYSTEM, onMain { engine.inputs.last().engine })
        onMain { engine.complete(original, sameLanguage = true) }
        await { it.latest?.translation?.text == original }
        assertEquals(LocalEngineKind.SYSTEM, latest.get().latest?.translation?.engine)
        assertTrue(latest.get().latest!!.translation!!.sameLanguage)
        assertEquals(listOf(LocalEngineKind.SYSTEM), onMain { noticeEngines.toList() })
        onMain {
            controller!!.openDetail(id, "en", LocalEngineKind.SYSTEM)
            controller!!.changeOptions(
                controller!!.state.options.copy(engine = LocalEngineKind.ML_KIT)
            )
        }
        await { engine.calls == 3 && it.detail?.translation?.text == original }
        assertEquals(LocalEngineKind.ML_KIT, onMain { engine.inputs.last().engine })
        onMain { controller!!.retry(id, "en", LocalEngineKind.SYSTEM) }
        assertEquals(3, onMain { engine.calls })
        onMain { engine.complete("Default result") }
        await {
            it.latest?.translation?.text == "Default result" &&
                it.detail?.translation?.text == original &&
                it.detailEngine == LocalEngineKind.SYSTEM
        }
        assertEquals(
            listOf(LocalEngineKind.SYSTEM, LocalEngineKind.ML_KIT),
            onMain {
                noticeEngines.toList()
            }
        )
    }

    @Test
    fun systemFailuresStayActionableAndRetryRequiresItsActiveConsent() {
        val id = store.insert(capture("[DRILL] System retry"))!!
        start(selectedEngine = LocalEngineKind.SYSTEM)
        await { engine.calls == 1 }
        onMain { engine.completeWith(LocalResult.Unavailable) }
        await { it.latest?.translation?.phase == TranslationPhase.UNAVAILABLE }
        assertEquals(LocalEngineKind.SYSTEM, latest.get().latest?.translation?.engine)
        onMain {
            controller!!.changeOptions(controller!!.state.options.copy(localConsent = false))
            controller!!.retry(id, "en", LocalEngineKind.SYSTEM)
        }
        assertEquals(1, onMain { engine.calls })
        onMain {
            controller!!.changeOptions(controller!!.state.options.copy(localConsent = true))
            controller!!.retry(id, "en", LocalEngineKind.SYSTEM)
        }
        await { engine.calls == 2 }
        onMain { engine.completeWith(LocalResult.UnsupportedPair) }
        await { it.latest?.translation?.phase == TranslationPhase.UNSUPPORTED_PAIR }
        onMain { controller!!.retry(id, "en", LocalEngineKind.SYSTEM) }
        await { engine.calls == 3 }
        onMain {
            engine.completeWith(LocalResult.Ready("Regional language result", "es-419", false))
        }
        await { it.latest?.translation?.sourceLanguage == "es-419" }
        assertEquals(listOf(LocalEngineKind.SYSTEM), onMain { noticeEngines.toList() })
    }

    @Test
    fun lateSystemResultsCannotRestoreWithdrawnWorkOrDeletedHistory() {
        val id = store.insert(capture("[DRILL] Withdraw and delete system work"))!!
        start(selectedEngine = LocalEngineKind.SYSTEM)
        await { engine.calls == 1 }
        onMain {
            controller!!.changeOptions(controller!!.state.options.copy(localConsent = false))
            engine.complete("Withdrawn result")
        }
        await { !it.loading && it.latest?.translation?.phase == TranslationPhase.QUEUED }
        assertTrue(onMain { notices.isEmpty() })
        onMain { controller!!.changeOptions(controller!!.state.options.copy(localConsent = true)) }
        await { engine.calls == 2 }
        onMain { controller!!.delete(id) }
        await { !it.loading && it.latest == null }
        onMain { engine.complete("Deleted result") }
        SystemClock.sleep(200)
        assertTrue(onMain { notices.isEmpty() })
        assertTrue(latest.get().records.isEmpty())
        assertEquals(null, store.find(id, "en", LocalEngineKind.SYSTEM))
    }

    @Test
    fun savingNearbyKeepsUnverifiedProvenanceAndDoesNotReplaceTheNativeLatest() {
        val local = store.insert(capture("[DRILL] Native latest"))!!
        start(local = false)
        await { it.latest?.id == local }
        val now = System.currentTimeMillis()
        val original = RelayProtocol.decode(
            RelayProtocol.encode(
                "[DRILL] Nearby",
                "[DRILL] Peer original 👩‍🚒",
                now
            ),
            now
        )!!
        val result = AtomicReference<Long>()
        onMain { controller!!.saveNearby(original, { true }) { result.set(it) } }
        await { it.records.size == 2 }
        val id = result.get()!!
        assertEquals(local, latest.get().latest!!.id)
        val peer = store.find(id, "en")!!
        assertEquals(CaptureSource.PEER, peer.capture.source)
        assertEquals(original.root, peer.capture.sourceIdentity)
        assertEquals(original.body, peer.capture.original)
        assertEquals(null, peer.capture.broadcast)
        assertEquals(null, peer.translation)
        assertEquals(0, onMain { engine.calls })
        onMain { controller!!.delete(id) }
        await { !it.loading && it.records.size == 1 }
        result.set(-1)
        onMain { controller!!.saveNearby(original, { true }) { result.set(it) } }
        await { !it.loading && result.get() == null }
        assertEquals(1, store.page("", "en").size)
    }

    @Test
    fun nearbySaveHonorsRevocationAndOnlyQueuesTheAllowedLocalEngine() {
        start(local = true)
        await { it.loaded && !it.loading }
        val now = System.currentTimeMillis()
        val original = RelayProtocol.decode(
            RelayProtocol.encode(
                "[DRILL] Nearby",
                "[DRILL] Explicit save",
                now
            ),
            now
        )!!
        var denied = false
        onMain { controller!!.saveNearby(original, { false }) { denied = it == null } }
        assertTrue(denied)
        assertTrue(store.page("", "en").isEmpty())
        onMain { controller!!.saveNearby(original, { true }) {} }
        await { onMain { engine.calls } == 1 }
        assertEquals(CaptureSource.PEER, latest.get().latest?.capture?.source)
        onMain { engine.complete("[DRILL] Local translation") }
        await { it.records.firstOrNull()?.translation?.phase == TranslationPhase.READY }
        assertEquals(1, onMain { engine.calls })
    }

    @Test
    fun automaticPeerRetentionSurvivesRestartAndDoesNotTranslateUntilRequested() {
        start(local = true)
        await { it.loaded && !it.loading }
        val now = System.currentTimeMillis()
        val original = RelayProtocol.decode(
            RelayProtocol.encode("[DRILL] Nearby", "[DRILL] Retained original", now),
            now
        )!!
        val result = AtomicReference<Long?>()
        onMain { controller!!.saveNearby(original, { true }, translate = false) { result.set(it) } }
        await { state -> !state.loading && result.get()?.let { state.latest?.id == it } == true }
        assertEquals(0, onMain { engine.calls })
        val id = result.get()!!
        val previous = controller!!
        onMain { previous.close() }
        assertTrue(previous.awaitClosed(5000))
        controller = null
        store = AlertHistoryStore(context, name)
        start(local = false)
        await { !it.loading && it.latest?.id == id }
        assertEquals(original.body, latest.get().latest?.capture?.original)
        assertEquals(CaptureSource.PEER, latest.get().latest?.capture?.source)
    }

    private fun start(
        local: Boolean = true,
        failSave: Boolean = false,
        selectedEngine: LocalEngineKind = LocalEngineKind.ML_KIT
    ) {
        if (local) {
            store.page("", "en").forEach {
                store.queueTranslation(it.id, "en", selectedEngine)
            }
        }
        onMain {
            val preferences = object : OptionsStorage {
                private var options =
                    MobileOptions(
                        localConsent = local,
                        target = LocalTarget.ENGLISH,
                        engine = selectedEngine
                    )
                override fun load() = options
                override fun save(options: MobileOptions) {
                    if (failSave) error("Synthetic storage failure")
                    this.options = options
                }
            }
            controller = MobileController(
                context,
                store,
                preferences,
                engine,
                changed = latest::set,
                translated = { id, _, producer ->
                    notices += id
                    noticeEngines += producer
                }
            ).also { it.start() }
        }
    }

    private fun await(predicate: (MobileState) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate(latest.get())) return
            SystemClock.sleep(10)
        }
        fail("Expected controller state was not reached")
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun capture(body: String) = CapturedAlert(
        "test.system.receiver",
        CaptureSource.NOTIFICATION,
        "[DRILL] Fixture",
        body,
        1700000000000
    )

    private class ControlledEngine : LocalEngine {
        @Volatile var calls = 0
        private var callback: ((LocalResult) -> Unit)? = null
        val inputs = mutableListOf<LocalInput>()
        override fun run(
            input: LocalInput,
            allowModelDownload: Boolean,
            complete: (LocalResult) -> Unit
        ) {
            check(callback == null)
            check(!allowModelDownload)
            calls++
            inputs += input
            callback = complete
        }
        fun complete(text: String, sameLanguage: Boolean = false) {
            completeWith(LocalResult.Ready(text, "en", sameLanguage))
        }
        fun completeWith(result: LocalResult) {
            val done = callback!!
            callback = null
            done(result)
        }
    }
}

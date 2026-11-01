package xyz.gaon.codeclear.mobile

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import xyz.gaon.codeclear.nearby.RelayProtocol
import xyz.gaon.codeclear.translation.TranslationEngine
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.TranslationInput
import xyz.gaon.codeclear.translation.TranslationResult
import xyz.gaon.codeclear.translation.TranslationTarget

class MobileControllerDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var name: String
    private lateinit var store: AlertHistoryStore
    private var controller: MobileController? = null
    private val latest = AtomicReference(MobileState())
    private val engine = ControlledEngine()
    private val notices = mutableListOf<Long>()
    private val noticeEngines = mutableListOf<TranslationEngineKind>()
    private val savedOptions = mutableListOf<MobileOptions>()
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
    fun remoteWorkWaitsForPersistenceAndOlderSavesCannotRestoreWithdrawnPermission() {
        val id = store.insert(capture("[DRILL] Synthetic persistence boundary"))!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            start(
                selectedEngine = TranslationEngineKind.MY_MEMORY,
                saveEntered = entered,
                saveRelease = release
            )
            await { it.loaded && !it.loading }
            onMain {
                controller!!.changeOptions(
                    latest.get().options.copy(serverAllowed = true, labsEnabled = true)
                )
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            onMain {
                controller!!.retry(id, "en", TranslationEngineKind.MY_MEMORY)
                controller!!.changeOptions(latest.get().options.copy(serverAllowed = false))
            }
            assertEquals(0, engine.calls)
            release.countDown()
            await { onMain { savedOptions.size == 2 } && !it.loading }
            assertFalse(onMain { savedOptions.last().serverAllowed })
            assertEquals(0, engine.calls)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun localPermissionNeverAuthorizesRemoteWorkAndWithdrawalDiscardsLateOutput() {
        val id = store.insert(capture("[DRILL] Synthetic remote permission scope"))!!
        start(selectedEngine = TranslationEngineKind.MY_MEMORY)
        await { it.loaded && !it.loading }
        assertEquals(0, engine.calls)
        onMain {
            controller!!.changeOptions(
                latest.get().options.copy(serverAllowed = true, labsEnabled = true)
            )
        }
        await { engine.calls == 1 }
        assertEquals(TranslationEngineKind.MY_MEMORY, onMain { engine.inputs.single().engine })
        onMain {
            controller!!.changeOptions(latest.get().options.copy(serverAllowed = false))
            engine.complete("[DRILL] Late provider response")
        }
        await { !it.loading && !it.options.translationAllowed }
        assertTrue(latest.get().options.localConsent)
        assertEquals(null, store.find(id, "en", TranslationEngineKind.MY_MEMORY)?.translation?.text)
        assertTrue(notices.isEmpty())
        assertEquals(1, engine.calls)
        onMain { controller!!.changeOptions(latest.get().options.copy(serverAllowed = true)) }
        await { engine.calls == 2 }
        onMain {
            controller!!.delete(id)
            engine.complete("[DRILL] Deleted provider response")
        }
        await { !it.loading && it.latest == null }
        assertEquals(null, store.find(id, "en", TranslationEngineKind.MY_MEMORY))
        assertTrue(notices.isEmpty())
    }

    @Test
    fun anOlderMissingModelResponseRetriesOnceAfterPreparationCompletes() {
        val id = store.insert(capture("[DRILL] Preparation raced an active translation"))!!
        start()
        await { engine.calls == 1 && it.latest?.id == id }
        onMain {
            controller!!.modelsPrepared()
            engine.completeWith(TranslationResult.NeedsModels)
        }
        await { engine.calls == 2 }
        onMain { engine.completeWith(TranslationResult.NeedsModels) }
        await { !it.loading && it.latest?.translation?.phase == TranslationPhase.NEEDS_MODELS }
        assertEquals(2, engine.calls)
        onMain { controller!!.modelsPrepared() }
        await { engine.calls == 3 }
        onMain { engine.complete("[DRILL] Fresh model result 112") }
        await { it.latest?.translation?.phase == TranslationPhase.READY }
        assertEquals(id, latest.get().latest!!.id)
    }

    @Test
    fun preparedModelsRetryTheOpenJapaneseResultWithoutChangingTheEnglishDefault() {
        val id = store.insert(capture("[DRILL] Installed models"))!!
        val ready = store.beginTranslation(id, "en")!!
        store.finishTranslation(
            ready,
            SavedTranslation(TranslationPhase.READY, "Saved English", "ko")
        )
        val missing = store.beginTranslation(id, "ja")!!
        store.finishTranslation(missing, SavedTranslation(TranslationPhase.NEEDS_MODELS))
        val other = store.beginTranslation(id, "de", engine = TranslationEngineKind.SYSTEM)!!
        store.finishTranslation(
            other,
            SavedTranslation(TranslationPhase.NEEDS_MODELS, engine = TranslationEngineKind.SYSTEM)
        )
        start()
        await { it.latest?.translation?.text == "Saved English" }
        onMain { controller!!.openDetail(id, "ja", TranslationEngineKind.ML_KIT) }
        await { it.detail?.translation?.phase == TranslationPhase.NEEDS_MODELS }
        onMain { controller!!.modelsPrepared() }
        await { engine.calls == 1 }
        assertEquals(TranslationTarget.JAPANESE, onMain { engine.inputs.single().target })
        onMain { engine.complete("[DRILL] 準備完了 112") }
        await { it.detail?.translation?.phase == TranslationPhase.READY }
        assertEquals(TranslationTarget.ENGLISH, latest.get().options.target)
        assertEquals("Saved English", latest.get().latest?.translation?.text)
        assertEquals(
            TranslationPhase.NEEDS_MODELS,
            store.find(id, "de", TranslationEngineKind.SYSTEM)!!.translation!!.phase
        )
        assertEquals(1, engine.calls)
    }

    @Test
    fun preparedModelsRemainQueuedWhileTranslationIsDisabledAndSurviveReopeningHistory() {
        val id = store.insert(capture("[DRILL] Another installed model"))!!
        for (language in listOf("ja", "fr")) {
            val ticket = store.beginTranslation(id, language)!!
            store.finishTranslation(ticket, SavedTranslation(TranslationPhase.NEEDS_MODELS))
        }
        start(local = false)
        await { it.loaded && !it.loading }
        onMain { controller!!.modelsPrepared() }
        await { !it.loading }
        onMain { controller!!.openDetail(id, "ja", TranslationEngineKind.ML_KIT) }
        await { it.detail?.translation?.phase == TranslationPhase.QUEUED }
        assertEquals(0, engine.calls)
        assertEquals(TranslationPhase.QUEUED, store.find(id, "fr")!!.translation!!.phase)
        assertEquals(0, notices.size)
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
            controller!!.changeOptions(
                controller!!.state.options.copy(target = TranslationTarget.GERMAN)
            )
        }
        await {
            it.options.target == TranslationTarget.GERMAN &&
                it.latest?.translation?.text == "Saved de" &&
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
        assertEquals(TranslationTarget.GERMAN, onMain { engine.inputs.last().target })
        onMain { engine.complete("Selected German") }
        await { it.detail?.translation?.text == "Selected German" }
        assertEquals(TranslationTarget.ENGLISH, latest.get().options.target)
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
        assertEquals(TranslationTarget.ENGLISH, latest.get().options.target)
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
            controller!!.changeOptions(
                controller!!.state.options.copy(target = TranslationTarget.GERMAN)
            )
        }
        await { engine.calls == 2 }
        onMain {
            controller!!.changeOptions(
                controller!!.state.options.copy(target = TranslationTarget.FRENCH)
            )
        }
        onMain { engine.complete("Stale German") }
        await { engine.calls == 3 }
        onMain { engine.complete("French result") }
        await { it.latest?.translation?.text == "French result" }
        assertEquals(TranslationTarget.FRENCH, latest.get().options.target)
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
                controller!!.state.options.copy(engine = TranslationEngineKind.SYSTEM)
            )
            engine.complete("Stale default result")
        }
        await { engine.calls == 2 }
        assertEquals(TranslationEngineKind.SYSTEM, onMain { engine.inputs.last().engine })
        onMain { engine.complete(original, sameLanguage = true) }
        await { it.latest?.translation?.text == original }
        assertEquals(TranslationEngineKind.SYSTEM, latest.get().latest?.translation?.engine)
        assertTrue(latest.get().latest!!.translation!!.sameLanguage)
        assertEquals(listOf(TranslationEngineKind.SYSTEM), onMain { noticeEngines.toList() })
        onMain {
            controller!!.openDetail(id, "en", TranslationEngineKind.SYSTEM)
            controller!!.changeOptions(
                controller!!.state.options.copy(engine = TranslationEngineKind.ML_KIT)
            )
        }
        await { engine.calls == 3 && it.detail?.translation?.text == original }
        assertEquals(TranslationEngineKind.ML_KIT, onMain { engine.inputs.last().engine })
        onMain { controller!!.retry(id, "en", TranslationEngineKind.SYSTEM) }
        assertEquals(3, onMain { engine.calls })
        onMain { engine.complete("Default result") }
        await {
            it.latest?.translation?.text == "Default result" &&
                it.detail?.translation?.text == original &&
                it.detailEngine == TranslationEngineKind.SYSTEM
        }
        assertEquals(
            listOf(TranslationEngineKind.SYSTEM, TranslationEngineKind.ML_KIT),
            onMain {
                noticeEngines.toList()
            }
        )
    }

    @Test
    fun systemFailuresStayActionableAndRetryRequiresItsActiveConsent() {
        val id = store.insert(capture("[DRILL] System retry"))!!
        start(selectedEngine = TranslationEngineKind.SYSTEM)
        await { engine.calls == 1 }
        onMain { engine.completeWith(TranslationResult.Unavailable) }
        await { it.latest?.translation?.phase == TranslationPhase.UNAVAILABLE }
        assertEquals(TranslationEngineKind.SYSTEM, latest.get().latest?.translation?.engine)
        onMain {
            controller!!.changeOptions(controller!!.state.options.copy(localConsent = false))
            controller!!.retry(id, "en", TranslationEngineKind.SYSTEM)
        }
        assertEquals(1, onMain { engine.calls })
        onMain {
            controller!!.changeOptions(controller!!.state.options.copy(localConsent = true))
            controller!!.retry(id, "en", TranslationEngineKind.SYSTEM)
        }
        await { engine.calls == 2 }
        onMain { engine.completeWith(TranslationResult.UnsupportedPair) }
        await { it.latest?.translation?.phase == TranslationPhase.UNSUPPORTED_PAIR }
        onMain { controller!!.retry(id, "en", TranslationEngineKind.SYSTEM) }
        await { engine.calls == 3 }
        onMain {
            engine.completeWith(
                TranslationResult.Ready("Regional language result", "es-419", false)
            )
        }
        await { it.latest?.translation?.sourceLanguage == "es-419" }
        assertEquals(listOf(TranslationEngineKind.SYSTEM), onMain { noticeEngines.toList() })
    }

    @Test
    fun lateSystemResultsCannotRestoreWithdrawnWorkOrDeletedHistory() {
        val id = store.insert(capture("[DRILL] Withdraw and delete system work"))!!
        start(selectedEngine = TranslationEngineKind.SYSTEM)
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
        assertEquals(null, store.find(id, "en", TranslationEngineKind.SYSTEM))
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
    fun aStopActionDuringSettingsLoadIsAppliedBeforeSharingCanBePublished() {
        val began = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val persisted = AtomicReference<MobileOptions?>()
        val preferences = object : OptionsStorage {
            override fun load(): MobileOptions {
                began.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return MobileOptions(labsEnabled = true, nearbyEnabled = true)
            }
            override fun save(options: MobileOptions) {
                persisted.set(options)
            }
        }
        try {
            onMain {
                controller = MobileController(context, store, preferences, engine, latest::set)
                controller!!.start()
            }
            assertTrue(began.await(5, java.util.concurrent.TimeUnit.SECONDS))
            onMain { controller!!.stopNearbySharing() }
            release.countDown()
            await { it.loaded && !it.loading && persisted.get() != null }
            assertFalse(latest.get().options.nearbyEnabled)
            assertFalse(persisted.get()!!.nearbyEnabled)
            assertEquals(0, onMain { engine.calls })
        } finally {
            release.countDown()
        }
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
        selectedEngine: TranslationEngineKind = TranslationEngineKind.ML_KIT,
        saveEntered: CountDownLatch? = null,
        saveRelease: CountDownLatch? = null
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
                        target = TranslationTarget.ENGLISH,
                        engine = selectedEngine
                    )
                override fun load() = options
                override fun save(options: MobileOptions) {
                    saveEntered?.countDown()
                    check(saveRelease?.await(5, TimeUnit.SECONDS) != false)
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
                },
                optionsReady = savedOptions::add
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

    private class ControlledEngine : TranslationEngine {
        @Volatile var calls = 0
        private var callback: ((TranslationResult) -> Unit)? = null
        val inputs = mutableListOf<TranslationInput>()
        override fun run(
            input: TranslationInput,
            allowModelDownload: Boolean,
            complete: (TranslationResult) -> Unit
        ) {
            check(callback == null)
            check(!allowModelDownload)
            calls++
            inputs += input
            callback = complete
        }
        fun complete(text: String, sameLanguage: Boolean = false) {
            completeWith(TranslationResult.Ready(text, "en", sameLanguage))
        }
        fun completeWith(result: TranslationResult) {
            val done = callback!!
            callback = null
            done(result)
        }
    }
}

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
import xyz.gaon.codeclear.probe.LocalEngine
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

    @Before
    fun preparePrivateFixtureStore() {
        name = "controller-test-${UUID.randomUUID()}.db"
        store = AlertHistoryStore(context, name)
    }

    @After
    fun closeBeforeRemovingOnlyTheFixtureDatabase() {
        val owner = controller
        if (owner != null) {
            onMain { owner.close() }
            assertTrue(owner.awaitClosed(5000))
        } else {
            store.close()
        }
        context.deleteDatabase(name)
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
        onMain { controller!!.changeOptions(controller!!.state.options.copy(localConsent = true)) }
        await { it.storageError }
        assertFalse(latest.get().options.localConsent)
        assertFalse(latest.get().options.notificationConsent)
        assertEquals(0, onMain { engine.calls })
    }

    private fun start(local: Boolean = true, failSave: Boolean = false) {
        if (local) store.page("", "en").forEach { store.queueTranslation(it.id, "en") }
        onMain {
            val preferences = object : OptionsStorage {
                private var options =
                    MobileOptions(localConsent = local, target = LocalTarget.ENGLISH)
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
                translated = { id, _ -> notices += id }
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
        override fun run(
            input: LocalInput,
            allowModelDownload: Boolean,
            complete: (LocalResult) -> Unit
        ) {
            check(callback == null)
            check(!allowModelDownload)
            calls++
            callback = complete
        }
        fun complete(text: String) {
            val done = callback!!
            callback = null
            done(LocalResult.Ready(text, "en", false))
        }
    }
}
